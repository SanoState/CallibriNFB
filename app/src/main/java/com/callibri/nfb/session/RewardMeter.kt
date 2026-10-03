package com.callibri.nfb.session

import com.callibri.nfb.feedback.FeedbackIntensity

/**
 * Fill of the floating reward meter. This is feedback intensity of the
 * existing reward percent. It is not a second reward or smoother.
 *
 * The lower bound fills nothing. The upper bound fills the track.
 * The numeric label stays the reward percent.
 */
object RewardMeter {
    fun fraction(
        smoothedReward: Double,
        lowerBound: Double = FeedbackIntensity.DEFAULT_LOWER,
        upperBound: Double = FeedbackIntensity.DEFAULT_UPPER,
    ): Double = FeedbackIntensity.intensity(smoothedReward, lowerBound, upperBound)

    /** A meter that is not live stays empty, even if a previous reward is still in memory. */
    fun displayedFraction(
        live: Boolean,
        smoothedReward: Double,
        lowerBound: Double = FeedbackIntensity.DEFAULT_LOWER,
        upperBound: Double = FeedbackIntensity.DEFAULT_UPPER,
    ): Double = if (live) fraction(smoothedReward, lowerBound, upperBound) else 0.0
}
