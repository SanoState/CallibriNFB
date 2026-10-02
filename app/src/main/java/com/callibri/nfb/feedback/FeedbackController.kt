package com.callibri.nfb.feedback

import java.util.ArrayDeque

/**
 * Chooses the reward percent that should be heard, then hands a linear gain
 * to a [FeedbackOutput]. Manual test replaces the live smoothed reward and
 * uses the same gain path. There is no second smoother: the live value is
 * already the output of [RewardSmoother].
 */
class FeedbackController(
    private val output: FeedbackOutput,
) {
    private val updateTimes = ArrayDeque<Long>()
    private var manualEnabled = false
    private var manualPercent = 50.0
    private var livePercent = VolumeMapping.FLOOR_PERCENT
    private var liveActive = false
    private var playing = false

    fun setLiveReward(smoothedPercent: Double, timeMs: Long, active: Boolean): FeedbackSnapshot = synchronized(this) {
        livePercent = smoothedPercent
        liveActive = active
        if (active) noteUpdate(timeMs) else updateTimes.clear()
        if (playing && !manualEnabled && active) {
            output.applyGain(VolumeMapping.linearGain(livePercent))
        }
        snapshot()
    }

    /** EEG is starting over. Keep test audio running and drop the live target to the session floor. */
    fun onSessionReset(floorPercent: Double): FeedbackSnapshot = synchronized(this) {
        livePercent = floorPercent
        liveActive = true
        updateTimes.clear()
        if (playing && !manualEnabled) output.applyGain(VolumeMapping.linearGain(livePercent))
        snapshot()
    }

    fun markLiveActive(): FeedbackSnapshot = synchronized(this) {
        liveActive = true
        if (playing && !manualEnabled) output.applyGain(VolumeMapping.linearGain(livePercent))
        snapshot()
    }

    fun setManualEnabled(enabled: Boolean): FeedbackSnapshot = synchronized(this) {
        manualEnabled = enabled
        if (playing) output.applyGain(VolumeMapping.linearGain(commandPercent()))
        snapshot()
    }

    fun setManualPercent(percent: Double): FeedbackSnapshot = synchronized(this) {
        manualPercent = VolumeMapping.clampedPercent(percent)
        if (playing && manualEnabled) output.applyGain(VolumeMapping.linearGain(manualPercent))
        snapshot()
    }

    fun startAudio(): FeedbackSnapshot = synchronized(this) {
        output.start()
        playing = output.isPlaying
        if (playing) output.applyGain(VolumeMapping.linearGain(commandPercent()))
        snapshot()
    }

    fun stopAudio(): FeedbackSnapshot = synchronized(this) {
        output.stop()
        playing = false
        snapshot()
    }

    /**
     * Live EEG is no longer driving feedback. [stopAudio] is set when the
     * sensor disconnects, or when manual test is off, so the tone does not
     * keep playing at the last reward.
     */
    fun onLiveInactive(stopAudio: Boolean): FeedbackSnapshot = synchronized(this) {
        liveActive = false
        updateTimes.clear()
        if (stopAudio) {
            output.stop()
            playing = false
            manualEnabled = false
        }
        snapshot()
    }

    fun shutdown(): FeedbackSnapshot = synchronized(this) {
        liveActive = false
        manualEnabled = false
        updateTimes.clear()
        output.stop()
        playing = false
        snapshot()
    }

    fun release() {
        synchronized(this) {
            output.stop()
            playing = false
        }
        output.release()
    }

    private fun commandPercent(): Double =
        if (manualEnabled) manualPercent else livePercent

    private fun noteUpdate(timeMs: Long) {
        updateTimes.addLast(timeMs)
        val cutoff = timeMs - 1_000L
        while (updateTimes.isNotEmpty() && updateTimes.first() < cutoff) {
            updateTimes.removeFirst()
        }
    }

    private fun snapshot(): FeedbackSnapshot {
        val requested = VolumeMapping.clampedPercent(commandPercent())
        return FeedbackSnapshot(
            requestedPercent = requested,
            playerVolume = if (playing) output.appliedGain else 0.0,
            manualEnabled = manualEnabled,
            manualPercent = manualPercent,
            playing = playing && output.isPlaying,
            liveActive = liveActive,
            updatesPerSecond = updateTimes.size,
            failure = output.failure,
        )
    }
}
