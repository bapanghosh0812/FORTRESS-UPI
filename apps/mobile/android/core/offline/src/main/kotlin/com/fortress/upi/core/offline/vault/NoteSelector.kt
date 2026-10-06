package com.fortress.upi.core.offline.vault

/** A note chosen for a payment and how much of it is applied. */
class NotePick<N>(val note: N, val appliedPaise: Long)

/**
 * Chooses notes for an amount, minimizing stranded value (overpay that must
 * wait for settlement to be refunded), then the number of signatures.
 *
 * Exact 0/1 knapsack over denomination units (gcd of all denominations, ₹10 by
 * default) — vaults hold tens of notes, so the table is tiny (< 1 ms).
 */
object NoteSelector {
    private const val UNREACHABLE = Int.MAX_VALUE

    fun <N> select(
        notes: List<N>,
        amountPaise: Long,
        denomination: (N) -> Long,
        maxNotes: Int = 16,
    ): List<NotePick<N>>? {
        if (amountPaise <= 0 || notes.isEmpty()) return null
        val total = notes.sumOf(denomination)
        if (total < amountPaise) return null

        val unit = notes.fold(0L) { g, n -> gcd(g, denomination(n)) }
        val maxSum = (total / unit).toInt()
        val target = ((amountPaise + unit - 1) / unit).toInt()
        val units = IntArray(notes.size) { (denomination(notes[it]) / unit).toInt() }

        // best[i][s] = fewest notes among the first i that sum to exactly s units.
        val best = Array(notes.size + 1) { IntArray(maxSum + 1) { UNREACHABLE } }
        best[0][0] = 0
        for (i in 1..notes.size) {
            val d = units[i - 1]
            val prev = best[i - 1]
            val cur = best[i]
            for (s in 0..maxSum) {
                var b = prev[s]
                if (s >= d && prev[s - d] != UNREACHABLE) b = minOf(b, prev[s - d] + 1)
                cur[s] = b
            }
        }

        val chosenSum = (target..maxSum).firstOrNull { best[notes.size][it] <= maxNotes } ?: return null

        val picked = ArrayList<N>()
        var s = chosenSum
        for (i in notes.size downTo 1) {
            if (best[i][s] == best[i - 1][s]) continue
            picked += notes[i - 1]
            s -= units[i - 1]
        }

        // Apply largest notes in full; only the last one is partial.
        var remaining = amountPaise
        return picked.sortedByDescending(denomination).map { n ->
            val applied = minOf(denomination(n), remaining)
            remaining -= applied
            NotePick(n, applied)
        }.also { picks -> check(remaining == 0L && picks.all { it.appliedPaise > 0 }) }
    }

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
}

/** Splits a vault load into notes, guaranteeing small "change" notes first. */
object DenominationPlanner {
    private val CHANGE_SET = longArrayOf(1_000, 2_000, 2_000, 5_000, 10_000) // ₹10, ₹20, ₹20, ₹50, ₹100
    private val LADDER = longArrayOf(50_000, 20_000, 10_000, 5_000, 2_000, 1_000) // ₹500 … ₹10

    fun plan(totalPaise: Long): List<Long> {
        require(totalPaise > 0 && totalPaise % 1_000 == 0L) { "vault loads are positive multiples of ₹10" }
        val out = ArrayList<Long>()
        var remaining = totalPaise
        for (d in CHANGE_SET) {
            if (remaining >= d) {
                out += d
                remaining -= d
            }
        }
        for (d in LADDER) {
            while (remaining >= d) {
                out += d
                remaining -= d
            }
        }
        return out.sortedDescending()
    }
}
