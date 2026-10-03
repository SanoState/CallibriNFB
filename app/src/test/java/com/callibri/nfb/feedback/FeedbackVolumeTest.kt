package com.callibri.nfb.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackVolumeTest {
    @Test
    fun rewardMapsLinearlyOntoVolume() {
        assertEquals(0.20, VolumeMapping.linearGain(20.0), 1e-9)
        assertEquals(0.50, VolumeMapping.linearGain(50.0), 1e-9)
        assertEquals(1.00, VolumeMapping.linearGain(100.0), 1e-9)
    }

    @Test
    fun volumeClampsBelow20AndAbove100() {
        assertEquals(0.20, VolumeMapping.linearGain(0.0), 1e-9)
        assertEquals(0.20, VolumeMapping.linearGain(19.9), 1e-9)
        assertEquals(1.00, VolumeMapping.linearGain(100.1), 1e-9)
        assertEquals(1.00, VolumeMapping.linearGain(140.0), 1e-9)
    }

    @Test
    fun manualTestOverridesLiveReward() {
        val output = RecordingOutput()
        val controller = FeedbackController(output)
        controller.setLiveReward(smoothedPercent = 80.0, timeMs = 1_000L, active = true)
        controller.startAudio()
        assertEquals(0.60, output.appliedGain, 1e-9)

        controller.setManualPercent(50.0)
        controller.setManualEnabled(true)
        assertEquals(0.20, output.appliedGain, 1e-9)
        assertTrue(controller.setLiveReward(100.0, timeMs = 1_200L, active = true).manualEnabled)
        assertEquals(0.20, output.appliedGain, 1e-9)

        controller.setManualPercent(20.0)
        assertEquals(0.20, output.appliedGain, 1e-9)
        controller.setManualPercent(100.0)
        assertEquals(1.00, output.appliedGain, 1e-9)
    }

    @Test
    fun defaultRangeMapsRewardThroughFeedbackIntensity() {
        val output = RecordingOutput()
        val controller = FeedbackController(output)
        controller.startAudio()

        val at80 = controller.setLiveReward(80.0, timeMs = 1_000L, active = true)
        assertEquals(80.0, at80.requestedPercent, 1e-9)
        assertEquals(0.60, output.appliedGain, 1e-9)

        val at70 = controller.setLiveReward(70.0, timeMs = 1_100L, active = true)
        assertEquals(70.0, at70.requestedPercent, 1e-9)
        assertEquals(0.20, output.appliedGain, 1e-9)

        val at90 = controller.setLiveReward(90.0, timeMs = 1_200L, active = true)
        assertEquals(90.0, at90.requestedPercent, 1e-9)
        assertEquals(1.0, output.appliedGain, 1e-9)
    }

    @Test
    fun invalidRangeDoesNotChangeTheAppliedGain() {
        val output = RecordingOutput()
        val controller = FeedbackController(output)
        controller.startAudio()
        val live = controller.setLiveReward(80.0, timeMs = 1_000L, active = true)
        assertEquals(80.0, live.requestedPercent, 1e-9)
        assertEquals(0.60, output.appliedGain, 1e-9)

        val rejected = controller.setFeedbackRange(90.0, 70.0)
        assertEquals(80.0, rejected.requestedPercent, 1e-9)
        assertEquals(0.60, output.appliedGain, 1e-9)
        controller.setFeedbackRange(80.0, 80.0)
        controller.setFeedbackRange(Double.NaN, 90.0)
        controller.setFeedbackRange(70.0, Double.NaN)
        assertEquals(0.60, output.appliedGain, 1e-9)

        val widened = controller.setFeedbackRange(60.0, 100.0)
        assertEquals(80.0, widened.requestedPercent, 1e-9)
        assertEquals(0.60, output.appliedGain, 1e-9)
    }

    @Test
    fun turningManualModeOffReturnsToLiveSmoothedReward() {
        val output = RecordingOutput()
        val controller = FeedbackController(output)
        controller.startAudio()
        controller.setLiveReward(40.0, timeMs = 1_000L, active = true)
        controller.setManualPercent(90.0)
        controller.setManualEnabled(true)
        assertEquals(1.00, output.appliedGain, 1e-9)

        controller.setLiveReward(70.0, timeMs = 1_500L, active = true)
        assertEquals(1.00, output.appliedGain, 1e-9)

        val after = controller.setManualEnabled(false)
        assertFalse(after.manualEnabled)
        assertEquals(70.0, after.requestedPercent, 1e-9)
        assertEquals(0.20, output.appliedGain, 1e-9)
        assertEquals(0.20, after.playerVolume, 1e-9)
    }

    @Test
    fun stoppingLiveFeedbackStopsAudioUnlessManualTestIsOn() {
        val output = RecordingOutput()
        val controller = FeedbackController(output)
        controller.startAudio()
        controller.setLiveReward(60.0, timeMs = 1_000L, active = true)
        controller.onLiveInactive(stopAudio = true)
        assertFalse(output.isPlaying)
        assertEquals(0.0, output.appliedGain, 1e-9)

        controller.setManualPercent(55.0)
        controller.setManualEnabled(true)
        val started = controller.startAudio()
        assertEquals(55.0, started.requestedPercent, 1e-9)
        assertEquals(0.20, output.appliedGain, 1e-9)
        val kept = controller.onLiveInactive(stopAudio = false)
        assertTrue(kept.playing)
        assertFalse(kept.liveActive)
        assertEquals(55.0, kept.requestedPercent, 1e-9)
        assertEquals(0.20, output.appliedGain, 1e-9)
    }

    private class RecordingOutput : FeedbackOutput {
        override var isPlaying: Boolean = false
            private set
        override var appliedGain: Double = 0.0
            private set
        override val failure: String? = null

        override fun start() {
            isPlaying = true
        }

        override fun stop() {
            isPlaying = false
            appliedGain = 0.0
        }

        override fun applyGain(linearGain: Double) {
            appliedGain = linearGain
        }
    }
}
