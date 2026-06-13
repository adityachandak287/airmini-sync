package com.airmini.sync

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.airmini.sync.ui.MainScreen

/**
 * Single Activity — entry point for the app.
 *
 * Responsibilities:
 *  - Request BLUETOOTH_CONNECT and BLUETOOTH_SCAN permissions at runtime.
 *  - Prompt the user to enable Bluetooth if it is off.
 *  - Host the Compose content tree.
 *
 * All state and business logic lives in BluetoothViewModel.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: BluetoothViewModel by viewModels()

    // ── Permission request ────────────────────────────────────────────────────

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Whether the user enabled Bluetooth or not, attempt to load bonded devices.
        viewModel.loadBondedDevices()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            ensureBluetoothEnabled()
        }
        // If permissions were denied the UI will show an empty device list with a
        // clear message. We do not re-request here to avoid permission-request loops.
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            )
        )

        setContent {
            val state by viewModel.state.collectAsState()
            MaterialTheme {
                MainScreen(
                    state = state,
                    onDeviceSelected = viewModel::selectDevice,
                    onSyncClicked = viewModel::startSync,
                    onShareClicked = {
                        val resultJson = state.result?.toString(2)
                        if (resultJson != null) {
                            shareSleepData(resultJson)
                        }
                    }
                )
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun shareSleepData(jsonData: String) {
        runCatching {
            val cacheFile = java.io.File(cacheDir, "sleep_data.json")
            cacheFile.writeText(jsonData)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "com.airmini.sync.fileprovider",
                cacheFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Sleep Data"))
        }.onFailure { e ->
            android.widget.Toast.makeText(
                this,
                "Error sharing file: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    @Suppress("MissingPermission") // Guarded by permissionLauncher above.
    private fun ensureBluetoothEnabled() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter != null && !adapter.isEnabled) {
            bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            viewModel.loadBondedDevices()
        }
    }
}
