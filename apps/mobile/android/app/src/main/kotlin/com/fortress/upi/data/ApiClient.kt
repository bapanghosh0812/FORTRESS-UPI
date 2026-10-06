package com.fortress.upi.data

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

/** A failed API call. [status] is the HTTP status (0 for transport failures); [code] is the server's stable error code. */
class ApiException(val status: Int, val code: String) : IOException("api error $status/$code")

/**
 * Minimal JSON-over-HTTPS client on top of the hardened [OkHttpClient]
 * (TLS 1.3, pinned, every call device-signed). Payloads are small, so
 * Android's built-in org.json keeps this dependency-free.
 */
class ApiClient(private val http: OkHttpClient, private val base: HttpUrl) {

    suspend fun get(path: String): JSONObject = call(Request.Builder().url(url(path)).get().build())

    suspend fun post(path: String, body: JSONObject, idempotencyKey: String? = null): JSONObject =
        call(
            Request.Builder()
                .url(url(path))
                .post(body.toString().toRequestBody(JSON))
                .apply { idempotencyKey?.let { header("Idempotency-Key", it) } }
                .build(),
        )

    private fun url(path: String): HttpUrl = requireNotNull(base.resolve(path)) { "bad path $path" }

    private suspend fun call(request: Request): JSONObject = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = try {
                    if (text.isBlank()) JSONObject() else JSONObject(text)
                } catch (e: JSONException) {
                    throw ApiException(resp.code, "malformed_response")
                }
                if (!resp.isSuccessful) throw ApiException(resp.code, json.optString("error", "http_${resp.code}"))
                json
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException(0, "network")
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
