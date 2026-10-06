package com.fortress.upi.upi

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** A validated `upi://pay` request. Everything here came from an untrusted QR or intent. */
data class UpiPaymentIntent(
    val payeeVpa: String,
    val payeeName: String?,
    /** Null when the payer must enter the amount. */
    val amountPaise: Long?,
    val note: String?,
    val merchantCode: String?,
    val transactionRef: String?,
    /** NPCI signed-QR parameters present (signature verified server-side before display as "Verified"). */
    val carriesSignature: Boolean,
)

/**
 * Strict UPI deep-link parser (NPCI linking spec subset). Rejects rather than
 * repairs: duplicate parameters (parameter-pollution attacks), unknown
 * currency, malformed VPAs, out-of-range amounts, oversized input, and
 * bidi/control characters that could visually spoof a payee name.
 */
object UpiUri {
    private const val MAX_URI = 2_048
    private const val MAX_NAME = 99
    private const val MAX_NOTE = 80
    /** Per-transaction ceiling enforced client-side for display; PSP/NPCI enforce the real limit. */
    private const val MAX_AMOUNT_PAISE = 10_000_000L * 100

    private val VPA = Regex("^[A-Za-z0-9._-]{2,256}@[A-Za-z][A-Za-z0-9]{1,64}$")
    private val AMOUNT = Regex("^\\d{1,9}(\\.\\d{1,2})?$")
    private val MCC = Regex("^\\d{4}$")
    private val TXN_REF = Regex("^[A-Za-z0-9._-]{1,35}$")
    private val SPOOF_CHARS = Regex("[\\p{Cc}\\p{Cf}\\u2028\\u2029]")

    fun parse(raw: String): UpiPaymentIntent? {
        if (raw.length > MAX_URI) return null
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd < 0 || !raw.substring(0, schemeEnd).equals("upi", ignoreCase = true)) return null
        val rest = raw.substring(schemeEnd + 3)
        val host = rest.substringBefore('?')
        if (!host.trimEnd('/').equals("pay", ignoreCase = true)) return null
        val params = parseQuery(rest.substringAfter('?', missingDelimiterValue = "")) ?: return null

        val vpa = params["pa"]?.takeIf(VPA::matches) ?: return null
        val currency = params["cu"]
        if (currency != null && currency != "INR") return null

        val amount = params["am"]?.let { am ->
            if (!AMOUNT.matches(am)) return null
            val (rupees, fraction) = am.split('.').let { it[0] to it.getOrElse(1) { "" }.padEnd(2, '0') }
            val paise = rupees.toLong() * 100 + fraction.toLong()
            if (paise <= 0 || paise > MAX_AMOUNT_PAISE) return null
            paise
        }

        val mc = params["mc"]?.also { if (!MCC.matches(it)) return null }
        val tr = params["tr"]?.also { if (!TXN_REF.matches(it)) return null }

        return UpiPaymentIntent(
            payeeVpa = vpa.lowercase(),
            payeeName = params["pn"]?.let { sanitize(it, MAX_NAME) },
            amountPaise = amount,
            note = params["tn"]?.let { sanitize(it, MAX_NOTE) },
            merchantCode = mc,
            transactionRef = tr,
            carriesSignature = params.containsKey("sign"),
        )
    }

    /** Returns null on duplicates or undecodable input. */
    private fun parseQuery(query: String): Map<String, String>? {
        if (query.isEmpty()) return null
        val out = HashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = pair.substringBefore('=').lowercase()
            val value = try {
                URLDecoder.decode(pair.substringAfter('=', missingDelimiterValue = ""), StandardCharsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (out.put(key, value) != null) return null
        }
        return out
    }

    private fun sanitize(s: String, max: Int): String? =
        s.replace(SPOOF_CHARS, "").trim().take(max).takeIf { it.isNotEmpty() }
}
