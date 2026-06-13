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
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class UiState(
    val status: SyncStatus = SyncStatus.Idle,
    val bondedDevices: List<BluetoothDevice> = emptyList(),
    val selectedDevice: BluetoothDevice? = null,
    val logs: List<String> = emptyList(),
    val result: JSONObject? = null,
    val stats: TherapyStats? = null,
    val error: String? = null,
    val dateRangePreset: String = "Last Year",
    val customStartDateMillis: Long? = null,
    val customEndDateMillis: Long? = null,
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

    fun setDateRangePreset(preset: String) {
        _state.update { it.copy(dateRangePreset = preset) }
    }

    fun setCustomDateRange(startMillis: Long?, endMillis: Long?) {
        _state.update { it.copy(customStartDateMillis = startMillis, customEndDateMillis = endMillis) }
    }

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

    private fun getQueryRange(): Pair<Instant, Instant> {
        val now = Instant.now()
        val currentState = _state.value
        return when (currentState.dateRangePreset) {
            "Last Week" -> Pair(now.minus(java.time.Duration.ofDays(7)), now)
            "Last Month" -> Pair(now.minus(java.time.Duration.ofDays(30)), now)
            "Last Year" -> Pair(now.minus(java.time.Duration.ofDays(365)), now)
            "Custom" -> {
                val start = currentState.customStartDateMillis?.let { Instant.ofEpochMilli(it) } ?: now.minus(java.time.Duration.ofDays(7))
                val end = currentState.customEndDateMillis?.let { Instant.ofEpochMilli(it).plus(java.time.Duration.ofDays(1)).minusMillis(1) } ?: now
                Pair(start, end)
            }
            else -> Pair(now.minus(java.time.Duration.ofDays(7)), now)
        }
    }

    private fun filterDownloadedData(data: JSONObject, start: Instant, end: Instant): JSONObject {
        val filtered = JSONObject()
        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val array = data.optJSONArray(key) ?: continue
            val filteredArray = JSONArray()

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                when (key) {
                    "UsageEvents-TherapyStatusEvent", "TherapyEvents-RespiratoryEvent" -> {
                        val events = item.optJSONArray("events")
                        if (events != null) {
                            val filteredEvents = JSONArray()
                            for (j in 0 until events.length()) {
                                val ev = events.getJSONObject(j)
                                val timeStr = ev.optString("time")
                                try {
                                    val t = Instant.parse(timeStr)
                                    if (!t.isBefore(start) && !t.isAfter(end)) {
                                        filteredEvents.put(ev)
                                    }
                                } catch (e: Exception) {
                                    filteredEvents.put(ev)
                                }
                            }
                            if (filteredEvents.length() > 0) {
                                val newItem = JSONObject(item.toString())
                                newItem.put("events", filteredEvents)
                                filteredArray.put(newItem)
                            }
                        }
                    }
                    "TherapyOneMinutePeriodic-InspiratoryPressure", "TherapyOneMinutePeriodic-Leak" -> {
                        val periodic = item.optJSONObject("periodic")
                        if (periodic != null) {
                            val timeStr = periodic.optString("startTime")
                            try {
                                val t = Instant.parse(timeStr)
                                if (!t.isBefore(start) && !t.isAfter(end)) {
                                    filteredArray.put(item)
                                }
                            } catch (e: Exception) {
                                filteredArray.put(item)
                            }
                        }
                    }
                    else -> {
                        filteredArray.put(item)
                    }
                }
            }
            filtered.put(key, filteredArray)
        }
        return filtered
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

                val (startInstant, endInstant) = getQueryRange()
                val formatter = java.time.format.DateTimeFormatter
                    .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .withZone(java.time.ZoneOffset.UTC)
                val fromTimeStr = formatter.format(startInstant)
                val toTimeStr = formatter.format(endInstant)

                log("Downloading telemetry since $fromTimeStr…")
                val latestTimestamps = DATA_IDS.associateWith { fromTimeStr }
                val rawResult = client.downloadData(latestTimestamps)
                client.disconnect()

                log("Filtering data up to $toTimeStr…")
                val result = filterDownloadedData(rawResult, startInstant, endInstant)

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
