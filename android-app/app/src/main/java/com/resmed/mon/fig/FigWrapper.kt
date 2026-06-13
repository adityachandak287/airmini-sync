package com.resmed.mon.fig

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JNI bridge to libfiglib.so.
 *
 * This class MUST remain in the com.resmed.mon.fig package. The native library
 * (libfiglib.so) exports symbols keyed to this exact fully-qualified class name:
 *   Java_com_resmed_mon_fig_FigWrapper_initialise
 *   Java_com_resmed_mon_fig_FigWrapper_nativeDecode
 *   Java_com_resmed_mon_fig_FigWrapper_nativeEncode
 *   Java_com_resmed_mon_fig_FigWrapper_pullTxData
 *
 * Moving this class to any other package will cause an UnsatisfiedLinkError at runtime.
 * All other application code lives in com.airmini.sync and interacts with this class
 * only via AirMiniCrypto.
 */
class FigWrapper private constructor() {

    @Suppress("unused") // Referenced by JNI via nativeHandle field name
    private var nativeHandle: Long = 0

    var aesKey: ByteArray? = null
    private val random = SecureRandom()

    // -------------------------------------------------------------------------
    // JNI callbacks — called by native code during encode/decode operations
    // -------------------------------------------------------------------------

    /** Called by JNI to deliver a decoded packet payload. */
    @Suppress("unused")
    fun callBack(str: String) {
        lastDecoded = str
    }

    /** Called by JNI for internal log output. */
    @Suppress("unused")
    fun figLogCallback(str: String) {
        // Intentionally minimal — native debug output is not surfaced to the UI.
    }

    /** Called by JNI to perform AES-CBC encryption. */
    @Suppress("unused")
    fun encrypt(data: ByteArray): ByteArray {
        val key = requireNotNull(aesKey) { "AES key not set" }
        val iv = ByteArray(16).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return ByteArrayOutputStream().apply {
            write(iv)
            write(cipher.doFinal(data))
        }.toByteArray()
    }

    /** Called by JNI to perform AES-CBC decryption. */
    @Suppress("unused")
    fun decrypt(data: ByteArray, iv: ByteArray): ByteArray {
        val key = requireNotNull(aesKey) { "AES key not set" }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** Called by JNI to generate cryptographically random bytes. */
    @Suppress("unused")
    fun generateRandomData(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    /** Called by JNI to compute a SHA-256 hash. */
    @Suppress("unused")
    fun hash(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** Called by JNI to compute an HMAC-SHA256. */
    @Suppress("unused")
    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    // -------------------------------------------------------------------------
    // Public API — used by AirMiniCrypto
    // -------------------------------------------------------------------------

    /**
     * Decode a raw packet from the device. Returns the decoded JSON string, or
     * null if the packet was an internal protocol frame with no payload.
     */
    fun decodePacket(bytes: ByteArray): String? {
        lastDecoded = null
        nativeDecode(bytes)
        return lastDecoded
    }

    /** Encode a JSON-RPC string into a framed packet ready to send to the device. */
    fun encodePacket(json: String): ByteArray = nativeEncode(json)

    // -------------------------------------------------------------------------
    // Native method declarations
    // -------------------------------------------------------------------------

    private external fun initialise(i: Int, encrypted: Boolean, i2: Int)
    private external fun nativeDecode(data: ByteArray): ByteArray
    private external fun nativeEncode(json: String): ByteArray

    /** Pull any internally queued outbound frames (e.g. ConfirmKeyExchange). */
    external fun pullTxData(): ByteArray?

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    companion object {
        var lastDecoded: String? = null

        /** Create an instance for the unencrypted pairing handshake phase. */
        fun unencrypted(): FigWrapper = FigWrapper().also { it.initialise(0, false, 0) }

        /** Create an instance for the encrypted data download phase. */
        fun encrypted(sessionKey: ByteArray): FigWrapper = FigWrapper().also {
            it.aesKey = sessionKey
            it.initialise(0, true, 0)
        }

        init {
            System.loadLibrary("figlib")
        }
    }
}
