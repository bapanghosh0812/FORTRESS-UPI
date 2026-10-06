package com.fortress.upi.core.crypto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.InvalidKeyException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.SignatureException
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * NIST P-256 helpers: ephemeral ECDH with full point validation (defeats
 * invalid-curve attacks), SPKI parsing restricted to P-256, ECDSA-SHA256 (DER).
 */
object P256 {
    const val POINT_SIZE = 65
    private const val COORD = 32
    private const val CURVE = "secp256r1"
    private val TWO: BigInteger = BigInteger.valueOf(2)
    private val THREE: BigInteger = BigInteger.valueOf(3)

    private val spec: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec(CURVE))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }

    /** Ephemeral software key for one session's ECDH; discarded after key derivation. */
    fun generateEphemeral(random: SecureRandom = SecureRandom()): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec(CURVE), random)
            generateKeyPair()
        }

    /** X9.62 uncompressed encoding: 0x04 || X || Y. */
    fun encodePoint(key: ECPublicKey): ByteArray {
        val out = ByteArray(POINT_SIZE)
        out[0] = 0x04
        key.w.affineX.toFixed(COORD).copyInto(out, destinationOffset = 1)
        key.w.affineY.toFixed(COORD).copyInto(out, destinationOffset = 1 + COORD)
        return out
    }

    /** Decodes and validates a peer point (range + curve equation; P-256 cofactor is 1). */
    fun decodePoint(encoded: ByteArray): ECPublicKey {
        if (encoded.size != POINT_SIZE || encoded[0] != 0x04.toByte()) {
            throw InvalidKeyException("not an uncompressed P-256 point")
        }
        val x = BigInteger(1, encoded.copyOfRange(1, 1 + COORD))
        val y = BigInteger(1, encoded.copyOfRange(1 + COORD, POINT_SIZE))
        requireOnCurve(x, y)
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), spec)) as ECPublicKey
    }

    fun ecdh(privateKey: PrivateKey, peer: ECPublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(peer, true)
            generateSecret()
        }

    /** Parses an X.509 SubjectPublicKeyInfo and accepts only P-256 keys. */
    fun publicKeyFromSpki(spki: ByteArray): ECPublicKey {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)) as? ECPublicKey
            ?: throw InvalidKeyException("not an EC key")
        if (key.params.curve != spec.curve || key.params.order != spec.order) {
            throw InvalidKeyException("not a P-256 key")
        }
        requireOnCurve(key.w.affineX, key.w.affineY)
        return key
    }

    /** ECDSA-SHA256 verification over the domain-separated body; false on any failure. */
    fun verify(publicKey: PublicKey, domain: String, body: ByteArray, signatureDer: ByteArray): Boolean =
        try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(Fce.signingInput(domain, body))
                verify(signatureDer)
            }
        } catch (e: SignatureException) {
            false
        }

    private fun requireOnCurve(x: BigInteger, y: BigInteger) {
        val curve = spec.curve
        val p = (curve.field as ECFieldFp).p
        if (x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) {
            throw InvalidKeyException("coordinate out of range")
        }
        val lhs = y.modPow(TWO, p)
        val rhs = x.modPow(THREE, p).add(curve.a.multiply(x)).add(curve.b).mod(p)
        if (lhs != rhs) throw InvalidKeyException("point is not on P-256")
    }

    private fun BigInteger.toFixed(size: Int): ByteArray {
        val raw = toByteArray()
        return when {
            raw.size == size -> raw
            raw.size == size + 1 && raw[0] == 0.toByte() -> raw.copyOfRange(1, raw.size)
            raw.size < size -> ByteArray(size - raw.size) + raw
            else -> throw IllegalArgumentException("coordinate exceeds $size bytes")
        }
    }
}
