package com.callibri.nfb.threshold

import com.callibri.nfb.protocol.BandGoal
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Percentile threshold over a time window. This is not a running average.
 *
 * Samples are kept as (time, amplitude) and dropped when they are older than
 * the window. The threshold is a Hyndman-Fan type-7 quantile of whatever is
 * still inside the window:
 *
 *     h = 1 + (n - 1) * q
 *     value = sorted[floor(h) - 1] + (h - floor(h)) * (sorted[ceil(h) - 1] - sorted[floor(h) - 1])
 *
 * Inhibit (goal: amplitude below threshold), target success p:
 *     q = p
 *     so about p of the window is at or below the threshold.
 *     Example: 1..10 µV, p = 0.80 → 8.2 µV, and 8 of the 10 values are strictly below it.
 *
 * Reward (goal: amplitude above threshold), target success p:
 *     q = 1 - p
 *     Example: 1..10 µV, p = 0.70 → 3.7 µV.
 *
 * One extreme rank cannot drag this the way a mean would: the quantile only
 * looks at the ranks around q. Values that fail [accept] never enter.
 */
class RollingThreshold(
    private var windowMillis: Long,
    private var goal: BandGoal,
    private var targetFraction: Double,
    private var minimumCount: Int,
    private var maxMicrovolts: Double,
) {
    private val samples = ArrayDeque<TimedAmplitude>()
    var acceptedCount: Int = 0
        private set
    var rejectedCount: Int = 0
        private set

    fun configure(
        windowMillis: Long,
        goal: BandGoal,
        targetFraction: Double,
        minimumCount: Int,
        maxMicrovolts: Double,
    ) {
        this.windowMillis = windowMillis
        this.goal = goal
        this.targetFraction = targetFraction
        this.minimumCount = minimumCount
        this.maxMicrovolts = maxMicrovolts
    }

    fun reset() {
        samples.clear()
        acceptedCount = 0
        rejectedCount = 0
    }

    /**
     * @param contactAcceptable false when the electrode is detached or high
     * resistance. Null/unknown contact is the caller's job to treat as acceptable.
     */
    fun accept(timeMs: Long, amplitudeUv: Double, contactAcceptable: Boolean): Boolean {
        if (!contactAcceptable) {
            rejectedCount++
            return false
        }
        if (!amplitudeUv.isFinite()) {
            rejectedCount++
            return false
        }
        if (amplitudeUv < 0.0 || amplitudeUv > maxMicrovolts) {
            rejectedCount++
            return false
        }
        expire(timeMs)
        samples.addLast(TimedAmplitude(timeMs, amplitudeUv))
        acceptedCount++
        return true
    }

    fun expire(nowMs: Long) {
        val cutoff = nowMs - windowMillis
        while (samples.isNotEmpty() && samples.first().timeMs < cutoff) {
            samples.removeFirst()
        }
    }

    fun windowSize(nowMs: Long): Int {
        expire(nowMs)
        return samples.size
    }

    /** Null until [minimumCount] valid samples are inside the window. */
    fun threshold(nowMs: Long): Double? {
        expire(nowMs)
        if (samples.size < minimumCount) return null
        val sorted = DoubleArray(samples.size) { index -> samples[index].amplitudeUv }
        sorted.sort()
        val quantile = if (goal == BandGoal.InhibitBelow) targetFraction else 1.0 - targetFraction
        return type7Quantile(sorted, quantile.coerceIn(0.0, 1.0))
    }

    /**
     * Fraction of the current window on the successful side of [threshold].
     * Inhibit counts amplitudes strictly below the threshold. Reward counts
     * amplitudes strictly above it.
     */
    fun windowSuccess(nowMs: Long): Double? {
        val threshold = threshold(nowMs) ?: return null
        return fractionOnSuccessSide(threshold, nowMs)
    }

    fun fractionOnSuccessSide(threshold: Double, nowMs: Long): Double? {
        expire(nowMs)
        if (samples.isEmpty()) return null
        var hits = 0
        for (sample in samples) {
            val success = when (goal) {
                BandGoal.InhibitBelow -> sample.amplitudeUv < threshold
                BandGoal.RewardAbove -> sample.amplitudeUv > threshold
            }
            if (success) hits++
        }
        return hits.toDouble() / samples.size
    }

    private data class TimedAmplitude(val timeMs: Long, val amplitudeUv: Double)

    companion object {
        fun type7Quantile(sorted: DoubleArray, quantile: Double): Double {
            val n = sorted.size
            if (n == 0) return Double.NaN
            if (n == 1) return sorted[0]
            val q = quantile.coerceIn(0.0, 1.0)
            val h = 1.0 + (n - 1) * q
            val lowerIndex = floor(h).toInt().coerceIn(1, n)
            val upperIndex = ceil(h).toInt().coerceIn(1, n)
            val lower = sorted[lowerIndex - 1]
            val upper = sorted[upperIndex - 1]
            val fraction = h - floor(h)
            return lower + fraction * (upper - lower)
        }
    }
}
