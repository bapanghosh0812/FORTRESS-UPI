package com.fortress.upi.core.offline.protocol

import com.fortress.upi.core.crypto.Clock
import com.fortress.upi.core.crypto.Domains
import com.fortress.upi.core.crypto.Fce
import com.fortress.upi.core.crypto.P256
import com.fortress.upi.core.crypto.SecureBytes
import com.fortress.upi.core.crypto.toHex
import com.fortress.upi.core.offline.transport.OfflineResponder
import com.fortress.upi.core.offline.transport.OfflineTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPair
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey

/**
 * Full FOP/1 run on the JVM: real ECDH, HKDF, AES-GCM and ECDSA; software keys
 * stand in for StrongBox (the protocol code is identical).
 */
class OfflineProtocolTest {
    private val now = 1_790_000_000_000L
    private val clock = Clock { now }
    private val issuer = P256.generateEphemeral()
    private val issuerKeyId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val trust = IssuerTrustStore(mapOf(issuerKeyId.toHex() to issuer.public as ECPublicKey), clock)
    private val wallet = SecureBytes.random(16)

    private fun signWith(key: PrivateKey, domain: String, body: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(Fce.signingInput(domain, body))
            sign()
        }

    private inner class TestNote(val denomination: Long) {
        val key: KeyPair = P256.generateEphemeral()
        val id: ByteArray = SecureBytes.random(16)
        val signedCert: ByteArray = run {
            val body = NoteCertificate(
                id, wallet, key.public.encoded, denomination, now / 1000 - 60, now / 1000 + 86_400, issuerKeyId,
                NoteCertificate.ENFORCEMENT_STRONGBOX,
            ).encodeBody()
            SignedEnvelope(body, signWith(issuer.private, Domains.NOTE_CERT, body)).encode()
        }
    }

    private inner class TestPayee(name: String = "Asha Kirana Store") : ReceiverIdentity {
        val key: KeyPair = P256.generateEphemeral()
        override val receiverId: ByteArray = SecureBytes.random(16)
        override val signedReceiverCert: ByteArray = run {
            val body = ReceiverCertificate(receiverId, name, key.public.encoded, now / 1000 - 60, now / 1000 + 86_400, issuerKeyId).encodeBody()
            SignedEnvelope(body, signWith(issuer.private, Domains.RECEIVER_CERT, body)).encode()
        }

        override fun sign(domain: String, body: ByteArray): ByteArray = signWith(key.private, domain, body)
    }

    private class MemoryInbox : PaymentInbox {
        val seen = HashSet<String>()
        val bundles = ArrayList<ByteArray>()

        override suspend fun record(verified: VerifiedBundle, rawBundle: ByteArray) {
            if (verified.noteIds.any { it in seen }) throw OfflineProtocolException(Reason.REPLAY)
            seen += verified.noteIds
            bundles += rawBundle
        }
    }

    /** Loopback "radio" with an optional in-flight tamper hook (models an active attacker). */
    private class Loopback(
        private val responder: OfflineResponder,
        private val tamper: (ByteArray) -> ByteArray = { it },
    ) : OfflineTransport {
        override suspend fun exchange(request: ByteArray): ByteArray = tamper(responder.respond(request))
        override fun close() = Unit
    }

    /** What OfflineVault.authorizePayment does, with software keys. */
    private fun buildBundle(offer: NegotiatedOffer, notes: List<TestNote>, applied: List<Long>): Pair<ByteArray, ByteArray> {
        val paymentId = SecureBytes.random(16)
        val total = applied.sum()
        val items = notes.zip(applied).map { (n, a) ->
            val body = spendSigBody(n.id, paymentId, offer.receiver.receiverId, offer.transcript, a, total, now)
            SpendItem(n.signedCert, a, signWith(n.key.private, Domains.SPEND, body))
        }
        return paymentId to PaymentBundle(paymentId, offer.transcript, offer.receiver.receiverId, total, now, items).encode()
    }

    private fun payeeSession(payee: TestPayee, inbox: PaymentInbox, amount: Long, events: MutableList<PayeeEvent>) =
        PayeeSession(payee, amount, BundleVerifier(trust, clock), inbox, clock) { events += it }

    @Test
    fun happyPathWithPartialNoteSettlesAndReceiptVerifies() = runTest {
        val payee = TestPayee()
        val inbox = MemoryInbox()
        val events = ArrayList<PayeeEvent>()
        val notes = listOf(TestNote(10_000), TestNote(5_000))

        PayerSession(Loopback(payeeSession(payee, inbox, 12_300, events)), trust).use { payer ->
            val offer = payer.negotiate()
            assertEquals("Asha Kirana Store", offer.receiver.displayName)
            assertEquals(12_300L, offer.requestedAmountPaise)
            assertEquals((events.first() as PayeeEvent.PayerConnected).sas, offer.sas)

            val (paymentId, bundle) = buildBundle(offer, notes, listOf(10_000, 2_300))
            val receipt = payer.pay(offer, paymentId, bundle)
            assertEquals("Asha Kirana Store", receipt.receiverName)
        }
        val received = events.last() as PayeeEvent.Received
        assertEquals(12_300L, received.amountPaise)
        assertEquals(1, inbox.bundles.size)
    }

    @Test
    fun tamperedOfferIsRejectedByPayer() = runTest {
        val flipLastByte: (ByteArray) -> ByteArray = { it.copyOf().also { b -> b[b.size - 1] = (b[b.size - 1].toInt() xor 1).toByte() } }
        val session = payeeSession(TestPayee(), MemoryInbox(), 0, ArrayList())
        PayerSession(Loopback(session, flipLastByte), trust).use { payer ->
            expectReason(Reason.MALFORMED) { payer.negotiate() }
        }
    }

    @Test
    fun receiverCertificateFromUnknownIssuerIsRejected() = runTest {
        val rogueTrust = IssuerTrustStore(mapOf("ffffffffffffffff" to P256.generateEphemeral().public as ECPublicKey), clock)
        PayerSession(Loopback(payeeSession(TestPayee(), MemoryInbox(), 0, ArrayList())), rogueTrust).use { payer ->
            expectReason(Reason.UNKNOWN_ISSUER) { payer.negotiate() }
        }
    }

    @Test
    fun wrongAmountAndReplayedBundleAreAborted() = runTest {
        val payee = TestPayee()
        val inbox = MemoryInbox()
        val note = TestNote(10_000)

        // Payer tries to pay less than requested → payee aborts with AMOUNT.
        val events = ArrayList<PayeeEvent>()
        PayerSession(Loopback(payeeSession(payee, inbox, 5_000, events)), trust).use { payer ->
            val offer = payer.negotiate()
            val (pid, bundle) = buildBundle(offer, listOf(note), listOf(4_000))
            expectReason(Reason.PEER_ABORTED) { payer.pay(offer, pid, bundle) }
        }
        assertEquals(Reason.AMOUNT, (events.last() as PayeeEvent.Failed).reason)

        // A bundle captured from one session can't be replayed into another (transcript-bound).
        var captured: ByteArray? = null
        PayerSession(Loopback(payeeSession(payee, inbox, 5_000, ArrayList())), trust).use { payer ->
            val offer = payer.negotiate()
            val (pid, bundle) = buildBundle(offer, listOf(note), listOf(5_000))
            payer.pay(offer, pid, bundle)
            captured = bundle
        }
        val replayEvents = ArrayList<PayeeEvent>()
        PayerSession(Loopback(payeeSession(payee, inbox, 5_000, replayEvents)), trust).use { payer ->
            val offer = payer.negotiate()
            expectReason(Reason.PEER_ABORTED) { payer.pay(offer, SecureBytes.random(16), checkNotNull(captured)) }
        }
        assertEquals(Reason.TRANSCRIPT_MISMATCH, (replayEvents.last() as PayeeEvent.Failed).reason)
    }

    @Test
    fun spendSignedForAnotherReceiverIsRejected() = runTest {
        val payee = TestPayee()
        val events = ArrayList<PayeeEvent>()
        PayerSession(Loopback(payeeSession(payee, MemoryInbox(), 0, events)), trust).use { payer ->
            val offer = payer.negotiate()
            val note = TestNote(5_000)
            val paymentId = SecureBytes.random(16)
            val otherReceiver = SecureBytes.random(16)
            val body = spendSigBody(note.id, paymentId, otherReceiver, offer.transcript, 5_000, 5_000, now)
            val forged = PaymentBundle(
                paymentId, offer.transcript, offer.receiver.receiverId, 5_000, now,
                listOf(SpendItem(note.signedCert, 5_000, signWith(note.key.private, Domains.SPEND, body))),
            ).encode()
            expectReason(Reason.PEER_ABORTED) { payer.pay(offer, paymentId, forged) }
        }
        assertEquals(Reason.BAD_SPEND_SIGNATURE, (events.last() as PayeeEvent.Failed).reason)
    }

    private suspend fun expectReason(reason: Reason, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (e: OfflineProtocolException) {
            assertTrue("got ${e.reason}, expected $reason", e.reason == reason)
        }
    }
}
