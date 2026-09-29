package com.callibri.nfb.feedback

import com.callibri.nfb.protocol.BandGoal
import com.callibri.nfb.threshold.AmplitudeReading
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardBehaviorTest {
    @Test
    fun rewardIncreasesWhenRewardBandAmplitudeImproves() {
        val atThreshold = RewardCalculator.bandScore(10.0, 10.0, BandGoal.RewardAbove)
        val higher = RewardCalculator.bandScore(20.0, 10.0, BandGoal.RewardAbove)
        val lower = RewardCalculator.bandScore(5.0, 10.0, BandGoal.RewardAbove)
        assertEquals(0.5, atThreshold, 1e-9)
        assertTrue(higher > atThreshold)
        assertTrue(lower < atThreshold)

        val pipeline = RewardPipeline()
        val baseline = settle(pipeline, reward = 10.0, inhibit = 10.0)
        val improved = pipeline.observe(
            timeMs = 4_000L,
            readings = readings(reward = 20.0, inhibit = 10.0),
            contactAcceptable = true,
        )
        assertTrue(improved.rawPercent > baseline.rawPercent)
        assertTrue(improved.rawPercent <= 100.0)
    }

    @Test
    fun rewardIncreasesWhenInhibitAmplitudesDecrease() {
        val atThreshold = RewardCalculator.bandScore(10.0, 10.0, BandGoal.InhibitBelow)
        val quieter = RewardCalculator.bandScore(5.0, 10.0, BandGoal.InhibitBelow)
        val louder = RewardCalculator.bandScore(20.0, 10.0, BandGoal.InhibitBelow)
        assertEquals(0.5, atThreshold, 1e-9)
        assertTrue(quieter > atThreshold)
        assertTrue(louder < atThreshold)
        assertEquals(
            1.0,
            RewardCalculator.bandScore(20.0, 10.0, BandGoal.RewardAbove) +
                RewardCalculator.bandScore(20.0, 10.0, BandGoal.InhibitBelow),
            1e-9,
        )

        val pipeline = RewardPipeline()
        val baseline = settle(pipeline, reward = 10.0, inhibit = 10.0)
        val improved = pipeline.observe(
            timeMs = 4_000L,
            readings = readings(reward = 10.0, inhibit = 5.0),
            contactAcceptable = true,
        )
        assertTrue(improved.rawPercent > baseline.rawPercent)
    }

    @Test
    fun outputAlwaysStaysWithin20And100() {
        val weights = doubleArrayOf(0.333, 0.334, 0.333)
        assertEquals(20.0, RewardCalculator.combinedPercent(doubleArrayOf(0.0, 0.0, 0.0), weights, 20.0), 1e-9)
        assertEquals(100.0, RewardCalculator.combinedPercent(doubleArrayOf(1.0, 1.0, 1.0), weights, 20.0), 1e-9)
        val random = Random(7)
        repeat(200) {
            val scores = DoubleArray(3) { random.nextDouble() }
            val percent = RewardCalculator.combinedPercent(scores, weights, 20.0)
            assertTrue(percent in 20.0..100.0)
        }

        val pipeline = RewardPipeline()
        var time = 0L
        repeat(40) { index ->
            time += 200L
            val amplitude = when {
                index % 5 == 0 -> Double.NaN
                index % 6 == 0 -> 5_000.0
                else -> index * 3.0
            }
            val state = pipeline.observe(
                timeMs = time,
                readings = readings(reward = amplitude, inhibit = amplitude),
                contactAcceptable = index % 7 != 0,
            )
            assertTrue(state.rawPercent in 20.0..100.0)
            assertTrue(state.smoothedPercent in 20.0..100.0)
        }
    }

    @Test
    fun smoothingConvergesTowardTarget() {
        val smoother = RewardSmoother(tauMillis = 500.0)
        smoother.reset(20.0)
        assertEquals(20.0, smoother.step(100.0, 0L), 1e-9)

        var previous = 20.0
        var time = 0L
        var atTwoSeconds = 0.0
        for (step in 1..100) {
            time += 100L
            val value = smoother.step(100.0, time)
            assertTrue(value > previous)
            assertTrue(value < 100.0)
            assertTrue(value >= 20.0)
            previous = value
            if (step == 20) atTwoSeconds = value
        }
        assertTrue(atTwoSeconds > 90.0)
        assertEquals(100.0, previous, 1.0)
    }

    @Test
    fun rewardStaysAtFloorUntilEachBandHasEnoughValidSamples() {
        val pipeline = RewardPipeline()
        var time = 0L
        repeat(7) {
            time += 160L
            val state = pipeline.observe(time, readings(reward = 10.0, inhibit = 10.0), contactAcceptable = true)
            assertFalse(state.rewardReady)
            assertEquals(20.0, state.rawPercent, 1e-9)
            assertEquals(20.0, state.smoothedPercent, 1e-9)
            assertTrue(state.statusLabel.startsWith("Calibrating..."))
        }
        time += 160L
        val ready = pipeline.observe(time, readings(reward = 10.0, inhibit = 10.0), contactAcceptable = true)
        assertTrue(ready.rewardReady)
        assertTrue(ready.rawPercent > 20.0)
        assertEquals(20.0, ready.smoothedPercent, 1e-6)
        assertTrue(ready.statusLabel.startsWith("Calibrating..."))
    }

    private fun settle(pipeline: RewardPipeline, reward: Double, inhibit: Double): RewardState {
        var state: RewardState? = null
        repeat(20) { index ->
            state = pipeline.observe(
                timeMs = index * 200L,
                readings = readings(reward, inhibit),
                contactAcceptable = true,
            )
        }
        return state ?: error("no state")
    }

    private fun readings(reward: Double, inhibit: Double) = listOf(
        AmplitudeReading("inhibit1", inhibit),
        AmplitudeReading("reward", reward),
        AmplitudeReading("inhibit2", inhibit),
    )
}
