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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.callibri.nfb.callibri.CallibriPermissions
import com.callibri.nfb.callibri.ElectrodeContact
import com.callibri.nfb.callibri.SessionPhase
import java.util.Locale

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
                        onDisconnect = viewModel::disconnect,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLine(ui: MainUiState) {
    val form = ui.formError
    val link = ui.linkMessage
    val text = form ?: link
    if (text.isNullOrBlank()) return
    val isError = form != null || ui.linkMessageIsError
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
    onDisconnect: () -> Unit,
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
    Text("FRE1", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(
        "Edit the band edges, then apply them to the live calculator. Defaults are temporary.",
        style = MaterialTheme.typography.bodySmall,
    )
    ui.bands.forEachIndexed { index, band ->
        BandCard(
            band = band,
            onLowChange = { onLowChange(band.id, it) },
            onHighChange = { onHighChange(band.id, it) },
            onDone = if (index == ui.bands.lastIndex) onApply else null,
        )
    }
    Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
        Text("Apply bands")
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Raw EEG", fontWeight = FontWeight.Medium)
            Text(
                ui.latestRawUv?.let { "Latest sample: ${formatMicrovolts(it)}" } ?: "Latest sample: —",
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
private fun BandCard(
    band: BandUi,
    onLowChange: (String) -> Unit,
    onHighChange: (String) -> Unit,
    onDone: (() -> Unit)?,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(band.label.uppercase(Locale.US), fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
            Text(
                "Applied ${formatHz(band.appliedLowHz)}–${formatHz(band.appliedHighHz)} Hz",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Amplitude: ${band.amplitudeUv?.let(::formatMicrovolts) ?: "—"}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
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
                        imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { onDone?.invoke() },
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

private fun formatMicrovolts(value: Double): String =
    String.format(Locale.US, "%.2f µV", value)
