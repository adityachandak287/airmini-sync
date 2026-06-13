package com.airmini.sync

import com.resmed.mon.fig.FigWrapper

/**
 * Thin wrapper around the FigWrapper JNI bridge.
 *
 * Starts in unencrypted mode for the GetPairKey handshake. Once a session key is
 * negotiated, call upgradeToEncrypted() to switch to the encrypted instance used
 * for all subsequent GetLoggedData requests.
 *
 * This is the only class in com.airmini.sync that may import com.resmed.mon.fig.
 */
class AirMiniCrypto {

    private var wrapper: FigWrapper = FigWrapper.unencrypted()

    /** Encode a JSON-RPC string into a framed packet ready to write to the socket. */
    fun encodePacket(json: String): ByteArray = wrapper.encodePacket(json)

    /**
     * Decode a raw packet received from the socket.
     * Returns the JSON string payload, or null for internal protocol frames.
     */
    fun decodePacket(bytes: ByteArray): String? = wrapper.decodePacket(bytes)

    /**
     * Switch from unencrypted to encrypted mode using the negotiated session key.
     * Must be called after a successful GetPairKey exchange.
     */
    fun upgradeToEncrypted(sessionKey: ByteArray) {
        wrapper = FigWrapper.encrypted(sessionKey)
    }

    /**
     * Pull any internally queued outbound frames from the JNI state machine.
     *
     * Protocol rule (Rule 4): After every decodePacket() call during the handshake
     * phase, the caller MUST invoke pullTxData() and write any non-null result back
     * to the transport. The JNI library queues frames like ConfirmKeyExchange that
     * must be flushed to advance the cryptographic state machine.
     */
    fun pullTxData(): ByteArray? = wrapper.pullTxData()
}
