package com.fortress.upi.core.security.binding

import com.fortress.upi.core.crypto.Clock
import com.fortress.upi.core.crypto.Domains
import com.fortress.upi.core.crypto.Fce
import com.fortress.upi.core.crypto.SecureBytes
import com.fortress.upi.core.crypto.sha256
import okhttp3.Interceptor
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.util.Base64

/**
 * Signs every API call with the hardware request key. Server twin:
 * services/security-gateway/internal/devicesig (golden vector shared in tests).
 *
 * Canonical request (FCE): {1 method, 2 encoded path, 3 encoded query,
 * 4 SHA-256(body), 5 timestamp ms, 6 nonce(16), 7 device id}.
 */
class RequestSigner(
    private val sign: (domain: String, body: ByteArray) -> ByteArray,
    private val deviceId: () -> String?,
    private val clock: Clock,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        // Unbound devices may only call binding endpoints, which carry their own proof.
        val id = deviceId() ?: return chain.proceed(original)

        val bodyBytes = original.body?.let { b -> Buffer().also(b::writeTo).readByteArray() } ?: ByteArray(0)
        val nonce = SecureBytes.random(NONCE_SIZE)
        val ts = clock.nowMillis()
        val canonical = canonicalRequest(
            method = original.method,
            encodedPath = original.url.encodedPath,
            encodedQuery = original.url.encodedQuery.orEmpty(),
            bodySha256 = sha256(bodyBytes),
            timestampMillis = ts,
            nonce = nonce,
            deviceId = id,
        )
        val signature = sign(Domains.REQUEST, canonical)

        val builder = original.newBuilder()
            .header(HEADER_DEVICE, id)
            .header(HEADER_TIMESTAMP, ts.toString())
            .header(HEADER_NONCE, B64.encodeToString(nonce))
            .header(HEADER_SIGNATURE, B64.encodeToString(signature))
        // Re-attach the exact bytes that were hashed (one-shot bodies can't be re-read).
        original.body?.let { builder.method(original.method, bodyBytes.toRequestBody(it.contentType())) }
        return chain.proceed(builder.build())
    }

    companion object {
        const val HEADER_DEVICE = "X-Fortress-Device"
        const val HEADER_TIMESTAMP = "X-Fortress-Timestamp"
        const val HEADER_NONCE = "X-Fortress-Nonce"
        const val HEADER_SIGNATURE = "X-Fortress-Signature"
        private const val NONCE_SIZE = 16
        private val B64: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        fun canonicalRequest(
            method: String,
            encodedPath: String,
            encodedQuery: String,
            bodySha256: ByteArray,
            timestampMillis: Long,
            nonce: ByteArray,
            deviceId: String,
        ): ByteArray = Fce.Writer()
            .string(1, method)
            .string(2, encodedPath)
            .string(3, encodedQuery)
            .bytes(4, bodySha256)
            .u64(5, timestampMillis)
            .bytes(6, nonce)
            .string(7, deviceId)
            .build()
    }
}
