package com.airmini.sync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/**
 * Transport abstraction — allows AirMiniClient to be tested against a fake implementation
 * without a real Bluetooth connection.
 */
interface Transport {
    suspend fun connect()
    suspend fun disconnect()

    /**
     * Read one complete framed packet from the device.
     * Blocks until a full packet (16-byte header + payload) is available.
     */
    suspend fun readPacket(): ByteArray

    /** Write raw bytes to the device socket. */
    suspend fun write(data: ByteArray)
}

/**
 * Android Bluetooth Classic (RFCOMM) transport.
 *
 * Manages the BluetoothSocket lifecycle. Knows nothing about AirMini messages,
 * packet framing, or encryption — those are AirMiniClient's responsibility.
 *
 * Note: When using the public BluetoothSocket API (as opposed to the ADB shell binder
 * hack used in run_sync.sh), there is NO 32-byte system socket handshake to consume.
 * The stream starts directly with AirMini protocol packets.
 */
@SuppressLint("MissingPermission")
class BluetoothTransport(
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
) : Transport {

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        adapter.cancelDiscovery()
        val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
        s.connect()
        socket = s
        inputStream = s.inputStream
        outputStream = s.outputStream
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { socket?.close() }
        socket = null
        inputStream = null
        outputStream = null
    }

    override suspend fun readPacket(): ByteArray = withContext(Dispatchers.IO) {
        val input = checkNotNull(inputStream) { "Not connected" }
        readFramedPacket(input)
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val output = checkNotNull(outputStream) { "Not connected" }
        output.write(data)
        output.flush()
    }

    // -------------------------------------------------------------------------
    // Packet framing (bebafeca magic, 16-byte header + variable payload)
    // -------------------------------------------------------------------------

    private fun readFramedPacket(input: InputStream): ByteArray {
        val header = ByteArray(16)
        input.readFully(header)

        if (header[0] != 0xbe.toByte() || header[1] != 0xba.toByte() ||
            header[2] != 0xfe.toByte() || header[3] != 0xca.toByte()
        ) {
            throw IOException(
                "Invalid packet magic: expected bebafeca, got ${header.take(4).joinToString("") { "%02x".format(it) }}"
            )
        }

        val payloadLen = ((header[7].toInt() and 0xFF) shl 8) or (header[6].toInt() and 0xFF)
        val payload = ByteArray(payloadLen)
        input.readFully(payload)

        return header + payload
    }

    private fun InputStream.readFully(buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = read(buf, offset, buf.size - offset)
            if (n == -1) throw IOException("Unexpected EOF reading from Bluetooth socket")
            offset += n
        }
    }
}
