package com.fortress.upi.core.offline.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

sealed interface SubmitResult {
    data object Settled : SubmitResult
    data object Duplicate : SubmitResult
    data object Conflict : SubmitResult
    class Rejected(val code: String) : SubmitResult
    class RetryLater(val reason: String) : SubmitResult
}

/** Offline settlement endpoint (services/offline-mesh via security-gateway). */
fun interface SettlementApi {
    suspend fun submit(bundle: ByteArray): SubmitResult
}

/**
 * HTTP adapter. The client passed in is [com.fortress.upi.core.security.network.SecureHttp]
 * with the [com.fortress.upi.core.security.binding.RequestSigner] interceptor, so every
 * submission is TLS 1.3 + SPKI-pinned + device-signed.
 */
class HttpSettlementApi(private val client: OkHttpClient, baseUrl: HttpUrl) : SettlementApi {
    private val endpoint: HttpUrl = baseUrl.newBuilder().encodedPath("/v1/offline/settlements").build()

    override suspend fun submit(bundle: ByteArray): SubmitResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint)
            .post(bundle.toRequestBody(FCE))
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                val json = resp.body?.string().orEmpty()
                when (resp.code) {
                    200 -> when (field(json, "outcome")) {
                        "SETTLED" -> SubmitResult.Settled
                        "DUPLICATE" -> SubmitResult.Duplicate
                        else -> SubmitResult.RetryLater("unexpected_outcome")
                    }
                    409 -> SubmitResult.Conflict
                    413, 415, 422 -> SubmitResult.Rejected(field(json, "error") ?: "rejected")
                    else -> SubmitResult.RetryLater("http_${resp.code}")
                }
            }
        } catch (e: IOException) {
            SubmitResult.RetryLater("io")
        }
    }

    private fun field(json: String, name: String): String? = try {
        JSONObject(json).optString(name).takeIf { it.isNotEmpty() }
    } catch (e: JSONException) {
        null
    }

    private companion object {
        val FCE = "application/vnd.fortress.fce".toMediaType()
    }
}
