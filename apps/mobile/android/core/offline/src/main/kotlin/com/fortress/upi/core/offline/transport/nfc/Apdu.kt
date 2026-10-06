package com.fortress.upi.core.offline.transport.nfc

import com.fortress.upi.core.crypto.hexToBytes
import java.io.IOException

/** ISO 7816-4 short APDU helpers for the Fortress HCE applet. */
internal object Apdu {
    /** Proprietary AID: F0 || "FORTRES". Registered in res/xml/fortress_apdu_service.xml. */
    val AID: ByteArray = "F0464F5254524553".hexToBytes()

    const val CLA_PROPRIETARY = 0x80
    const val INS_EXCHANGE = 0xD0
    const val INS_GET_RESPONSE = 0xC0
    const val P1_FINAL = 0x00
    const val P1_MORE = 0x01

    /** Command data per APDU (short APDUs carry ≤ 255; margin for readers). */
    const val MAX_COMMAND_DATA = 240

    /** Response data per APDU (≤ 256 for short APDUs). */
    const val MAX_RESPONSE_DATA = 250

    const val MAX_MESSAGE = 32 * 1024

    val SW_OK = sw(0x9000)
    val SW_WRONG_LENGTH = sw(0x6700)
    val SW_CONDITIONS_NOT_SATISFIED = sw(0x6985)
    val SW_FILE_NOT_FOUND = sw(0x6A82)
    val SW_INS_NOT_SUPPORTED = sw(0x6D00)
    val SW_UNKNOWN = sw(0x6F00)

    val GET_RESPONSE: ByteArray = byteArrayOf(0x00, INS_GET_RESPONSE.toByte(), 0x00, 0x00, 0x00)

    class Command(val cla: Int, val ins: Int, val p1: Int, val p2: Int, val data: ByteArray) {
        fun isSelect(aid: ByteArray): Boolean = cla == 0x00 && ins == 0xA4 && p1 == 0x04 && data.contentEquals(aid)
    }

    /** Parses short APDU cases 1–4; extended-length APDUs are rejected (null). */
    fun parse(raw: ByteArray): Command? {
        if (raw.size < 4) return null
        val cla = raw[0].toInt() and 0xFF
        val ins = raw[1].toInt() and 0xFF
        val p1 = raw[2].toInt() and 0xFF
        val p2 = raw[3].toInt() and 0xFF
        if (raw.size <= 5) return Command(cla, ins, p1, p2, ByteArray(0))
        val lc = raw[4].toInt() and 0xFF
        if (lc == 0) return null
        return when (raw.size) {
            5 + lc, 6 + lc -> Command(cla, ins, p1, p2, raw.copyOfRange(5, 5 + lc))
            else -> null
        }
    }

    fun command(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray): ByteArray {
        require(data.size in 1..255) { "short APDU data must be 1..255 bytes" }
        return byteArrayOf(cla.toByte(), ins.toByte(), p1.toByte(), p2.toByte(), data.size.toByte()) + data + 0x00.toByte()
    }

    fun select(aid: ByteArray): ByteArray = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid + 0x00.toByte()

    fun statusWord(response: ByteArray): Int {
        if (response.size < 2) throw IOException("short APDU response")
        return ((response[response.size - 2].toInt() and 0xFF) shl 8) or (response[response.size - 1].toInt() and 0xFF)
    }

    fun sw(value: Int): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())
}
