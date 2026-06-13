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

/**
 * Main screen composable.
 *
 * Contains: device picker, PIN input, sync button, status/log output, and result summary.
 * No protocol logic lives here — everything is driven through callbacks into the ViewModel.
 */
@Suppress("MissingPermission") // Permission checked in MainActivity before this is shown.
@Composable
fun MainScreen(
    state: UiState,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onSyncClicked: (pin: String) -> Unit,
    onShareClicked: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    val logScrollState = rememberScrollState()

    // Auto-scroll log to bottom when new lines arrive.
    LaunchedEffect(state.logs.size) {
        logScrollState.animateScrollTo(logScrollState.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
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
            onClick = { onSyncClicked(pin) },
            enabled = state.selectedDevice != null &&
                      pin.length == 4 &&
                      state.status == SyncStatus.Idle,
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
