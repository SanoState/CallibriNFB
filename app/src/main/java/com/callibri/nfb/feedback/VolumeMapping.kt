package com.callibri.nfb.feedback

/**
 * Maps a reward percent onto the loudness of this app's own test audio.
 *
 *     volume = clamp(reward, 20, 100) / 100
 *
 * 20% stays audible. Nothing here writes Android's media stream volume.
 */
object VolumeMapping {
    const val FLOOR_PERCENT = 20.0
    const val CEILING_PERCENT = 100.0

    fun clampedPercent(rewardPercent: Double): Double =
        rewardPercent.coerceIn(FLOOR_PERCENT, CEILING_PERCENT)

    fun linearGain(rewardPercent: Double): Double =
        clampedPercent(rewardPercent) / 100.0
}
