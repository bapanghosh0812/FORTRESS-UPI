package com.fortress.upi.core.offline.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.fortress.upi.core.crypto.FceException
import com.fortress.upi.core.offline.protocol.Frame
import com.fortress.upi.core.offline.protocol.FrameType
import com.fortress.upi.core.offline.transport.ChunkFraming
import com.fortress.upi.core.offline.transport.OfflineResponder
import com.fortress.upi.core.offline.transport.Reassembler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Payee side of BLE: GATT server + connectable advertising.
 *  * Serves one payer at a time (other centrals get GATT_FAILURE while busy).
 *  * Low TX power: proximity by design.
 *  * A fresh [OfflineResponder] (PayeeSession) per payment, from [responderFactory].
 *
 * Callers must hold BLUETOOTH_ADVERTISE + BLUETOOTH_CONNECT ([BlePermissions.require]).
 */
@SuppressLint("MissingPermission")
class BlePeripheralServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val responderFactory: suspend () -> OfflineResponder,
    private val onError: (Throwable) -> Unit,
) : AutoCloseable {
    private val manager = context.getSystemService(BluetoothManager::class.java)
        ?: throw IOException("Bluetooth unavailable")
    private val lock = Any()
    private val handling = Mutex()
    private val mtuByDevice = HashMap<String, Int>()
    private val reassembler = Reassembler()
    private val tx = BluetoothGattCharacteristic(
        FortressGatt.TX,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ,
    ).apply {
        addDescriptor(
            BluetoothGattDescriptor(
                FortressGatt.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
    }

    private var server: BluetoothGattServer? = null
    private var activeAddress: String? = null
    private var responder: OfflineResponder? = null

    @Volatile
    private var notifyAck: CompletableDeferred<Int>? = null

    fun start() {
        BlePermissions.require(context, BlePermissions.PAYEE)
        val gattServer = manager.openGattServer(context, callback) ?: throw IOException("cannot open GATT server")
        val service = BluetoothGattService(FortressGatt.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(
                BluetoothGattCharacteristic(
                    FortressGatt.RX,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE,
                ),
            )
            addCharacteristic(tx)
        }
        server = gattServer
        // Advertising starts in onServiceAdded so no central can connect to a half-built server.
        if (!gattServer.addService(service)) throw IOException("cannot add GATT service")
    }

    override fun close() {
        manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        server?.close()
        server = null
        synchronized(lock) { resetSessionLocked() }
    }

    private fun startAdvertising() {
        val advertiser = manager.adapter?.bluetoothLeAdvertiser ?: return onError(IOException("BLE advertising unsupported"))
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false) // device names often contain the owner's name
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(FortressGatt.SERVICE))
            .build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) = onError(IOException("advertising failed ($errorCode)"))
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising() else onError(IOException("service add failed ($status)"))
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized(lock) {
                    mtuByDevice.remove(device.address)
                    if (device.address == activeAddress) resetSessionLocked()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            synchronized(lock) { mtuByDevice[device.address] = mtu }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            val ok = descriptor.uuid == FortressGatt.CCCD && !preparedWrite && offset == 0
            if (responseNeeded) server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            var message: ByteArray? = null
            val accepted = synchronized(lock) {
                val busy = activeAddress != null && activeAddress != device.address
                if (characteristic.uuid != FortressGatt.RX || preparedWrite || offset != 0 || busy) {
                    false
                } else {
                    activeAddress = device.address
                    try {
                        message = reassembler.accept(value)
                        true
                    } catch (e: IOException) {
                        resetSessionLocked()
                        false
                    }
                }
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
            }
            message?.let { msg -> scope.launch(Dispatchers.IO) { handle(device, msg) } }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notifyAck?.complete(status)
        }
    }

    private suspend fun handle(device: BluetoothDevice, message: ByteArray) = handling.withLock {
        try {
            val r = synchronized(lock) { responder } ?: responderFactory().also { synchronized(lock) { responder = it } }
            val reply = r.respond(message)
            sendReply(device, reply)
            if (isTerminal(reply)) synchronized(lock) { resetSessionLocked() }
        } catch (e: IOException) {
            synchronized(lock) { resetSessionLocked() }
            onError(e)
        } catch (e: IllegalStateException) {
            synchronized(lock) { resetSessionLocked() }
            onError(e)
        }
    }

    private suspend fun sendReply(device: BluetoothDevice, reply: ByteArray) {
        val gattServer = server ?: throw IOException("server closed")
        val mtu = synchronized(lock) { mtuByDevice[device.address] ?: FortressGatt.DEFAULT_MTU }
        for (chunk in ChunkFraming.split(reply, FortressGatt.chunkSize(mtu))) {
            val ack = CompletableDeferred<Int>().also { notifyAck = it }
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gattServer.notifyCharacteristicChanged(device, tx, false, chunk) == BluetoothStatusCodes.SUCCESS
            } else {
                legacyNotify(gattServer, device, chunk)
            }
            if (!started) throw IOException("notification not sent")
            val status = withTimeoutOrNull(NOTIFY_TIMEOUT_MS) { ack.await() }
            if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("notification failed ($status)")
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyNotify(gattServer: BluetoothGattServer, device: BluetoothDevice, chunk: ByteArray): Boolean {
        tx.value = chunk
        return gattServer.notifyCharacteristicChanged(device, tx, false)
    }

    private fun isTerminal(reply: ByteArray): Boolean = try {
        Frame.decode(reply).type in setOf(FrameType.RECEIPT, FrameType.ABORT)
    } catch (e: FceException) {
        true
    }

    private fun resetSessionLocked() {
        (responder as? AutoCloseable)?.close()
        responder = null
        activeAddress = null
        reassembler.reset()
    }

    private companion object {
        const val NOTIFY_TIMEOUT_MS = 3_000L
    }
}
