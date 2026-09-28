package com.callibri.nfb.signal

import com.callibri.nfb.protocol.FrequencyBand
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EegProcessorTest {
    @Test
    fun bandRmsMatchesInBandSinesAndRejectsMains() {
        val processor = processor(
            FrequencyBand("inhibit1", "Inhibit 1", 4.0, 8.0),
            FrequencyBand("reward", "Reward", 12.0, 16.0),
            FrequencyBand("inhibit2", "Inhibit 2", 19.0, 38.0),
        )
        val snapshot = feed(
            processor,
            mix(
                dc = 100.0,
                tones = listOf(
                    6.0 to 10.0,
                    14.0 to 20.0,
                    30.0 to 5.0,
                    60.0 to 50.0,
                ),
            ),
        )
        assertEquals(10.0 / sqrt(2.0), amplitude(snapshot, "inhibit1"), 0.6)
        assertEquals(20.0 / sqrt(2.0), amplitude(snapshot, "reward"), 0.6)
        assertEquals(5.0 / sqrt(2.0), amplitude(snapshot, "inhibit2"), 0.6)
    }

    @Test
    fun sixtyHertzAndDcDoNotShowUpInTheBands() {
        val processor = fre1()
        val snapshot = feed(processor, mix(dc = 100.0, tones = listOf(60.0 to 50.0)))
        assertTrue(amplitude(snapshot, "inhibit1") < 0.25)
        assertTrue(amplitude(snapshot, "reward") < 0.25)
        assertTrue(amplitude(snapshot, "inhibit2") < 0.25)
    }

    @Test
    fun outOfBandToneIsAttenuated() {
        val processor = fre1()
        val snapshot = feed(processor, mix(dc = 0.0, tones = listOf(10.0 to 10.0)))
        assertTrue(amplitude(snapshot, "inhibit1") < 2.0)
        assertTrue(amplitude(snapshot, "reward") < 2.0)
    }

    @Test
    fun bandEdgesComeFromTheCaller() {
        val processor = processor(
            FrequencyBand("low", "Low", 4.0, 8.0),
            FrequencyBand("high", "High", 30.0, 40.0),
        )
        val lowBand = feed(processor, mix(dc = 0.0, tones = listOf(6.0 to 10.0)))
        assertEquals(10.0 / sqrt(2.0), amplitude(lowBand, "low"), 0.5)
        assertTrue(amplitude(lowBand, "high") < 1.0)

        processor.setBands(
            listOf(
                FrequencyBand("low", "Low", 30.0, 40.0),
                FrequencyBand("high", "High", 4.0, 8.0),
            ),
        )
        val swapped = feed(processor, mix(dc = 0.0, tones = listOf(6.0 to 10.0)))
        assertTrue(amplitude(swapped, "low") < 1.0)
        assertEquals(10.0 / sqrt(2.0), amplitude(swapped, "high"), 0.5)
    }

    private fun fre1(): EegProcessor = processor(
        FrequencyBand("inhibit1", "Inhibit 1", 4.0, 8.0),
        FrequencyBand("reward", "Reward", 12.0, 16.0),
        FrequencyBand("inhibit2", "Inhibit 2", 19.0, 38.0),
    )

    private fun processor(vararg bands: FrequencyBand): EegProcessor =
        EegProcessor(SAMPLE_RATE_HZ).apply { setBands(bands.toList()) }

    private fun feed(processor: EegProcessor, samples: DoubleArray): EegSnapshot {
        var last: EegSnapshot? = null
        for (sample in samples) {
            val snapshot = processor.push(sample)
            if (snapshot != null) last = snapshot
        }
        assertNotNull(last)
        return last!!
    }

    private fun mix(dc: Double, tones: List<Pair<Double, Double>>): DoubleArray {
        val count = (SECONDS * SAMPLE_RATE_HZ).toInt()
        return DoubleArray(count) { index ->
            val time = index / SAMPLE_RATE_HZ
            dc + tones.sumOf { (frequency, peak) -> peak * sin(2.0 * PI * frequency * time) }
        }
    }

    private fun amplitude(snapshot: EegSnapshot, id: String): Double =
        snapshot.amplitudes.first { it.bandId == id }.microvoltsRms

    private companion object {
        const val SAMPLE_RATE_HZ = 250.0
        const val SECONDS = 8.0
    }
}
