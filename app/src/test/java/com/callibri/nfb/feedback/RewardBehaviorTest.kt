package com.callibri.nfb.feedback

import com.callibri.nfb.protocol.BandGoal
import com.callibri.nfb.session.SessionPolicy
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

    @Test
    fun allThreeEnabledKeepsTheExistingCombination() {
        val pipeline = manualPipeline()
        val state = pipeline.observe(1_000L, bandReadings(inhibit1 = 10.0, reward = 20.0, inhibit2 = 5.0), true)
        val scores = state.bands.map { it.score ?: error("score") }.toDoubleArray()
        val weights = doubleArrayOf(0.333, 0.334, 0.333)
        assertEquals(3, state.activeBandCount)
        assertTrue(state.bands.all { it.enabled && it.includedInReward })
        assertEquals(RewardCalculator.combinedPercent(scores, weights, 20.0), state.rawPercent, 1e-9)
        assertTrue(state.rewardReady)
    }

    @Test
    fun disablingOneBandRenormalizesTheOtherTwo() {
        val weights = doubleArrayOf(0.333, 0.334, 0.333)
        val firstTwo = doubleArrayOf(0.333, 0.334, 0.0)
        val lastTwo = doubleArrayOf(0.0, 0.334, 0.333)
        val scores = doubleArrayOf(0.2, 0.8, 0.1)
        val allThree = RewardCalculator.combinedPercent(scores, weights, 20.0)
        val withoutThird = RewardCalculator.combinedPercent(scores, firstTwo, 20.0)
        val withoutFirst = RewardCalculator.combinedPercent(scores, lastTwo, 20.0)
        assertTrue(withoutThird != allThree)
        assertTrue(withoutFirst != allThree)

        val dropThird = manualPipeline()
        dropThird.setBandEnabled("inhibit2", false)
        val state = dropThird.observe(1_000L, bandReadings(inhibit1 = 10.0, reward = 20.0, inhibit2 = 40.0), true)
        val activeScores = state.bands.map { it.score ?: 0.0 }.toDoubleArray()
        assertEquals(RewardCalculator.combinedPercent(activeScores, firstTwo, 20.0), state.rawPercent, 1e-9)
        assertFalse(state.bands.first { it.bandId == "inhibit2" }.includedInReward)
        assertEquals(2, state.activeBandCount)

        val dropFirst = manualPipeline()
        dropFirst.setBandEnabled("inhibit1", false)
        val other = dropFirst.observe(1_000L, bandReadings(inhibit1 = 2.5, reward = 20.0, inhibit2 = 5.0), true)
        val otherScores = other.bands.map { it.score ?: 0.0 }.toDoubleArray()
        assertEquals(RewardCalculator.combinedPercent(otherScores, lastTwo, 20.0), other.rawPercent, 1e-9)
        assertFalse(other.bands.first { it.bandId == "inhibit1" }.includedInReward)
    }

    @Test
    fun oneEnabledBandBecomesTheWholeReward() {
        val pipeline = manualPipeline()
        pipeline.setBandEnabled("inhibit1", false)
        pipeline.setBandEnabled("inhibit2", false)
        val atThreshold = pipeline.observe(1_000L, bandReadings(10.0, 10.0, 10.0), true)
        val doubled = pipeline.observe(1_200L, bandReadings(2.5, 20.0, 40.0), true)
        val halved = pipeline.observe(1_400L, bandReadings(2.5, 5.0, 40.0), true)
        assertEquals(1, atThreshold.activeBandCount)
        assertEquals(60.0, atThreshold.rawPercent, 1e-6)
        assertEquals(100.0, doubled.rawPercent, 1e-6)
        assertEquals(20.0, halved.rawPercent, 1e-6)
        assertTrue(doubled.bands.first { it.bandId == "reward" }.includedInReward)
    }

    @Test
    fun disabledBandCannotRaiseOrLowerTheCombinedReward() {
        val weights = doubleArrayOf(0.333, 0.334, 0.0)
        val pipeline = manualPipeline()
        pipeline.observe(500L, bandReadings(inhibit1 = 10.0, reward = 20.0, inhibit2 = 10.0), true)
        pipeline.setBandEnabled("inhibit2", false)
        val excellent = pipeline.observe(1_000L, bandReadings(inhibit1 = 10.0, reward = 20.0, inhibit2 = 2.5), true)
        val poor = pipeline.observe(1_200L, bandReadings(inhibit1 = 10.0, reward = 20.0, inhibit2 = 80.0), true)
        val scores = excellent.bands.map { it.score ?: 0.0 }.toDoubleArray()
        val expected = RewardCalculator.combinedPercent(scores, weights, 20.0)
        assertEquals(expected, excellent.rawPercent, 1e-9)
        assertEquals(expected, poor.rawPercent, 1e-9)
        assertTrue(excellent.bands.first { it.bandId == "inhibit2" }.score!! > 0.9)
        assertTrue(poor.bands.first { it.bandId == "inhibit2" }.score!! < 0.1)
        assertFalse(excellent.bands.first { it.bandId == "inhibit2" }.includedInReward)
        assertFalse(poor.bands.first { it.bandId == "inhibit2" }.includedInReward)
    }

    @Test
    fun allBandsDisabledProducesNoLiveReward() {
        val pipeline = manualPipeline()
        pipeline.setBandEnabled("inhibit1", false)
        pipeline.setBandEnabled("reward", false)
        pipeline.setBandEnabled("inhibit2", false)
        val quiet = pipeline.observe(1_000L, bandReadings(2.5, 40.0, 2.5), true)
        val wild = pipeline.observe(2_000L, bandReadings(80.0, 2.5, 80.0), true)
        assertEquals(0, quiet.activeBandCount)
        assertFalse(quiet.rewardReady)
        assertFalse(wild.rewardReady)
        assertEquals("No active training bands", quiet.statusLabel)
        assertEquals("No active training bands", wild.statusLabel)
        assertTrue(quiet.rawPercent != 0.0 && quiet.rawPercent != 100.0)
        assertTrue(wild.rawPercent != 0.0 && wild.rawPercent != 100.0)
        assertEquals(quiet.rawPercent, wild.rawPercent, 1e-9)
        assertTrue(quiet.bands.all { !it.includedInReward })
        assertFalse(SessionPolicy.trainingDrivesFeedback(quiet.activeBandCount, quiet.rewardReady))
    }

    @Test
    fun togglingABandOffAndOnKeepsItsThreshold() {
        val pipeline = RewardPipeline()
        var time = 0L
        lateinit var settled: RewardState
        repeat(20) {
            time += 200L
            settled = pipeline.observe(time, bandReadings(10.0, 10.0, 10.0), true)
        }
        val before = settled.bands.first { it.bandId == "inhibit2" }
        val threshold = before.thresholdUv ?: error("threshold")
        val window = before.validInWindow
        pipeline.setBandEnabled("inhibit2", false)
        val disabled = pipeline.observe(time + 500L, bandReadings(10.0, 10.0, 150.0), true)
        val held = disabled.bands.first { it.bandId == "inhibit2" }
        assertFalse(held.enabled)
        assertEquals(threshold, held.thresholdUv!!, 1e-9)
        assertEquals(window, held.validInWindow)
        assertEquals(150.0, held.amplitudeUv!!, 1e-9)
        pipeline.setBandEnabled("inhibit2", true)
        val restored = pipeline.recompute(time + 500L)
        val again = restored.bands.first { it.bandId == "inhibit2" }
        assertTrue(again.enabled)
        assertEquals(threshold, again.thresholdUv!!, 1e-9)
        assertEquals(window, again.validInWindow)
    }

    private fun manualPipeline(): RewardPipeline = RewardPipeline().also { it.setAutoEnabled(false) }

    private fun bandReadings(inhibit1: Double, reward: Double, inhibit2: Double) = listOf(
        AmplitudeReading("inhibit1", inhibit1),
        AmplitudeReading("reward", reward),
        AmplitudeReading("inhibit2", inhibit2),
    )

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

    private fun readings(reward: Double, inhibit: Double) = bandReadings(inhibit, reward, inhibit)
}
