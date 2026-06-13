package com.airmini.sync.ui

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.airmini.sync.SyncStatus
import com.airmini.sync.UiState
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.TextButton

/**
 * Main screen composable.
 *
 * Contains: device picker, PIN input, sync button, status/log output, and result summary.
 * No protocol logic lives here — everything is driven through callbacks into the ViewModel.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Suppress("MissingPermission") // Permission checked in MainActivity before this is shown.
@Composable
fun MainScreen(
    state: UiState,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onSyncClicked: (pin: String) -> Unit,
    onShareClicked: () -> Unit,
    onDateRangePresetSelected: (String) -> Unit,
    onCustomDateRangeSelected: (startMillis: Long?, endMillis: Long?) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    val logScrollState = rememberScrollState()
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var showCustomPicker by remember { mutableStateOf(false) }

    // Auto-scroll log to bottom when new lines arrive.
    LaunchedEffect(state.logs.size) {
        logScrollState.animateScrollTo(logScrollState.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {

        // ── Header ───────────────────────────────────────────────────────────
        Text(text = "AirMini Sync", style = MaterialTheme.typography.headlineMedium)

        // ── Device picker ────────────────────────────────────────────────────
        if (state.bondedDevices.isEmpty()) {
            Text(
                text = "No paired AirMini or ResMed device found.\nPair your CPAP machine in Android Bluetooth settings first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(text = "Select device:", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.bondedDevices.forEach { device ->
                    FilterChip(
                        selected = device == state.selectedDevice,
                        onClick = { onDeviceSelected(device) },
                        label = { Text(device.name ?: device.address) },
                    )
                }
            }
        }

        // ── Period Selection ──────────────────────────────────────────────────
        Text(text = "Report Period:", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val presets = listOf("Last Week", "Last Month", "Last Year", "Custom")
            presets.forEach { preset ->
                FilterChip(
                    selected = state.dateRangePreset == preset,
                    onClick = {
                        onDateRangePresetSelected(preset)
                        if (preset == "Custom") {
                            showCustomPicker = true
                        }
                    },
                    label = { Text(preset) }
                )
            }
        }

        if (state.dateRangePreset == "Custom" && state.customStartDateMillis != null && state.customEndDateMillis != null) {
            val startStr = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.ofEpochMilli(state.customStartDateMillis))
            val endStr = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.ofEpochMilli(state.customEndDateMillis))
            Text(
                text = "Selected Range: $startStr to $endStr",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (showCustomPicker) {
            val dateRangePickerState = rememberDateRangePickerState()
            DatePickerDialog(
                onDismissRequest = { showCustomPicker = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showCustomPicker = false
                            onCustomDateRangeSelected(
                                dateRangePickerState.selectedStartDateMillis,
                                dateRangePickerState.selectedEndDateMillis
                            )
                        },
                        enabled = dateRangePickerState.selectedStartDateMillis != null && dateRangePickerState.selectedEndDateMillis != null
                    ) {
                        Text("OK")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCustomPicker = false }) {
                        Text("Cancel")
                    }
                }
            ) {
                DateRangePicker(
                    state = dateRangePickerState,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ── PIN input ────────────────────────────────────────────────────────
        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) pin = it },
            label = { Text("4-digit PIN") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )

        // ── Sync button ──────────────────────────────────────────────────────
        Button(
            onClick = {
                keyboardController?.hide()
                onSyncClicked(pin)
            },
            enabled = state.selectedDevice != null &&
                      pin.length == 4 &&
                      state.status != SyncStatus.Connecting &&
                      state.status != SyncStatus.Syncing &&
                      (state.dateRangePreset != "Custom" || (state.customStartDateMillis != null && state.customEndDateMillis != null)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = when (state.status) {
                    SyncStatus.Idle    -> "Sync"
                    SyncStatus.Connecting -> "Connecting…"
                    SyncStatus.Syncing -> "Downloading…"
                    SyncStatus.Done    -> "Sync again"
                    SyncStatus.Error   -> "Retry"
                }
            )
        }

        // ── Log output ───────────────────────────────────────────────────────
        if (state.logs.isNotEmpty()) {
            val isDark = androidx.compose.foundation.isSystemInDarkTheme()
            Surface(
                color = if (isDark) androidx.compose.ui.graphics.Color(0xFF121212) else androidx.compose.ui.graphics.Color(0xFFF5F5F5),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
            ) {
                Column(
                    modifier = Modifier
                        .padding(12.dp)
                        .verticalScroll(logScrollState),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    state.logs.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = if (isDark) androidx.compose.ui.graphics.Color(0xFFE0E0E0) else androidx.compose.ui.graphics.Color(0xFF212121)
                        )
                    }
                }
            }
        }

        // ── Result summary ───────────────────────────────────────────────────
        if (state.status == SyncStatus.Done && state.result != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    text = "✓ Sync complete",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Button(onClick = onShareClicked) {
                    Text("Share Data")
                }
            }

            state.stats?.let { stats ->
                androidx.compose.material3.Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Therapy Summary",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        androidx.compose.material3.HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Usage Duration", style = MaterialTheme.typography.bodyMedium)
                            Text(stats.totalUsageDurationFormatted, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Mask Sessions", style = MaterialTheme.typography.bodyMedium)
                            Text("${stats.maskSessionsCount}", style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Timeline Range", style = MaterialTheme.typography.bodyMedium)
                            Text("${stats.firstDate.split(" ")[0]} to ${stats.lastDate.split(" ")[0]}", style = MaterialTheme.typography.bodySmall)
                        }

                        if (stats.respiratoryEventsCount > 0) {
                            androidx.compose.material3.HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                            Text(
                                text = "Respiratory Events: ${stats.respiratoryEventsCount}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            )
                            stats.eventCountsBreakdown.forEach { (event, count) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("  • $event", style = MaterialTheme.typography.bodySmall)
                                    Text("$count", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (stats.avgApneaDurationSeconds > 0) {
                                Text(
                                    text = String.format(java.util.Locale.US, "Avg Apnea Duration: %.1fs", stats.avgApneaDurationSeconds),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }
                        }

                        stats.pressureStats?.let { p ->
                            androidx.compose.material3.HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text("Inspiratory Pressure (cmH2O)", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                Text(
                                    text = String.format(java.util.Locale.US, "  Min: %.1f | Avg: %.1f | Med: %.1f | p95: %.1f | Max: %.1f", p.min, p.avg, p.median, p.p95, p.max),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                            }
                        }

                        stats.leakStats?.let { l ->
                            androidx.compose.material3.HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text("Leak Rate (L/min)", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                Text(
                                    text = String.format(java.util.Locale.US, "  Min: %.1f | Avg: %.1f | Med: %.1f | p95: %.1f | Max: %.1f", l.min, l.avg, l.median, l.p95, l.max),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.status == SyncStatus.Error && state.error != null) {
            Text(
                text = "✗ ${state.error}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Pushes content above to the top and dynamically places the version string at the absolute bottom
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "AirMini Sync ${com.airmini.sync.BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally)
        )
    }
}
