package com.callibri.nfb.feedback

import kotlin.math.exp

/**
 * Exponential smoother on the final reward percent.
 *
 *     y(t) = y(t-1) + (1 - exp(-dt / tau)) * (target - y(t-1))
 *
 * Default tau is 500 ms, so a step is about two-thirds of the way there in
 * half a second and does not average across many seconds. The first call
 * after [reset] only starts the clock; it does not jump to the new target.
 * Threshold jumps therefore show up as a calm slide, not a snap.
 */
class RewardSmoother(
    var tauMillis: Double = 500.0,
) {
    private var value: Double? = null
    private var lastTimeMs: Long? = null

    fun reset(seed: Double) {
        value = seed
        lastTimeMs = null
    }

    fun step(target: Double, timeMs: Long): Double {
        val current = value ?: target
        val previousTime = lastTimeMs
        if (previousTime == null) {
            value = current
            lastTimeMs = timeMs
            return current
        }
        val dt = (timeMs - previousTime).coerceAtLeast(0L)
        lastTimeMs = timeMs
        if (dt == 0L || tauMillis <= 0.0) return current
        val alpha = 1.0 - exp(-dt.toDouble() / tauMillis)
        val next = current + alpha * (target - current)
        value = next
        return next
    }
}
