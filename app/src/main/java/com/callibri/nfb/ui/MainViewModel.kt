package com.callibri.nfb.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.callibri.nfb.callibri.CallibriDevice
import com.callibri.nfb.callibri.ElectrodeContact
import com.callibri.nfb.callibri.SessionPhase
import com.callibri.nfb.callibri.SignalIngress
import com.callibri.nfb.feedback.FeedbackDestination
import com.callibri.nfb.feedback.FeedbackIntensity
import com.callibri.nfb.feedback.FeedbackSnapshot
import com.callibri.nfb.feedback.MediaRestore
import com.callibri.nfb.feedback.MediaVolumeStatus
import com.callibri.nfb.feedback.RewardState
import com.callibri.nfb.protocol.BandGoal
import com.callibri.nfb.protocol.FRE1Protocol
import com.callibri.nfb.threshold.BandThresholdSpec
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

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
    val enabled: Boolean = true,
    val includedInReward: Boolean = true,
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
    val activityInForeground: Boolean = true,
    val serviceRunning: Boolean = false,
    val overlayPermissionGranted: Boolean = false,
    val notificationPermissionGranted: Boolean = true,
    val overlayWanted: Boolean = false,
    val overlayVisible: Boolean = false,
    val overlayDisplayedReward: Double? = null,
    val serviceStartedAtElapsedMs: Long? = null,
    val feedbackDestination: FeedbackDestination = FeedbackDestination.BuiltIn,
    val mediaFixed: Boolean = false,
    val mediaCurrentIndex: Int = 0,
    val mediaDeviceMaxIndex: Int = 0,
    val mediaCapturedMaxIndex: Int? = null,
    val mediaMinimumIndex: Int? = null,
    val mediaDesiredIndex: Int? = null,
    val mediaLastAppliedIndex: Int? = null,
    val mediaStepCount: Int? = null,
    val mediaControlling: Boolean = false,
    val mediaRestore: MediaRestore = MediaRestore.NA,
    val mediaCurrentPercent: Int = 0,
    val mediaLevelPercent: Int? = null,
    val mediaNote: String? = null,
    val audioFeedbackEnabled: Boolean = false,
    val visualFeedbackEnabled: Boolean = false,
    val maxDimAlpha: Double = 0.75,
    val safeMaxDimAlpha: Double = 0.78,
    val systemObscuringOpacity: Double? = null,
    val visualNormalized: Double = 0.0,
    val visualRequestedAlpha: Double = 0.0,
    val visualAppliedAlpha: Double = 0.0,
    val visualOverlayActive: Boolean = false,
    val visualUpdatesPerSecond: Int = 0,
    val visualNote: String? = null,
    val feedbackLowerBound: Double = FeedbackIntensity.DEFAULT_LOWER,
    val feedbackUpperBound: Double = FeedbackIntensity.DEFAULT_UPPER,
    val feedbackIntensity: Double = 0.0,
    val activeBandCount: Int = 3,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val session = (application as com.callibri.nfb.CallibriNfbApp).session()
    val ui: StateFlow<MainUiState> = session.ui

    init {
        session.onUiObserverStarted()
    }

    fun refreshEnvironment() = session.refreshEnvironment()

    fun onPermissionResult(granted: Boolean) = session.onPermissionResult(granted)

    fun scan() = session.scan()

    fun stopScan() = session.stopScan()

    fun connect(address: String) = session.connect(address)

    fun startEeg() = session.startEeg()

    fun selectAdcInput(label: String) = session.selectAdcInput(label)

    fun stopEeg() = session.stopEeg()

    fun startTestAudio() = session.startTestAudio()

    fun stopTestAudio() = session.stopTestAudio()

    fun setManualFeedback(enabled: Boolean) = session.setManualFeedback(enabled)

    fun setManualFeedbackPercent(percent: Double) = session.setManualFeedbackPercent(percent)

    fun disconnect() = session.disconnect()

    fun onScreenDisposed() = session.onScreenDisposed()

    fun setActivityForeground(foreground: Boolean) = session.setActivityForeground(foreground)

    fun setOverlayWanted(wanted: Boolean) = session.setOverlayWanted(wanted)

    fun selectBuiltInFeedback() = session.selectBuiltInFeedback()

    fun selectExternalFeedback() = session.selectExternalFeedback()

    fun captureMediaMaximum() = session.captureMediaMaximum()

    fun setAudioFeedbackEnabled(enabled: Boolean) = session.setAudioFeedbackEnabled(enabled)

    fun setVisualFeedbackEnabled(enabled: Boolean) = session.setVisualFeedbackEnabled(enabled)

    fun setMaxDimAlpha(alpha: Double) = session.setMaxDimAlpha(alpha)

    fun setFeedbackLowerBound(lower: Double) = session.setFeedbackLowerBound(lower)

    fun setFeedbackUpperBound(upper: Double) = session.setFeedbackUpperBound(upper)

    fun updateLow(id: String, text: String) = session.updateLow(id, text)

    fun updateHigh(id: String, text: String) = session.updateHigh(id, text)

    fun updateTarget(id: String, text: String) = session.updateTarget(id, text)

    fun updateWeight(id: String, text: String) = session.updateWeight(id, text)

    fun updateManual(id: String, text: String) = session.updateManual(id, text)

    fun updateWindow(text: String) = session.updateWindow(text)

    fun updateSmoothing(text: String) = session.updateSmoothing(text)

    fun updateMinReward(text: String) = session.updateMinReward(text)

    fun setAutoThreshold(enabled: Boolean) = session.setAutoThreshold(enabled)

    fun setBandEnabled(id: String, enabled: Boolean) = session.setBandEnabled(id, enabled)

    fun applyBands() = session.applyBands()

    fun applyFeedbackSettings() = session.applyFeedbackSettings()

    override fun onCleared() {
        session.onUiObserverStopped()
        super.onCleared()
    }
}

internal fun electrodeAcceptable(electrode: ElectrodeContact?): Boolean =
    electrode == null || electrode == ElectrodeContact.Normal

internal fun MainUiState.withMedia(status: MediaVolumeStatus, destination: FeedbackDestination): MainUiState = copy(
    feedbackDestination = destination,
    mediaFixed = status.fixed,
    mediaCurrentIndex = status.currentIndex,
    mediaDeviceMaxIndex = status.deviceMaxIndex,
    mediaCapturedMaxIndex = status.capturedMaxIndex,
    mediaMinimumIndex = status.minimumIndex,
    mediaDesiredIndex = status.desiredIndex,
    mediaLastAppliedIndex = status.lastAppliedIndex,
    mediaStepCount = status.stepCount,
    mediaControlling = status.controlling,
    mediaRestore = status.restore,
    mediaCurrentPercent = status.currentPercent,
    mediaLevelPercent = status.levelPercent,
)

internal fun MainUiState.withFeedback(snapshot: FeedbackSnapshot): MainUiState = copy(
    feedbackVolumePercent = snapshot.requestedPercent,
    playerVolume = snapshot.playerVolume,
    audioPlaying = snapshot.playing,
    manualFeedback = snapshot.manualEnabled,
    manualFeedbackPercent = snapshot.manualPercent,
    liveFeedbackActive = snapshot.liveActive,
    feedbackUpdatesPerSecond = snapshot.updatesPerSecond,
    audioFailure = snapshot.failure,
).withOutputMapping()

internal fun MainUiState.withOutputMapping(): MainUiState {
    val command = if (manualFeedback) feedbackVolumePercent else rewardSmoothed
    val intensity = if (!manualFeedback && activeBandCount == 0) {
        0.0
    } else {
        FeedbackIntensity.intensity(command, feedbackLowerBound, feedbackUpperBound)
    }
    return copy(feedbackIntensity = intensity)
}

internal fun MainUiState.withReward(reward: RewardState): MainUiState = copy(
    rewardRaw = reward.rawPercent,
    rewardSmoothed = reward.smoothedPercent,
    rewardStatus = reward.statusLabel,
    rewardReady = reward.rewardReady,
    elapsedMillis = reward.elapsedMillis,
    validObservations = reward.validObservations,
    rejectedObservations = reward.rejectedObservations,
    autoThreshold = reward.autoEnabled,
    activeBandCount = reward.activeBandCount,
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
            enabled = match.enabled,
            includedInReward = match.includedInReward,
        )
    },
).withOutputMapping()

internal fun MainUiState.clearedSignal(autoOn: Boolean): MainUiState {
    val windowSeconds = windowText.trim().toDoubleOrNull()?.toInt()?.coerceAtLeast(1)
        ?: FRE1Protocol.WINDOW_SECONDS.toInt()
    val status = when {
        bands.none { it.enabled } -> "No active training bands"
        autoOn -> "Calibrating... 0 / $windowSeconds seconds"
        else -> "Auto threshold: Off"
    }
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
    ).withOutputMapping()
}

internal fun initialBands(): List<BandUi> {
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

internal fun parseRange(lowText: String, highText: String): Pair<Double, Double>? {
    val low = lowText.trim().toDoubleOrNull() ?: return null
    val high = highText.trim().toDoubleOrNull() ?: return null
    if (!low.isFinite() || !high.isFinite()) return null
    if (low < 0.5 || high > 100.0 || high - low < 1.0) return null
    return low to high
}
