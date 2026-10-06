package com.fortress.upi.ui.dashboard

/**
 * DEMO DATA — stands in for the PSP account/transaction APIs, which arrive with
 * the feature/upi-online module. All names and handles are fictional.
 */
data class DemoContact(val id: String, val name: String, val vpa: String)

enum class TxStatus { SETTLED, OFFLINE_PENDING, FAILED }

data class DemoTx(
    val id: String,
    val counterparty: String,
    val subtitle: String,
    val amountPaise: Long,
    val credit: Boolean,
    val status: TxStatus,
)

internal object DemoData {
    const val BANK_BALANCE_PAISE = 18_42_550_00L
    const val MONTH_SPEND_PAISE = 46_210_00L

    /** Daily spend (₹) for the sparkline, last 14 days. */
    val spendSeries = floatArrayOf(820f, 1450f, 300f, 2200f, 640f, 980f, 4100f, 1200f, 760f, 1900f, 520f, 3050f, 870f, 1340f)

    val contacts = listOf(
        DemoContact("c1", "Ananya Rao", "ananya.rao@okfortress"),
        DemoContact("c2", "Vikram Sethi", "vsethi@fortress"),
        DemoContact("c3", "Meera Nair", "meera.n@okbank"),
        DemoContact("c4", "Kabir Das", "kabir@fortress"),
        DemoContact("c5", "Asha Kirana", "asha.store@okbank"),
        DemoContact("c6", "Rohan Iyer", "rohan.iyer@fortress"),
        DemoContact("c7", "Zara Khan", "zara.k@okbank"),
    )

    val recent = listOf(
        DemoTx("t1", "Asha Kirana Store", "Offline · Tap & Pay", 7_300, credit = false, status = TxStatus.OFFLINE_PENDING),
        DemoTx("t2", "Ananya Rao", "Dinner split", 1_250_00, credit = true, status = TxStatus.SETTLED),
        DemoTx("t3", "Metro Card Recharge", "Bill payment", 500_00, credit = false, status = TxStatus.SETTLED),
        DemoTx("t4", "Vikram Sethi", "Rent share", 18_000_00, credit = false, status = TxStatus.SETTLED),
        DemoTx("t5", "Chai Point", "Merchant · QR", 140_00, credit = false, status = TxStatus.SETTLED),
        DemoTx("t6", "Salary — Fortress Labs", "NEFT credit", 1_42_000_00, credit = true, status = TxStatus.SETTLED),
    )
}

/** Demo-mode profile shown before a bank account is linked through the PSP. Fictional. */
internal val DemoProfile = com.fortress.upi.data.UserProfile(name = "Aarav Mehta", vpa = "aarav@fortress", mobile = "+91 98765 43210")
