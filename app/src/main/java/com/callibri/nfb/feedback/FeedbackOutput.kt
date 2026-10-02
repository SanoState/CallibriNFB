package com.callibri.nfb.feedback

/**
 * One feedback destination. Reward math calls this with a linear gain and
 * does not know whether the destination is a tone, a later media session, or a light.
 */
interface FeedbackOutput {
    val isPlaying: Boolean
    val appliedGain: Double
    val failure: String?

    fun start()
    fun stop()
    fun applyGain(linearGain: Double)
    fun release() {}
}

data class FeedbackSnapshot(
    val requestedPercent: Double,
    val playerVolume: Double,
    val manualEnabled: Boolean,
    val manualPercent: Double,
    val playing: Boolean,
    val liveActive: Boolean,
    val updatesPerSecond: Int,
    val failure: String? = null,
)
