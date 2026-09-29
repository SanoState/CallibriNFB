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
            BandThresholdReading(
                bandId = id,
                goal = state.goal,
                amplitudeUv = state.lastAmplitude,
                thresholdUv = used,
                targetSuccess = state.targetSuccess,
                weight = state.weight,
                validInWindow = state.rolling.windowSize(timeMs),
                acceptedTotal = state.rolling.acceptedCount,
                rejectedTotal = state.rolling.rejectedCount,
                windowSuccess = if (used != null) state.rolling.fractionOnSuccessSide(used, timeMs) else null,
                latestAccepted = accepted,
                scoringAmplitudeUv = state.lastAcceptedAmplitude,
            )
        }
    }

    /** True when every band can contribute a real threshold to the reward. */
    fun readyForReward(nowMs: Long): Boolean {
        if (!autoEnabled) return bands.values.all { it.manualUv > 0.0 }
        return bands.values.all { it.rolling.threshold(nowMs) != null }
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
                rolling = rolling,
                lastAmplitude = existing?.lastAmplitude,
                lastAcceptedAmplitude = existing?.lastAcceptedAmplitude,
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
        val rolling: RollingThreshold,
        var lastAmplitude: Double?,
        var lastAcceptedAmplitude: Double?,
    )
}
