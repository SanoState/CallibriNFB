package com.callibri.nfb.threshold

import com.callibri.nfb.protocol.BandGoal
import com.callibri.nfb.protocol.FRE1Protocol
import com.callibri.nfb.protocol.Fre1BandPolicy

data class BandThresholdSpec(
    val bandId: String,
    val goal: BandGoal,
    val targetSuccess: Double,
    val weight: Double,
    val manualThresholdUv: Double = DEFAULT_MANUAL_UV,
) {
    companion object {
        const val DEFAULT_MANUAL_UV = 10.0

        fun fromPolicy(policy: Fre1BandPolicy, manualThresholdUv: Double = DEFAULT_MANUAL_UV) =
            BandThresholdSpec(
                bandId = policy.id,
                goal = policy.goal,
                targetSuccess = policy.targetSuccess,
                weight = policy.weight,
                manualThresholdUv = manualThresholdUv,
            )
    }
}

/**
 * Knobs for auto-threshold and the reward map. Defaults come from [FRE1Protocol].
 *
 * Reward stays at [minRewardPercent] until every band has
 * [minimumValidObservations] valid readings. Provisional thresholds are still
 * shown once a band reaches that count, so the first 30 seconds can move
 * without waiting for a full window before any number appears.
 */
data class ThresholdConfig(
    val windowSeconds: Double = FRE1Protocol.WINDOW_SECONDS,
    val minimumValidObservations: Int = FRE1Protocol.MIN_VALID_OBSERVATIONS,
    val maxPlausibleMicrovolts: Double = FRE1Protocol.MAX_PLAUSIBLE_MICROVOLTS,
    val minRewardPercent: Double = FRE1Protocol.MIN_REWARD_PERCENT,
    val smoothingMillis: Double = FRE1Protocol.SMOOTHING_MILLIS,
    val bands: List<BandThresholdSpec> = FRE1Protocol.policies().map { BandThresholdSpec.fromPolicy(it) },
) {
    val windowMillis: Long
        get() = (windowSeconds * 1_000.0).toLong()
}
