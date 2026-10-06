package com.fortress.upi.core.crypto

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Fortress Canonical Encoding v1 (FCE/1) — byte-for-byte twin of `shared/crypto/fce` (Go).
 *
 * ```
 * field   = tag:u8 || len:u32be || value
 * message = field*      (strictly ascending tags; unknown tags rejected; ≤ 64 KiB)
 * ```
 * Strict ordering makes the encoding canonical, so a signature can never be
 * re-interpreted over a different logical message. See docs/OFFLINE_PROTOCOL.md §2.
 */
object Fce {
    const val MAX_MESSAGE_SIZE: Int = 64 * 1024
    const val MAX_LIST_ITEMS: Int = 64
    private const val HEADER_SIZE = 5

    /** Builds an FCE message. Throws [FceException] on any contract violation. */
    class Writer {
        private val out = ByteArrayOutputStream()
        private var lastTag = -1

        fun bytes(tag: Int, value: ByteArray): Writer = apply {
            if (tag !in 0..255) throw FceException("tag out of range")
            if (tag <= lastTag) throw FceException("tags must be strictly ascending")
            if (value.size > MAX_MESSAGE_SIZE - HEADER_SIZE ||
                out.size() + HEADER_SIZE + value.size > MAX_MESSAGE_SIZE
            ) {
                throw FceException("message too large")
            }
            lastTag = tag
            out.write(tag)
            out.write(int32(value.size))
            out.write(value)
        }

        /** 8-byte big-endian; negative values are rejected (Go side is uint64 ≤ MaxInt64). */
        fun u64(tag: Int, value: Long): Writer {
            if (value < 0) throw FceException("u64 must be non-negative")
            return bytes(tag, ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
        }

        fun string(tag: Int, value: String): Writer = bytes(tag, value.toByteArray(StandardCharsets.UTF_8))

        fun list(tag: Int, items: List<ByteArray>): Writer {
            if (items.size > MAX_LIST_ITEMS) throw FceException("too many list items")
            val buf = ByteArrayOutputStream()
            buf.write(int32(items.size))
            for (item in items) {
                buf.write(int32(item.size))
                buf.write(item)
            }
            return bytes(tag, buf.toByteArray())
        }

        fun build(): ByteArray = out.toByteArray()
    }

    /** A strictly decoded message. Accessors validate sizes and throw [FceException]. */
    class Message internal constructor(private val fields: Map<Int, ByteArray>) {
        fun has(tag: Int): Boolean = fields.containsKey(tag)

        fun bytes(tag: Int): ByteArray = fields[tag] ?: throw FceException("missing tag $tag")

        fun bytes(tag: Int, minLen: Int, maxLen: Int): ByteArray {
            val v = bytes(tag)
            if (v.size < minLen || v.size > maxLen) throw FceException("invalid length for tag $tag")
            return v
        }

        fun fixed(tag: Int, size: Int): ByteArray = bytes(tag, size, size)

        fun u64(tag: Int): Long {
            val v = ByteBuffer.wrap(fixed(tag, Long.SIZE_BYTES)).long
            if (v < 0) throw FceException("u64 out of range")
            return v
        }

        fun string(tag: Int, maxLen: Int): String {
            val raw = bytes(tag, 0, maxLen)
            return try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString()
            } catch (e: CharacterCodingException) {
                throw FceException("invalid utf-8 in tag $tag")
            }
        }

        fun list(tag: Int): List<ByteArray> {
            val buf = ByteBuffer.wrap(bytes(tag))
            if (buf.remaining() < Int.SIZE_BYTES) throw FceException("truncated list")
            val count = buf.int
            if (count < 0 || count > MAX_LIST_ITEMS) throw FceException("too many list items")
            val items = ArrayList<ByteArray>(count)
            repeat(count) {
                if (buf.remaining() < Int.SIZE_BYTES) throw FceException("truncated list")
                val n = buf.int
                if (n < 0 || n > buf.remaining()) throw FceException("truncated list")
                items += ByteArray(n).also(buf::get)
            }
            if (buf.hasRemaining()) throw FceException("trailing bytes in list")
            return items
        }
    }

    /** Parses [input], accepting only the tags in [allowed]. */
    fun decode(input: ByteArray, vararg allowed: Int): Message {
        if (input.size > MAX_MESSAGE_SIZE) throw FceException("message too large")
        val allow = BooleanArray(256)
        allowed.forEach { allow[it] = true }
        val fields = HashMap<Int, ByteArray>(allowed.size * 2)
        val buf = ByteBuffer.wrap(input)
        var last = -1
        while (buf.hasRemaining()) {
            if (buf.remaining() < HEADER_SIZE) throw FceException("truncated header")
            val tag = buf.get().toInt() and 0xFF
            val len = buf.int
            if (len < 0 || len > buf.remaining()) throw FceException("truncated field")
            if (tag <= last) throw FceException("tags not strictly ascending")
            if (!allow[tag]) throw FceException("unknown tag $tag")
            last = tag
            fields[tag] = ByteArray(len).also(buf::get)
        }
        return Message(fields)
    }

    /** `ASCII(domain) || 0x00 || body` — the exact bytes hashed and signed. */
    fun signingInput(domain: String, body: ByteArray): ByteArray {
        val d = domain.toByteArray(StandardCharsets.US_ASCII)
        val out = ByteArray(d.size + 1 + body.size)
        d.copyInto(out)
        body.copyInto(out, destinationOffset = d.size + 1)
        return out
    }

    private fun int32(v: Int): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(v).array()
}

/** Malformed or non-canonical FCE input. Callers map it to a single generic error. */
class FceException(message: String) : IllegalArgumentException(message)
