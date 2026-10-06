package com.fortress.upi.upi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpiUriTest {

    @Test
    fun parsesMerchantQr() {
        val p = UpiUri.parse("upi://pay?pa=asha.store@okbank&pn=Asha%20Kirana&am=73.50&cu=INR&mc=5411&tr=ORD-91&tn=Groceries")!!
        assertEquals("asha.store@okbank", p.payeeVpa)
        assertEquals("Asha Kirana", p.payeeName)
        assertEquals(7_350L, p.amountPaise)
        assertEquals("5411", p.merchantCode)
    }

    @Test
    fun amountIsOptionalForP2p() {
        assertNull(UpiUri.parse("upi://pay?pa=ravi@upi")!!.amountPaise)
    }

    @Test
    fun rejectsAttacks() {
        // Parameter pollution: a second `pa` could be honoured by a different parser downstream.
        assertNull(UpiUri.parse("upi://pay?pa=good@bank&pa=evil@bank"))
        assertNull(UpiUri.parse("upi://pay?pa=good@bank&am=-5"))
        assertNull(UpiUri.parse("upi://pay?pa=good@bank&am=1e3"))
        assertNull(UpiUri.parse("upi://pay?pa=good@bank&cu=USD"))
        assertNull(UpiUri.parse("upi://pay?pa=not-a-vpa"))
        assertNull(UpiUri.parse("https://pay?pa=good@bank"))
        assertNull(UpiUri.parse("upi://mandate?pa=good@bank"))
        assertNull(UpiUri.parse("upi://pay?pa=good@bank&tn=%ZZ"))
        assertNull(UpiUri.parse("upi://pay?pa=good@bank&x=" + "a".repeat(3_000)))
    }

    @Test
    fun stripsBidiSpoofingFromNames() {
        // U+202E (RTL override) could render "Store" text reversed to impersonate another payee.
        val p = UpiUri.parse("upi://pay?pa=a.b@bank&pn=Safe%E2%80%AEerotS")!!
        assertEquals("SafeerotS", p.payeeName)
        assertTrue(p.payeeName!!.none { Character.getType(it) == Character.FORMAT.toInt() })
    }
}
