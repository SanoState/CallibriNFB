package com.callibri.nfb.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaVolumeTest {
    @Test
    fun externalMediaStartsFromEegWithoutTheTestTone() {
        assertTrue(
            ExternalEngagement.shouldStart(
                externalSelected = true,
                volumeFixed = false,
                hasCapturedMax = true,
                alreadyControlling = false,
                manual = false,
                eegStreaming = true,
            ),
        )
        assertFalse(
            ExternalEngagement.shouldStart(
                externalSelected = true,
                volumeFixed = false,
                hasCapturedMax = false,
                alreadyControlling = false,
                manual = false,
                eegStreaming = true,
            ),
        )
        assertFalse(
            ExternalEngagement.shouldStart(
                externalSelected = true,
                volumeFixed = false,
                hasCapturedMax = true,
                alreadyControlling = false,
                manual = false,
                eegStreaming = false,
            ),
        )
    }

    @Test
    fun capturedMaximumOf10MapsTheRewardRange() {
        assertEquals(2, MediaVolumeMapping.desiredIndex(20.0, 10))
        assertEquals(6, MediaVolumeMapping.desiredIndex(60.0, 10))
        assertEquals(10, MediaVolumeMapping.desiredIndex(100.0, 10))
    }

    @Test
    fun capturedMaximumOf15MapsTheRewardRange() {
        assertEquals(3, MediaVolumeMapping.desiredIndex(20.0, 15))
        assertEquals(9, MediaVolumeMapping.desiredIndex(60.0, 15))
        assertEquals(15, MediaVolumeMapping.desiredIndex(100.0, 15))
    }

    @Test
    fun rewardOutside20To100ClampsInsideTheCapturedCeiling() {
        assertEquals(2, MediaVolumeMapping.desiredIndex(0.0, 10))
        assertEquals(2, MediaVolumeMapping.desiredIndex(19.0, 10))
        assertEquals(10, MediaVolumeMapping.desiredIndex(100.1, 10))
        assertEquals(10, MediaVolumeMapping.desiredIndex(140.0, 10))
        assertEquals(15, MediaVolumeMapping.desiredIndex(180.0, 15))
    }

    @Test
    fun outputNeverExceedsTheCapturedMaximum() {
        val port = FakeStream(max = 15, current = 15)
        val gate = MediaVolumeGate(port)
        port.current = 10
        assertEquals(10, gate.captureCurrentAsMaximum())
        assertNull(gate.start())
        gate.apply(rewardPercent = 100.0, manual = true, rewardReady = true, nowMs = 1_000L)
        assertTrue(port.writes.all { it <= 10 })
        assertEquals(10, port.current)
    }

    @Test
    fun minimumStepIsNonZeroWhenTheCeilingIsAboveZero() {
        for (max in 1..15) {
            assertTrue(MediaVolumeMapping.minimumIndex(max) >= 1)
            assertTrue(MediaVolumeMapping.desiredIndex(20.0, max) >= 1)
        }
    }

    @Test
    fun duplicateDesiredIndexDoesNotWriteAgain() {
        val port = FakeStream(max = 15, current = 10)
        val gate = MediaVolumeGate(port)
        gate.captureCurrentAsMaximum()
        gate.start()
        gate.apply(60.0, manual = true, rewardReady = true, nowMs = 1_000L)
        gate.apply(60.0, manual = true, rewardReady = true, nowMs = 1_050L)
        assertEquals(listOf(6), port.writes)
    }

    @Test
    fun aHardwareVolumeChangeIsPulledBackWithoutBecomingTheNewCeiling() {
        val port = FakeStream(max = 15, current = 10)
        val gate = MediaVolumeGate(port)
        gate.captureCurrentAsMaximum()
        gate.start()
        gate.apply(60.0, manual = true, rewardReady = true, nowMs = 1_000L)
        port.current = 8
        gate.apply(60.0, manual = true, rewardReady = true, nowMs = 1_200L)
        assertEquals(listOf(6, 6), port.writes)
        assertEquals(10, gate.capturedMaxIndex)
    }

    @Test
    fun disablingFeedbackRestoresTheOriginalIndexOnce() {
        val port = FakeStream(max = 15, current = 10)
        val gate = MediaVolumeGate(port)
        gate.captureCurrentAsMaximum()
        gate.start()
        gate.apply(20.0, manual = true, rewardReady = true, nowMs = 1_000L)
        assertEquals(2, port.current)
        gate.stop()
        gate.stop()
        assertEquals(10, port.current)
        assertEquals(listOf(2, 10), port.writes)
        assertEquals(MediaRestore.YES, gate.status(20.0).restore)
    }

    @Test
    fun stoppingTheSessionAndDisconnectingRequestRestoration() {
        val stoppedPort = FakeStream(max = 15, current = 10)
        val stopped = MediaVolumeGate(stoppedPort)
        stopped.captureCurrentAsMaximum()
        stopped.start()
        stopped.apply(40.0, manual = false, rewardReady = true, nowMs = 1_000L)
        stopped.stop()
        assertEquals(10, stoppedPort.current)
        assertEquals(MediaRestore.YES, stopped.status(40.0).restore)

        val droppedPort = FakeStream(max = 15, current = 12)
        val dropped = MediaVolumeGate(droppedPort)
        dropped.captureCurrentAsMaximum()
        dropped.start()
        dropped.apply(100.0, manual = false, rewardReady = true, nowMs = 1_000L)
        dropped.stop()
        assertEquals(12, droppedPort.current)
        assertFalse(dropped.isControlling)
    }

    @Test
    fun fixedVolumeDeviceDisablesOutput() {
        val port = FakeStream(max = 15, current = 8, fixed = true)
        val gate = MediaVolumeGate(port)
        assertNull(gate.captureCurrentAsMaximum())
        assertEquals("External media feedback unavailable on this device.", gate.start())
        gate.apply(80.0, manual = true, rewardReady = true, nowMs = 1_000L)
        assertTrue(port.writes.isEmpty())
        assertEquals(8, port.current)
    }

    @Test
    fun startDoesNotWriteAndUnreadyRewardHoldsTheCurrentIndex() {
        val port = FakeStream(max = 15, current = 10)
        val gate = MediaVolumeGate(port)
        gate.captureCurrentAsMaximum()
        assertNull(gate.start())
        assertTrue(port.writes.isEmpty())
        gate.apply(20.0, manual = false, rewardReady = false, nowMs = 1_000L)
        assertTrue(port.writes.isEmpty())
        gate.apply(80.0, manual = false, rewardReady = true, nowMs = 1_200L)
        assertEquals(1, port.writes.size)
        assertTrue(port.writes.single() <= 10)
    }

    @Test
    fun manualDiagnosticUsesTheSameMappingThenReturnsToTheLiveReward() {
        val port = FakeStream(max = 15, current = 10)
        val gate = MediaVolumeGate(port)
        gate.captureCurrentAsMaximum()
        val output = GateOutput(gate)
        val controller = FeedbackController(output)
        controller.startAudio()
        output.manual = true
        controller.setManualPercent(20.0)
        controller.setManualEnabled(true)
        assertEquals(2, port.current)

        controller.setManualPercent(60.0)
        assertEquals(6, port.current)
        controller.setManualPercent(100.0)
        assertEquals(10, port.current)

        controller.setLiveReward(smoothedPercent = 100.0, timeMs = 5_000L, active = true)
        assertEquals(10, port.current)
        output.manual = false
        output.rewardReady = true
        val after = controller.setManualEnabled(false)
        assertFalse(after.manualEnabled)
        assertEquals(100.0, after.requestedPercent, 1e-9)
        assertEquals(10, port.current)
    }

    @Test
    fun liveRewardBelowTheCeilingUsesTheSameIndexFormula() {
        val port = FakeStream(max = 15, current = 15)
        val gate = MediaVolumeGate(port)
        port.current = 15
        gate.captureCurrentAsMaximum()
        val output = GateOutput(gate)
        output.rewardReady = true
        val controller = FeedbackController(output)
        controller.setLiveReward(60.0, timeMs = 1_000L, active = true)
        controller.startAudio()
        assertEquals(9, port.current)
    }

    private class FakeStream(
        var max: Int,
        var current: Int,
        var fixed: Boolean = false,
    ) : MediaStreamPort {
        val writes = ArrayList<Int>()

        override val isFixed: Boolean
            get() = fixed

        override fun maxIndex(): Int = max

        override fun currentIndex(): Int = current

        override fun setIndex(index: Int) {
            writes.add(index)
            current = index
        }
    }

    private class GateOutput(private val gate: MediaVolumeGate) : FeedbackOutput {
        var rewardReady = false
        var manual = false
        private var playingFlag = false
        private var gain = 0.0

        override val isPlaying: Boolean
            get() = playingFlag
        override val appliedGain: Double
            get() = gain
        override val failure: String?
            get() = null

        override fun start() {
            playingFlag = gate.start() == null
        }

        override fun stop() {
            gate.stop()
            playingFlag = false
            gain = 0.0
        }

        override fun applyGain(linearGain: Double) {
            if (!playingFlag) return
            gain = linearGain
            gate.apply(
                rewardPercent = VolumeMapping.clampedPercent(linearGain * 100.0),
                manual = manual,
                rewardReady = rewardReady,
                nowMs = 10_000L,
            )
        }
    }
}
