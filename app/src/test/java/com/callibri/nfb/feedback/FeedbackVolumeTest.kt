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
        assertEquals(0.80, output.appliedGain, 1e-9)

        controller.setManualPercent(50.0)
        controller.setManualEnabled(true)
        assertEquals(0.50, output.appliedGain, 1e-9)
        assertTrue(controller.setLiveReward(100.0, timeMs = 1_200L, active = true).manualEnabled)
        assertEquals(0.50, output.appliedGain, 1e-9)

        controller.setManualPercent(20.0)
        assertEquals(0.20, output.appliedGain, 1e-9)
        controller.setManualPercent(100.0)
        assertEquals(1.00, output.appliedGain, 1e-9)
    }

    @Test
    fun turningManualModeOffReturnsToLiveSmoothedReward() {
        val output = RecordingOutput()
        val controller = FeedbackController(output)
        controller.startAudio()
        controller.setLiveReward(40.0, timeMs = 1_000L, active = true)
        controller.setManualPercent(90.0)
        controller.setManualEnabled(true)
        assertEquals(0.90, output.appliedGain, 1e-9)

        controller.setLiveReward(70.0, timeMs = 1_500L, active = true)
        assertEquals(0.90, output.appliedGain, 1e-9)

        val after = controller.setManualEnabled(false)
        assertFalse(after.manualEnabled)
        assertEquals(70.0, after.requestedPercent, 1e-9)
        assertEquals(0.70, output.appliedGain, 1e-9)
        assertEquals(0.70, after.playerVolume, 1e-9)
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
        controller.startAudio()
        assertEquals(0.55, output.appliedGain, 1e-9)
        val kept = controller.onLiveInactive(stopAudio = false)
        assertTrue(kept.playing)
        assertFalse(kept.liveActive)
        assertEquals(0.55, output.appliedGain, 1e-9)
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
