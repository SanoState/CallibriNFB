package com.callibri.nfb.feedback

/**
 * Output mapping after the smoothed reward.
 *
 * The smoothed reward percent is unchanged. Audio, visual dimming, and the
 * floating reward bar all read one feedback intensity:
 *
 *     intensity = clamp((reward - lower) / (upper - lower), 0, 1)
 *
 * Defaults are 70% and 90%. An invalid range (upper not strictly above lower,
 * or a non-finite bound) is not a usable span.
 */
object FeedbackIntensity {
    const val DEFAULT_LOWER = 70.0
    const val DEFAULT_UPPER = 90.0
    const val MIN_BOUND = 0.0
    const val MAX_BOUND = 100.0
    const val MIN_SPAN = 1.0

    fun isValid(lowerBound: Double, upperBound: Double): Boolean =
        lowerBound.isFinite() && upperBound.isFinite() && upperBound > lowerBound

    /** Null when [lower] is not a finite number. Otherwise kept at least [MIN_SPAN] below [upper]. */
    fun coerceLower(lower: Double, upper: Double): Double? {
        if (!lower.isFinite() || !upper.isFinite()) return null
        val ceiling = (upper - MIN_SPAN).coerceIn(MIN_BOUND, MAX_BOUND - MIN_SPAN)
        return lower.coerceIn(MIN_BOUND, ceiling)
    }

    /** Null when [upper] is not a finite number. Otherwise kept at least [MIN_SPAN] above [lower]. */
    fun coerceUpper(lower: Double, upper: Double): Double? {
        if (!lower.isFinite() || !upper.isFinite()) return null
        val floor = (lower + MIN_SPAN).coerceIn(MIN_SPAN, MAX_BOUND)
        return upper.coerceIn(floor, MAX_BOUND)
    }

    fun intensity(
        rewardPercent: Double,
        lowerBound: Double = DEFAULT_LOWER,
        upperBound: Double = DEFAULT_UPPER,
    ): Double {
        if (!rewardPercent.isFinite() || !isValid(lowerBound, upperBound)) return 0.0
        return ((rewardPercent - lowerBound) / (upperBound - lowerBound)).coerceIn(0.0, 1.0)
    }

    /** Built-in and external audio gain. 0 maps to the existing non-zero floor, 1 to full gain. */
    fun audioGain(intensity: Double): Double {
        val unit = if (intensity.isFinite()) intensity.coerceIn(0.0, 1.0) else 0.0
        return 0.20 + unit * 0.80
    }
}
