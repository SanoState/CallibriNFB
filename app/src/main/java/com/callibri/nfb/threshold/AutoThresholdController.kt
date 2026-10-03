package com.callibri.nfb.threshold

import com.callibri.nfb.protocol.BandGoal

data class AmplitudeReading(
    val bandId: String,
    val microvolts: Double,
)

data class BandThresholdReading(
    val bandId: String,
    val goal: BandGoal,
    val amplitudeUv: Double?,
    val thresholdUv: Double?,
    val targetSuccess: Double,
    val weight: Double,
    val validInWindow: Int,
    val acceptedTotal: Int,
    val rejectedTotal: Int,
    val windowSuccess: Double?,
    val latestAccepted: Boolean,
    val scoringAmplitudeUv: Double?,
    val enabled: Boolean,
)

/**
 * One [RollingThreshold] per band. Auto mode uses the percentile. Manual mode
 * keeps collecting history (for the success diagnostic) but scores against the
 * entered threshold instead.
 */
class AutoThresholdController(
    config: ThresholdConfig,
) {
    private var config: ThresholdConfig = config
    private val bands = linkedMapOf<String, BandState>()
    var autoEnabled: Boolean = true

    init {
        rebuild(config, keepSamples = false)
    }

    fun updateConfig(config: ThresholdConfig) {
        rebuild(config, keepSamples = true)
    }

    fun setManualThreshold(bandId: String, microvolts: Double) {
        bands[bandId]?.manualUv = microvolts
    }

    /** Flips participation only. The rolling window and the current threshold stay as they are. */
    fun setEnabled(bandId: String, enabled: Boolean) {
        bands[bandId]?.enabled = enabled
    }

    fun reset() {
        bands.values.forEach { it.rolling.reset() }
    }

    fun observe(
        timeMs: Long,
        readings: List<AmplitudeReading>,
        contactAcceptable: Boolean,
    ): List<BandThresholdReading> {
        val byId = readings.associateBy { it.bandId }
        return bands.map { (id, state) ->
            val reading = byId[id]
            if (!state.enabled) {
                if (reading != null && reading.microvolts.isFinite()) {
                    state.lastAmplitude = reading.microvolts
                }
                return@map state.snapshot(id, latestAccepted = false)
            }
            val accepted = if (reading != null) {
                state.lastAmplitude = reading.microvolts
                val took = state.rolling.accept(timeMs, reading.microvolts, contactAcceptable)
                if (took) state.lastAcceptedAmplitude = reading.microvolts
                took
            } else {
                state.rolling.expire(timeMs)
                false
            }
            val autoThreshold = state.rolling.threshold(timeMs)
            val used = if (autoEnabled) autoThreshold else state.manualUv
            state.heldThresholdUv = used
            state.heldWindowSize = state.rolling.windowSize(timeMs)
            state.heldWindowSuccess = if (used != null) state.rolling.fractionOnSuccessSide(used, timeMs) else null
            state.snapshot(id, latestAccepted = accepted)
        }
    }

    /** True when every enabled band can contribute a real threshold. No enabled bands means not ready. */
    fun readyForReward(nowMs: Long): Boolean {
        val active = bands.values.filter { it.enabled }
        if (active.isEmpty()) return false
        if (!autoEnabled) return active.all { it.manualUv > 0.0 }
        return active.all { it.rolling.threshold(nowMs) != null }
    }

    private fun rebuild(config: ThresholdConfig, keepSamples: Boolean) {
        this.config = config
        val next = linkedMapOf<String, BandState>()
        for (spec in config.bands) {
            val existing = bands[spec.bandId]
            val rolling = if (keepSamples && existing != null) {
                existing.rolling
            } else {
                RollingThreshold(
                    windowMillis = config.windowMillis,
                    goal = spec.goal,
                    targetFraction = spec.targetSuccess,
                    minimumCount = config.minimumValidObservations,
                    maxMicrovolts = config.maxPlausibleMicrovolts,
                )
            }
            rolling.configure(
                windowMillis = config.windowMillis,
                goal = spec.goal,
                targetFraction = spec.targetSuccess,
                minimumCount = config.minimumValidObservations,
                maxMicrovolts = config.maxPlausibleMicrovolts,
            )
            next[spec.bandId] = BandState(
                goal = spec.goal,
                targetSuccess = spec.targetSuccess,
                weight = spec.weight,
                manualUv = spec.manualThresholdUv,
                enabled = spec.enabled,
                rolling = rolling,
                lastAmplitude = existing?.lastAmplitude,
                lastAcceptedAmplitude = existing?.lastAcceptedAmplitude,
                heldThresholdUv = existing?.heldThresholdUv,
                heldWindowSize = existing?.heldWindowSize ?: 0,
                heldWindowSuccess = existing?.heldWindowSuccess,
            )
        }
        bands.clear()
        bands.putAll(next)
    }

    private class BandState(
        var goal: BandGoal,
        var targetSuccess: Double,
        var weight: Double,
        var manualUv: Double,
        var enabled: Boolean,
        val rolling: RollingThreshold,
        var lastAmplitude: Double?,
        var lastAcceptedAmplitude: Double?,
        var heldThresholdUv: Double? = null,
        var heldWindowSize: Int = 0,
        var heldWindowSuccess: Double? = null,
    ) {
        fun snapshot(id: String, latestAccepted: Boolean) = BandThresholdReading(
            bandId = id,
            goal = goal,
            amplitudeUv = lastAmplitude,
            thresholdUv = heldThresholdUv,
            targetSuccess = targetSuccess,
            weight = weight,
            validInWindow = heldWindowSize,
            acceptedTotal = rolling.acceptedCount,
            rejectedTotal = rolling.rejectedCount,
            windowSuccess = heldWindowSuccess,
            latestAccepted = latestAccepted,
            scoringAmplitudeUv = lastAcceptedAmplitude,
            enabled = enabled,
        )
    }
}
