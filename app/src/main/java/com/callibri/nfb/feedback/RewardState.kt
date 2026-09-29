package com.callibri.nfb.feedback

import com.callibri.nfb.threshold.AmplitudeReading
import com.callibri.nfb.threshold.AutoThresholdController
import com.callibri.nfb.threshold.BandThresholdReading
import com.callibri.nfb.threshold.ThresholdConfig

data class BandRewardState(
    val bandId: String,
    val amplitudeUv: Double?,
    val thresholdUv: Double?,
    val targetSuccess: Double,
    val score: Double?,
    val windowSuccess: Double?,
    val validInWindow: Int,
    val latestAccepted: Boolean,
)

data class RewardState(
    val rawPercent: Double,
    val smoothedPercent: Double,
    val rewardReady: Boolean,
    val calibrating: Boolean,
    val elapsedMillis: Long,
    val windowMillis: Long,
    val validObservations: Int,
    val rejectedObservations: Int,
    val bands: List<BandRewardState>,
    val autoEnabled: Boolean,
) {
    val statusLabel: String
        get() {
            if (!autoEnabled) return "Auto threshold: Off"
            if (calibrating || !rewardReady) {
                val elapsedSeconds = (elapsedMillis / 1_000L).toInt().coerceAtLeast(0)
                val windowSeconds = (windowMillis / 1_000L).toInt().coerceAtLeast(1)
                val shown = elapsedSeconds.coerceAtMost(windowSeconds)
                return "Calibrating... $shown / $windowSeconds seconds"
            }
            return "Auto threshold: Active"
        }
}

/**
 * Ties the rolling thresholds, the band scores, and the smoother together.
 * Call it from the signal thread, not from Compose.
 *
 * Until auto-threshold has [ThresholdConfig.minimumValidObservations] valid
 * readings in every band, both raw and smoothed reward stay at the configured
 * minimum. That avoids a score built from one or two samples.
 */
class RewardPipeline(
    config: ThresholdConfig = ThresholdConfig(),
) {
    private var config: ThresholdConfig = config
    private val thresholds = AutoThresholdController(config)
    private val smoother = RewardSmoother(config.smoothingMillis)
    private var sessionStartMs: Long? = null
    init {
        smoother.reset(config.minRewardPercent)
    }

    fun updateConfig(config: ThresholdConfig) {
        this.config = config
        thresholds.updateConfig(config)
        smoother.tauMillis = config.smoothingMillis
    }

    fun setAutoEnabled(enabled: Boolean) {
        thresholds.autoEnabled = enabled
    }

    fun setManualThreshold(bandId: String, microvolts: Double) {
        thresholds.setManualThreshold(bandId, microvolts)
    }

    fun resetSession() {
        thresholds.reset()
        smoother.reset(config.minRewardPercent)
        sessionStartMs = null
    }

    /** Starts the calibrating clock without adding a sample. Later calls do not move the start. */
    fun noteTime(timeMs: Long) {
        if (sessionStartMs == null) sessionStartMs = timeMs
    }

    fun observe(
        timeMs: Long,
        readings: List<AmplitudeReading>,
        contactAcceptable: Boolean,
    ): RewardState {
        if (sessionStartMs == null) sessionStartMs = timeMs
        val bandReadings = thresholds.observe(timeMs, readings, contactAcceptable)
        return assemble(timeMs, bandReadings, advanceSmoother = true)
    }

    /**
     * Recompute scores from the current window after a settings change.
     * Does not insert a sample. Before the first EEG observation, reward stays
     * at the floor and the session clock does not start.
     */
    fun recompute(timeMs: Long): RewardState {
        val bandReadings = thresholds.observe(timeMs, emptyList(), contactAcceptable = true)
        if (sessionStartMs == null) return resting(bandReadings)
        return assemble(timeMs, bandReadings, advanceSmoother = true)
    }

    private fun assemble(
        timeMs: Long,
        bandReadings: List<BandThresholdReading>,
        advanceSmoother: Boolean,
    ): RewardState {
        val start = sessionStartMs ?: timeMs
        val elapsed = (timeMs - start).coerceAtLeast(0L)
        val ready = thresholds.readyForReward(timeMs)
        val scores = DoubleArray(bandReadings.size)
        val weights = DoubleArray(bandReadings.size)
        val bandStates = bandReadings.mapIndexed { index, band ->
            weights[index] = band.weight
            val score = if (band.thresholdUv != null && band.scoringAmplitudeUv != null) {
                RewardCalculator.bandScore(band.scoringAmplitudeUv, band.thresholdUv, band.goal)
            } else {
                null
            }
            scores[index] = score ?: 0.0
            band.toRewardState(score)
        }
        val floor = config.minRewardPercent.coerceIn(0.0, 100.0)
        val raw = if (ready) {
            RewardCalculator.combinedPercent(scores, weights, floor).coerceIn(floor, 100.0)
        } else {
            floor
        }
        val stepped = if (!ready) {
            smoother.reset(floor)
            floor
        } else if (advanceSmoother) {
            smoother.step(raw, timeMs)
        } else {
            raw
        }
        // Raising the minimum snaps the smoother up to that floor so the
        // displayed reward never sits under the configured minimum.
        val smoothedOut = if (stepped < floor) {
            smoother.reset(floor)
            floor
        } else {
            stepped.coerceAtMost(100.0)
        }
        val calibrating = thresholds.autoEnabled && elapsed < config.windowMillis
        return RewardState(
            rawPercent = raw,
            smoothedPercent = smoothedOut,
            rewardReady = ready,
            calibrating = calibrating,
            elapsedMillis = elapsed,
            windowMillis = config.windowMillis,
            validObservations = bandReadings.sumOf { it.acceptedTotal },
            rejectedObservations = bandReadings.sumOf { it.rejectedTotal },
            bands = bandStates,
            autoEnabled = thresholds.autoEnabled,
        )
    }

    private fun resting(bandReadings: List<BandThresholdReading>): RewardState {
        val floor = config.minRewardPercent.coerceIn(0.0, 100.0)
        return RewardState(
            rawPercent = floor,
            smoothedPercent = floor,
            rewardReady = false,
            calibrating = thresholds.autoEnabled,
            elapsedMillis = 0L,
            windowMillis = config.windowMillis,
            validObservations = bandReadings.sumOf { it.acceptedTotal },
            rejectedObservations = bandReadings.sumOf { it.rejectedTotal },
            bands = bandReadings.map { it.toRewardState(score = null) },
            autoEnabled = thresholds.autoEnabled,
        )
    }

    companion object {
        fun fre1() = RewardPipeline(ThresholdConfig())
    }
}

private fun BandThresholdReading.toRewardState(score: Double?) = BandRewardState(
    bandId = bandId,
    amplitudeUv = amplitudeUv,
    thresholdUv = thresholdUv,
    targetSuccess = targetSuccess,
    score = score,
    windowSuccess = windowSuccess,
    validInWindow = validInWindow,
    latestAccepted = latestAccepted,
)
