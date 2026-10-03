package com.callibri.nfb.session

import com.callibri.nfb.feedback.VolumeMapping

/**
 * Fill of the floating reward meter. This is the existing smoothed reward
 * placed in the 20–100 feedback range. It is not a second reward or smoother.
 *
 * Reward 20 fills nothing. Reward 100 fills the track.
 */
object RewardMeter {
    fun fraction(smoothedReward: Double): Double {
        val clamped = VolumeMapping.clampedPercent(smoothedReward)
        val span = VolumeMapping.CEILING_PERCENT - VolumeMapping.FLOOR_PERCENT
        return ((clamped - VolumeMapping.FLOOR_PERCENT) / span).coerceIn(0.0, 1.0)
    }

    /** A meter that is not live stays empty, even if a previous reward is still in memory. */
    fun displayedFraction(live: Boolean, smoothedReward: Double): Double =
        if (live) fraction(smoothedReward) else 0.0
}
