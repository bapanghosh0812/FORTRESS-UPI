package com.fortress.upi.data

import com.fortress.upi.upi.UpiPaymentIntent

/** What the NPCI Common Library needs to render its secure PIN pad. */
class CredentialRequest(
    val paymentId: String,
    val payeeVpa: String,
    val payeeName: String?,
    val amountPaise: Long,
    val accountRef: String,
    /** Server-issued challenge the CL binds into the encrypted credential block. */
    val clChallenge: String,
)

/** Encrypted credential block produced by the CL; opaque to Fortress, decrypted only by the issuer bank. */
class CredentialBlock(val payload: String)

/**
 * Boundary to the NPCI UPI Common Library — the only component allowed to
 * capture a UPI PIN. The SDK is distributed by NPCI to certified PSP/TPAP
 * partners and is linked in the release pipeline; the app never renders a PIN pad.
 */
interface CommonLibrary {
    /** Returns null when the user cancels on the NPCI screen. */
    suspend fun authorize(request: CredentialRequest): CredentialBlock?
}

/** Release default until the certified CL artifact is linked: fails closed. */
class UnlinkedCommonLibrary : CommonLibrary {
    override suspend fun authorize(request: CredentialRequest): CredentialBlock? =
        throw UnsupportedOperationException("NPCI Common Library is not linked in this build")
}

/**
 * Debug-only stand-in that talks to the PSP **sandbox** (payments service with
 * PSP_MODE=sandbox), which accepts this marker instead of a real credential.
 * Never compiled into release behaviour: AppContainer picks it only when BuildConfig.DEBUG.
 */
class SandboxCommonLibrary : CommonLibrary {
    override suspend fun authorize(request: CredentialRequest): CredentialBlock = CredentialBlock("sandbox:${request.clChallenge}")
}

/** Payment request derived from a scanned QR, intent link or contact. */
fun UpiPaymentIntent.displayName(): String = payeeName ?: payeeVpa
