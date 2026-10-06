package com.fortress.upi.core.offline.vault

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyProperties
import com.fortress.upi.core.security.keystore.HardwareKeys
import com.fortress.upi.core.security.keystore.KeyLocation

/** What this handset can enforce for offline notes. */
class VaultCapability(
    val strongBox: Boolean,
    /** KeyMint enforces single use in hardware (not just Keystore software). */
    val hardwareSingleUse: Boolean,
) {
    /** Offline vault is disabled when single use would only be software-enforced. */
    val supported: Boolean get() = hardwareSingleUse
}

class GeneratedNoteKey(
    val alias: String,
    val publicKeySpki: ByteArray,
    val attestationChain: List<ByteArray>,
    val location: KeyLocation,
)

/**
 * Creates the single-use keys that back offline notes.
 *
 * Each key: P-256, StrongBox-preferred, `setMaxUsageCount(1)` (KeyMint deletes it
 * after one signature), usable only while the device is unlocked, and only within
 * [authWindowSeconds] of a strong biometric / device-credential authentication.
 * The attestation chain proves all of this to the issuer before any value is
 * certified against the key.
 */
class NoteKeyFactory(private val context: Context) {

    fun capability(): VaultCapability {
        val pm = context.packageManager
        return VaultCapability(
            strongBox = pm.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE),
            hardwareSingleUse = pm.hasSystemFeature(PackageManager.FEATURE_KEYSTORE_SINGLE_USE_KEY),
        )
    }

    /** Blocking (StrongBox key generation with attestation can take ~0.3–2 s). Call on Dispatchers.IO. */
    fun generate(alias: String, attestationChallenge: ByteArray, authWindowSeconds: Int): GeneratedNoteKey {
        val location = HardwareKeys.generateSigningKey(context, alias, attestationChallenge, preferStrongBox = true) {
            setMaxUsageCount(1)
            setUnlockedDeviceRequired(true)
            setUserAuthenticationRequired(true)
            setUserAuthenticationParameters(
                authWindowSeconds,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
            setInvalidatedByBiometricEnrollment(true)
        }
        return GeneratedNoteKey(
            alias = alias,
            publicKeySpki = requireNotNull(HardwareKeys.publicKeySpki(alias)) { "note key missing after generation" },
            attestationChain = HardwareKeys.attestationChain(alias),
            location = location,
        )
    }

    fun publicKeySpki(alias: String): ByteArray? = HardwareKeys.publicKeySpki(alias)

    /** Signs once; the key no longer exists afterwards. */
    fun signOnce(alias: String, domain: String, body: ByteArray): ByteArray = HardwareKeys.sign(alias, domain, body)

    fun exists(alias: String): Boolean = HardwareKeys.exists(alias)

    fun delete(alias: String) = HardwareKeys.delete(alias)
}
