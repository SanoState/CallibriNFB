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

data class CallibriState(
    val phase: SessionPhase = SessionPhase.Disconnected,
    val devices: List<CallibriDevice> = emptyList(),
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val batteryPercent: Int? = null,
    val electrode: ElectrodeContact? = null,
    val streaming: Boolean = false,
    val sampleRateHz: Int = FRE1Protocol.SAMPLE_RATE_HZ,
    val bluetoothEnabled: Boolean = true,
    val locationServicesRequired: Boolean = false,
    val locationServicesEnabled: Boolean = true,
    val message: String? = null,
    val messageIsError: Boolean = false,
)
