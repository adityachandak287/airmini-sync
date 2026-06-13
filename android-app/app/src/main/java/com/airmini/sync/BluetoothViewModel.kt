package com.airmini.sync

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class UiState(
    val status: SyncStatus = SyncStatus.Idle,
    val bondedDevices: List<BluetoothDevice> = emptyList(),
    val selectedDevice: BluetoothDevice? = null,
    val logs: List<String> = emptyList(),
    val result: JSONObject? = null,
    val stats: TherapyStats? = null,
    val error: String? = null,
)

enum class SyncStatus { Idle, Connecting, Syncing, Done, Error }

/**
 * ViewModel for the main screen.
 *
 * Exposes UI state as a StateFlow and orchestrates the connect → session → download
 * workflow using coroutines. Uses AndroidViewModel to access application context
 * (for BluetoothManager) without leaking an Activity reference.
 */
class BluetoothViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    @SuppressLint("MissingPermission")
    fun loadBondedDevices() {
        val manager = getApplication<Application>().getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter ?: return
        val devices = adapter.bondedDevices
            .filter { dev ->
                val name = dev.name ?: ""
                name.contains("AirMini", ignoreCase = true) ||
                name.contains("ResMed", ignoreCase = true)
            }
            .sortedBy { it.name ?: it.address }
        _state.update { it.copy(bondedDevices = devices) }
    }

    fun selectDevice(device: BluetoothDevice) {
        _state.update { it.copy(selectedDevice = device) }
    }

    @SuppressLint("MissingPermission")
    fun startSync(pin: String) {
        val device = _state.value.selectedDevice ?: return
        _state.update { it.copy(status = SyncStatus.Connecting, logs = emptyList(), error = null, result = null) }

        viewModelScope.launch {
            runCatching {
                val manager = getApplication<Application>().getSystemService(BluetoothManager::class.java)
                val adapter = checkNotNull(manager?.adapter) { "Bluetooth adapter not available" }

                val crypto = AirMiniCrypto()
                val transport = BluetoothTransport(adapter, device)
                val client = AirMiniClient(transport, crypto)

                log("Connecting to ${device.name ?: device.address}…")
                client.connect()

                log("Establishing session…")
                _state.update { it.copy(status = SyncStatus.Syncing) }
                client.establishSession(pin)

                log("Downloading telemetry data…")
                val result = client.downloadData()
                client.disconnect()

                val recordCount = DATA_IDS.sumOf {
                    result.optJSONArray(it)?.length() ?: 0
                }
                log("Done — $recordCount records across ${DATA_IDS.size} streams.")
                val stats = TherapyStatsCalculator.computeStats(result)
                _state.update { it.copy(status = SyncStatus.Done, result = result, stats = stats) }
            }.onFailure { e ->
                val msg = e.message ?: e.javaClass.simpleName
                log("Error: $msg")
                _state.update { it.copy(status = SyncStatus.Error, error = msg) }
            }
        }
    }

    private fun log(message: String) = _state.update { it.copy(logs = it.logs + message) }

    private companion object {
        val DATA_IDS = listOf(
            "UsageEvents-TherapyStatusEvent",
            "TherapyEvents-RespiratoryEvent",
            "TherapyOneMinutePeriodic-InspiratoryPressure",
            "TherapyOneMinutePeriodic-Leak",
        )
    }
}
