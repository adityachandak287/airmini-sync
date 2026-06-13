package com.airmini.sync

import org.json.JSONArray
import org.json.JSONObject

/** The four telemetry streams the AirMini exposes via GetLoggedData. */
private val DATA_IDS = listOf(
    "UsageEvents-TherapyStatusEvent",
    "TherapyEvents-RespiratoryEvent",
    "TherapyOneMinutePeriodic-InspiratoryPressure",
    "TherapyOneMinutePeriodic-Leak",
)

/**
 * AirMini protocol client.
 *
 * Implements the JSON-RPC protocol over the provided Transport. All AirMini-specific
 * message framing, session establishment, and data download logic lives here.
 * No Android UI or Bluetooth API concerns belong in this class.
 *
 * Protocol overview:
 *   1. Connect via transport.
 *   2. Send GetPairKey with the 4-digit PIN.
 *   3. Loop-read packets; flush pullTxData() after each decode (Rule 4).
 *   4. On success, extract sessionKey and call crypto.upgradeToEncrypted().
 *   5. For each data ID, send GetLoggedData.
 *   6. Read the initial acknowledgment to capture logStreamId (Rule 5).
 *   7. Loop-read LoggedData notifications matching logStreamId until complete=true.
 */
class AirMiniClient(
    private val transport: Transport,
    private val crypto: AirMiniCrypto,
) {

    suspend fun connect() = transport.connect()

    suspend fun disconnect() = transport.disconnect()

    /**
     * Execute the GetPairKey handshake.
     *
     * Sends the 4-digit PIN, loops reading packets (flushing pullTxData() on each),
     * and returns once the session key is negotiated and crypto is upgraded to
     * encrypted mode.
     *
     * @param pin 4-digit pairing PIN printed on the AirMini device.
     */
    suspend fun establishSession(pin: String) {
        require(pin.length == 4 && pin.all(Char::isDigit)) { "PIN must be exactly 4 digits" }

        val request = jsonRpc(method = "GetPairKey", params = JSONObject().put("passKey", pin), id = 1)
        transport.write(crypto.encodePacket(request))

        var decoded: String? = null
        while (decoded == null) {
            val bytes = transport.readPacket()
            decoded = crypto.decodePacket(bytes)
            // Rule 4: flush any JNI-queued frames (e.g. ConfirmKeyExchange) after every decode.
            crypto.pullTxData()?.let { transport.write(it) }
        }

        val response = JSONObject(decoded)
        if (response.has("error")) {
            error("GetPairKey failed: ${response.get("error")}")
        }

        val sessionKeyHex = response.getJSONObject("result").getString("sessionKey")
        crypto.upgradeToEncrypted(hexToBytes(sessionKeyHex))
    }

    /**
     * Download all telemetry streams from the device.
     *
     * @param latestTimestamps Map of dataId → ISO-8601 timestamp. Records at or before
     *   this time will be skipped, enabling incremental syncs. Pass an empty map to
     *   fetch all available history.
     * @return JSONObject keyed by dataId, each value a JSONArray of records.
     */
    suspend fun downloadData(latestTimestamps: Map<String, String> = emptyMap()): JSONObject {
        val allData = JSONObject()

        for (dataId in DATA_IDS) {
            val fromTime = latestTimestamps[dataId] ?: "2000-01-01T00:00:00.000Z"
            val request = jsonRpc(
                method = "GetLoggedData",
                params = JSONArray().put(
                    JSONObject().put("dataId", dataId).put("fromTime", fromTime)
                ),
                id = 2,
            )
            transport.write(crypto.encodePacket(request))

            val records = JSONArray()
            allData.put(dataId, records)

            // Rule 5: the first response is an acknowledgment containing logStreamId.
            // Subsequent packets are LoggedData notifications on that stream.
            var logStreamId = -1
            var streamComplete = false

            while (!streamComplete) {
                val bytes = transport.readPacket()
                val json = crypto.decodePacket(bytes) ?: continue
                val obj = JSONObject(json)

                if (obj.has("error")) {
                    error("GetLoggedData error for $dataId: ${obj.get("error")}")
                }

                // Initial acknowledgment — capture logStreamId.
                if (obj.optInt("id", -1) == 2) {
                    logStreamId = obj.optJSONObject("result")?.optInt("logStreamId", -1) ?: -1
                    continue
                }

                // Streaming notification.
                if (obj.optString("method") == "LoggedData") {
                    val params = obj.optJSONObject("params") ?: continue
                    if (logStreamId != -1 && params.optInt("logStreamId", -1) != logStreamId) continue

                    val data = params.optJSONArray("data") ?: continue
                    var batchComplete = true
                    for (i in 0 until data.length()) {
                        val item = data.getJSONObject(i)
                        if (!item.optBoolean("complete", true)) batchComplete = false
                        records.put(item)
                    }
                    if (batchComplete) streamComplete = true
                }
            }
        }

        return allData
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun jsonRpc(method: String, params: Any, id: Int): String =
        """{"jsonrpc":"2.0","method":"$method","params":$params,"id":$id}"""

    private fun hexToBytes(hex: String): ByteArray {
        check(hex.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }
}
