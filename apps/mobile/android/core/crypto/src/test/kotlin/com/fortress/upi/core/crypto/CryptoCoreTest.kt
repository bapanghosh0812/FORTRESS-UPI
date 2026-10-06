package com.fortress.upi.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.InvalidKeyException
import java.security.Signature
import java.security.interfaces.ECPublicKey
import javax.crypto.AEADBadTagException

class CryptoCoreTest {

    // ── FCE: golden vector shared with shared/crypto/fce/fce_test.go ──
    @Test
    fun fceGoldenVectorMatchesGo() {
        val encoded = Fce.Writer().u64(1, 5).string(2, "ab").build()
        assertEquals("0100000008000000000000000502000000026162", encoded.toHex())
    }

    @Test
    fun fceRoundTripAndStrictness() {
        val items = listOf(byteArrayOf(1, 2), ByteArray(0), ByteArray(300) { 9 })
        val enc = Fce.Writer().bytes(1, "x".toByteArray()).u64(4, 42).list(9, items).build()
        val m = Fce.decode(enc, 1, 4, 9)
        assertEquals(42L, m.u64(4))
        assertArrayEquals(items[2], m.list(9)[2])

        assertThrows<FceException> { Fce.Writer().u64(2, 1).u64(1, 1) }
        assertThrows<FceException> { Fce.decode(enc, 1, 4) } // unknown tag 9
        assertThrows<FceException> { Fce.decode(enc.copyOf(enc.size - 1), 1, 4, 9) }
        assertThrows<FceException> { Fce.decode("0100000008800000000000000000".hexToBytes(), 1).u64(1) }
    }

    // ── HKDF: RFC 5869 test case 1 ──
    @Test
    fun hkdfMatchesRfc5869() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val prk = Hkdf.extract(salt, ikm)
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", prk.toHex())
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Hkdf.expand(prk, info, 42).toHex(),
        )
    }

    // ── ECDH + AEAD session ──
    @Test
    fun bothSidesDeriveTheSameKeysAndTamperingFails() {
        val payer = P256.generateEphemeral()
        val payee = P256.generateEphemeral()
        val payerPub = P256.encodePoint(payer.public as ECPublicKey)
        val payeePub = P256.encodePoint(payee.public as ECPublicKey)
        val nP = SecureBytes.random(32)
        val nQ = SecureBytes.random(32)

        val payerKeys = SessionKeys.derive(P256.ecdh(payer.private, P256.decodePoint(payeePub)), nP, nQ, payerPub, payeePub)
        val payeeKeys = SessionKeys.derive(P256.ecdh(payee.private, P256.decodePoint(payerPub)), nP, nQ, payerPub, payeePub)

        val aad = "aad".toByteArray()
        val sealed = payeeKeys.seal(Direction.PAYEE_TO_PAYER, 0, aad, "offer".toByteArray())
        assertArrayEquals("offer".toByteArray(), payerKeys.open(Direction.PAYEE_TO_PAYER, 0, aad, sealed))

        assertThrows<AEADBadTagException> { payerKeys.open(Direction.PAYEE_TO_PAYER, 1, aad, sealed) }
        assertThrows<AEADBadTagException> { payerKeys.open(Direction.PAYER_TO_PAYEE, 0, aad, sealed) }
        assertThrows<AEADBadTagException> { payerKeys.open(Direction.PAYEE_TO_PAYER, 0, "AAD".toByteArray(), sealed) }
    }

    @Test
    fun rejectsOffCurvePoints() {
        val valid = P256.encodePoint(P256.generateEphemeral().public as ECPublicKey)
        val tampered = valid.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }
        assertThrows<InvalidKeyException> { P256.decodePoint(tampered) }
        assertThrows<InvalidKeyException> { P256.decodePoint(ByteArray(65)) }
    }

    @Test
    fun signaturesAreDomainSeparated() {
        val kp = P256.generateEphemeral()
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(kp.private)
            update(Fce.signingInput(Domains.SPEND, "body".toByteArray()))
            sign()
        }
        val pub = P256.publicKeyFromSpki(kp.public.encoded)
        assertTrue(P256.verify(pub, Domains.SPEND, "body".toByteArray(), sig))
        assertFalse(P256.verify(pub, Domains.RECEIPT, "body".toByteArray(), sig))
        assertFalse(P256.verify(pub, Domains.SPEND, "body".toByteArray(), byteArrayOf(0x30, 0x00)))
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return
            throw AssertionError("expected ${T::class.simpleName}, got $t", t)
        }
        throw AssertionError("expected ${T::class.simpleName}, nothing thrown")
    }
}
