package com.callibri.nfb.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.callibri.nfb.callibri.CallibriManager
import com.callibri.nfb.callibri.CallibriPermissions
import com.callibri.nfb.callibri.CallibriState
import com.callibri.nfb.callibri.ElectrodeContact
import com.callibri.nfb.callibri.SessionPhase
import com.callibri.nfb.feedback.AndroidMediaStream
import com.callibri.nfb.feedback.AudioFeedbackOutput
import com.callibri.nfb.feedback.ExternalEngagement
import com.callibri.nfb.feedback.FeedbackController
import com.callibri.nfb.feedback.FeedbackIntensity
import com.callibri.nfb.feedback.FeedbackModes
import com.callibri.nfb.feedback.TouchObscuringLimit
import com.callibri.nfb.feedback.VisualDimPreference
import com.callibri.nfb.feedback.VisualDimming
import com.callibri.nfb.feedback.FeedbackDestination
import com.callibri.nfb.feedback.FeedbackSnapshot
import com.callibri.nfb.feedback.MediaVolumeGate
import com.callibri.nfb.feedback.SelectingFeedbackOutput
import com.callibri.nfb.feedback.SystemMediaVolumeFeedbackOutput
import com.callibri.nfb.feedback.RewardPipeline
import com.callibri.nfb.feedback.RewardState
import com.callibri.nfb.protocol.FRE1Protocol
import com.callibri.nfb.protocol.FrequencyBand
import com.callibri.nfb.session.DimmingOverlay
import com.callibri.nfb.session.NeurofeedbackSessionService
import com.callibri.nfb.session.SessionLifetime
import com.callibri.nfb.session.SessionPolicy
import com.callibri.nfb.signal.EegProcessor
import com.callibri.nfb.signal.EegSnapshot
import com.callibri.nfb.threshold.AmplitudeReading
import com.callibri.nfb.threshold.BandThresholdSpec
import com.callibri.nfb.threshold.ThresholdConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The one live Callibri session for this process.
 *
 * Signal processing is the same path the Activity used to own: Callibri samples,
 * [EegProcessor], [RewardPipeline], and [FeedbackController]. The scope is not
 * cancelled when the Activity stops, so EEG and the test tone keep running.
 */
class NeurofeedbackSession(private val app: Application) {
    val lifetime = SessionLifetime()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val processor = EegProcessor(FRE1Protocol.SAMPLE_RATE_HZ.toDouble()).apply {
        setBands(FRE1Protocol.defaults().asList())
    }
    private val pipeline = RewardPipeline()
    private val mediaGate = MediaVolumeGate(AndroidMediaStream(app))
    private val mediaOutput = SystemMediaVolumeFeedbackOutput(mediaGate)
    private val outputs = SelectingFeedbackOutput(AudioFeedbackOutput(), mediaOutput)
    private val feedback = FeedbackController(outputs)
    private val systemObscuringOpacity = TouchObscuringLimit.systemMaximum(app)
    private val safeMaxAlpha = TouchObscuringLimit.safeMaximum(systemObscuringOpacity).toDouble()
    private val dimming = DimmingOverlay(app, safeMaxAlpha.toFloat())
    private val visualPrefs = VisualDimPreference(app)
    private var maxDimAlpha = visualPrefs.load(safeMaxAlpha)
    private val storedRange = visualPrefs.loadRange()
    @Volatile
    private var feedbackLowerBound = storedRange.first
    @Volatile
    private var feedbackUpperBound = storedRange.second
    private var audioEnabled = false
    private var visualEnabled = false
    private var visualEpoch = 0
    private var liveVisualOpen = true
    private var lastVisualCommand = Double.NaN
    private val visualUpdateTimes = ArrayDeque<Long>()
    private val signalMutex = Mutex()
    private var liveDriveActive = false
    private var processingSession = Long.MIN_VALUE
    private var lastProcessErrorNs = 0L
    private var lastRawUiNs = 0L

    @Volatile
    private var latestElectrode: ElectrodeContact? = null
    private var linkWasConnected = false

    private val _ui = MutableStateFlow(
        MainUiState(
            bands = initialBands(),
            maxDimAlpha = maxDimAlpha,
            safeMaxDimAlpha = safeMaxAlpha,
            systemObscuringOpacity = systemObscuringOpacity?.toDouble(),
            feedbackLowerBound = feedbackLowerBound,
            feedbackUpperBound = feedbackUpperBound,
            feedbackIntensity = FeedbackIntensity.intensity(
                FRE1Protocol.MIN_REWARD_PERCENT,
                feedbackLowerBound,
                feedbackUpperBound,
            ),
        ),
    )
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    init {
        feedback.setFeedbackRange(feedbackLowerBound, feedbackUpperBound)
        Log.i(
            TAG,
            "visual dim safe max %.3f (system obscuring %s)".format(
                Locale.US,
                safeMaxAlpha,
                systemObscuringOpacity?.let { "%.3f".format(Locale.US, it) } ?: "unavailable",
            ),
        )
        dimming.onApplied = { alpha ->
            val attached = dimming.isAttached()
            _ui.update { state ->
                state.copy(
                    visualAppliedAlpha = alpha,
                    visualOverlayActive = attached,
                    visualNote = if (attached) null else state.visualNote,
                )
            }
        }
        dimming.onUnavailable = { note ->
            _ui.update { state ->
                if (state.visualNote == note) state else state.copy(visualNote = note, visualOverlayActive = false)
            }
        }
    }

    private val manager = CallibriManager(
        context = app.applicationContext,
        scope = scope,
        onBeforeSignalStart = {
            val floor = _ui.value.appliedMinReward
            mediaOutput.prepare(manual = _ui.value.manualFeedback, rewardReady = false)
            val snap = feedback.onSessionReset(floor)
            _ui.update { state ->
                state.clearedSignal(autoOn = state.autoThreshold)
                    .withFeedback(snap)
                    .withMedia(mediaStatus(snap.requestedPercent), outputs.destination)
            }
        },
    )

    init {
        scope.launch {
            manager.state.collect { applyLink(it) }
        }
        scope.launch(Dispatchers.Default) {
            manager.samplesMicrovolts.collect { chunk ->
                signalMutex.withLock {
                    if (chunk.sessionId < processingSession) return@withLock
                    if (chunk.sessionId != processingSession) {
                        processingSession = chunk.sessionId
                        processor.reset()
                        processor.setBands(currentBands())
                        pipeline.resetSession()
                    }
                    pipeline.noteTime(SystemClock.elapsedRealtime())
                    val latestUv = if (chunk.samples.isEmpty()) null else chunk.samples[chunk.samples.size - 1]
                    var snapshot: EegSnapshot? = null
                    var reward: RewardState? = null
                    val contactAcceptable = electrodeAcceptable(latestElectrode)
                    for (sample in chunk.samples) {
                        try {
                            val next = processor.push(sample)
                            if (next != null) {
                                snapshot = next
                                reward = pipeline.observe(
                                    timeMs = SystemClock.elapsedRealtime(),
                                    readings = next.amplitudes.map {
                                        AmplitudeReading(it.bandId, it.microvoltsRms)
                                    },
                                    contactAcceptable = contactAcceptable,
                                )
                            }
                        } catch (error: Exception) {
                            logProcessingError(error)
                        }
                    }
                    val published = snapshot
                    val publishedReward = reward
                    val nowNs = System.nanoTime()
                    val rawDue = nowNs - lastRawUiNs >= 100_000_000L
                    if (published != null && publishedReward != null) {
                        lastRawUiNs = nowNs
                        publishSignal(published, publishedReward, latestUv)
                    } else if (latestUv != null && rawDue) {
                        lastRawUiNs = nowNs
                        publishIncoming(latestUv)
                    }
                }
            }
        }
        refreshEnvironment()
    }

    fun onUiObserverStarted() {
        lifetime.onUiObserverStarted()
    }

    fun onUiObserverStopped() {
        lifetime.onUiObserverStopped()
    }

    fun refreshEnvironment() {
        val granted = CallibriPermissions.hasRuntimePermissions(app)
        _ui.update {
            it.copy(
                permissionsGranted = granted,
                overlayPermissionGranted = Settings.canDrawOverlays(app),
                notificationPermissionGranted = notificationsGranted(),
            ).withMedia(mediaStatus(it.feedbackVolumePercent), outputs.destination)
        }
        manager.refreshRadioState()
    }

    fun onPermissionResult(granted: Boolean) {
        _ui.update {
            it.copy(
                permissionsGranted = granted,
                linkMessage = if (granted) it.linkMessage else "Bluetooth permission was denied. Allow it in system settings, then scan again.",
                linkMessageIsError = !granted || it.linkMessageIsError,
            )
        }
        if (granted) scan()
    }

    fun scan() {
        refreshEnvironment()
        if (!_ui.value.permissionsGranted) return
        manager.startScan()
    }

    fun stopScan() {
        manager.stopScan()
    }

    fun connect(address: String) {
        manager.connect(address)
    }

    fun startEeg() {
        if (!SessionPolicy.shouldStartForegroundService(explicitEegStart = true)) return
        liveVisualOpen = true
        lifetime.arm()
        _ui.update {
            it.copy(
                serviceStartedAtElapsedMs = SystemClock.elapsedRealtime(),
                linkMessage = if (it.linkMessage?.startsWith("Couldn't start EEG") == true) null else it.linkMessage,
                linkMessageIsError = if (it.linkMessage?.startsWith("Couldn't start EEG") == true) false else it.linkMessageIsError,
            )
        }
        manager.startEeg()
        try {
            NeurofeedbackSessionService.start(app)
        } catch (error: Exception) {
            Log.e(TAG, "foreground service start failed", error)
            _ui.update {
                it.copy(
                    linkMessage = "EEG started, but the background session could not start: ${error.message ?: "unknown error"}",
                    linkMessageIsError = true,
                )
            }
        }
    }

    fun selectAdcInput(label: String) {
        manager.selectAdcInput(label)
    }

    fun stopEeg() {
        liveVisualOpen = false
        visualEpoch++
        lifetime.disarm()
        releaseExternalVolume()
        clearVisualOverlay()
        manager.stopEeg()
    }

    fun selectBuiltInFeedback() {
        if (outputs.destination == FeedbackDestination.BuiltIn) return
        val keepAudio = audioEnabled
        releaseExternalVolume()
        outputs.select(FeedbackDestination.BuiltIn)
        if (keepAudio) {
            publishFeedback(feedback.startAudio())
        } else {
            publishMedia()
        }
    }

    fun selectExternalFeedback() {
        if (mediaGate.isFixed) {
            _ui.update {
                it.copy(mediaNote = "External media feedback unavailable on this device.")
                    .withMedia(mediaStatus(it.feedbackVolumePercent), outputs.destination)
            }
            return
        }
        if (outputs.destination == FeedbackDestination.ExternalMedia) return
        if (_ui.value.audioPlaying) publishFeedback(feedback.stopAudio())
        audioEnabled = true
        outputs.select(FeedbackDestination.ExternalMedia)
        publishMedia(note = if (mediaGate.capturedMaxIndex == null) {
            "Set the current volume as the maximum before feedback can move it."
        } else {
            null
        })
        engageExternalIfNeeded()
    }

    fun toggleExternalMedia() {
        if (outputs.destination == FeedbackDestination.ExternalMedia) {
            selectBuiltInFeedback()
        } else {
            selectExternalFeedback()
        }
    }

    fun captureMediaMaximum() {
        val captured = mediaGate.captureCurrentAsMaximum()
        publishMedia(
            note = if (captured == null) {
                "External media feedback unavailable on this device."
            } else {
                "Captured media step $captured as the loudest feedback level. Hardware volume buttons do not change this ceiling."
            },
        )
        engageExternalIfNeeded()
    }

    fun startTestAudio() {
        if (outputs.destination != FeedbackDestination.BuiltIn) return
        audioEnabled = true
        publishFeedback(feedback.startAudio())
    }

    fun stopTestAudio() {
        if (outputs.destination == FeedbackDestination.BuiltIn) audioEnabled = false
        publishFeedback(feedback.stopAudio())
    }

    fun setAudioFeedbackEnabled(enabled: Boolean) {
        audioEnabled = enabled
        if (!enabled) {
            if (outputs.destination == FeedbackDestination.ExternalMedia) {
                releaseExternalVolume()
            } else {
                publishFeedback(feedback.stopAudio())
            }
            _ui.update { it.copy(audioFeedbackEnabled = false) }
            return
        }
        if (outputs.destination == FeedbackDestination.ExternalMedia) {
            _ui.update { it.copy(audioFeedbackEnabled = true) }
            engageExternalIfNeeded()
        } else {
            publishFeedback(feedback.startAudio())
        }
    }

    fun setVisualFeedbackEnabled(enabled: Boolean) {
        visualEnabled = enabled
        if (!enabled) {
            visualEpoch++
            clearVisualOverlay()
            _ui.update { it.copy(visualFeedbackEnabled = false, visualNote = null) }
            return
        }
        _ui.update { it.copy(visualFeedbackEnabled = true, visualNote = null) }
        pushVisual(currentFeedbackPercent(), _ui.value.rewardReady, _ui.value.manualFeedback)
    }

    fun setMaxDimAlpha(alpha: Double) {
        val clamped = VisualDimming.clampSetting(alpha, safeMaxAlpha)
        maxDimAlpha = clamped
        visualPrefs.save(clamped, safeMaxAlpha)
        _ui.update { it.copy(maxDimAlpha = clamped) }
        if (visualEnabled) {
            pushVisual(currentFeedbackPercent(), _ui.value.rewardReady, _ui.value.manualFeedback)
        }
    }

    fun setFeedbackLowerBound(lower: Double) {
        if (!lower.isFinite()) return
        val next = FeedbackIntensity.coerceLower(lower.roundToInt().toDouble(), feedbackUpperBound) ?: return
        commitFeedbackRange(next, feedbackUpperBound)
    }

    fun setFeedbackUpperBound(upper: Double) {
        if (!upper.isFinite()) return
        val next = FeedbackIntensity.coerceUpper(feedbackLowerBound, upper.roundToInt().toDouble()) ?: return
        commitFeedbackRange(feedbackLowerBound, next)
    }

    private fun commitFeedbackRange(lower: Double, upper: Double) {
        if (!FeedbackIntensity.isValid(lower, upper)) return
        if (lower == feedbackLowerBound && upper == feedbackUpperBound) return
        val snap = feedback.setFeedbackRange(lower, upper)
        feedbackLowerBound = lower
        feedbackUpperBound = upper
        visualPrefs.saveRange(lower, upper)
        _ui.update { state ->
            state.copy(
                feedbackLowerBound = lower,
                feedbackUpperBound = upper,
            ).withFeedback(snap).withMedia(mediaStatus(snap.requestedPercent), outputs.destination)
        }
        if (visualEnabled) {
            val state = _ui.value
            pushVisual(currentFeedbackPercent(), state.rewardReady, state.manualFeedback)
        }
    }

    fun dimmingGeneration(): Int = dimming.generation

    fun dimmingAttached(): Boolean = dimming.isAttached()

    fun setManualFeedback(enabled: Boolean) {
        if (enabled) {
            feedback.setManualPercent(_ui.value.rewardSmoothed)
        }
        mediaOutput.prepare(manual = enabled, rewardReady = _ui.value.rewardReady)
        publishFeedback(feedback.setManualEnabled(enabled))
        if (enabled && audioEnabled) engageExternalIfNeeded()
        pushVisual(currentFeedbackPercent(), _ui.value.rewardReady, enabled)
    }

    fun setManualFeedbackPercent(percent: Double) {
        mediaOutput.prepare(manual = true, rewardReady = _ui.value.rewardReady)
        val updated = feedback.setManualPercent(percent)
        val arm = outputs.destination == FeedbackDestination.ExternalMedia &&
            _ui.value.manualFeedback &&
            !mediaGate.isControlling
        if (arm && audioEnabled) {
            engageExternalIfNeeded()
            if (!mediaGate.isControlling) publishFeedback(updated)
        } else {
            publishFeedback(updated)
        }
        pushVisual(percent, rewardReady = true, manual = true)
    }

    fun disconnect() {
        liveVisualOpen = false
        visualEpoch++
        lifetime.disarm()
        clearVisualOverlay()
        manager.disconnect()
    }

    fun onScreenDisposed() {
        manager.stopScan()
    }

    fun setActivityForeground(foreground: Boolean) {
        _ui.update { it.copy(activityInForeground = foreground) }
    }

    fun setOverlayWanted(wanted: Boolean) {
        _ui.update { it.copy(overlayWanted = wanted) }
    }

    fun noteOverlayPermissionDenied() {
        _ui.update { it.copy(overlayPermissionGranted = false, overlayVisible = false, overlayDisplayedReward = null) }
    }

    fun markServiceRunning(running: Boolean) {
        _ui.update { current ->
            if (current.serviceRunning == running && (running || current.serviceStartedAtElapsedMs == null)) {
                current
            } else {
                current.copy(
                    serviceRunning = running,
                    serviceStartedAtElapsedMs = if (running) {
                        current.serviceStartedAtElapsedMs ?: SystemClock.elapsedRealtime()
                    } else {
                        null
                    },
                )
            }
        }
    }

    fun noteOverlayPaint(visible: Boolean, reward: Double?) {
        _ui.update { current ->
            if (current.overlayVisible == visible && current.overlayDisplayedReward == reward) {
                current
            } else {
                current.copy(overlayVisible = visible, overlayDisplayedReward = reward)
            }
        }
    }

    fun updateLow(id: String, text: String) {
        _ui.update { state ->
            state.copy(
                bands = state.bands.map { if (it.id == id) it.copy(lowText = text) else it },
                formError = null,
            )
        }
    }

    fun updateHigh(id: String, text: String) {
        _ui.update { state ->
            state.copy(
                bands = state.bands.map { if (it.id == id) it.copy(highText = text) else it },
                formError = null,
            )
        }
    }

    fun updateTarget(id: String, text: String) {
        editBand(id) { it.copy(targetText = text) }
    }

    fun updateWeight(id: String, text: String) {
        editBand(id) { it.copy(weightText = text) }
    }

    fun updateManual(id: String, text: String) {
        editBand(id) { it.copy(manualText = text) }
    }

    fun updateWindow(text: String) {
        _ui.update { it.copy(windowText = text, feedbackError = null) }
    }

    fun updateSmoothing(text: String) {
        _ui.update { it.copy(smoothingText = text, feedbackError = null) }
    }

    fun updateMinReward(text: String) {
        _ui.update { it.copy(minRewardText = text, feedbackError = null) }
    }

    fun setAutoThreshold(enabled: Boolean) {
        _ui.update { it.copy(autoThreshold = enabled, feedbackError = null) }
        scope.launch(Dispatchers.Default) {
            val reward = signalMutex.withLock {
                pipeline.setAutoEnabled(enabled)
                pipeline.recompute(SystemClock.elapsedRealtime())
            }
            publishReward(reward)
        }
    }

    fun applyBands() {
        val parsed = ArrayList<FrequencyBand>(_ui.value.bands.size)
        for (band in _ui.value.bands) {
            val range = parseRange(band.lowText, band.highText)
            if (range == null) {
                _ui.update {
                    it.copy(formError = "Use a low and a high between 0.5 and 100 Hz, with at least 1 Hz between them.")
                }
                return
            }
            parsed += FrequencyBand(band.id, band.label, range.first, range.second)
        }
        scope.launch(Dispatchers.Default) {
            val applied = signalMutex.withLock {
                try {
                    processor.setBands(parsed)
                    pipeline.resetSession()
                    true
                } catch (error: Exception) {
                    logProcessingError(error)
                    false
                }
            }
            if (!applied) {
                _ui.update { it.copy(formError = "Those frequencies could not be applied. Try a wider band.") }
                return@launch
            }
            _ui.update { state ->
                state.clearedSignal(autoOn = state.autoThreshold).copy(
                    formError = null,
                    bands = state.bands.map { band ->
                        val match = parsed.first { it.id == band.id }
                        band.copy(
                            appliedLowHz = match.lowHz,
                            appliedHighHz = match.highHz,
                            amplitudeUv = null,
                            thresholdUv = null,
                            score = null,
                            windowSuccess = null,
                            validInWindow = 0,
                            latestAccepted = false,
                        )
                    },
                )
            }
        }
    }

    fun applyFeedbackSettings() {
        val state = _ui.value
        val windowSeconds = state.windowText.trim().toDoubleOrNull()
        if (windowSeconds == null || windowSeconds < 5.0 || windowSeconds > 120.0) {
            _ui.update { it.copy(feedbackError = "Rolling window must be between 5 and 120 seconds.") }
            return
        }
        val smoothing = state.smoothingText.trim().toDoubleOrNull()
        if (smoothing == null || smoothing < 100.0 || smoothing > 2_000.0) {
            _ui.update { it.copy(feedbackError = "Smoothing response must be between 100 and 2000 ms.") }
            return
        }
        val minReward = state.minRewardText.trim().toDoubleOrNull()
        if (minReward == null || minReward < 0.0 || minReward > 50.0) {
            _ui.update { it.copy(feedbackError = "Minimum reward must be between 0 and 50%.") }
            return
        }
        val specs = ArrayList<BandThresholdSpec>(state.bands.size)
        val parsedTargets = HashMap<String, Double>()
        val parsedWeights = HashMap<String, Double>()
        val parsedManuals = HashMap<String, Double>()
        var weightSum = 0.0
        for (band in state.bands) {
            val targetPercent = band.targetText.trim().toDoubleOrNull()
            if (targetPercent == null || targetPercent < 50.0 || targetPercent > 95.0) {
                _ui.update { it.copy(feedbackError = "Target success for ${band.label} must be between 50 and 95%.") }
                return
            }
            val weightPercent = band.weightText.trim().toDoubleOrNull()
            if (weightPercent == null || weightPercent < 0.0 || weightPercent > 100.0) {
                _ui.update { it.copy(feedbackError = "Weight for ${band.label} must be between 0 and 100%.") }
                return
            }
            val manual = band.manualText.trim().toDoubleOrNull()
            if (manual == null || manual < 0.1 || manual > FRE1Protocol.MAX_PLAUSIBLE_MICROVOLTS) {
                _ui.update {
                    it.copy(feedbackError = "Manual threshold for ${band.label} must be between 0.1 and ${FRE1Protocol.MAX_PLAUSIBLE_MICROVOLTS.toInt()} µV.")
                }
                return
            }
            parsedTargets[band.id] = targetPercent / 100.0
            parsedWeights[band.id] = weightPercent
            parsedManuals[band.id] = manual
            weightSum += weightPercent
        }
        if (weightSum <= 0.0) {
            _ui.update { it.copy(feedbackError = "At least one band weight must be above 0.") }
            return
        }
        for (band in state.bands) {
            specs += BandThresholdSpec(
                bandId = band.id,
                goal = band.goal,
                targetSuccess = parsedTargets.getValue(band.id),
                weight = parsedWeights.getValue(band.id) / weightSum,
                manualThresholdUv = parsedManuals.getValue(band.id),
            )
        }
        val config = ThresholdConfig(
            windowSeconds = windowSeconds,
            minRewardPercent = minReward,
            smoothingMillis = smoothing,
            bands = specs,
        )
        scope.launch(Dispatchers.Default) {
            val reward = signalMutex.withLock {
                pipeline.updateConfig(config)
                pipeline.setAutoEnabled(_ui.value.autoThreshold)
                pipeline.recompute(SystemClock.elapsedRealtime())
            }
            _ui.update { current ->
                current.copy(
                    feedbackError = null,
                    appliedMinReward = minReward,
                    windowText = formatNumber(windowSeconds),
                    smoothingText = formatNumber(smoothing),
                    minRewardText = formatNumber(minReward),
                    bands = current.bands.map { band ->
                        val spec = specs.first { it.bandId == band.id }
                        band.copy(
                            targetSuccess = spec.targetSuccess,
                            targetText = formatPercentNumber(spec.targetSuccess * 100.0),
                            weight = spec.weight,
                            weightText = formatPercentNumber(spec.weight * 100.0),
                            manualThresholdUv = spec.manualThresholdUv,
                            manualText = formatNumber(spec.manualThresholdUv),
                        )
                    },
                )
            }
            publishReward(reward)
        }
    }

    private fun editBand(id: String, edit: (BandUi) -> BandUi) {
        _ui.update { state ->
            state.copy(
                bands = state.bands.map { if (it.id == id) edit(it) else it },
                feedbackError = null,
            )
        }
    }

    private fun applyLink(link: CallibriState) {
        val connected = link.phase == SessionPhase.Connected
        latestElectrode = if (connected) link.electrode else null
        if (linkWasConnected && !connected) {
            liveVisualOpen = false
            visualEpoch++
            lifetime.disarm()
            clearVisualOverlay()
            scope.launch(Dispatchers.Default) {
                signalMutex.withLock { pipeline.resetSession() }
            }
        }
        val streaming = connected && link.streaming
        lifetime.setStreaming(streaming)
        if (liveDriveActive && !streaming) {
            val stopAudio = !connected || !_ui.value.manualFeedback
            publishFeedback(feedback.onLiveInactive(stopAudio = stopAudio))
        } else if (streaming && !liveDriveActive) {
            publishFeedback(feedback.markLiveActive())
            engageExternalIfNeeded()
        }
        liveDriveActive = streaming
        linkWasConnected = connected
        _ui.update { current ->
            val connectedNow = link.phase == SessionPhase.Connected
            val base = if (connectedNow) current else current.clearedSignal(autoOn = current.autoThreshold)
            base.copy(
                phase = link.phase,
                bluetoothEnabled = link.bluetoothEnabled,
                locationServicesRequired = link.locationServicesRequired,
                locationServicesEnabled = link.locationServicesEnabled,
                devices = link.devices,
                deviceName = link.deviceName,
                batteryPercent = link.batteryPercent,
                electrode = link.electrode,
                extSwInput = link.extSwInput,
                adcInput = link.adcInput,
                gain = link.gain,
                dataOffset = link.dataOffset,
                hardwareFilter = link.hardwareFilter,
                signalIngress = link.signalIngress,
                streaming = link.streaming,
                sampleRateHz = link.sampleRateHz,
                linkMessage = link.message,
                linkMessageIsError = link.messageIsError,
            ).withMedia(mediaStatus(current.feedbackVolumePercent), outputs.destination)
        }
    }

    private fun publishFeedback(snapshot: FeedbackSnapshot) {
        _ui.update {
            it.withFeedback(snapshot)
                .copy(audioFeedbackEnabled = audioEnabled)
                .withMedia(mediaStatus(snapshot.requestedPercent), outputs.destination)
        }
    }

    private fun publishMedia(note: String? = _ui.value.mediaNote) {
        _ui.update {
            it.copy(mediaNote = note, audioFeedbackEnabled = audioEnabled)
                .withMedia(mediaStatus(it.feedbackVolumePercent), outputs.destination)
        }
    }

    private fun releaseExternalVolume() {
        if (outputs.destination != FeedbackDestination.ExternalMedia) return
        mediaOutput.prepare(manual = false, rewardReady = false)
        publishFeedback(feedback.stopAudio())
    }

    private fun engageExternalIfNeeded() {
        if (!audioEnabled) return
        val state = _ui.value
        val manual = state.manualFeedback
        val streaming = state.streaming && state.phase == SessionPhase.Connected
        if (!ExternalEngagement.shouldStart(
                externalSelected = outputs.destination == FeedbackDestination.ExternalMedia,
                volumeFixed = mediaGate.isFixed,
                hasCapturedMax = mediaGate.capturedMaxIndex != null,
                alreadyControlling = mediaGate.isControlling,
                manual = manual,
                eegStreaming = streaming,
            )
        ) {
            return
        }
        mediaOutput.prepare(manual = manual, rewardReady = state.rewardReady)
        publishFeedback(feedback.startAudio())
    }

    private fun publishSignal(snapshot: EegSnapshot, reward: RewardState, latestUv: Double?) {
        val active = _ui.value.phase == SessionPhase.Connected && _ui.value.streaming
        if (active && audioEnabled) engageExternalIfNeeded()
        mediaOutput.prepare(manual = _ui.value.manualFeedback, rewardReady = reward.rewardReady)
        val heard = feedback.setLiveReward(reward.smoothedPercent, SystemClock.elapsedRealtime(), active)
        val manual = _ui.value.manualFeedback
        _ui.update { state ->
            if (state.phase != SessionPhase.Connected) return@update state
            state.withReward(reward).withFeedback(heard).copy(
                latestRawUv = latestUv ?: snapshot.latestRawMicrovolts,
                waveform = snapshot.waveform,
                audioFeedbackEnabled = audioEnabled,
            ).withMedia(mediaStatus(heard.requestedPercent), outputs.destination)
        }
        if (!manual) pushVisual(reward.smoothedPercent, reward.rewardReady, manual = false, liveSample = true)
    }

    private fun publishIncoming(latestUv: Double) {
        _ui.update { state ->
            if (state.phase != SessionPhase.Connected) state else state.copy(latestRawUv = latestUv)
        }
    }

    private fun publishReward(reward: RewardState) {
        val active = _ui.value.phase == SessionPhase.Connected && _ui.value.streaming
        mediaOutput.prepare(manual = _ui.value.manualFeedback, rewardReady = reward.rewardReady)
        val manual = _ui.value.manualFeedback
        val heard = feedback.setLiveReward(reward.smoothedPercent, SystemClock.elapsedRealtime(), active)
        _ui.update { state ->
            val next = if (state.phase != SessionPhase.Connected) {
                state.copy(autoThreshold = reward.autoEnabled, rewardStatus = reward.statusLabel).withFeedback(heard)
            } else {
                state.withReward(reward).withFeedback(heard)
            }
            next.copy(audioFeedbackEnabled = audioEnabled)
                .withMedia(mediaStatus(heard.requestedPercent), outputs.destination)
        }
        if (!manual) pushVisual(reward.smoothedPercent, reward.rewardReady, manual = false, liveSample = true)
    }

    private fun currentBands(): List<FrequencyBand> =
        _ui.value.bands.map { FrequencyBand(it.id, it.label, it.appliedLowHz, it.appliedHighHz) }

    private fun notificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            app,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun currentFeedbackPercent(): Double {
        val state = _ui.value
        return if (state.manualFeedback) state.feedbackVolumePercent else state.rewardSmoothed
    }

    private fun mediaStatus(commandPercent: Double) =
        mediaGate.status(commandPercent, feedbackLowerBound, feedbackUpperBound)

    private fun pushVisual(
        percent: Double,
        rewardReady: Boolean,
        manual: Boolean,
        liveSample: Boolean = false,
    ) {
        if (liveSample && !liveVisualOpen) return
        val epoch = visualEpoch
        val state = _ui.value
        val live = state.streaming && state.phase == SessionPhase.Connected
        if (!visualEnabled || (!manual && !live)) {
            if (epoch == visualEpoch && (state.visualOverlayActive || dimming.isAttached())) {
                clearVisualOverlay()
            }
            return
        }
        if (epoch != visualEpoch) return
        val intensity = FeedbackIntensity.intensity(percent, feedbackLowerBound, feedbackUpperBound)
        val alpha = FeedbackModes.visualAlpha(
            visualEnabled = true,
            manual = manual,
            rewardReady = rewardReady,
            rewardPercent = percent,
            maxDimAlpha = maxDimAlpha,
            lowerBound = feedbackLowerBound,
            upperBound = feedbackUpperBound,
        )
        val normalized = if (manual || rewardReady) intensity else 0.0
        noteVisualUpdate(alpha)
        dimming.setTargetAlpha(alpha)
        if (epoch != visualEpoch) {
            dimming.hide()
            return
        }
        _ui.update { current ->
            if (epoch != visualEpoch) return@update current
            current.copy(
                visualFeedbackEnabled = true,
                visualNormalized = normalized,
                visualRequestedAlpha = alpha,
                visualUpdatesPerSecond = visualUpdateTimes.size,
                maxDimAlpha = maxDimAlpha,
            )
        }
    }

    private fun clearVisualOverlay() {
        lastVisualCommand = Double.NaN
        visualUpdateTimes.clear()
        dimming.hide()
        _ui.update {
            it.copy(
                visualOverlayActive = false,
                visualRequestedAlpha = 0.0,
                visualAppliedAlpha = 0.0,
                visualNormalized = 0.0,
                visualUpdatesPerSecond = 0,
            )
        }
    }

    private fun noteVisualUpdate(alpha: Double) {
        if (!lastVisualCommand.isNaN() && abs(alpha - lastVisualCommand) < 0.005) return
        lastVisualCommand = alpha
        val now = SystemClock.elapsedRealtime()
        visualUpdateTimes.addLast(now)
        while (visualUpdateTimes.isNotEmpty() && now - visualUpdateTimes.first() > 1_000L) {
            visualUpdateTimes.removeFirst()
        }
    }

    private fun logProcessingError(error: Exception) {
        val now = System.nanoTime()
        if (now - lastProcessErrorNs < 1_000_000_000L) return
        lastProcessErrorNs = now
        Log.e(TAG, "processing error", error)
    }

    private companion object {
        const val TAG = "CallibriNFB"
    }
}
