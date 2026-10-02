package com.callibri.nfb.callibri

import com.callibri.nfb.protocol.FRE1Protocol

/** One BLE notification, already converted to microvolts. [sessionId] increases each time EEG starts. */
class MicrovoltChunk(
    val sessionId: Long,
    val samples: DoubleArray,
)

data class CallibriDevice(
    val name: String,
    val address: String,
)

enum class SessionPhase {
    Disconnected,
    Scanning,
    Connecting,
    Connected,
}

/** Mapped from NeuroSDK CallibriElectrodeState. Null means no reading yet. */
enum class ElectrodeContact {
    Normal,
    HighResistance,
    Detached,
}

/**
 * What [com.neurosdk2.neuro.types.CallibriSignalData] contained, before filtering.
 * Volt fields are the doubles from getSamples(), not microvolts.
 */
data class SignalIngress(
    val callbackCount: Long = 0,
    val callbacksPerSecond: Int = 0,
    val latestPackNum: Int? = null,
    val previousPackNum: Int? = null,
    val packNumChanging: Boolean = false,
    val latestCallbackSamples: Int = 0,
    val latestPacketSamples: Int = 0,
    val firstVolts: Double? = null,
    val lastVolts: Double? = null,
    val minVolts: Double? = null,
    val maxVolts: Double? = null,
    val distinctValuesLastSecond: Int = 0,
    val samplesPerSecond: Int = 0,
    val droppedChunks: Long = 0,
    /** Lowest getSamples() value in the last completed second. */
    val minVoltsLastSecond: Double? = null,
    /** Highest getSamples() value in the last completed second. */
    val maxVoltsLastSecond: Double? = null,
    /** Set when every sample in the last second sat on one code. */
    val railNote: String? = null,
)

data class CallibriState(
    val phase: SessionPhase = SessionPhase.Disconnected,
    val devices: List<CallibriDevice> = emptyList(),
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val batteryPercent: Int? = null,
    val electrode: ElectrodeContact? = null,
    /** Read-back of SensorExternalSwitchInput, or a failure label if the setter did not stick. */
    val extSwInput: String? = null,
    /** Read-back of SensorADCInput, or a failure label if the setter did not stick. */
    val adcInput: String? = null,
    /** Read-back of SensorGain, or a failure label if the setter did not stick. */
    val gain: String? = null,
    /** Read-back of SensorDataOffset, or a failure label if the setter did not stick. */
    val dataOffset: String? = null,
    val signalIngress: SignalIngress = SignalIngress(),
    val streaming: Boolean = false,
    val sampleRateHz: Int = FRE1Protocol.SAMPLE_RATE_HZ,
    val bluetoothEnabled: Boolean = true,
    val locationServicesRequired: Boolean = false,
    val locationServicesEnabled: Boolean = true,
    val message: String? = null,
    val messageIsError: Boolean = false,
)
