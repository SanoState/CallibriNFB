package com.callibri.nfb.feedback

/**
 * Maps the existing smoothed reward onto a black-overlay alpha.
 * This is not a second reward smoother. Android display brightness is not involved.
 *
 * normalized = clamp((reward - 20) / 80, 0, 1)
 * alpha = maxDimAlpha * (1 - normalized)
 */
object VisualDimming {
    const val DEFAULT_MAX_ALPHA = 0.50
    const val MIN_SETTING = 0.10
    const val MAX_SETTING = 0.60

    /**
     * Android drops touches that pass through an untrusted overlay once the
     * combined opacity is above about 0.8. Stay under that so the app underneath
     * remains usable.
     */
    const val TOUCH_SAFE_CEILING = 0.60

    fun clampSetting(maxDimAlpha: Double): Double =
        maxDimAlpha.coerceIn(MIN_SETTING, minOf(MAX_SETTING, TOUCH_SAFE_CEILING))

    fun normalized(rewardPercent: Double): Double {
        val clamped = VolumeMapping.clampedPercent(rewardPercent)
        val span = VolumeMapping.CEILING_PERCENT - VolumeMapping.FLOOR_PERCENT
        return ((clamped - VolumeMapping.FLOOR_PERCENT) / span).coerceIn(0.0, 1.0)
    }

    fun alpha(rewardPercent: Double, maxDimAlpha: Double): Double {
        val maxDim = clampSetting(maxDimAlpha)
        return (maxDim * (1.0 - normalized(rewardPercent))).coerceIn(0.0, maxDim)
    }
}

/**
 * Audio and visual are separate switches. Both read the same reward percent
 * when they are on. Neither switch changes EEG.
 */
object FeedbackModes {
    fun drivesAudio(audioEnabled: Boolean): Boolean = audioEnabled

    fun visualAlpha(
        visualEnabled: Boolean,
        manual: Boolean,
        rewardReady: Boolean,
        rewardPercent: Double,
        maxDimAlpha: Double,
    ): Double {
        if (!visualEnabled) return 0.0
        if (!manual && !rewardReady) return 0.0
        return VisualDimming.alpha(rewardPercent, maxDimAlpha)
    }
}
