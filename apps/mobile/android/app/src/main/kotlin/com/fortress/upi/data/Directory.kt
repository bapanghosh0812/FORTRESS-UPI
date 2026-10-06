package com.fortress.upi.data

import android.net.Uri

/**
 * Payment limits published by the server. They encode NPCI and sponsor-bank
 * rules (and RBI's offline caps), so the app never hard-codes them: when a
 * regulator changes a cap, the server changes and every app follows.
 */
data class PaymentLimits(
    val perTxnUpiPaise: Long,
    val dailyUpiPaise: Long,
    val dailyCount: Int,
    val offlinePerTxnPaise: Long,
    val offlineWalletPaise: Long,
    val newDeviceCapPaise: Long,
    val source: String,
    val fetchedAtMillis: Long,
) {
    companion object {
        /**
         * Used only until the first successful fetch. Values mirror the caps in
         * force when this build was made (UPI ₹1 lakh/day; UPI Lite ₹1,000 per
         * payment, ₹5,000 balance); the server is the source of truth.
         */
        val FALLBACK = PaymentLimits(
            perTxnUpiPaise = 1_00_000_00,
            dailyUpiPaise = 1_00_000_00,
            dailyCount = 20,
            offlinePerTxnPaise = 1_000_00,
            offlineWalletPaise = 5_000_00,
            newDeviceCapPaise = 5_000_00,
            source = "Built-in defaults",
            fetchedAtMillis = 0,
        )
    }
}

/** A payee whose name the PSP confirmed with the payee's bank. */
data class VerifiedPayee(val vpa: String, val name: String)

class UserProfile(val name: String, val vpa: String, val mobile: String)

/** Limits, profile and payee lookups (validate-address / mobile mapper via the sponsor PSP). */
class DirectoryRepository(private val api: ApiClient) {

    suspend fun limits(nowMillis: Long): PaymentLimits = api.get("/v1/limits").let {
        PaymentLimits(
            perTxnUpiPaise = it.getLong("perTxnUpiPaise"),
            dailyUpiPaise = it.getLong("dailyUpiPaise"),
            dailyCount = it.getInt("dailyCount"),
            offlinePerTxnPaise = it.getLong("offlinePerTxnPaise"),
            offlineWalletPaise = it.getLong("offlineWalletPaise"),
            newDeviceCapPaise = it.optLong("newDeviceCapPaise", PaymentLimits.FALLBACK.newDeviceCapPaise),
            source = it.optString("source", "Fortress policy service"),
            fetchedAtMillis = nowMillis,
        )
    }

    suspend fun profile(): UserProfile = api.get("/v1/profile").let { UserProfile(it.getString("name"), it.getString("vpa"), it.getString("mobile")) }

    /** UPI "validate address": returns the registered name, or throws ApiException(404, "vpa_not_found"). */
    suspend fun verifyVpa(vpa: String): VerifiedPayee =
        api.get("/v1/directory/vpa/${Uri.encode(vpa)}").let { VerifiedPayee(it.getString("vpa"), it.getString("name")) }

    /** UPI number / mobile mapper lookup. */
    suspend fun resolveMobile(mobile: String): VerifiedPayee =
        api.get("/v1/directory/mobile/${Uri.encode(mobile)}").let { VerifiedPayee(it.getString("vpa"), it.getString("name")) }

    companion object {
        private val VPA = Regex("^[A-Za-z0-9._-]{2,256}@[A-Za-z][A-Za-z0-9.]{1,64}$")
        private val MOBILE = Regex("^[6-9][0-9]{9}$")
        private val IFSC = Regex("^[A-Z]{4}0[A-Z0-9]{6}$")
        private val ACCOUNT = Regex("^[0-9]{9,18}$")

        fun isVpa(v: String) = VPA.matches(v)
        fun isMobile(v: String) = MOBILE.matches(v)
        fun isIfsc(v: String) = IFSC.matches(v)
        fun isAccount(v: String) = ACCOUNT.matches(v)

        /** NPCI's virtual address for an account + IFSC payment. */
        fun accountVpa(account: String, ifsc: String) = "$account@$ifsc.ifsc.npci"
    }
}
