package com.fortress.upi.core.security.binding

import android.content.Intent
import android.net.Uri
import com.fortress.upi.core.crypto.Clock
import com.fortress.upi.core.crypto.Domains
import com.fortress.upi.core.crypto.Fce
import com.fortress.upi.core.crypto.constantTimeEquals
import com.fortress.upi.core.crypto.sha256
import com.fortress.upi.core.security.integrity.PlayIntegrityClient
import com.fortress.upi.core.security.integrity.RuntimeIntegrityScanner
import com.fortress.upi.core.security.keystore.SealedStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BindingChallenge(
    val sessionId: ByteArray,
    val attestationChallenge: ByteArray,
    val smsToken: String,
    val virtualNumber: String,
)

class BindingCompletion(
    val payload: ByteArray,
    val identitySignature: ByteArray,
    val identityChain: List<ByteArray>,
    val requestChain: List<ByteArray>,
    val integrityToken: String,
    val riskReport: ByteArray,
)

class BindingResult(val deviceId: String, val receiverCertificate: ByteArray, val receiverId: ByteArray)

/** Server contract for device binding (implemented over the pinned HTTP client). */
interface BindingApi {
    suspend fun begin(): BindingChallenge

    /** Long-polls until the SMS from the bound SIM has reached the operator gateway. */
    suspend fun complete(request: BindingCompletion): BindingResult
}

class Binding(
    val deviceId: String,
    val receiverCertificate: ByteArray,
    val receiverId: ByteArray,
    val simFingerprint: ByteArray,
)

/** Binding record, sealed under a hardware key in no-backup storage. */
class BindingStore(private val sealed: SealedStore) {
    fun load(): Binding? = sealed.read(SLOT)?.let { raw ->
        val m = Fce.decode(raw, 1, 2, 3, 4)
        Binding(m.string(1, 64), m.bytes(2), m.fixed(3, 16), m.fixed(4, 32))
    }

    fun save(b: Binding) = sealed.write(
        SLOT,
        Fce.Writer().string(1, b.deviceId).bytes(2, b.receiverCertificate).bytes(3, b.receiverId)
            .bytes(4, b.simFingerprint).build(),
    )

    fun clear() = sealed.delete(SLOT)

    private companion object {
        const val SLOT = "binding"
    }
}

enum class BindingStatus { BOUND, UNBOUND, SIM_CHANGED, SIM_UNVERIFIABLE }

class BindingException(message: String) : IllegalStateException(message)

/**
 * NPCI-style device binding:
 *  1. Server issues an attestation challenge + one-time SMS token.
 *  2. Device creates attested identity/request keys bound to that challenge.
 *  3. User sends the token by SMS **from the SIM being bound** (proves MSISDN possession).
 *  4. Device proves key possession (identity signature), integrity (Play Integrity
 *     token bound to the payload hash) and reports RASP signals; the server verifies the
 *     attestation chains to Google's roots and only then activates the device.
 */
class DeviceBindingManager(
    private val identity: DeviceIdentity,
    private val sim: SimBindingMonitor,
    private val integrity: PlayIntegrityClient,
    private val scanner: RuntimeIntegrityScanner,
    private val api: BindingApi,
    private val store: BindingStore,
    private val clock: Clock,
) {
    class Pending internal constructor(val challenge: BindingChallenge, val smsIntent: Intent)

    suspend fun start(): Pending {
        val challenge = api.begin()
        withContext(Dispatchers.IO) { identity.regenerate(challenge.attestationChallenge) }
        // ACTION_SENDTO needs no SEND_SMS permission; the user confirms in the SMS app,
        // which also lets them pick the SIM on dual-SIM phones.
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", challenge.virtualNumber, null))
            .putExtra("sms_body", "FORTRESS ${challenge.smsToken}")
        return Pending(challenge, intent)
    }

    suspend fun finish(pending: Pending): BindingResult = withContext(Dispatchers.IO) {
        val simFingerprint = sim.currentFingerprint()
            ?: throw BindingException("phone state permission is required for SIM binding")
        val material = identity.material()
        val payload = Fce.Writer()
            .bytes(1, pending.challenge.sessionId)
            .bytes(2, material.identitySpki)
            .bytes(3, material.requestSpki)
            .bytes(4, simFingerprint)
            .u64(5, clock.nowMillis())
            .build()
        val completion = BindingCompletion(
            payload = payload,
            identitySignature = identity.signWithIdentity(Domains.BINDING, payload),
            identityChain = material.identityChain,
            requestChain = material.requestChain,
            integrityToken = integrity.requestToken(sha256(payload)),
            riskReport = scanner.scan().encode(),
        )
        val result = api.complete(completion)
        store.save(Binding(result.deviceId, result.receiverCertificate, result.receiverId, simFingerprint))
        result
    }

    /** Called on every cold start and resume. A changed SIM revokes all local credentials. */
    fun status(): BindingStatus {
        val binding = store.load() ?: return BindingStatus.UNBOUND
        if (!identity.isProvisioned()) {
            revoke()
            return BindingStatus.UNBOUND
        }
        val fingerprint = sim.currentFingerprint() ?: return BindingStatus.SIM_UNVERIFIABLE
        if (!fingerprint.constantTimeEquals(binding.simFingerprint)) {
            revoke()
            return BindingStatus.SIM_CHANGED
        }
        return BindingStatus.BOUND
    }

    fun revoke() {
        identity.destroy()
        store.clear()
    }
}
