package com.fortress.upi.core.offline.protocol

import com.fortress.upi.core.crypto.Domains
import com.fortress.upi.core.crypto.Fce
import com.fortress.upi.core.crypto.sha256
import java.util.Locale

/** Transcript hashing, AEAD associated data and the short authentication string. */
internal object Transcript {

    /** T = SHA-256(TRANSCRIPT ‖ 0 ‖ {1 hello, 2 payeeEph, 3 payeeNonce, 4 offerRequest}). */
    fun hash(helloPayload: ByteArray, payeeEphemeral: ByteArray, payeeNonce: ByteArray, offerRequest: ByteArray): ByteArray =
        sha256(
            Fce.signingInput(
                Domains.TRANSCRIPT,
                Fce.Writer().bytes(1, helloPayload).bytes(2, payeeEphemeral).bytes(3, payeeNonce).bytes(4, offerRequest).build(),
            ),
        )

    fun offerAad(helloHash: ByteArray, payeeEphemeral: ByteArray, payeeNonce: ByteArray): ByteArray =
        Fce.signingInput(Domains.AAD_OFFER, helloHash + payeeEphemeral + payeeNonce)

    fun payAad(transcript: ByteArray): ByteArray = Fce.signingInput(Domains.AAD_PAY, transcript)

    fun receiptAad(transcript: ByteArray): ByteArray = Fce.signingInput(Domains.AAD_RECEIPT, transcript)

    /** 4-digit code shown on both phones for BLE so users confirm they're paired with each other. */
    fun sas(transcript: ByteArray): String {
        val d = sha256(Fce.signingInput(Domains.SAS, transcript))
        val v = ((d[0].toLong() and 0xFF) shl 24) or ((d[1].toLong() and 0xFF) shl 16) or
            ((d[2].toLong() and 0xFF) shl 8) or (d[3].toLong() and 0xFF)
        return String.format(Locale.ROOT, "%04d", v % 10_000)
    }
}
