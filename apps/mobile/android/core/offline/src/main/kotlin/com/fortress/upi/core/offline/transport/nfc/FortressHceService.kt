package com.fortress.upi.core.offline.transport.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.fortress.upi.core.offline.transport.OfflineResponder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Process-wide switch: the HCE applet answers only while the user is on the
 * Receive screen (activate on resume, deactivate on pause). Otherwise SELECT
 * fails, so a phone in a pocket can never be "tapped" into a session.
 */
object ReceiveSessionRegistry {
    @Volatile
    private var factory: (suspend () -> OfflineResponder)? = null

    fun activate(responderFactory: suspend () -> OfflineResponder) {
        factory = responderFactory
    }

    fun deactivate() {
        factory = null
    }

    internal fun current(): (suspend () -> OfflineResponder)? = factory
}

/**
 * Payee side of NFC: Host Card Emulation. The payer's phone (reader mode)
 * SELECTs our AID, streams the request in `80 D0` chunks, then drains the
 * response via `61xx` / GET RESPONSE chaining. The manifest marks the service
 * `requireDeviceUnlock`, so a locked phone never responds.
 *
 * All state is touched on the main thread; protocol work runs on IO.
 */
class FortressHceService : HostApduService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val inbound = ByteArrayOutputStream()
    private var outbound = ByteArray(0)
    private var outboundOffset = 0
    private var selectedFactory: (suspend () -> OfflineResponder)? = null
    private var responder: OfflineResponder? = null
    private var job: Job? = null

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray? {
        val apdu = Apdu.parse(commandApdu) ?: return Apdu.SW_WRONG_LENGTH
        return when {
            apdu.isSelect(Apdu.AID) -> select()
            selectedFactory == null -> Apdu.SW_CONDITIONS_NOT_SATISFIED
            apdu.cla == Apdu.CLA_PROPRIETARY && apdu.ins == Apdu.INS_EXCHANGE -> exchange(apdu)
            apdu.ins == Apdu.INS_GET_RESPONSE -> nextChunk()
            else -> Apdu.SW_INS_NOT_SUPPORTED
        }
    }

    private fun select(): ByteArray {
        val factory = ReceiveSessionRegistry.current() ?: return Apdu.SW_FILE_NOT_FOUND
        resetSession()
        selectedFactory = factory
        return Apdu.SW_OK
    }

    private fun exchange(apdu: Apdu.Command): ByteArray? {
        if (job?.isActive == true) return Apdu.SW_CONDITIONS_NOT_SATISFIED
        if (inbound.size() + apdu.data.size > Apdu.MAX_MESSAGE) {
            resetSession()
            return Apdu.SW_WRONG_LENGTH
        }
        inbound.write(apdu.data, 0, apdu.data.size)
        if (apdu.p1 == Apdu.P1_MORE) return Apdu.SW_OK

        val message = inbound.toByteArray()
        inbound.reset()
        val factory = selectedFactory ?: return Apdu.SW_CONDITIONS_NOT_SATISFIED
        job = scope.launch {
            val reply = try {
                withContext(Dispatchers.IO) {
                    val r = responder ?: factory().also { responder = it }
                    r.respond(message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (reply == null) {
                sendResponseApdu(Apdu.SW_UNKNOWN)
            } else {
                outbound = reply
                outboundOffset = 0
                sendResponseApdu(nextChunk())
            }
        }
        return null // answered asynchronously via sendResponseApdu
    }

    private fun nextChunk(): ByteArray {
        val remaining = outbound.size - outboundOffset
        if (remaining <= 0) return Apdu.SW_OK
        val n = minOf(remaining, Apdu.MAX_RESPONSE_DATA)
        val chunk = outbound.copyOfRange(outboundOffset, outboundOffset + n)
        outboundOffset += n
        val left = outbound.size - outboundOffset
        val sw = if (left == 0) Apdu.SW_OK else byteArrayOf(0x61, (if (left > 0xFF) 0 else left).toByte())
        return chunk + sw
    }

    override fun onDeactivated(reason: Int) {
        resetSession()
    }

    override fun onDestroy() {
        scope.cancel()
        resetSession()
        super.onDestroy()
    }

    private fun resetSession() {
        job?.cancel()
        job = null
        inbound.reset()
        outbound = ByteArray(0)
        outboundOffset = 0
        (responder as? AutoCloseable)?.close()
        responder = null
        selectedFactory = null
    }
}
