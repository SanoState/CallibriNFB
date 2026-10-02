package com.callibri.nfb.callibri

import android.bluetooth.BluetoothManager
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.callibri.nfb.protocol.FRE1Protocol
import com.neurosdk2.neuro.Callibri
import com.neurosdk2.neuro.Scanner
import com.neurosdk2.neuro.Sensor
import com.neurosdk2.neuro.interfaces.CallibriElectrodeStateChanged
import com.neurosdk2.neuro.interfaces.CallibriSignalDataReceived
import com.neurosdk2.neuro.types.CallibriElectrodeState
import com.neurosdk2.neuro.types.CallibriSignalType
import com.neurosdk2.neuro.types.SensorADCInput
import com.neurosdk2.neuro.types.SensorCommand
import com.neurosdk2.neuro.types.SensorExternalSwitchInput
import com.neurosdk2.neuro.types.SensorFamily
import com.neurosdk2.neuro.types.SensorInfo
import com.neurosdk2.neuro.types.SensorParameter
import com.neurosdk2.neuro.types.SensorSamplingFrequency
import com.neurosdk2.neuro.types.SensorState
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * All NeuroSDK calls for a single Callibri.
 *
 * The README's Kotlin snippets use `Sensor.CallibriSignalDataReceived` and
 * `SamplingFrequency`. In neurosdk2 1.0.6.18 the callback interfaces live in
 * `com.neurosdk2.neuro.interfaces`, the command method is `execCommand`, the
 * rate enum is `SensorSamplingFrequency`, and the EEG preset is
 * `Callibri.setSignalType(CallibriSignalType.EEG)`.
 *
 * Scanner creation, `createSensor`, `connect`, and `execCommand` are blocking,
 * so they run on a single background thread. Only one sensor is held at a time.
 */
class CallibriManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onBeforeSignalStart: () -> Unit = {},
) {
    private val sdkExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "callibri-sdk")
    }
    private val sdkDispatcher = sdkExecutor.asCoroutineDispatcher()
    private val connectGate = AtomicBoolean(false)

    private val _state = MutableStateFlow(CallibriState())
    val state: StateFlow<CallibriState> = _state.asStateFlow()

    private val _samplesMicrovolts = MutableSharedFlow<MicrovoltChunk>(
        extraBufferCapacity = 128,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val samplesMicrovolts: SharedFlow<MicrovoltChunk> = _samplesMicrovolts.asSharedFlow()

    private var scanner: Scanner? = null
    private var scanning = false
    private var scanGeneration = 0
    private val found = LinkedHashMap<String, SensorInfo>()

    private var sensor: Callibri? = null
    private var streaming = false
    private var closingSensor = false

    @Volatile
    private var acceptingSamples = false

    @Volatile
    private var signalSession = 0L
    private var samplesSinceLog = 0
    private var lastLogAtMs = 0L
    private var lastLoggedElectrode: ElectrodeContact? = null
    private var callbackCount = 0L
    private var callbacksThisSecond = 0
    private var distinctThisSecond = HashSet<Long>()
    private var lastSeenPackNum: Int? = null
    private var packNumChangedThisSecond = false
    private var callbacksLogged = 0
    private var droppedChunks = 0L
    private var lastDiagUiMs = 0L
    private var ingress = SignalIngress()

    fun refreshRadioState() {
        scope.launch(sdkDispatcher) {
            _state.update { it.withRadio() }
        }
    }

    fun startScan() {
        scope.launch(sdkDispatcher) {
            startScanLocked()
        }
    }

    fun stopScan() {
        scope.launch(sdkDispatcher) {
            stopScanLocked(updatePhase = true)
        }
    }

    fun connect(address: String) {
        if (!connectGate.compareAndSet(false, true)) {
            Log.i(TAG, "connect ignored; a connection is already in progress")
            return
        }
        scope.launch(sdkDispatcher) {
            try {
                connectLocked(address)
            } finally {
                connectGate.set(false)
            }
        }
    }

    fun startEeg() {
        scope.launch(sdkDispatcher) {
            try {
                startSignalLocked()
            } catch (error: Exception) {
                Log.e(TAG, "EEG start failed", error)
                acceptingSamples = false
                _state.update {
                    it.copy(
                        streaming = false,
                        message = "Couldn't start EEG: ${error.message ?: "unknown error"}",
                        messageIsError = true,
                    )
                }
            }
        }
    }

    fun stopEeg() {
        scope.launch(sdkDispatcher) {
            stopSignalLocked()
        }
    }

    fun disconnect() {
        scope.launch(sdkDispatcher) {
            if (sensor == null) {
                Log.i(TAG, "disconnect ignored; no sensor")
                return@launch
            }
            releaseSensorLocked(message = "Disconnected.", isError = false)
        }
    }

    /** Blocks until the sensor and scanner are released. Safe from onCleared. */
    fun release() {
        runBlocking(sdkDispatcher) {
            releaseSensorLocked(message = null, isError = false)
            closeScannerLocked()
        }
        sdkExecutor.shutdown()
    }

    private fun startScanLocked() {
        if (sensor != null || _state.value.phase == SessionPhase.Connecting) {
            Log.i(TAG, "scan ignored; a sensor session is active")
            return
        }
        if (scanning) {
            Log.i(TAG, "scan ignored; already scanning")
            return
        }
        val radio = _state.value.withRadio()
        if (!radio.bluetoothEnabled) {
            Log.w(TAG, "scan blocked; bluetooth off")
            _state.update {
                radio.copy(
                    phase = SessionPhase.Disconnected,
                    message = "Bluetooth is off. Turn it on, then scan again.",
                    messageIsError = true,
                )
            }
            return
        }
        if (radio.locationServicesRequired && !radio.locationServicesEnabled) {
            Log.w(TAG, "scan blocked; location services off")
            _state.update {
                radio.copy(
                    phase = SessionPhase.Disconnected,
                    message = "Turn on location services, then scan again. Android 10 and earlier need them for Bluetooth scanning.",
                    messageIsError = true,
                )
            }
            return
        }
        if (!CallibriPermissions.hasRuntimePermissions(context)) {
            Log.w(TAG, "scan blocked; permissions missing")
            _state.update {
                radio.copy(
                    message = "Bluetooth permission is required before scanning.",
                    messageIsError = true,
                )
            }
            return
        }

        closeScannerLocked()
        found.clear()
        try {
            val created = Scanner(SensorFamily.SensorLECallibri)
            val generation = scanGeneration
            created.sensorsChanged = Scanner.ScannerCallback { _, sensors ->
                if (sensors == null || generation != scanGeneration) return@ScannerCallback
                val snapshot = sensors.filterNotNull()
                scope.launch(sdkDispatcher) {
                    if (generation == scanGeneration) mergeFound(snapshot)
                }
            }
            scanner = created
            created.start()
            scanning = true
            Log.i(TAG, "scan start")
            val already = created.sensors
            if (already != null) mergeFound(already.filterNotNull())
            _state.update {
                it.withRadio().copy(
                    phase = SessionPhase.Scanning,
                    devices = emptyList(),
                    message = null,
                    messageIsError = false,
                )
            }
        } catch (error: Exception) {
            Log.e(TAG, "scan start failed", error)
            closeScannerLocked()
            _state.update {
                it.copy(
                    phase = SessionPhase.Disconnected,
                    message = "Couldn't start scanning: ${error.message ?: "unknown error"}",
                    messageIsError = true,
                )
            }
        }
    }

    private fun stopScanLocked(updatePhase: Boolean) {
        val current = scanner
        if (current == null || !scanning) return
        try {
            current.stop()
            scanning = false
            Log.i(TAG, "scan stop")
        } catch (error: Exception) {
            scanning = false
            Log.e(TAG, "scan stop failed", error)
        }
        if (updatePhase && sensor == null && _state.value.phase == SessionPhase.Scanning) {
            _state.update { it.copy(phase = SessionPhase.Disconnected) }
        }
    }

    private fun mergeFound(sensors: List<SensorInfo>) {
        if (scanner == null) return
        var changed = false
        for (info in sensors) {
            if (info.sensFamily != SensorFamily.SensorLECallibri) continue
            val address = info.address ?: continue
            if (address.isBlank()) continue
            if (!found.containsKey(address)) {
                val name = info.name?.takeIf { it.isNotBlank() } ?: "Callibri"
                Log.i(TAG, "device found: $name $address")
                changed = true
            }
            found[address] = info
        }
        if (!changed && _state.value.devices.size == found.size) return
        val devices = found.values.map { info ->
            CallibriDevice(
                name = info.name?.takeIf { it.isNotBlank() } ?: "Callibri",
                address = info.address.orEmpty(),
            )
        }
        _state.update { it.copy(devices = devices) }
    }

    private fun connectLocked(address: String) {
        if (sensor != null) {
            Log.i(TAG, "connect ignored; a sensor is already open")
            _state.update {
                it.copy(
                    message = "Disconnect the current Callibri before connecting another.",
                    messageIsError = true,
                )
            }
            return
        }
        val info = found[address]
        if (info == null) {
            _state.update {
                it.copy(
                    message = "That Callibri is no longer in the scan list. Scan again.",
                    messageIsError = true,
                )
            }
            return
        }
        val activeScanner = scanner
        if (activeScanner == null) {
            _state.update {
                it.copy(
                    message = "Scan for a Callibri before connecting.",
                    messageIsError = true,
                )
            }
            return
        }

        _state.update {
            it.copy(
                phase = SessionPhase.Connecting,
                message = "Connecting… Keep the sensor awake and nearby.",
                messageIsError = false,
            )
        }
        Log.i(TAG, "connect: ${info.name} ${info.address}")
        stopScanLocked(updatePhase = false)

        val created = try {
            activeScanner.createSensor(info)
        } catch (error: Exception) {
            Log.e(TAG, "connect failed", error)
            _state.update {
                it.copy(
                    phase = SessionPhase.Disconnected,
                    message = "Couldn't connect: ${error.message ?: "unknown error"}",
                    messageIsError = true,
                )
            }
            return
        }

        val callibri = created as? Callibri
        if (callibri == null) {
            Log.e(TAG, "connect failed: sensor type ${created.javaClass.name}")
            try {
                created.close()
            } catch (error: Exception) {
                Log.e(TAG, "sensor close failed", error)
            }
            _state.update {
                it.copy(
                    phase = SessionPhase.Disconnected,
                    message = "The scanner did not return a Callibri sensor.",
                    messageIsError = true,
                )
            }
            return
        }

        sensor = callibri
        closeScannerLocked()
        wireSensor(callibri)

        if (callibri.state != SensorState.StateInRange) {
            Log.e(TAG, "connect failed: state ${callibri.state}")
            releaseSensorLocked("Callibri did not stay connected.", isError = true)
            return
        }

        val deviceName = callibri.name?.takeIf { it.isNotBlank() }
            ?: info.name?.takeIf { it.isNotBlank() }
            ?: "Callibri"
        try {
            configureEeg(callibri)
        } catch (error: Exception) {
            Log.e(TAG, "EEG configure failed", error)
            _state.update {
                it.copy(
                    phase = SessionPhase.Connected,
                    deviceName = deviceName,
                    deviceAddress = callibri.address ?: info.address,
                    batteryPercent = readBattery(callibri),
                    streaming = false,
                    electrode = null,
                    message = "Connected, but EEG setup failed: ${error.message ?: "unknown error"}",
                    messageIsError = true,
                )
            }
            return
        }

        _state.update {
            it.copy(
                phase = SessionPhase.Connected,
                deviceName = deviceName,
                deviceAddress = callibri.address ?: info.address,
                batteryPercent = readBattery(callibri),
                streaming = false,
                sampleRateHz = describedRate(callibri),
                electrode = null,
                message = null,
                messageIsError = false,
            )
        }
        try {
            startSignalLocked()
        } catch (error: Exception) {
            Log.e(TAG, "EEG start failed", error)
            acceptingSamples = false
            _state.update {
                it.copy(
                    streaming = false,
                    message = "Connected, but EEG did not start: ${error.message ?: "unknown error"}",
                    messageIsError = true,
                )
            }
        }
    }

    private fun wireSensor(callibri: Callibri) {
        callibri.sensorStateChanged = Sensor.SensorStateChanged { state ->
            Log.i(TAG, "connection state: $state")
            if (state == SensorState.StateOutOfRange && !closingSensor) {
                scope.launch(sdkDispatcher) {
                    if (sensor !== callibri) return@launch
                    releaseSensorLocked(
                        message = "Callibri disconnected or went out of range.",
                        isError = true,
                    )
                }
            }
        }
        callibri.batteryChanged = Sensor.BatteryChanged { power ->
            _state.update { it.copy(batteryPercent = power) }
        }
    }

    private fun configureEeg(callibri: Callibri) {
        val signalType = CallibriSignalType.EEG
        val sampling = SensorSamplingFrequency.FrequencyHz250
        val extSw = SensorExternalSwitchInput.ExtSwInUSB
        val adc = SensorADCInput.ADCInputResistance
        Log.i(
            TAG,
            "EEG configure enums: CallibriSignalType.${signalType.name} " +
                "SensorSamplingFrequency.${sampling.name} " +
                "SensorExternalSwitchInput.${extSw.name} " +
                "SensorADCInput.${adc.name}",
        )
        callibri.signalType = signalType
        if (callibri.isSupportedParameter(SensorParameter.ParameterSamplingFrequency)) {
            callibri.setSamplingFrequency(sampling)
        } else {
            Log.w(TAG, "sampling frequency parameter is not supported; reading the current rate")
        }
        val rate = describedRate(callibri)
        Log.i(
            TAG,
            "configured EEG preset=${callibri.signalType} rate=$rate Hz (${callibri.samplingFrequency}) " +
                "startSignal=${callibri.isSupportedCommand(SensorCommand.StartSignal)}",
        )
        if (rate != FRE1Protocol.SAMPLE_RATE_HZ) {
            throw IllegalStateException("Callibri sampling rate is $rate Hz; expected ${FRE1Protocol.SAMPLE_RATE_HZ} Hz")
        }
        // Signal-type preset is applied first. ExtSw and ADC are set after it so the
        // preset cannot leave the built-in terminals selected.
        val extSwRead = writeExtSw(callibri, extSw)
        val adcRead = writeAdc(callibri, adc)
        val extSwAfterAdc = readExtSw(callibri)
        if (extSwAfterAdc != extSw) {
            val detail = "ExtSwInput read back ${extSwAfterAdc?.name ?: "null"} after ADC was set; wanted ${extSw.name}."
            Log.e(TAG, detail)
            _state.update { it.copy(extSwInput = extSwAfterAdc?.name ?: "read failed", adcInput = adcRead.name) }
            throw IllegalStateException(detail)
        }
        Log.i(
            TAG,
            "EEG input confirmed ExtSwInput=${extSwRead.name} index=${extSwRead.index()} " +
                "ADCInput=${adcRead.name} index=${adcRead.index()}",
        )
        _state.update { it.copy(extSwInput = extSwRead.name, adcInput = adcRead.name) }
    }

    private fun writeExtSw(callibri: Callibri, wanted: SensorExternalSwitchInput): SensorExternalSwitchInput {
        if (!supports(callibri, SensorParameter.ParameterExternalSwitchState)) {
            val detail = "ExtSwInput is not supported. Wanted SensorExternalSwitchInput.${wanted.name}."
            Log.e(TAG, detail)
            _state.update { it.copy(extSwInput = "unsupported") }
            throw IllegalStateException(detail)
        }
        try {
            callibri.extSwInput = wanted
        } catch (error: Exception) {
            val detail = "ExtSwInput setter failed for SensorExternalSwitchInput.${wanted.name}: ${error.message ?: error.javaClass.simpleName}"
            Log.e(TAG, detail, error)
            _state.update { it.copy(extSwInput = "set failed") }
            throw IllegalStateException(detail, error)
        }
        val read = readExtSw(callibri)
        Log.i(TAG, "ExtSwInput set SensorExternalSwitchInput.${wanted.name} readBack=${read?.name ?: "null"} index=${read?.index()}")
        if (read != wanted) {
            val detail = "ExtSwInput read back ${read?.name ?: "null"}; wanted ${wanted.name}. USB mode was not set."
            Log.e(TAG, detail)
            _state.update { it.copy(extSwInput = read?.name ?: "read failed") }
            throw IllegalStateException(detail)
        }
        _state.update { it.copy(extSwInput = read.name) }
        return read
    }

    private fun writeAdc(callibri: Callibri, wanted: SensorADCInput): SensorADCInput {
        if (!supports(callibri, SensorParameter.ParameterADCInputState)) {
            val detail = "ADCInput is not supported. Wanted SensorADCInput.${wanted.name}."
            Log.e(TAG, detail)
            _state.update { it.copy(adcInput = "unsupported") }
            throw IllegalStateException(detail)
        }
        try {
            callibri.setADCInput(wanted)
        } catch (error: Exception) {
            val detail = "ADCInput setter failed for SensorADCInput.${wanted.name}: ${error.message ?: error.javaClass.simpleName}"
            Log.e(TAG, detail, error)
            _state.update { it.copy(adcInput = "set failed") }
            throw IllegalStateException(detail)
        }
        val read = readAdc(callibri)
        Log.i(TAG, "ADCInput set SensorADCInput.${wanted.name} readBack=${read?.name ?: "null"} index=${read?.index()}")
        if (read != wanted) {
            val detail = "ADCInput read back ${read?.name ?: "null"}; wanted ${wanted.name}."
            Log.e(TAG, detail)
            _state.update { it.copy(adcInput = read?.name ?: "read failed") }
            throw IllegalStateException(detail)
        }
        _state.update { it.copy(adcInput = read.name) }
        return read
    }

    private fun readExtSw(callibri: Callibri): SensorExternalSwitchInput? =
        try {
            callibri.extSwInput
        } catch (error: Exception) {
            Log.e(TAG, "ExtSwInput read failed", error)
            null
        }

    private fun readAdc(callibri: Callibri): SensorADCInput? =
        try {
            callibri.getADCInput()
        } catch (error: Exception) {
            Log.e(TAG, "ADCInput read failed", error)
            null
        }

    private fun supports(callibri: Callibri, parameter: SensorParameter): Boolean =
        try {
            callibri.isSupportedParameter(parameter)
        } catch (error: Exception) {
            Log.e(TAG, "isSupportedParameter ${parameter.name} failed", error)
            false
        }

    private fun startSignalLocked() {
        val callibri = sensor ?: run {
            _state.update {
                it.copy(
                    message = "Connect a Callibri before starting EEG.",
                    messageIsError = true,
                )
            }
            return
        }
        if (streaming) {
            Log.i(TAG, "EEG start ignored; already streaming")
            return
        }
        if (callibri.state != SensorState.StateInRange) {
            releaseSensorLocked("Callibri disconnected or went out of range.", isError = true)
            return
        }
        if (!callibri.isSupportedCommand(SensorCommand.StartSignal)) {
            _state.update {
                it.copy(
                    message = "This Callibri does not support raw signal.",
                    messageIsError = true,
                )
            }
            return
        }
        configureEeg(callibri)

        callibri.callibriElectrodeStateChanged = CallibriElectrodeStateChanged { electrode ->
            publishElectrode(electrode)
        }
        callibri.callibriSignalDataReceived = CallibriSignalDataReceived { packets ->
            onSignalPackets(packets)
        }
        signalSession += 1
        onBeforeSignalStart()
        acceptingSamples = true
        resetIngressCounters()
        lastLogAtMs = SystemClock.elapsedRealtime()
        try {
            callibri.execCommand(SensorCommand.StartSignal)
        } catch (error: Exception) {
            acceptingSamples = false
            callibri.callibriSignalDataReceived = null
            callibri.callibriElectrodeStateChanged = null
            throw error
        }
        streaming = true
        Log.i(
            TAG,
            "EEG start ExtSwInput=${_state.value.extSwInput} ADCInput=${_state.value.adcInput}. " +
                "callibriElectrodeStateChanged stays subscribed. NeuroSDK documents that callback as the electrode parameter and does not say it follows ExtSwInUSB.",
        )
        publishElectrode(readElectrode(callibri))
        _state.update {
            it.copy(
                streaming = true,
                sampleRateHz = describedRate(callibri),
                message = null,
                messageIsError = false,
            )
        }
    }

    private fun stopSignalLocked() {
        val callibri = sensor ?: return
        if (!streaming) {
            Log.i(TAG, "EEG stop ignored; not streaming")
            return
        }
        acceptingSamples = false
        streaming = false
        try {
            callibri.callibriSignalDataReceived = null
        } catch (error: Exception) {
            Log.e(TAG, "clearing signal callback failed", error)
        }
        try {
            callibri.callibriElectrodeStateChanged = null
        } catch (error: Exception) {
            Log.e(TAG, "clearing electrode callback failed", error)
        }
        try {
            callibri.execCommand(SensorCommand.StopSignal)
            Log.i(TAG, "EEG stop")
            _state.update { it.copy(streaming = false, message = null, messageIsError = false) }
        } catch (error: Exception) {
            Log.e(TAG, "EEG stop failed", error)
            _state.update {
                it.copy(
                    streaming = false,
                    message = "Couldn't stop EEG: ${error.message ?: "unknown error"}",
                    messageIsError = true,
                )
            }
        }
    }

    private fun onSignalPackets(packets: Array<com.neurosdk2.neuro.types.CallibriSignalData>?) {
        if (!acceptingSamples || packets == null) return
        try {
            callbackCount++
            callbacksThisSecond++
            val logThisCallback = callbacksLogged < LOGGED_CALLBACKS
            if (logThisCallback) {
                Log.i(
                    TAG,
                    "signal callback #$callbackCount packets=${packets.size} " +
                        "read with getPackNum() and getSamples()",
                )
            }
            val merged = ArrayList<Double>(64)
            var callbackSamples = 0
            var latestPacketSamples = 0
            var firstVolts = Double.NaN
            var lastVolts = Double.NaN
            var minVolts = Double.POSITIVE_INFINITY
            var maxVolts = Double.NEGATIVE_INFINITY
            var latestPack = lastSeenPackNum
            var previousPack = lastSeenPackNum
            for ((index, packet) in packets.withIndex()) {
                val packNum = packet.getPackNum()
                val samples = packet.getSamples()?.copyOf() ?: DoubleArray(0)
                val previous = lastSeenPackNum
                if (previous != null && packNum != previous) packNumChangedThisSecond = true
                previousPack = previous
                lastSeenPackNum = packNum
                latestPack = packNum
                if (logThisCallback) logPacket(index, packNum, samples)
                if (samples.isEmpty()) continue
                latestPacketSamples = samples.size
                firstVolts = samples[0]
                lastVolts = samples[samples.size - 1]
                minVolts = Double.POSITIVE_INFINITY
                maxVolts = Double.NEGATIVE_INFINITY
                for (volts in samples) {
                    if (volts < minVolts) minVolts = volts
                    if (volts > maxVolts) maxVolts = volts
                    distinctThisSecond.add(volts.toRawBits())
                    merged.add(volts * VOLTS_TO_MICROVOLTS)
                }
                callbackSamples += samples.size
            }
            if (logThisCallback) callbacksLogged++
            val hasPacket = latestPacketSamples > 0 && firstVolts.isFinite()
            ingress = ingress.copy(
                callbackCount = callbackCount,
                latestPackNum = latestPack,
                previousPackNum = previousPack,
                packNumChanging = packNumChangedThisSecond,
                latestCallbackSamples = callbackSamples,
                latestPacketSamples = latestPacketSamples,
                firstVolts = if (hasPacket) firstVolts else ingress.firstVolts,
                lastVolts = if (hasPacket) lastVolts else ingress.lastVolts,
                minVolts = if (hasPacket) minVolts else ingress.minVolts,
                maxVolts = if (hasPacket) maxVolts else ingress.maxVolts,
                droppedChunks = droppedChunks,
            )
            if (merged.isNotEmpty()) {
                val emitted = _samplesMicrovolts.tryEmit(
                    MicrovoltChunk(signalSession, merged.toDoubleArray()),
                )
                if (!emitted) {
                    droppedChunks++
                    ingress = ingress.copy(droppedChunks = droppedChunks)
                    Log.e(TAG, "signal chunk dropped before the EEG pipeline; droppedChunks=$droppedChunks")
                }
            }
            samplesSinceLog += callbackSamples
            val now = SystemClock.elapsedRealtime()
            val secondElapsed = now - lastLogAtMs >= 1_000L
            if (secondElapsed) {
                ingress = ingress.copy(
                    callbacksPerSecond = callbacksThisSecond,
                    packNumChanging = packNumChangedThisSecond,
                    distinctValuesLastSecond = distinctThisSecond.size,
                    samplesPerSecond = samplesSinceLog,
                    droppedChunks = droppedChunks,
                )
                Log.i(
                    TAG,
                    "signal ingress callbacks=$callbackCount callbacksPerSec=$callbacksThisSecond " +
                        "packNum=${ingress.latestPackNum} previous=${ingress.previousPackNum} " +
                        "packNumChanging=$packNumChangedThisSecond " +
                        "callbackSamples=$callbackSamples packetSamples=$latestPacketSamples " +
                        "first=${formatVolts(ingress.firstVolts)} last=${formatVolts(ingress.lastVolts)} " +
                        "min=${formatVolts(ingress.minVolts)} max=${formatVolts(ingress.maxVolts)} " +
                        "distinct=${distinctThisSecond.size} samplesPerSec=$samplesSinceLog " +
                        "droppedChunks=$droppedChunks windowMs=${now - lastLogAtMs}",
                )
                callbacksThisSecond = 0
                samplesSinceLog = 0
                distinctThisSecond.clear()
                packNumChangedThisSecond = false
                lastLogAtMs = now
            }
            if (secondElapsed || logThisCallback || now - lastDiagUiMs >= 200L) {
                lastDiagUiMs = now
                val published = ingress
                _state.update { it.copy(signalIngress = published) }
            }
        } catch (error: Exception) {
            Log.e(TAG, "signal callback failed", error)
        }
    }

    private fun logPacket(index: Int, packNum: Int, samples: DoubleArray) {
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        for (volts in samples) {
            if (volts < min) min = volts
            if (volts > max) max = volts
        }
        val preview = if (samples.isEmpty()) {
            "[]"
        } else {
            samples.joinToString(limit = 32, prefix = "[", postfix = "]") { volts ->
                String.format(Locale.US, "%.8e", volts)
            }
        }
        val first = samples.firstOrNull()
        val last = samples.lastOrNull()
        Log.i(
            TAG,
            "  packet[$index] packNum=$packNum n=${samples.size} " +
                "first=${formatVolts(first)} last=${formatVolts(last)} " +
                "min=${if (samples.isEmpty()) "none" else formatVolts(min)} " +
                "max=${if (samples.isEmpty()) "none" else formatVolts(max)} values=$preview",
        )
    }

    private fun resetIngressCounters() {
        callbackCount = 0
        callbacksThisSecond = 0
        samplesSinceLog = 0
        distinctThisSecond.clear()
        lastSeenPackNum = null
        packNumChangedThisSecond = false
        callbacksLogged = 0
        droppedChunks = 0
        lastDiagUiMs = 0
        ingress = SignalIngress()
        _state.update { it.copy(signalIngress = ingress) }
    }

    private fun formatVolts(volts: Double?): String =
        if (volts == null || !volts.isFinite()) "none" else String.format(Locale.US, "%.8e", volts)

    private fun publishElectrode(electrode: CallibriElectrodeState?) {
        val mapped = when (electrode) {
            CallibriElectrodeState.Normal -> ElectrodeContact.Normal
            CallibriElectrodeState.HighResistance -> ElectrodeContact.HighResistance
            CallibriElectrodeState.Detached -> ElectrodeContact.Detached
            else -> return
        }
        if (mapped != lastLoggedElectrode) {
            lastLoggedElectrode = mapped
            Log.i(TAG, "electrode state: CallibriElectrodeState.${electrode.name}")
        }
        _state.update { it.copy(electrode = mapped) }
    }

    private fun readElectrode(callibri: Callibri): CallibriElectrodeState? =
        try {
            callibri.electrodeState
        } catch (error: Exception) {
            Log.w(TAG, "electrode state read failed", error)
            null
        }

    private fun readBattery(callibri: Callibri): Int? =
        try {
            callibri.battPower
        } catch (error: Exception) {
            Log.w(TAG, "battery read failed", error)
            null
        }

    private fun releaseSensorLocked(message: String?, isError: Boolean) {
        val current = sensor ?: run {
            if (message != null) {
                _state.update {
                    it.copy(
                        phase = SessionPhase.Disconnected,
                        streaming = false,
                        message = message,
                        messageIsError = isError,
                    )
                }
            }
            return
        }
        closingSensor = true
        sensor = null
        acceptingSamples = false
        val wasStreaming = streaming
        streaming = false
        lastLoggedElectrode = null
        val name = try {
            current.name
        } catch (_: Exception) {
            null
        }
        try {
            current.callibriSignalDataReceived = null
            current.callibriElectrodeStateChanged = null
            current.sensorStateChanged = null
            current.batteryChanged = null
        } catch (error: Exception) {
            Log.e(TAG, "clearing sensor callbacks failed", error)
        }
        if (wasStreaming) {
            try {
                current.execCommand(SensorCommand.StopSignal)
                Log.i(TAG, "EEG stop")
            } catch (error: Exception) {
                Log.e(TAG, "EEG stop failed", error)
            }
        }
        try {
            current.disconnect()
        } catch (error: Exception) {
            Log.e(TAG, "disconnect failed", error)
        }
        try {
            current.close()
        } catch (error: Exception) {
            Log.e(TAG, "sensor close failed", error)
        } finally {
            closingSensor = false
        }
        Log.i(TAG, "disconnect: ${name?.takeIf { it.isNotBlank() } ?: "unknown"}")
        resetIngressCounters()
        _state.update {
            it.copy(
                phase = SessionPhase.Disconnected,
                deviceName = null,
                deviceAddress = null,
                batteryPercent = null,
                electrode = null,
                extSwInput = null,
                adcInput = null,
                signalIngress = SignalIngress(),
                streaming = false,
                message = message,
                messageIsError = message != null && isError,
            )
        }
    }

    private fun closeScannerLocked() {
        val current = scanner ?: return
        scanner = null
        scanning = false
        scanGeneration++
        try {
            current.sensorsChanged = null
        } catch (error: Exception) {
            Log.e(TAG, "clearing scanner callback failed", error)
        }
        try {
            current.stop()
        } catch (_: Exception) {
            // Already stopped, or the native scanner was not started.
        }
        try {
            current.close()
        } catch (error: Exception) {
            Log.e(TAG, "scanner close failed", error)
        }
    }

    private fun describedRate(callibri: Callibri): Int = callibri.samplingFrequency.toHz()

    private fun CallibriState.withRadio(): CallibriState {
        val locationRequired = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        return copy(
            bluetoothEnabled = bluetoothAdapter()?.isEnabled == true,
            locationServicesRequired = locationRequired,
            locationServicesEnabled = !locationRequired || locationEnabled(),
        )
    }

    private fun bluetoothAdapter() =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun locationEnabled(): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ||
                runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        }
    }

    private fun SensorSamplingFrequency.toHz(): Int = when (this) {
        SensorSamplingFrequency.FrequencyHz10 -> 10
        SensorSamplingFrequency.FrequencyHz20 -> 20
        SensorSamplingFrequency.FrequencyHz100 -> 100
        SensorSamplingFrequency.FrequencyHz125 -> 125
        SensorSamplingFrequency.FrequencyHz250 -> 250
        SensorSamplingFrequency.FrequencyHz500 -> 500
        SensorSamplingFrequency.FrequencyHz1000 -> 1_000
        SensorSamplingFrequency.FrequencyHz2000 -> 2_000
        SensorSamplingFrequency.FrequencyHz4000 -> 4_000
        SensorSamplingFrequency.FrequencyHz8000 -> 8_000
        SensorSamplingFrequency.FrequencyUnsupported -> 0
    }

    private companion object {
        const val TAG = "CallibriNFB"
        const val VOLTS_TO_MICROVOLTS = 1_000_000.0
        const val LOGGED_CALLBACKS = 8
    }
}
