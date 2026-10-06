package com.fortress.upi.core.security.binding

import android.content.Context
import com.fortress.upi.core.security.keystore.HardwareKeys
import com.fortress.upi.core.security.keystore.KeyLocation

/** Public material sent to the server at binding time. */
class IdentityMaterial(
    val identitySpki: ByteArray,
    val identityChain: List<ByteArray>,
    val identityLocation: KeyLocation,
    val requestSpki: ByteArray,
    val requestChain: List<ByteArray>,
    val requestLocation: KeyLocation,
)

/**
 * The device's two long-term keys:
 *  * **identity** (StrongBox): binding proof, offline offers/receipts, transaction authorization.
 *  * **request** (TEE): per-API-call signatures. TEE signing is ~10× faster than
 *    StrongBox, which keeps request signing off the latency budget; the key is
 *    still hardware-bound and non-exportable, and is attested at binding.
 */
class DeviceIdentity(private val context: Context) {

    /** Replaces both keys; [challenge] is the server nonce embedded in attestation. */
    fun regenerate(challenge: ByteArray): IdentityMaterial {
        destroy()
        HardwareKeys.generateSigningKey(context, IDENTITY_ALIAS, challenge, preferStrongBox = true) {
            setUnlockedDeviceRequired(true)
        }
        HardwareKeys.generateSigningKey(context, REQUEST_ALIAS, challenge, preferStrongBox = false)
        return material()
    }

    fun material(): IdentityMaterial = IdentityMaterial(
        identitySpki = requireNotNull(HardwareKeys.publicKeySpki(IDENTITY_ALIAS)) { "identity key missing" },
        identityChain = HardwareKeys.attestationChain(IDENTITY_ALIAS),
        identityLocation = HardwareKeys.location(IDENTITY_ALIAS),
        requestSpki = requireNotNull(HardwareKeys.publicKeySpki(REQUEST_ALIAS)) { "request key missing" },
        requestChain = HardwareKeys.attestationChain(REQUEST_ALIAS),
        requestLocation = HardwareKeys.location(REQUEST_ALIAS),
    )

    fun isProvisioned(): Boolean = HardwareKeys.exists(IDENTITY_ALIAS) && HardwareKeys.exists(REQUEST_ALIAS)

    fun signWithIdentity(domain: String, body: ByteArray): ByteArray = HardwareKeys.sign(IDENTITY_ALIAS, domain, body)

    fun signRequest(domain: String, body: ByteArray): ByteArray = HardwareKeys.sign(REQUEST_ALIAS, domain, body)

    fun destroy() {
        HardwareKeys.delete(IDENTITY_ALIAS)
        HardwareKeys.delete(REQUEST_ALIAS)
    }

    companion object {
        const val IDENTITY_ALIAS = "fortress.identity.v1"
        const val REQUEST_ALIAS = "fortress.request.v1"
    }
}
