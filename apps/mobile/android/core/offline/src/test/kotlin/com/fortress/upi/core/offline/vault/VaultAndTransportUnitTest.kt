package com.fortress.upi.core.offline.vault

import com.fortress.upi.core.offline.transport.ChunkFraming
import com.fortress.upi.core.offline.transport.Reassembler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class VaultAndTransportUnitTest {

    @Test
    fun plannerAlwaysIncludesChangeNotes() {
        val plan = DenominationPlanner.plan(200_000) // ₹2,000
        assertEquals(200_000L, plan.sum())
        assertEquals(listOf(50_000L, 50_000L, 50_000L, 20_000L, 10_000L, 10_000L, 5_000L, 2_000L, 2_000L, 1_000L), plan)
    }

    @Test
    fun selectorMinimisesStrandedValueThenNoteCount() {
        val notes = DenominationPlanner.plan(200_000)
        // ₹73 → ₹50 + ₹20 + ₹10 = ₹80 (₹7 stranded until settlement refunds it).
        val picks = checkNotNull(NoteSelector.select(notes, 7_300, { it }))
        assertEquals(listOf(5_000L, 2_000L, 1_000L), picks.map { it.note })
        assertEquals(listOf(5_000L, 2_000L, 300L), picks.map { it.appliedPaise })

        // Exact amounts use exact notes.
        assertEquals(listOf(50_000L, 20_000L), checkNotNull(NoteSelector.select(notes, 70_000, { it })).map { it.note })

        // Insufficient balance → null.
        assertNull(NoteSelector.select(notes, 200_001, { it }))
    }

    @Test
    fun framingRoundTripsAndEnforcesLimits() {
        val message = ByteArray(1_000) { it.toByte() }
        val r = Reassembler()
        val chunks = ChunkFraming.split(message, 100)
        val out = chunks.map { r.accept(it) }
        assertNull(out.first())
        assertArrayEquals(message, out.last())

        val small = Reassembler(limit = 10)
        try {
            ChunkFraming.split(ByteArray(50), 20).forEach { small.accept(it) }
            throw AssertionError("oversized message accepted")
        } catch (e: IOException) {
            // expected
        }
    }
}
