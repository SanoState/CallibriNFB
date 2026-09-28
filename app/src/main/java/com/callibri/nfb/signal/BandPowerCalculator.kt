package com.callibri.nfb.signal

import com.callibri.nfb.protocol.FrequencyBand
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Band-limited RMS for a configurable set of frequency ranges.
 *
 * The band definitions are an input. Nothing in this class knows the FRE1
 * defaults. Swap the body of [push] later (another amplitude definition,
 * shorter window, etc.) without touching Callibri or the UI.
 *
 * Each band is an 8th-order Butterworth band-pass. RMS is taken over a
 * one-second rectangular window and reported about 6 times per second, once
 * the window is full. See [FilterDesign] for the coefficient design.
 */
class BandPowerCalculator(
    private val sampleRateHz: Double,
) {
    private val emitEverySamples = (sampleRateHz / UPDATES_PER_SECOND).roundToInt().coerceIn(25, 63)
    private val windowSamples = sampleRateHz.roundToInt().coerceAtLeast(8)
    private var channels: List<BandChannel> = emptyList()
    private var samplesUntilEmit = emitEverySamples

    fun setBands(bands: List<FrequencyBand>) {
        val built = bands.map { band ->
            val sections = FilterDesign.butterworthBandpass(
                lowHz = band.lowHz,
                highHz = band.highHz,
                sampleRateHz = sampleRateHz,
            )
            BandChannel(
                id = band.id,
                filters = sections.map { it.toBiquad() },
                window = RmsWindow(windowSamples),
            )
        }
        channels = built
        samplesUntilEmit = emitEverySamples
    }

    fun reset() {
        channels.forEach { channel ->
            channel.filters.forEach { it.reset() }
            channel.window.reset()
        }
        samplesUntilEmit = emitEverySamples
    }

    /**
     * @param filteredMicrovolts one sample that has already had DC removal,
     *   the EEG band-pass, and the mains notch applied.
     * @return amplitudes in band order when a display update is due and every
     *   window is full; otherwise null. Acquisition should still call this
     *   for every sample.
     */
    fun push(filteredMicrovolts: Double): List<BandAmplitude>? {
        if (channels.isEmpty() || !filteredMicrovolts.isFinite()) return null
        val ready = ArrayList<BandAmplitude>(channels.size)
        var allFull = true
        for (channel in channels) {
            var value = filteredMicrovolts
            for (filter in channel.filters) {
                value = filter.process(value)
            }
            channel.window.push(value)
            if (channel.window.isFull()) {
                ready += BandAmplitude(channel.id, channel.window.rms())
            } else {
                allFull = false
            }
        }
        samplesUntilEmit--
        if (samplesUntilEmit <= 0) {
            samplesUntilEmit = emitEverySamples
            if (allFull) return ready
        }
        return null
    }

    private class BandChannel(
        val id: String,
        val filters: List<Biquad>,
        val window: RmsWindow,
    )

    private class RmsWindow(private val length: Int) {
        private val buffer = DoubleArray(length)
        private var index = 0
        private var count = 0

        fun push(sample: Double) {
            buffer[index] = sample
            index = (index + 1) % length
            if (count < length) count++
        }

        fun isFull(): Boolean = count == length

        fun rms(): Double {
            var sum = 0.0
            for (i in 0 until count) {
                val sample = buffer[i]
                sum += sample * sample
            }
            return sqrt((sum / count).coerceAtLeast(0.0))
        }

        fun reset() {
            index = 0
            count = 0
        }
    }

    private companion object {
        const val UPDATES_PER_SECOND = 6.25
    }
}

data class BandAmplitude(
    val bandId: String,
    val microvoltsRms: Double,
)
