package com.fortress.upi.core.security.keystore

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.fortress.upi.core.crypto.Fce
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Where a key physically lives, as reported by Keystore itself. */
enum class KeyLocation { STRONGBOX, TEE, SOFTWARE }

/**
 * Android Keystore facade. Private keys are generated inside StrongBox (a
 * discrete secure element) when present, else the TEE; they are non-exportable
 * and every signature happens inside the secure hardware.
 */
object HardwareKeys {
    private const val PROVIDER = "AndroidKeyStore"

    fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun hasStrongBox(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    /**
     * Generates a P-256 signing key. [customize] adds key-specific constraints
     * (usage limits, user auth, unlocked-device requirement).
     */
    fun generateSigningKey(
        context: Context,
        alias: String,
        attestationChallenge: ByteArray?,
        preferStrongBox: Boolean,
        customize: KeyGenParameterSpec.Builder.() -> Unit = {},
    ): KeyLocation {
        fun attempt(strongBox: Boolean) {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setIsStrongBoxBacked(strongBox)
                .apply { attestationChallenge?.let(::setAttestationChallenge) }
                .apply(customize)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).run {
                initialize(spec)
                generateKeyPair()
            }
        }
        if (preferStrongBox && hasStrongBox(context)) {
            try {
                attempt(strongBox = true)
                return location(alias)
            } catch (e: ProviderException) {
                // StrongBoxUnavailableException (a ProviderException) or a feature the
                // secure element doesn't implement: fall back to the TEE.
                delete(alias)
            }
        }
        attempt(strongBox = false)
        return location(alias)
    }

    /** AES-256-GCM key for wrapping local secrets (e.g. the SQLCipher key). */
    fun getOrCreateAesKey(context: Context, alias: String, unlockedDeviceRequired: Boolean): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        fun attempt(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUnlockedDeviceRequired(unlockedDeviceRequired)
                .setIsStrongBoxBacked(strongBox)
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
                init(spec)
                generateKey()
            }
        }
        if (hasStrongBox(context)) {
            try {
                return attempt(strongBox = true)
            } catch (e: ProviderException) {
                delete(alias) // StrongBox unavailable for this spec: fall back to the TEE
            }
        }
        return attempt(strongBox = false)
    }

    fun exists(alias: String): Boolean = keyStore().containsAlias(alias)

    fun delete(alias: String) {
        val ks = keyStore()
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    fun publicKeySpki(alias: String): ByteArray? = keyStore().getCertificate(alias)?.publicKey?.encoded

    /** Attestation chain (leaf first), DER-encoded, for server-side verification. */
    fun attestationChain(alias: String): List<ByteArray> =
        keyStore().getCertificateChain(alias)?.map { it.encoded }.orEmpty()

    /**
     * Signs `domain || 0x00 || body` with SHA256withECDSA inside secure hardware.
     * Throws [android.security.keystore.UserNotAuthenticatedException] when the
     * key's auth window has lapsed — callers prompt BiometricPrompt and retry.
     */
    fun sign(alias: String, domain: String, body: ByteArray): ByteArray {
        val key = keyStore().getKey(alias, null) as? PrivateKey
            ?: throw KeyMissingException(alias)
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(Fce.signingInput(domain, body))
            sign()
        }
    }

    fun location(alias: String): KeyLocation {
        val key = keyStore().getKey(alias, null) ?: throw KeyMissingException(alias)
        val info = KeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java)
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyLocation.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
            KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> KeyLocation.TEE
            else -> KeyLocation.SOFTWARE
        }
    }
}

class KeyMissingException(alias: String) : IllegalStateException("keystore entry missing: $alias")
