package com.fortress.upi.core.offline.transport

import java.io.ByteArrayOutputStream
import java.io.IOException

/** Initiator side (payer): one request → one response, over BLE GATT or NFC IsoDep. */
interface OfflineTransport : AutoCloseable {
    suspend fun exchange(request: ByteArray): ByteArray
}

/** Responder side (payee): transports deliver whole messages and send back the reply. */
fun interface OfflineResponder {
    suspend fun respond(request: ByteArray): ByteArray
}

/** Transport-level chunking: `flags:u8 || data`, bit 0 = final chunk. */
internal object ChunkFraming {
    const val FLAG_FINAL = 0x01
    const val MAX_MESSAGE = 32 * 1024

    fun split(message: ByteArray, maxChunk: Int): List<ByteArray> {
        require(maxChunk >= 2) { "chunk size too small" }
        require(message.size <= MAX_MESSAGE) { "message too large" }
        val payload = maxChunk - 1
        if (message.isEmpty()) return listOf(byteArrayOf(FLAG_FINAL.toByte()))
        return (message.indices step payload).map { start ->
            val end = minOf(start + payload, message.size)
            val flags = if (end == message.size) FLAG_FINAL else 0
            byteArrayOf(flags.toByte()) + message.copyOfRange(start, end)
        }
    }
}

/** Reassembles chunks with a hard size cap (memory-exhaustion defense). Not thread-safe. */
internal class Reassembler(private val limit: Int = ChunkFraming.MAX_MESSAGE) {
    private val buf = ByteArrayOutputStream()

    /** Returns the full message when the final chunk arrives, else null. */
    fun accept(chunk: ByteArray): ByteArray? {
        if (chunk.isEmpty()) throw IOException("empty chunk")
        val flags = chunk[0].toInt() and 0xFF
        if (flags and ChunkFraming.FLAG_FINAL.inv() != 0) {
            reset()
            throw IOException("unknown chunk flags")
        }
        if (buf.size() + chunk.size - 1 > limit) {
            reset()
            throw IOException("message exceeds limit")
        }
        buf.write(chunk, 1, chunk.size - 1)
        if (flags and ChunkFraming.FLAG_FINAL == 0) return null
        return buf.toByteArray().also { reset() }
    }

    fun reset() = buf.reset()
}
