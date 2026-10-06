package com.fortress.upi.core.offline.transport.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import com.fortress.upi.core.offline.transport.OfflineTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Payer side of NFC: ISO-DEP reader talking to the payee's HCE applet. */
class IsoDepTransport private constructor(private val isoDep: IsoDep) : OfflineTransport {

    override suspend fun exchange(request: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        require(request.isNotEmpty()) { "empty request" }
        var response = ByteArray(0)
        val chunks = (request.indices step Apdu.MAX_COMMAND_DATA).map {
            request.copyOfRange(it, minOf(it + Apdu.MAX_COMMAND_DATA, request.size))
        }
        chunks.forEachIndexed { i, chunk ->
            val last = i == chunks.lastIndex
            response = isoDep.transceive(
                Apdu.command(Apdu.CLA_PROPRIETARY, Apdu.INS_EXCHANGE, if (last) Apdu.P1_FINAL else Apdu.P1_MORE, 0, chunk),
            )
            if (!last && Apdu.statusWord(response) != 0x9000) throw IOException("receiver rejected chunk")
        }

        val out = ByteArrayOutputStream()
        while (true) {
            val sw = Apdu.statusWord(response)
            out.write(response, 0, response.size - 2)
            if (out.size() > Apdu.MAX_MESSAGE) throw IOException("response too large")
            when {
                sw == 0x9000 -> break
                (sw shr 8) == 0x61 -> response = isoDep.transceive(Apdu.GET_RESPONSE)
                else -> throw IOException("receiver status %04X".format(sw))
            }
        }
        out.toByteArray()
    }

    override fun close() {
        try {
            isoDep.close()
        } catch (e: IOException) {
            // Tag already left the field.
        }
    }

    companion object {
        private const val TIMEOUT_MS = 3_000

        /** Blocking; call from the reader callback thread. */
        fun open(tag: Tag): IsoDepTransport {
            val isoDep = IsoDep.get(tag) ?: throw IOException("not an ISO-DEP target")
            isoDep.connect()
            isoDep.timeout = TIMEOUT_MS
            val selected = isoDep.transceive(Apdu.select(Apdu.AID))
            if (Apdu.statusWord(selected) != 0x9000) {
                isoDep.close()
                throw IOException("receiver is not in Receive mode")
            }
            return IsoDepTransport(isoDep)
        }
    }
}

/**
 * Enables reader mode while the payer's "Tap to pay" screen is visible.
 * The payment is armed (amount confirmed + biometric) *before* the tap, so the
 * whole exchange completes in one ~300 ms touch.
 */
class NfcPayReader(
    private val activity: Activity,
    private val onConnected: (IsoDepTransport) -> Unit,
    private val onError: (Throwable) -> Unit,
) : NfcAdapter.ReaderCallback {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    val available: Boolean get() = adapter?.isEnabled == true

    fun enable() {
        adapter?.enableReaderMode(
            activity,
            this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, PRESENCE_CHECK_MS) },
        )
    }

    fun disable() {
        adapter?.disableReaderMode(activity)
    }

    override fun onTagDiscovered(tag: Tag) {
        try {
            onConnected(IsoDepTransport.open(tag))
        } catch (e: IOException) {
            onError(e)
        }
    }

    private companion object {
        const val PRESENCE_CHECK_MS = 250
    }
}
