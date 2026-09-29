package com.callibri.nfb.feedback

import com.callibri.nfb.protocol.BandGoal
import kotlin.math.ln

/**
 * Continuous band score and the combined 20–100% map.
 *
 * Band score is a ratio distance from the threshold, clamped to 0..1.
 * A doubling or halving of amplitude (relative to the threshold) saturates
 * the score. Sitting on the threshold scores 0.5, so a small crossing only
 * moves the score a little, and the two goals are symmetric:
 *
 *     logRatio = ln(amplitude / threshold) / ln(2)
 *     reward   = clamp(0.5 + 0.5 * logRatio, 0, 1)
 *     inhibit  = clamp(0.5 - 0.5 * logRatio, 0, 1)
 *
 * Combined reward, with weights renormalized so they sum to 1:
 *
 *     raw        = clamp(sum(weight_i * score_i) / sum(weight_i), 0, 1)
 *     percent    = minPercent + raw * (100 - minPercent)
 *
 * [minPercent] defaults to 20, so the result stays inside 20..100.
 */
object RewardCalculator {
    private val LOG_TWO = ln(2.0)

    fun bandScore(amplitudeUv: Double, thresholdUv: Double, goal: BandGoal): Double {
        if (thresholdUv <= 0.0) return 0.0
        if (amplitudeUv <= 0.0) {
            return if (goal == BandGoal.InhibitBelow) 1.0 else 0.0
        }
        val logRatio = ln(amplitudeUv / thresholdUv) / LOG_TWO
        val centered = when (goal) {
            BandGoal.RewardAbove -> 0.5 + 0.5 * logRatio
            BandGoal.InhibitBelow -> 0.5 - 0.5 * logRatio
        }
        return centered.coerceIn(0.0, 1.0)
    }

    fun combinedPercent(scores: DoubleArray, weights: DoubleArray, minPercent: Double): Double {
        var weighted = 0.0
        var weightSum = 0.0
        val count = minOf(scores.size, weights.size)
        for (index in 0 until count) {
            val weight = weights[index]
            if (weight <= 0.0) continue
            weighted += weight * scores[index].coerceIn(0.0, 1.0)
            weightSum += weight
        }
        val normalized = if (weightSum <= 0.0) 0.0 else (weighted / weightSum).coerceIn(0.0, 1.0)
        val floor = minPercent.coerceIn(0.0, 100.0)
        return floor + normalized * (100.0 - floor)
    }
}
