package com.fortress.upi.core.security.integrity

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.Base64

/**
 * Play Integrity (Standard API). Tokens are bound to a request hash so a token
 * minted for one payload can't be replayed for another. The **server** decrypts
 * and enforces the verdict (MEETS_STRONG_INTEGRITY for payments, PLAY_RECOGNIZED
 * app, licensed account); the client never interprets it.
 */
class PlayIntegrityClient(context: Context, private val cloudProjectNumber: Long) {
    private val manager = IntegrityManagerFactory.createStandard(context.applicationContext)
    private val mutex = Mutex()

    @Volatile
    private var provider: StandardIntegrityTokenProvider? = null

    /** Call early (app start) — preparation can take seconds; requests are then ~100 ms. */
    suspend fun warmUp() {
        mutex.withLock {
            provider = manager.prepareIntegrityToken(
                PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(cloudProjectNumber).build(),
            ).await()
        }
    }

    suspend fun requestToken(requestHash: ByteArray): String {
        val hash = Base64.getUrlEncoder().withoutPadding().encodeToString(requestHash)
        return try {
            request(hash)
        } catch (e: StandardIntegrityException) {
            if (e.errorCode != StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID) throw e
            warmUp() // provider expired: re-prepare once
            request(hash)
        }
    }

    private suspend fun request(hash: String): String {
        val p = provider ?: run {
            warmUp()
            checkNotNull(provider)
        }
        return p.request(StandardIntegrityTokenRequest.builder().setRequestHash(hash).build()).await().token()
    }
}
