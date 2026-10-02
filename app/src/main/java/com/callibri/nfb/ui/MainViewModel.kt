package com.callibri.nfb.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.callibri.nfb.callibri.CallibriDevice
import com.callibri.nfb.callibri.CallibriManager
import com.callibri.nfb.callibri.CallibriPermissions
import com.callibri.nfb.callibri.CallibriState
import com.callibri.nfb.callibri.ElectrodeContact
import com.callibri.nfb.callibri.SessionPhase
import com.callibri.nfb.callibri.SignalIngress
import com.callibri.nfb.feedback.AudioFeedbackOutput
import com.callibri.nfb.feedback.FeedbackController
import com.callibri.nfb.feedback.FeedbackSnapshot
import com.callibri.nfb.feedback.RewardPipeline
import com.callibri.nfb.feedback.RewardState
import com.callibri.nfb.protocol.BandGoal
import com.callibri.nfb.protocol.FRE1Protocol
import com.callibri.nfb.protocol.FrequencyBand
import com.callibri.nfb.signal.EegProcessor
import com.callibri.nfb.signal.EegSnapshot
import com.callibri.nfb.threshold.AmplitudeReading
import com.callibri.nfb.threshold.BandThresholdSpec
import com.callibri.nfb.threshold.ThresholdConfig
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BandUi(
    val id: String,
    val label: String,
    val lowText: String,
    val highText: String,
    val appliedLowHz: Double,
    val appliedHighHz: Double,
    val amplitudeUv: Double?,
    val thresholdUv: Double?,
    val targetSuccess: Double,
    val targetText: String,
    val score: Double?,
    val windowSuccess: Double?,
    val validInWindow: Int,
    val latestAccepted: Boolean,
    val weight: Double,
    val weightText: String,
    val manualThresholdUv: Double,
    val manualText: String,
    val goal: BandGoal,
)

data class MainUiState(
    val phase: SessionPhase = SessionPhase.Disconnected,
    val permissionsGranted: Boolean = false,
    val bluetoothEnabled: Boolean = true,
    val locationServicesRequired: Boolean = false,
    val locationServicesEnabled: Boolean = true,
    val devices: List<CallibriDevice> = emptyList(),
    val deviceName: String? = null,
    val batteryPercent: Int? = null,
    val electrode: ElectrodeContact? = null,
    val extSwInput: String? = null,
    val adcInput: String? = null,
    val gain: String? = null,
    val dataOffset: String? = null,
    val hardwareFilter: String? = null,
    val signalIngress: SignalIngress = SignalIngress(),
    val streaming: Boolean = false,
    val sampleRateHz: Int = FRE1Protocol.SAMPLE_RATE_HZ,
    val linkMessage: String? = null,
    val linkMessageIsError: Boolean = false,
    val formError: String? = null,
    val feedbackError: String? = null,
    val bands: List<BandUi> = emptyList(),
    val latestRawUv: Double? = null,
    val waveform: List<Float> = emptyList(),
    val autoThreshold: Boolean = true,
    val rewardRaw: Double = FRE1Protocol.MIN_REWARD_PERCENT,
    val rewardSmoothed: Double = FRE1Protocol.MIN_REWARD_PERCENT,
    val rewardStatus: String = "Calibrating... 0 / ${FRE1Protocol.WINDOW_SECONDS.toInt()} seconds",
    val rewardReady: Boolean = false,
    val windowText: String = FRE1Protocol.WINDOW_SECONDS.toInt().toString(),
    val smoothingText: String = FRE1Protocol.SMOOTHING_MILLIS.toInt().toString(),
    val minRewardText: String = FRE1Protocol.MIN_REWARD_PERCENT.toInt().toString(),
    val appliedMinReward: Double = FRE1Protocol.MIN_REWARD_PERCENT,
    val elapsedMillis: Long = 0L,
    val validObservations: Int = 0,
    val rejectedObservations: Int = 0,
    val feedbackVolumePercent: Double = FRE1Protocol.MIN_REWARD_PERCENT,
    val playerVolume: Double = 0.0,
    val audioPlaying: Boolean = false,
    val manualFeedback: Boolean = false,
    val manualFeedbackPercent: Double = 50.0,
    val liveFeedbackActive: Boolean = false,
    val feedbackUpdatesPerSecond: Int = 0,
    val audioFailure: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val processor = EegProcessor(FRE1Protocol.SAMPLE_RATE_HZ.toDouble()).apply {
        setBands(FRE1Protocol.defaults().asList())
    }
    private val pipeline = RewardPipeline()
    private val feedback = FeedbackController(AudioFeedbackOutput())
    private val signalMutex = Mutex()
    private var liveDriveActive = false
    private var processingSession = Long.MIN_VALUE
    private var lastProcessErrorNs = 0L
    private var lastRawUiNs = 0L

    @Volatile
    private var latestElectrode: ElectrodeContact? = null
    private var linkWasConnected = false

    private val _ui = MutableStateFlow(MainUiState(bands = initialBands()))
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    private val manager = CallibriManager(
        context = application.applicationContext,
        scope = viewModelScope,
        onBeforeSignalStart = {
            val floor = _ui.value.appliedMinReward
            val snap = feedback.onSessionReset(floor)
            _ui.update { state -> state.clearedSignal(autoOn = state.autoThreshold).withFeedback(snap) }
        },
    )

    init {
        viewModelScope.launch {
            manager.state.collect { applyLink(it) }
        }
        viewModelScope.launch(Dispatchers.Default) {
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

    fun refreshEnvironment() {
        val granted = CallibriPermissions.hasRuntimePermissions(getApplication())
        _ui.update { it.copy(permissionsGranted = granted) }
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
        manager.startEeg()
    }

    fun selectAdcInput(label: String) {
        manager.selectAdcInput(label)
    }

    fun stopEeg() {
        manager.stopEeg()
    }

    fun startTestAudio() {
        publishFeedback(feedback.startAudio())
    }

    fun stopTestAudio() {
        publishFeedback(feedback.stopAudio())
    }

    fun setManualFeedback(enabled: Boolean) {
        if (enabled) {
            feedback.setManualPercent(_ui.value.rewardSmoothed)
        }
        publishFeedback(feedback.setManualEnabled(enabled))
    }

    fun setManualFeedbackPercent(percent: Double) {
        publishFeedback(feedback.setManualPercent(percent))
    }

    fun disconnect() {
        manager.disconnect()
    }

    fun onScreenDisposed() {
        manager.stopScan()
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
        editBand(id, feedback = true) { it.copy(targetText = text) }
    }

    fun updateWeight(id: String, text: String) {
        editBand(id, feedback = true) { it.copy(weightText = text) }
    }

    fun updateManual(id: String, text: String) {
        editBand(id, feedback = true) { it.copy(manualText = text) }
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
        viewModelScope.launch(Dispatchers.Default) {
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
        viewModelScope.launch(Dispatchers.Default) {
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
        viewModelScope.launch(Dispatchers.Default) {
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

    override fun onCleared() {
        feedback.release()
        manager.release()
        super.onCleared()
    }

    private fun editBand(id: String, feedback: Boolean, edit: (BandUi) -> BandUi) {
        _ui.update { state ->
            state.copy(
                bands = state.bands.map { if (it.id == id) edit(it) else it },
                formError = if (feedback) state.formError else null,
                feedbackError = if (feedback) null else state.feedbackError,
            )
        }
    }

    private fun applyLink(link: CallibriState) {
        val connected = link.phase == SessionPhase.Connected
        latestElectrode = if (connected) link.electrode else null
        if (linkWasConnected && !connected) {
            viewModelScope.launch(Dispatchers.Default) {
                signalMutex.withLock { pipeline.resetSession() }
            }
        }
        val streaming = connected && link.streaming
        if (liveDriveActive && !streaming) {
            val stopAudio = !connected || !_ui.value.manualFeedback
            publishFeedback(feedback.onLiveInactive(stopAudio = stopAudio))
        } else if (streaming && !liveDriveActive) {
            publishFeedback(feedback.markLiveActive())
        }
        liveDriveActive = streaming
        linkWasConnected = connected
        _ui.update { current ->
            val connected = link.phase == SessionPhase.Connected
            val base = if (connected) current else current.clearedSignal(autoOn = current.autoThreshold)
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
            )
        }
    }

    private fun publishFeedback(snapshot: FeedbackSnapshot) {
        _ui.update { it.withFeedback(snapshot) }
    }

    private fun publishSignal(snapshot: EegSnapshot, reward: RewardState, latestUv: Double?) {
        val active = _ui.value.phase == SessionPhase.Connected && _ui.value.streaming
        val heard = feedback.setLiveReward(reward.smoothedPercent, SystemClock.elapsedRealtime(), active)
        _ui.update { state ->
            if (state.phase != SessionPhase.Connected) return@update state
            state.withReward(reward).withFeedback(heard).copy(
                latestRawUv = latestUv ?: snapshot.latestRawMicrovolts,
                waveform = snapshot.waveform,
            )
        }
    }

    private fun publishIncoming(latestUv: Double) {
        _ui.update { state ->
            if (state.phase != SessionPhase.Connected) state else state.copy(latestRawUv = latestUv)
        }
    }

    private fun publishReward(reward: RewardState) {
        val active = _ui.value.phase == SessionPhase.Connected && _ui.value.streaming
        val heard = feedback.setLiveReward(reward.smoothedPercent, SystemClock.elapsedRealtime(), active)
        _ui.update { state ->
            if (state.phase != SessionPhase.Connected) {
                state.copy(autoThreshold = reward.autoEnabled, rewardStatus = reward.statusLabel).withFeedback(heard)
            } else {
                state.withReward(reward).withFeedback(heard)
            }
        }
    }

    private fun currentBands(): List<FrequencyBand> =
        _ui.value.bands.map { FrequencyBand(it.id, it.label, it.appliedLowHz, it.appliedHighHz) }

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

private fun electrodeAcceptable(electrode: ElectrodeContact?): Boolean =
    electrode == null || electrode == ElectrodeContact.Normal

private fun MainUiState.withFeedback(snapshot: FeedbackSnapshot): MainUiState = copy(
    feedbackVolumePercent = snapshot.requestedPercent,
    playerVolume = snapshot.playerVolume,
    audioPlaying = snapshot.playing,
    manualFeedback = snapshot.manualEnabled,
    manualFeedbackPercent = snapshot.manualPercent,
    liveFeedbackActive = snapshot.liveActive,
    feedbackUpdatesPerSecond = snapshot.updatesPerSecond,
    audioFailure = snapshot.failure,
)

private fun MainUiState.withReward(reward: RewardState): MainUiState = copy(
    rewardRaw = reward.rawPercent,
    rewardSmoothed = reward.smoothedPercent,
    rewardStatus = reward.statusLabel,
    rewardReady = reward.rewardReady,
    elapsedMillis = reward.elapsedMillis,
    validObservations = reward.validObservations,
    rejectedObservations = reward.rejectedObservations,
    autoThreshold = reward.autoEnabled,
    bands = bands.map { band ->
        val match = reward.bands.firstOrNull { it.bandId == band.id } ?: return@map band
        band.copy(
            amplitudeUv = match.amplitudeUv,
            thresholdUv = match.thresholdUv,
            targetSuccess = match.targetSuccess,
            score = match.score,
            windowSuccess = match.windowSuccess,
            validInWindow = match.validInWindow,
            latestAccepted = match.latestAccepted,
        )
    },
)

private fun MainUiState.clearedSignal(autoOn: Boolean): MainUiState {
    val windowSeconds = windowText.trim().toDoubleOrNull()?.toInt()?.coerceAtLeast(1)
        ?: FRE1Protocol.WINDOW_SECONDS.toInt()
    val status = if (autoOn) "Calibrating... 0 / $windowSeconds seconds" else "Auto threshold: Off"
    return copy(
        rewardRaw = appliedMinReward,
        rewardSmoothed = appliedMinReward,
        rewardStatus = status,
        rewardReady = false,
        elapsedMillis = 0L,
        validObservations = 0,
        rejectedObservations = 0,
        latestRawUv = null,
        waveform = emptyList(),
        bands = bands.map {
            it.copy(
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

private fun initialBands(): List<BandUi> {
    val policies = FRE1Protocol.policies().associateBy { it.id }
    return FRE1Protocol.defaults().asList().map { band ->
        val policy = policies.getValue(band.id)
        BandUi(
            id = band.id,
            label = band.label,
            lowText = formatHz(band.lowHz),
            highText = formatHz(band.highHz),
            appliedLowHz = band.lowHz,
            appliedHighHz = band.highHz,
            amplitudeUv = null,
            thresholdUv = null,
            targetSuccess = policy.targetSuccess,
            targetText = formatPercentNumber(policy.targetSuccess * 100.0),
            score = null,
            windowSuccess = null,
            validInWindow = 0,
            latestAccepted = false,
            weight = policy.weight,
            weightText = formatPercentNumber(policy.weight * 100.0),
            manualThresholdUv = BandThresholdSpec.DEFAULT_MANUAL_UV,
            manualText = formatNumber(BandThresholdSpec.DEFAULT_MANUAL_UV),
            goal = policy.goal,
        )
    }
}

internal fun formatHz(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)

internal fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)

internal fun formatPercentNumber(value: Double): String =
    if (kotlin.math.abs(value - value.toInt()) < 0.05) {
        value.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }

private fun parseRange(lowText: String, highText: String): Pair<Double, Double>? {
    val low = lowText.trim().toDoubleOrNull() ?: return null
    val high = highText.trim().toDoubleOrNull() ?: return null
    if (!low.isFinite() || !high.isFinite()) return null
    if (low < 0.5 || high > 100.0 || high - low < 1.0) return null
    return low to high
}
