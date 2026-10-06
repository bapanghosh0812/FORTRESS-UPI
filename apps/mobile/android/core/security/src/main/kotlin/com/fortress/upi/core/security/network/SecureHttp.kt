package com.fortress.upi.core.security.network

import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.util.concurrent.TimeUnit

/**
 * The only HTTP client Fortress uses.
 *  * TLS 1.3 only, restricted cipher suites.
 *  * Certificate pinning comes from `network_security_config.xml` (platform trust
 *    manager), so pins apply to every stack in the process, not just OkHttp.
 *  * No silent retries or redirects: payment calls retry explicitly with the
 *    same idempotency key, never implicitly.
 */
object SecureHttp {
    fun client(vararg interceptors: Interceptor): OkHttpClient = OkHttpClient.Builder()
        .connectionSpecs(
            listOf(
                ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
                    .tlsVersions(TlsVersion.TLS_1_3)
                    .build(),
            ),
        )
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .apply { interceptors.forEach(::addInterceptor) }
        .build()
}
