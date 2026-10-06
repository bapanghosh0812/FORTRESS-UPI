package com.fortress.upi.data

import com.fortress.upi.core.crypto.SecureBytes
import com.fortress.upi.core.crypto.toHex
import com.fortress.upi.core.offline.vault.IssuedNote
import com.fortress.upi.core.offline.vault.PreparedNoteKey
import com.fortress.upi.core.security.binding.BindingApi
import com.fortress.upi.core.security.binding.BindingChallenge
import com.fortress.upi.core.security.binding.BindingCompletion
import com.fortress.upi.core.security.binding.BindingResult
import com.fortress.upi.upi.UpiPaymentIntent
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

class BankAccount(val ref: String, val bankName: String, val maskedNumber: String, val primary: Boolean)

class TxRecord(
    val id: String,
    val counterparty: String,
    val note: String,
    val amountPaise: Long,
    val credit: Boolean,
    val atMillis: Long,
    val status: String,
    val upiRef: String?,
)

enum class PaymentState { CREATED, PENDING, SUCCESS, FAILED, DEEMED }

class PaymentResult(val id: String, val state: PaymentState, val upiRef: String?, val failureReason: String?)

private val B64 = Base64.getEncoder()
private val B64D = Base64.getDecoder()

/** Online UPI payments via the Fortress payments service (→ sponsor PSP → NPCI). */
class PaymentsRepository(private val api: ApiClient, private val cl: CommonLibrary) {

    suspend fun accounts(): List<BankAccount> = api.get("/v1/accounts").getJSONArray("accounts").map {
        BankAccount(it.getString("ref"), it.getString("bank"), it.getString("masked"), it.optBoolean("primary"))
    }

    suspend fun transactions(): List<TxRecord> = api.get("/v1/transactions").getJSONArray("transactions").map {
        TxRecord(
            id = it.getString("id"),
            counterparty = it.getString("counterparty"),
            note = it.optString("note"),
            amountPaise = it.getLong("amountPaise"),
            credit = it.optBoolean("credit"),
            atMillis = it.getLong("at"),
            status = it.getString("status"),
            upiRef = it.optString("upiRef").ifEmpty { null },
        )
    }

    /**
     * Full online payment: create (idempotent) → NPCI CL PIN capture → submit →
     * poll until final. Returns null if the user cancelled on the PIN screen.
     */
    suspend fun pay(intent: UpiPaymentIntent, amountPaise: Long, account: BankAccount): PaymentResult? {
        val idempotency = SecureBytes.random(16).toHex()
        val created = api.post(
            "/v1/payments",
            JSONObject()
                .put("payeeVpa", intent.payeeVpa)
                .put("payeeName", intent.payeeName ?: JSONObject.NULL)
                .put("amountPaise", amountPaise)
                .put("note", intent.note ?: JSONObject.NULL)
                .put("merchantCode", intent.merchantCode ?: JSONObject.NULL)
                .put("txnRef", intent.transactionRef ?: JSONObject.NULL)
                .put("accountRef", account.ref),
            idempotencyKey = idempotency,
        )
        val id = created.getString("id")
        val credential = cl.authorize(
            CredentialRequest(id, intent.payeeVpa, intent.payeeName, amountPaise, account.ref, created.getString("clChallenge")),
        ) ?: return null

        api.post("/v1/payments/$id/authorize", JSONObject().put("credential", credential.payload), idempotencyKey = "$idempotency-auth")
        return poll(id)
    }

    private suspend fun poll(id: String): PaymentResult {
        var wait = 400L
        repeat(MAX_POLLS) {
            val s = api.get("/v1/payments/$id")
            val state = PaymentState.valueOf(s.getString("state"))
            if (state != PaymentState.PENDING && state != PaymentState.CREATED) {
                return PaymentResult(id, state, s.optString("upiRef").ifEmpty { null }, s.optString("reason").ifEmpty { null })
            }
            delay(wait)
            wait = (wait * 3 / 2).coerceAtMost(3_000)
        }
        // NPCI "deemed" handling: the status check continues server-side and the user is notified.
        return PaymentResult(id, PaymentState.DEEMED, null, null)
    }

    private companion object {
        const val MAX_POLLS = 20
    }
}

/** HTTP implementation of the device-binding contract (core:security). */
class HttpBindingApi(private val api: ApiClient) : BindingApi {
    override suspend fun begin(): BindingChallenge {
        val r = api.post("/v1/bind/begin", JSONObject())
        return BindingChallenge(
            sessionId = B64D.decode(r.getString("sessionId")),
            attestationChallenge = B64D.decode(r.getString("challenge")),
            smsToken = r.getString("smsToken"),
            virtualNumber = r.getString("virtualNumber"),
        )
    }

    override suspend fun complete(request: BindingCompletion): BindingResult {
        val r = api.post(
            "/v1/bind/complete",
            JSONObject()
                .put("payload", B64.encodeToString(request.payload))
                .put("identitySignature", B64.encodeToString(request.identitySignature))
                .put("identityChain", JSONArray(request.identityChain.map(B64::encodeToString)))
                .put("requestChain", JSONArray(request.requestChain.map(B64::encodeToString)))
                .put("integrityToken", request.integrityToken)
                .put("riskReport", B64.encodeToString(request.riskReport)),
        )
        return BindingResult(
            deviceId = r.getString("deviceId"),
            receiverCertificate = B64D.decode(r.getString("receiverCertificate")),
            receiverId = B64D.decode(r.getString("receiverId")),
        )
    }
}

/** Offline vault issuance: attested single-use keys in, issuer-signed note certificates out. */
class OfflineIssuerRepository(private val api: ApiClient) {
    suspend fun challenge(): ByteArray = B64D.decode(api.post("/v1/offline/issuance/challenge", JSONObject()).getString("challenge"))

    suspend fun issue(challenge: ByteArray, keys: List<PreparedNoteKey>, accountRef: String): List<IssuedNote> {
        val body = JSONObject()
            .put("challenge", B64.encodeToString(challenge))
            .put("accountRef", accountRef)
            .put(
                "notes",
                JSONArray(
                    keys.map { k ->
                        JSONObject()
                            .put("keyAlias", k.keyAlias)
                            .put("denominationPaise", k.denominationPaise)
                            .put("publicKey", B64.encodeToString(k.publicKeySpki))
                            .put("attestationChain", JSONArray(k.attestationChain.map(B64::encodeToString)))
                    },
                ),
            )
        return api.post("/v1/offline/issuance", body, idempotencyKey = B64.encodeToString(challenge))
            .getJSONArray("certificates")
            .map { IssuedNote(it.getString("keyAlias"), B64D.decode(it.getString("certificate"))) }
    }
}

private inline fun <T> JSONArray.map(transform: (JSONObject) -> T): List<T> = List(length()) { transform(getJSONObject(it)) }
