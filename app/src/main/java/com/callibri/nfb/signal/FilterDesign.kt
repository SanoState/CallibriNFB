package com.callibri.nfb.signal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time IIR sections for 250 Hz EEG.
 *
 * Frontend ([eegFrontend]), applied once to every sample:
 * - 2nd-order Butterworth high-pass at 1 Hz (RBJ cookbook, Q = 1/sqrt(2)).
 *   This is the DC-offset removal. A one-pole subtract-the-mean would also
 *   work, but a Butterworth high-pass has a defined cutoff and no extra state
 *   machine.
 * - 2nd-order notch at 60 Hz, Q = 30 (RBJ). Narrow enough to leave the 38 Hz
 *   edge of a wide band alone, deep enough to remove North American mains.
 * - 4th-order Butterworth low-pass at 45 Hz, two cascaded RBJ low-pass
 *   sections. Q values are 1/(2*cos((2k+1)π/(2N))) for N = 4, which is the
 *   Butterworth pole pairing. 45 Hz rather than 40 Hz so a band that ends at
 *   38 Hz is not already rolled off before the band filter runs. Together
 *   with the 1 Hz high-pass this is the ~1–40 Hz EEG analysis band.
 *
 * Per band ([butterworthBandpass]):
 * - 8th-order Butterworth band-pass (analog prototype order 4, bilinear
 *   transform). That is four biquads. Gain is normalized to 0 dB at the
 *   geometric center sqrt(low*high), so a sine in the middle of the band
 *   keeps its RMS. RMS is used because, after DC removal, it is the
 *   amplitude of the band-limited signal (for a sine, peak/sqrt(2)), and it
 *   can be updated continuously from a one-second window without an FFT.
 */
internal object FilterDesign {
    private const val HIGHPASS_HZ = 1.0
    private const val LOWPASS_HZ = 45.0
    private const val LOWPASS_ORDER = 4
    private const val NOTCH_HZ = 60.0
    private const val NOTCH_Q = 30.0
    private const val BANDPASS_PROTOTYPE_ORDER = 4

    fun eegFrontend(sampleRateHz: Double): List<BiquadCoefficients> {
        val highpass = rbjHighpass(HIGHPASS_HZ, sampleRateHz, q = 1.0 / sqrt(2.0))
        val notch = rbjNotch(NOTCH_HZ, sampleRateHz, NOTCH_Q)
        val lowpass = butterworthLowpass(LOWPASS_HZ, sampleRateHz, LOWPASS_ORDER)
        return listOf(highpass, notch) + lowpass
    }

    fun butterworthBandpass(
        lowHz: Double,
        highHz: Double,
        sampleRateHz: Double,
        prototypeOrder: Int = BANDPASS_PROTOTYPE_ORDER,
    ): List<BiquadCoefficients> {
        require(lowHz > 0.0 && highHz > lowHz && highHz < sampleRateHz / 2.0) {
            "Band $lowHz–$highHz Hz is outside 0..Nyquist at $sampleRateHz Hz"
        }
        require(prototypeOrder >= 1) { "prototype order must be positive" }

        val analogPoles = ArrayList<Complex>(prototypeOrder)
        for (k in 0 until prototypeOrder) {
            val theta = PI * (2 * k + 1) / (2 * prototypeOrder)
            analogPoles += Complex(-sin(theta), cos(theta))
        }

        val w1 = kotlin.math.tan(PI * lowHz / sampleRateHz)
        val w2 = kotlin.math.tan(PI * highHz / sampleRateHz)
        val bandwidth = w2 - w1
        val w0 = sqrt(w1 * w2)

        val digitalPoles = ArrayList<Complex>(prototypeOrder * 2)
        for (pole in analogPoles) {
            val scaled = pole * bandwidth
            val discriminant = sqrt(scaled * scaled - Complex(4.0 * w0 * w0, 0.0))
            val plus = (scaled + discriminant) * 0.5
            val minus = (scaled - discriminant) * 0.5
            digitalPoles += bilinear(plus)
            digitalPoles += bilinear(minus)
        }
        if (digitalPoles.any { it.abs() >= 1.0 }) {
            throw IllegalArgumentException("Band $lowHz–$highHz Hz produced an unstable filter")
        }

        val ones = ArrayDeque(List(prototypeOrder) { Complex.ONE })
        val negatives = ArrayDeque(List(prototypeOrder) { Complex(-1.0, 0.0) })
        val remaining = digitalPoles.toMutableList()
        val sections = ArrayList<BiquadCoefficients>(prototypeOrder)
        while (remaining.isNotEmpty()) {
            val pole = remaining.removeAt(0)
            val matchIndex = remaining.indices.minBy { abs((remaining[it] - pole.conjugate()).abs()) }
            val conjugate = remaining.removeAt(matchIndex)
            val zeroAtDc = ones.removeFirst()
            val zeroAtNyquist = negatives.removeFirst()
            sections += biquadFromZeroPole(zeroAtDc, zeroAtNyquist, pole, conjugate)
        }

        val centerHz = sqrt(lowHz * highHz)
        val gain = magnitudeAt(sections, centerHz, sampleRateHz)
        if (!gain.isFinite() || gain < 1e-12) {
            throw IllegalArgumentException("Band $lowHz–$highHz Hz has no passband gain")
        }
        val first = sections[0]
        sections[0] = first.copy(
            b0 = first.b0 / gain,
            b1 = first.b1 / gain,
            b2 = first.b2 / gain,
        )
        return sections
    }

    private fun butterworthLowpass(
        cutoffHz: Double,
        sampleRateHz: Double,
        order: Int,
    ): List<BiquadCoefficients> {
        require(order % 2 == 0) { "low-pass order must be even" }
        return List(order / 2) { k ->
            val theta = PI * (2 * k + 1) / (2.0 * order)
            val q = 1.0 / (2.0 * cos(theta))
            rbjLowpass(cutoffHz, sampleRateHz, q)
        }
    }

    private fun rbjLowpass(cutoffHz: Double, sampleRateHz: Double, q: Double): BiquadCoefficients {
        val (cw, alpha) = trig(cutoffHz, sampleRateHz, q)
        val b0 = (1.0 - cw) / 2.0
        val b1 = 1.0 - cw
        val b2 = (1.0 - cw) / 2.0
        return BiquadCoefficients(b0, b1, b2, 1.0 + alpha, -2.0 * cw, 1.0 - alpha)
    }

    private fun rbjHighpass(cutoffHz: Double, sampleRateHz: Double, q: Double): BiquadCoefficients {
        val (cw, alpha) = trig(cutoffHz, sampleRateHz, q)
        val b0 = (1.0 + cw) / 2.0
        val b1 = -(1.0 + cw)
        val b2 = (1.0 + cw) / 2.0
        return BiquadCoefficients(b0, b1, b2, 1.0 + alpha, -2.0 * cw, 1.0 - alpha)
    }

    private fun rbjNotch(cutoffHz: Double, sampleRateHz: Double, q: Double): BiquadCoefficients {
        val (cw, alpha) = trig(cutoffHz, sampleRateHz, q)
        return BiquadCoefficients(1.0, -2.0 * cw, 1.0, 1.0 + alpha, -2.0 * cw, 1.0 - alpha)
    }

    private fun trig(cutoffHz: Double, sampleRateHz: Double, q: Double): Pair<Double, Double> {
        val w0 = 2.0 * PI * cutoffHz / sampleRateHz
        val cw = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        return cw to alpha
    }

    private fun bilinear(analog: Complex): Complex {
        // Frequencies were pre-warped with tan(), so s maps with 2/T = 1:
        // z = (1 + s) / (1 - s).
        return (Complex.ONE + analog) / (Complex.ONE - analog)
    }

    private fun biquadFromZeroPole(
        zeroA: Complex,
        zeroB: Complex,
        poleA: Complex,
        poleB: Complex,
    ): BiquadCoefficients {
        val b1 = -(zeroA + zeroB)
        val b2 = zeroA * zeroB
        val a1 = -(poleA + poleB)
        val a2 = poleA * poleB
        val coeffs = listOf(b1, b2, a1, a2)
        if (coeffs.any { abs(it.im) > 1e-6 }) {
            throw IllegalArgumentException("Band-pass section was not conjugate-paired")
        }
        return BiquadCoefficients(
            b0 = 1.0,
            b1 = b1.re,
            b2 = b2.re,
            a0 = 1.0,
            a1 = a1.re,
            a2 = a2.re,
        )
    }

    private fun magnitudeAt(
        sections: List<BiquadCoefficients>,
        freqHz: Double,
        sampleRateHz: Double,
    ): Double {
        val w = 2.0 * PI * freqHz / sampleRateHz
        val zr = cos(w)
        val zi = sin(w)
        var hr = 1.0
        var hi = 0.0
        for (section in sections) {
            val zm1r = zr
            val zm1i = -zi
            val zm2r = zm1r * zm1r - zm1i * zm1i
            val zm2i = 2.0 * zm1r * zm1i
            val numR = section.b0 + section.b1 * zm1r + section.b2 * zm2r
            val numI = section.b1 * zm1i + section.b2 * zm2i
            val denR = section.a0 + section.a1 * zm1r + section.a2 * zm2r
            val denI = section.a1 * zm1i + section.a2 * zm2i
            val denMag = denR * denR + denI * denI
            val rr = (numR * denR + numI * denI) / denMag
            val ri = (numI * denR - numR * denI) / denMag
            val nextR = hr * rr - hi * ri
            val nextI = hr * ri + hi * rr
            hr = nextR
            hi = nextI
        }
        return sqrt(hr * hr + hi * hi)
    }
}

internal data class Complex(val re: Double, val im: Double) {
    operator fun unaryMinus() = Complex(-re, -im)
    operator fun plus(other: Complex) = Complex(re + other.re, im + other.im)
    operator fun minus(other: Complex) = Complex(re - other.re, im - other.im)
    operator fun times(other: Complex) = Complex(
        re * other.re - im * other.im,
        re * other.im + im * other.re,
    )
    operator fun times(scale: Double) = Complex(re * scale, im * scale)
    operator fun div(other: Complex): Complex {
        val denominator = other.re * other.re + other.im * other.im
        return Complex(
            (re * other.re + im * other.im) / denominator,
            (im * other.re - re * other.im) / denominator,
        )
    }

    fun conjugate() = Complex(re, -im)
    fun abs() = sqrt(re * re + im * im)

    companion object {
        val ONE = Complex(1.0, 0.0)
    }
}

private fun sqrt(value: Complex): Complex {
    val magnitude = sqrt(value.abs())
    val angle = atan2(value.im, value.re)
    return Complex(magnitude * cos(angle / 2.0), magnitude * sin(angle / 2.0))
}
