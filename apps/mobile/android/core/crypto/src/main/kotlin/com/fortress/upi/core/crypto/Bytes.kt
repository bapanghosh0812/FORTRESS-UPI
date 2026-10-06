package com.fortress.upi.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom

/** CSPRNG access. One instance per process; SecureRandom is thread-safe. */
object SecureBytes {
    private val rng = SecureRandom()

    fun random(size: Int): ByteArray = ByteArray(size).also(rng::nextBytes)
}

/** Wall-clock source, injectable for tests. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM: Clock = Clock { System.currentTimeMillis() }
    }
}

fun sha256(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    for (p in parts) md.update(p)
    return md.digest()
}

/** Constant-time comparison for secrets, MACs and identifiers. */
fun ByteArray.constantTimeEquals(other: ByteArray): Boolean = MessageDigest.isEqual(this, other)

/** Overwrites the array. JVM copies made elsewhere are out of reach — keep secrets short-lived. */
fun ByteArray.wipe() = fill(0)

inline fun <T> ByteArray.useThenWipe(block: (ByteArray) -> T): T =
    try {
        block(this)
    } finally {
        wipe()
    }

private val HEX = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    forEachIndexed { i, b ->
        out[i * 2] = HEX[(b.toInt() shr 4) and 0x0F]
        out[i * 2 + 1] = HEX[b.toInt() and 0x0F]
    }
    return String(out)
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i ->
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        require(hi >= 0 && lo >= 0) { "invalid hex" }
        ((hi shl 4) or lo).toByte()
    }
}
