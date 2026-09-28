package com.callibri.nfb.signal

import com.callibri.nfb.protocol.FrequencyBand
import kotlin.math.roundToInt

/**
 * Turns a 250 Hz microvolt stream into display snapshots.
 *
 * Order for every sample: 1 Hz high-pass (DC), 60 Hz notch, 45 Hz low-pass,
 * then [BandPowerCalculator] for the current band list. Snapshots are emitted
 * only when the calculator has a new amplitude update (about 6 Hz). The
 * caller should still pass every sample so the filters stay at full rate.
 */
class EegProcessor(
    private val sampleRateHz: Double,
    private val calculator: BandPowerCalculator = BandPowerCalculator(sampleRateHz),
) {
    private val frontend: List<Biquad> =
        FilterDesign.eegFrontend(sampleRateHz).map { it.toBiquad() }
    private val waveform = FloatArray(sampleRateHz.roundToInt().coerceIn(32, 2000))
    private var waveIndex = 0
    private var waveCount = 0

    fun setBands(bands: List<FrequencyBand>) {
        calculator.setBands(bands)
    }

    fun reset() {
        frontend.forEach { it.reset() }
        calculator.reset()
        waveIndex = 0
        waveCount = 0
    }

    fun push(rawMicrovolts: Double): EegSnapshot? {
        if (!rawMicrovolts.isFinite()) return null
        var filtered = rawMicrovolts
        for (section in frontend) {
            filtered = section.process(filtered)
        }
        if (!filtered.isFinite()) return null
        waveform[waveIndex] = filtered.toFloat()
        waveIndex = (waveIndex + 1) % waveform.size
        if (waveCount < waveform.size) waveCount++

        val amplitudes = calculator.push(filtered) ?: return null
        return EegSnapshot(
            amplitudes = amplitudes,
            latestRawMicrovolts = rawMicrovolts,
            latestFilteredMicrovolts = filtered,
            waveform = copyWaveform(),
        )
    }

    private fun copyWaveform(): List<Float> {
        if (waveCount == 0) return emptyList()
        val start = if (waveCount < waveform.size) 0 else waveIndex
        return List(waveCount) { offset -> waveform[(start + offset) % waveform.size] }
    }
}

data class EegSnapshot(
    val amplitudes: List<BandAmplitude>,
    val latestRawMicrovolts: Double,
    val latestFilteredMicrovolts: Double,
    val waveform: List<Float>,
)
