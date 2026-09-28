package com.callibri.nfb.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.callibri.nfb.callibri.CallibriDevice
import com.callibri.nfb.callibri.CallibriManager
import com.callibri.nfb.callibri.CallibriPermissions
import com.callibri.nfb.callibri.CallibriState
import com.callibri.nfb.callibri.ElectrodeContact
import com.callibri.nfb.callibri.SessionPhase
import com.callibri.nfb.protocol.FRE1Protocol
import com.callibri.nfb.protocol.FrequencyBand
import com.callibri.nfb.signal.EegProcessor
import com.callibri.nfb.signal.EegSnapshot
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BandUi(
    val id: String,
    val label: String,
    val lowText: String,
    val highText: String,
    val appliedLowHz: Double,
    val appliedHighHz: Double,
    val amplitudeUv: Double?,
)

data class MainUiState(
    val phase: SessionPhase = SessionPhase.Disconnected,
    val permissionsGranted: Boolean = false,
    val bluetoothEnabled: Boolean = true,
    val locationServicesRequired: Boolean = false,
    val locationServicesEnabled: Boolean = true,
    val devices: List<CallibriDevice> = emptyList(),
    val deviceName: String? = null,
    val batteryPercent: Int? = null,
    val electrode: ElectrodeContact? = null,
    val streaming: Boolean = false,
    val sampleRateHz: Int = FRE1Protocol.SAMPLE_RATE_HZ,
    val linkMessage: String? = null,
    val linkMessageIsError: Boolean = false,
    val formError: String? = null,
    val bands: List<BandUi> = emptyList(),
    val latestRawUv: Double? = null,
    val waveform: List<Float> = emptyList(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val processor = EegProcessor(FRE1Protocol.SAMPLE_RATE_HZ.toDouble()).apply {
        setBands(FRE1Protocol.defaults().asList())
    }
    private val signalMutex = Mutex()
    private var processingSession = Long.MIN_VALUE
    private var lastProcessErrorNs = 0L

    private val _ui = MutableStateFlow(MainUiState(bands = initialBands()))
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    private val manager = CallibriManager(
        context = application.applicationContext,
        scope = viewModelScope,
        onBeforeSignalStart = {
            _ui.update { state ->
                state.copy(
                    bands = state.bands.map { it.copy(amplitudeUv = null) },
                    latestRawUv = null,
                    waveform = emptyList(),
                )
            }
        },
    )

    init {
        viewModelScope.launch {
            manager.state.collect { applyLink(it) }
        }
        viewModelScope.launch(Dispatchers.Default) {
            manager.samplesMicrovolts.collect { chunk ->
                signalMutex.withLock {
                    if (chunk.sessionId < processingSession) return@withLock
                    if (chunk.sessionId != processingSession) {
                        processingSession = chunk.sessionId
                        processor.reset()
                        processor.setBands(currentBands())
                    }
                    var snapshot: EegSnapshot? = null
                    for (sample in chunk.samples) {
                        try {
                            val next = processor.push(sample)
                            if (next != null) snapshot = next
                        } catch (error: Exception) {
                            logProcessingError(error)
                        }
                    }
                    snapshot?.let(::publishSnapshot)
                }
            }
        }
        refreshEnvironment()
    }

    fun refreshEnvironment() {
        val granted = CallibriPermissions.hasRuntimePermissions(getApplication())
        _ui.update { it.copy(permissionsGranted = granted) }
        manager.refreshRadioState()
    }

    fun onPermissionResult(granted: Boolean) {
        _ui.update {
            it.copy(
                permissionsGranted = granted,
                linkMessage = if (granted) it.linkMessage else "Bluetooth permission was denied. Allow it in system settings, then scan again.",
                linkMessageIsError = !granted || it.linkMessageIsError,
            )
        }
        if (granted) scan()
    }

    fun scan() {
        refreshEnvironment()
        if (!_ui.value.permissionsGranted) return
        manager.startScan()
    }

    fun stopScan() {
        manager.stopScan()
    }

    fun connect(address: String) {
        manager.connect(address)
    }

    fun startEeg() {
        manager.startEeg()
    }

    fun stopEeg() {
        manager.stopEeg()
    }

    fun disconnect() {
        manager.disconnect()
    }

    fun onScreenDisposed() {
        manager.stopScan()
    }

    fun updateLow(id: String, text: String) {
        _ui.update { state ->
            state.copy(
                bands = state.bands.map { if (it.id == id) it.copy(lowText = text) else it },
                formError = null,
            )
        }
    }

    fun updateHigh(id: String, text: String) {
        _ui.update { state ->
            state.copy(
                bands = state.bands.map { if (it.id == id) it.copy(highText = text) else it },
                formError = null,
            )
        }
    }

    fun applyBands() {
        val parsed = ArrayList<FrequencyBand>(_ui.value.bands.size)
        for (band in _ui.value.bands) {
            val range = parseRange(band.lowText, band.highText)
            if (range == null) {
                _ui.update {
                    it.copy(formError = "Use a low and a high between 0.5 and 100 Hz, with at least 1 Hz between them.")
                }
                return
            }
            parsed += FrequencyBand(band.id, band.label, range.first, range.second)
        }
        viewModelScope.launch(Dispatchers.Default) {
            val applied = signalMutex.withLock {
                try {
                    processor.setBands(parsed)
                    true
                } catch (error: Exception) {
                    logProcessingError(error)
                    false
                }
            }
            if (!applied) {
                _ui.update { it.copy(formError = "Those frequencies could not be applied. Try a wider band.") }
                return@launch
            }
            _ui.update { state ->
                state.copy(
                    formError = null,
                    bands = state.bands.map { band ->
                        val match = parsed.first { it.id == band.id }
                        band.copy(
                            appliedLowHz = match.lowHz,
                            appliedHighHz = match.highHz,
                            amplitudeUv = null,
                        )
                    },
                )
            }
        }
    }

    override fun onCleared() {
        manager.release()
        super.onCleared()
    }

    private fun applyLink(link: CallibriState) {
        _ui.update { current ->
            val connected = link.phase == SessionPhase.Connected
            current.copy(
                phase = link.phase,
                bluetoothEnabled = link.bluetoothEnabled,
                locationServicesRequired = link.locationServicesRequired,
                locationServicesEnabled = link.locationServicesEnabled,
                devices = link.devices,
                deviceName = link.deviceName,
                batteryPercent = link.batteryPercent,
                electrode = link.electrode,
                streaming = link.streaming,
                sampleRateHz = link.sampleRateHz,
                linkMessage = link.message,
                linkMessageIsError = link.messageIsError,
                bands = if (connected) current.bands else current.bands.map { it.copy(amplitudeUv = null) },
                latestRawUv = if (connected) current.latestRawUv else null,
                waveform = if (connected) current.waveform else emptyList(),
            )
        }
    }

    private fun publishSnapshot(snapshot: EegSnapshot) {
        _ui.update { state ->
            if (state.phase != SessionPhase.Connected) return@update state
            state.copy(
                latestRawUv = snapshot.latestRawMicrovolts,
                waveform = snapshot.waveform,
                bands = state.bands.map { band ->
                    val amplitude = snapshot.amplitudes.firstOrNull { it.bandId == band.id }
                    if (amplitude == null) band else band.copy(amplitudeUv = amplitude.microvoltsRms)
                },
            )
        }
    }

    private fun currentBands(): List<FrequencyBand> =
        _ui.value.bands.map { FrequencyBand(it.id, it.label, it.appliedLowHz, it.appliedHighHz) }

    private fun logProcessingError(error: Exception) {
        val now = System.nanoTime()
        if (now - lastProcessErrorNs < 1_000_000_000L) return
        lastProcessErrorNs = now
        Log.e(TAG, "processing error", error)
    }

    private companion object {
        const val TAG = "CallibriNFB"
    }
}

private fun initialBands(): List<BandUi> =
    FRE1Protocol.defaults().asList().map { band ->
        BandUi(
            id = band.id,
            label = band.label,
            lowText = formatHz(band.lowHz),
            highText = formatHz(band.highHz),
            appliedLowHz = band.lowHz,
            appliedHighHz = band.highHz,
            amplitudeUv = null,
        )
    }

internal fun formatHz(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)

private fun parseRange(lowText: String, highText: String): Pair<Double, Double>? {
    val low = lowText.trim().toDoubleOrNull() ?: return null
    val high = highText.trim().toDoubleOrNull() ?: return null
    if (!low.isFinite() || !high.isFinite()) return null
    if (low < 0.5 || high > 100.0 || high - low < 1.0) return null
    return low to high
}
