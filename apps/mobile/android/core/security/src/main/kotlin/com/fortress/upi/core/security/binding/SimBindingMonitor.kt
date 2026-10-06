package com.fortress.upi.core.security.binding

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.fortress.upi.core.crypto.Fce
import com.fortress.upi.core.crypto.sha256

/**
 * Detects SIM swaps/removal so a stolen phone with a new SIM (or a device
 * whose bound SIM was ported) loses its binding immediately.
 *
 * Android 10+ hides ICCID/IMEI from non-privileged apps, so the *authoritative*
 * SIM binding is the server-side SMS proof (the bound MSISDN sent the binding
 * token). Locally we fingerprint the subscription metadata the OS still
 * exposes; any change forces re-binding.
 */
class SimBindingMonitor(private val context: Context) {

    /** SHA-256 over active subscriptions, or null when the permission is missing. */
    @SuppressLint("MissingPermission") // checked explicitly on the first line
    fun currentFingerprint(): ByteArray? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return null
        val subs = try {
            sm.activeSubscriptionInfoList.orEmpty()
        } catch (e: SecurityException) {
            return null
        }
        val items = subs.sortedBy { it.simSlotIndex }.map { s ->
            listOf(s.simSlotIndex, s.subscriptionId, s.carrierId, s.cardId, s.mccString, s.mncString)
                .joinToString("|")
                .toByteArray(Charsets.UTF_8)
        }
        return sha256(Fce.Writer().list(1, items).build())
    }
}
