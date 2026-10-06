package com.fortress.upi.core.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF-SHA256 (RFC 5869). Verified against the RFC test vectors in HkdfTest. */
object Hkdf {
    private const val ALG = "HmacSHA256"
    private const val HASH_LEN = 32

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALG)
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, ALG))
        return mac.doFinal(ikm)
    }

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_LEN) { "invalid HKDF output length" }
        val mac = Mac.getInstance(ALG)
        mac.init(SecretKeySpec(prk, ALG))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t.wipe()
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            t.copyInto(out, destinationOffset = pos, endIndex = n)
            pos += n
            counter++
        }
        t.wipe()
        return out
    }
}
