package com.fortress.upi.core.crypto

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Direction label baked into every AEAD nonce. */
enum class Direction(internal val label: Int) {
    PAYEE_TO_PAYER(1),
    PAYER_TO_PAYEE(2),
}

/**
 * Per-session AES-256-GCM keys, one per direction, derived with HKDF from an
 * ephemeral ECDH secret. Nonce = direction:u32be || counter:u64be, and every
 * (direction, counter) pair is used exactly once by the protocol, so nonce
 * reuse is structurally impossible.
 */
class SessionKeys private constructor(
    private val payeeToPayer: ByteArray,
    private val payerToPayee: ByteArray,
) : AutoCloseable {
    @Volatile
    private var closed = false

    fun seal(direction: Direction, counter: Long, aad: ByteArray, plaintext: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, direction, counter, aad).doFinal(plaintext)

    /** Throws [javax.crypto.AEADBadTagException] on any tampering. */
    fun open(direction: Direction, counter: Long, aad: ByteArray, ciphertext: ByteArray): ByteArray =
        cipher(Cipher.DECRYPT_MODE, direction, counter, aad).doFinal(ciphertext)

    private fun cipher(mode: Int, direction: Direction, counter: Long, aad: ByteArray): Cipher {
        check(!closed) { "session keys already destroyed" }
        require(counter >= 0) { "negative counter" }
        val key = if (direction == Direction.PAYEE_TO_PAYER) payeeToPayer else payerToPayee
        val nonce = ByteBuffer.allocate(NONCE_SIZE).putInt(direction.label).putLong(counter).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }
    }

    override fun close() {
        closed = true
        payeeToPayer.wipe()
        payerToPayee.wipe()
    }

    companion object {
        private const val NONCE_SIZE = 12
        private const val TAG_BITS = 128
        private const val KEY_SIZE = 32
        private val INFO = Domains.SESSION_INFO.toByteArray(StandardCharsets.US_ASCII)

        /** Caller owns and must wipe [sharedSecret]. */
        fun derive(
            sharedSecret: ByteArray,
            payerNonce: ByteArray,
            payeeNonce: ByteArray,
            payerEphemeral: ByteArray,
            payeeEphemeral: ByteArray,
        ): SessionKeys {
            val prk = Hkdf.extract(payerNonce + payeeNonce, sharedSecret)
            val okm = Hkdf.expand(prk, INFO + payerEphemeral + payeeEphemeral, KEY_SIZE * 2)
            try {
                return SessionKeys(okm.copyOfRange(0, KEY_SIZE), okm.copyOfRange(KEY_SIZE, KEY_SIZE * 2))
            } finally {
                prk.wipe()
                okm.wipe()
            }
        }
    }
}
