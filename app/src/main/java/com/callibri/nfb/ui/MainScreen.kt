package com.callibri.nfb.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.callibri.nfb.protocol.BandGoal
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.callibri.nfb.callibri.CallibriPermissions
import com.callibri.nfb.callibri.ElectrodeContact
import com.callibri.nfb.callibri.SessionPhase
import com.callibri.nfb.callibri.SignalIngress
import com.callibri.nfb.protocol.FRE1Protocol
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        viewModel.onPermissionResult(result.values.all { it })
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.refreshEnvironment()
                Lifecycle.Event.ON_STOP -> viewModel.onScreenDisposed()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "CALLIBRI NFB",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                )
                StatusLine(ui)
                if (ui.phase == SessionPhase.Connecting) {
                    Text("Connecting… Keep the sensor awake and nearby.")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else if (ui.phase != SessionPhase.Connected) {
                    DisconnectedSection(
                        ui = ui,
                        onScan = {
                            if (ui.permissionsGranted) {
                                viewModel.scan()
                            } else {
                                permissionLauncher.launch(CallibriPermissions.runtimePermissions())
                            }
                        },
                        onStopScan = viewModel::stopScan,
                        onConnect = viewModel::connect,
                        onBluetoothSettings = {
                            context.startActivity(
                                Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        onLocationSettings = {
                            context.startActivity(
                                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        onAppSettings = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        },
                    )
                } else {
                    ConnectedSection(
                        ui = ui,
                        onLowChange = viewModel::updateLow,
                        onHighChange = viewModel::updateHigh,
                        onApply = viewModel::applyBands,
                        onToggleEeg = { if (ui.streaming) viewModel.stopEeg() else viewModel.startEeg() },
                        onSelectAdc = viewModel::selectAdcInput,
                        onDisconnect = viewModel::disconnect,
                        onAutoChange = viewModel::setAutoThreshold,
                        onTargetChange = viewModel::updateTarget,
                        onWeightChange = viewModel::updateWeight,
                        onManualChange = viewModel::updateManual,
                        onWindowChange = viewModel::updateWindow,
                        onSmoothingChange = viewModel::updateSmoothing,
                        onMinRewardChange = viewModel::updateMinReward,
                        onApplyFeedback = viewModel::applyFeedbackSettings,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLine(ui: MainUiState) {
    val form = ui.formError
    val feedback = ui.feedbackError
    val link = ui.linkMessage
    val text = form ?: feedback ?: link
    if (text.isNullOrBlank()) return
    val isError = form != null || feedback != null || ui.linkMessageIsError
    Text(
        text = text,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun DisconnectedSection(
    ui: MainUiState,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (String) -> Unit,
    onBluetoothSettings: () -> Unit,
    onLocationSettings: () -> Unit,
    onAppSettings: () -> Unit,
) {
    if (ui.phase == SessionPhase.Scanning) {
        Button(onClick = onStopScan, modifier = Modifier.fillMaxWidth()) {
            Text("Stop scan")
        }
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
            Text("Scan for Callibri")
        }
    }
    if (!ui.bluetoothEnabled) {
        OutlinedButton(onClick = onBluetoothSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Open Bluetooth settings")
        }
    }
    if (ui.locationServicesRequired && !ui.locationServicesEnabled) {
        OutlinedButton(onClick = onLocationSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Open location settings")
        }
    }
    if (!ui.permissionsGranted) {
        Text(
            "Scanning needs Bluetooth permission. On Android 11 and earlier, also allow location.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = onAppSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Open app settings")
        }
    }
    Text("Found devices:", fontWeight = FontWeight.Medium)
    if (ui.devices.isEmpty()) {
        Text(
            if (ui.phase == SessionPhase.Scanning) {
                "Searching for Callibri sensors…"
            } else {
                "No Callibri devices yet. Turn the sensor on, then scan."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        ui.devices.forEach { device ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(device.name, fontWeight = FontWeight.SemiBold)
                    Text(device.address, style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = { onConnect(device.address) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Connect")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectedSection(
    ui: MainUiState,
    onLowChange: (String, String) -> Unit,
    onHighChange: (String, String) -> Unit,
    onApply: () -> Unit,
    onToggleEeg: () -> Unit,
    onSelectAdc: (String) -> Unit,
    onDisconnect: () -> Unit,
    onAutoChange: (Boolean) -> Unit,
    onTargetChange: (String, String) -> Unit,
    onWeightChange: (String, String) -> Unit,
    onManualChange: (String, String) -> Unit,
    onWindowChange: (String) -> Unit,
    onSmoothingChange: (String) -> Unit,
    onMinRewardChange: (String) -> Unit,
    onApplyFeedback: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Device: ${ui.deviceName ?: "Callibri"}")
            Text("Status: Connected")
            Text("Battery: ${ui.batteryPercent?.let { "$it%" } ?: "—"}")
            ElectrodeRow(ui.electrode)
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (ui.streaming) "EEG: Streaming" else "EEG: Stopped")
            Text("Sample rate: ${ui.sampleRateHz} Hz")
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Signal source", fontWeight = FontWeight.Medium)
            SignalSourceLines(ui)
            AdcInputChoices(selected = ui.adcInput, onSelect = onSelectAdc)
            PacketDiagnostics(ui.signalIngress)
        }
    }
    Text("FRE1", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    RewardCard(ui)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(if (ui.autoThreshold) "Auto threshold: ON" else "Auto threshold: OFF")
        Switch(checked = ui.autoThreshold, onCheckedChange = onAutoChange)
    }
    Text(
        "Edit the band edges, then apply them. Thresholds, weights, and smoothing apply separately.",
        style = MaterialTheme.typography.bodySmall,
    )
    ui.bands.forEachIndexed { index, band ->
        BandCard(
            band = band,
            autoThreshold = ui.autoThreshold,
            onLowChange = { onLowChange(band.id, it) },
            onHighChange = { onHighChange(band.id, it) },
            onTargetChange = { onTargetChange(band.id, it) },
            onWeightChange = { onWeightChange(band.id, it) },
            onManualChange = { onManualChange(band.id, it) },
            onDone = if (index == ui.bands.lastIndex) onApply else null,
        )
    }
    Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
        Text("Apply bands")
    }
    FeedbackSettingsCard(
        ui = ui,
        onWindowChange = onWindowChange,
        onSmoothingChange = onSmoothingChange,
        onMinRewardChange = onMinRewardChange,
        onApply = onApplyFeedback,
    )
    SessionDiagnostics(ui)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Raw EEG", fontWeight = FontWeight.Medium)
            Text(
                ui.latestRawUv?.let { "Latest sample: ${formatVolts(it)} (${formatMicrovolts(it)})" } ?: "Latest sample: —",
            )
            Text(
                "Filtered trace (about 1–45 Hz, 60 Hz notch). It should move when the signal changes.",
                style = MaterialTheme.typography.bodySmall,
            )
            SignalTrace(ui.waveform)
        }
    }
    Button(onClick = onToggleEeg, modifier = Modifier.fillMaxWidth()) {
        Text(if (ui.streaming) "Stop EEG" else "Start EEG")
    }
    OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
        Text("Disconnect")
    }
}

@Composable
private fun RewardCard(ui: MainUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "NEUROFEEDBACK REWARD",
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )
            Text(ui.rewardStatus, style = MaterialTheme.typography.bodyMedium)
            Text(
                formatPercent(ui.rewardSmoothed, decimals = 1),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
            )
            val fraction = rewardFraction(ui.rewardSmoothed, ui.appliedMinReward)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatPercent(ui.appliedMinReward, decimals = 0))
                Text("100%")
            }
            Text("Raw reward: ${formatPercent(ui.rewardRaw, decimals = 1)}")
            Text("Smoothed reward: ${formatPercent(ui.rewardSmoothed, decimals = 1)}")
        }
    }
}

@Composable
private fun FeedbackSettingsCard(
    ui: MainUiState,
    onWindowChange: (String) -> Unit,
    onSmoothingChange: (String) -> Unit,
    onMinRewardChange: (String) -> Unit,
    onApply: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Feedback settings", fontWeight = FontWeight.Medium)
            Text(
                "Window 5–120 s. Target success 50–95% on each band. Minimum reward 0–50%. Smoothing 100–2000 ms. Weights are renormalized.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = ui.windowText,
                onValueChange = onWindowChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Rolling window seconds") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = ui.minRewardText,
                onValueChange = onMinRewardChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Minimum reward %") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = ui.smoothingText,
                onValueChange = onSmoothingChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Smoothing response ms") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onApply() }),
            )
            Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
                Text("Apply feedback settings")
            }
        }
    }
}

@Composable
private fun SessionDiagnostics(ui: MainUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Session diagnostics", fontWeight = FontWeight.Medium)
            SignalSourceLines(ui)
            Text("Elapsed EEG time: ${formatElapsed(ui.elapsedMillis)}")
            Text("Valid observations: ${ui.validObservations}")
            Text("Rejected observations: ${ui.rejectedObservations}")
        }
    }
}

@Composable
private fun BandCard(
    band: BandUi,
    autoThreshold: Boolean,
    onLowChange: (String) -> Unit,
    onHighChange: (String) -> Unit,
    onTargetChange: (String) -> Unit,
    onWeightChange: (String) -> Unit,
    onManualChange: (String) -> Unit,
    onDone: (() -> Unit)?,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(band.label.uppercase(Locale.US), fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
            Text(
                goalLine(band.goal),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Applied ${formatHz(band.appliedLowHz)}–${formatHz(band.appliedHighHz)} Hz",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Current: ${band.amplitudeUv?.let(::formatMicrovolts) ?: "—"}")
            if (band.amplitudeUv != null && !band.latestAccepted) {
                Text(
                    "Latest reading was rejected. Scoring holds the previous valid amplitude.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                if (autoThreshold) {
                    "Auto threshold: ${band.thresholdUv?.let(::formatMicrovolts) ?: "—"}"
                } else {
                    "Manual threshold: ${band.thresholdUv?.let(::formatMicrovolts) ?: "—"}"
                },
            )
            Text("Target success: ${percentWhole(band.targetSuccess)}")
            Text("Current normalized score: ${band.score?.let(::percentWhole) ?: "—"}")
            Text("Window success: ${band.windowSuccess?.let(::percentWhole) ?: "—"}")
            Text(
                "In window: ${band.validInWindow}",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = band.lowText,
                    onValueChange = onLowChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("Low Hz") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                )
                OutlinedTextField(
                    value = band.highText,
                    onValueChange = onHighChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("High Hz") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = band.targetText,
                    onValueChange = onTargetChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("Target %") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                )
                OutlinedTextField(
                    value = band.weightText,
                    onValueChange = onWeightChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("Weight %") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                )
            }
            if (!autoThreshold) {
                OutlinedTextField(
                    value = band.manualText,
                    onValueChange = onManualChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Manual threshold µV") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ElectrodeRow(electrode: ElectrodeContact?) {
    val (label, color) = when (electrode) {
        ElectrodeContact.Normal -> "Contact good" to Color(0xFF1B7F4E)
        ElectrodeContact.HighResistance -> "High resistance" to Color(0xFFB86E00)
        ElectrodeContact.Detached -> "Detached" to Color(0xFFB42318)
        null -> "Waiting for electrode state" to Color(0xFF6B7280)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(Modifier.size(14.dp)) {
            drawCircle(color = color)
        }
        Text("Electrode: $label")
    }
}

@Composable
private fun SignalTrace(samples: List<Float>) {
    val line = MaterialTheme.colorScheme.primary
    if (samples.size < 2) {
        Text("Waiting for samples…", style = MaterialTheme.typography.bodySmall)
        return
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp),
    ) {
        var min = samples[0]
        var max = samples[0]
        for (sample in samples) {
            if (sample < min) min = sample
            if (sample > max) max = sample
        }
        val span = (max - min).coerceAtLeast(1f)
        val step = size.width / (samples.size - 1).coerceAtLeast(1)
        for (index in 1 until samples.size) {
            val x0 = (index - 1) * step
            val x1 = index * step
            val y0 = size.height - ((samples[index - 1] - min) / span) * size.height
            val y1 = size.height - ((samples[index] - min) / span) * size.height
            drawLine(
                color = line,
                start = Offset(x0, y0),
                end = Offset(x1, y1),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun AdcInputChoices(selected: String?, onSelect: (String) -> Unit) {
    Text(
        "ADC input. Resistance pinned this sensor at full scale. Electrodes is the physiological input. Short should sit near 0 V. Test is a 1 Hz square wave, about ±1 mV.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        AdcChoice("Electrodes", selected, onSelect, Modifier.weight(1f))
        AdcChoice("Short", selected, onSelect, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        AdcChoice("Test", selected, onSelect, Modifier.weight(1f))
        AdcChoice("Resistance", selected, onSelect, Modifier.weight(1f))
    }
}

@Composable
private fun AdcChoice(
    label: String,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = selected == "ADCInput$label"
    if (active) {
        Button(onClick = { onSelect(label) }, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = { onSelect(label) }, modifier = modifier) { Text(label) }
    }
}

@Composable
private fun SignalSourceLines(ui: MainUiState) {
    Text("ExtSwInput: ${ui.extSwInput ?: "—"}")
    Text("ADCInput: ${ui.adcInput ?: "—"}")
    Text("Gain: ${ui.gain ?: "—"}")
    Text("Offset: ${ui.dataOffset ?: "—"}")
    Text("Hardware HPF: ${ui.hardwareFilter ?: "—"}")
    Text("Electrode: ${electrodeDiagnostic(ui.electrode)}")
    Text("Incoming raw: ${ui.latestRawUv?.let(::formatVolts) ?: "—"}")
    Text("Converted: ${ui.latestRawUv?.let(::formatMicrovolts) ?: "—"}")
    val span = rawSpanMicrovolts(ui.signalIngress)
    Text("Raw span last second: ${span?.let(::formatMicrovolts) ?: "—"}")
    ui.signalIngress.railNote?.let { note ->
        Text(note, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun PacketDiagnostics(ingress: SignalIngress) {
    Text("Signal callbacks: ${ingress.callbackCount}")
    Text("Callbacks per second: ${ingress.callbacksPerSecond} (last second)")
    Text("Latest PackNum: ${ingress.latestPackNum?.toString() ?: "—"}")
    Text(
        "PackNum changing: ${if (ingress.callbackCount == 0L) "—" else if (ingress.packNumChanging) "yes" else "no"}" +
            (ingress.previousPackNum?.let { " (previous $it)" } ?: ""),
    )
    Text("Samples in latest callback: ${ingress.latestCallbackSamples}")
    Text("Samples in latest packet: ${ingress.latestPacketSamples}")
    Text("First sample: ${ingress.firstVolts?.let(::formatSdkVolts) ?: "—"}")
    Text("Last sample: ${ingress.lastVolts?.let(::formatSdkVolts) ?: "—"}")
    Text("Minimum sample: ${ingress.minVolts?.let(::formatSdkVolts) ?: "—"}")
    Text("Maximum sample: ${ingress.maxVolts?.let(::formatSdkVolts) ?: "—"}")
    Text("Distinct raw values last second: ${ingress.distinctValuesLastSecond}")
    Text("Samples per second: ${ingress.samplesPerSecond} (last second, expected ${FRE1Protocol.SAMPLE_RATE_HZ})")
    if (ingress.droppedChunks > 0) {
        Text(
            "Chunks dropped before the EEG pipeline: ${ingress.droppedChunks}",
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun electrodeDiagnostic(electrode: ElectrodeContact?): String = when (electrode) {
    ElectrodeContact.Normal -> "Normal (contact good)"
    ElectrodeContact.HighResistance -> "HighResistance"
    ElectrodeContact.Detached -> "Detached"
    null -> "—"
}

private fun rawSpanMicrovolts(ingress: SignalIngress): Double? {
    val min = ingress.minVoltsLastSecond ?: return null
    val max = ingress.maxVoltsLastSecond ?: return null
    if (!min.isFinite() || !max.isFinite()) return null
    return (max - min) * 1_000_000.0
}

private fun formatMicrovolts(value: Double): String =
    String.format(Locale.US, "%.2f µV", value)

private fun formatVolts(microvolts: Double): String =
    String.format(Locale.US, "%.4e V", microvolts / 1_000_000.0)

private fun formatSdkVolts(volts: Double): String =
    String.format(Locale.US, "%.8e V", volts)

private fun formatPercent(value: Double, decimals: Int): String =
    String.format(Locale.US, "%." + decimals + "f%%", value)

private fun percentWhole(fraction: Double): String =
    "${(fraction * 100.0).roundToInt()}%"

private fun formatElapsed(millis: Long): String =
    String.format(Locale.US, "%.1f s", millis.coerceAtLeast(0L) / 1000.0)

private fun rewardFraction(smoothed: Double, minPercent: Double): Float {
    val span = (100.0 - minPercent).coerceAtLeast(1e-6)
    return ((smoothed - minPercent) / span).coerceIn(0.0, 1.0).toFloat()
}

private fun goalLine(goal: BandGoal): String = when (goal) {
    BandGoal.InhibitBelow -> "Goal: amplitude lower than threshold"
    BandGoal.RewardAbove -> "Goal: amplitude higher than threshold"
}
