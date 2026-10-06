package com.fortress.upi.core.offline.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.fortress.upi.core.offline.transport.ChunkFraming
import com.fortress.upi.core.offline.transport.OfflineTransport
import com.fortress.upi.core.offline.transport.Reassembler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Payer side of BLE: GATT central. Writes framed requests to RX and collects
 * framed responses from TX notifications. Link-layer BLE security is not relied
 * on — every payload is already end-to-end AEAD-sealed by the protocol.
 *
 * Callers must hold BLUETOOTH_SCAN + BLUETOOTH_CONNECT ([BlePermissions.require]).
 */
@SuppressLint("MissingPermission")
class BleCentralTransport private constructor(
    private val gatt: BluetoothGatt,
    private val bridge: GattBridge,
    private val rx: BluetoothGattCharacteristic,
    private val chunkSize: Int,
) : OfflineTransport {
    private val mutex = Mutex()

    override suspend fun exchange(request: ByteArray): ByteArray = mutex.withLock {
        withTimeout(EXCHANGE_TIMEOUT_MS) {
            for (chunk in ChunkFraming.split(request, chunkSize)) write(chunk)
            bridge.messages.receive()
        }
    }

    private suspend fun write(chunk: ByteArray) {
        val ack = CompletableDeferred<Int>().also { bridge.writeAck = it }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(rx, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            legacyWrite(chunk)
        }
        if (!started) throw IOException("GATT write was not started")
        val status = ack.await()
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("GATT write failed ($status)")
    }

    @Suppress("DEPRECATION")
    private fun legacyWrite(chunk: ByteArray): Boolean {
        rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        rx.value = chunk
        return gatt.writeCharacteristic(rx)
    }

    override fun close() {
        bridge.fail(IOException("transport closed"))
        gatt.disconnect()
        gatt.close()
    }

    companion object {
        private const val EXCHANGE_TIMEOUT_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 8_000L

        /**
         * Scans for Fortress receivers and returns the closest one. The payee
         * advertises at low TX power, and [minRssi] rejects far-away devices, so
         * "nearby" really means within about an arm's length to a few metres.
         */
        suspend fun findNearestPayee(context: Context, windowMs: Long = 1_500, minRssi: Int = -70): BluetoothDevice {
            BlePermissions.require(context, BlePermissions.PAYER)
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw IOException("Bluetooth unavailable")
            if (!adapter.isEnabled) throw IOException("Bluetooth is off")
            val scanner = adapter.bluetoothLeScanner ?: throw IOException("BLE scanner unavailable")

            val strongest = ConcurrentHashMap<String, ScanResult>()
            val scanError = AtomicInteger(0)
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    strongest.merge(result.device.address, result) { old, new -> if (new.rssi > old.rssi) new else old }
                }

                override fun onScanFailed(errorCode: Int) {
                    scanError.set(errorCode)
                }
            }
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(FortressGatt.SERVICE)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(listOf(filter), settings, callback)
            try {
                delay(windowMs)
            } finally {
                scanner.stopScan(callback)
            }
            if (scanError.get() != 0) throw IOException("BLE scan failed (${scanError.get()})")
            return strongest.values.filter { it.rssi >= minRssi }.maxByOrNull { it.rssi }?.device
                ?: throw IOException("no Fortress receiver nearby")
        }

        suspend fun connect(context: Context, device: BluetoothDevice): BleCentralTransport {
            BlePermissions.require(context, BlePermissions.PAYER)
            val bridge = GattBridge()
            val gatt = device.connectGatt(context, false, bridge, BluetoothDevice.TRANSPORT_LE)
                ?: throw IOException("connectGatt failed")
            try {
                return withTimeout(CONNECT_TIMEOUT_MS) {
                    bridge.connected.await()
                    val mtu = if (gatt.requestMtu(FortressGatt.TARGET_MTU)) bridge.mtu.await() else FortressGatt.DEFAULT_MTU
                    if (!gatt.discoverServices()) throw IOException("service discovery not started")
                    bridge.services.await()

                    val service = gatt.getService(FortressGatt.SERVICE) ?: throw IOException("not a Fortress receiver")
                    val rx = service.getCharacteristic(FortressGatt.RX) ?: throw IOException("RX missing")
                    val tx = service.getCharacteristic(FortressGatt.TX) ?: throw IOException("TX missing")
                    enableNotifications(gatt, bridge, tx)
                    BleCentralTransport(gatt, bridge, rx, FortressGatt.chunkSize(mtu))
                }
            } catch (t: Throwable) {
                gatt.close()
                throw t
            }
        }

        private suspend fun enableNotifications(gatt: BluetoothGatt, bridge: GattBridge, tx: BluetoothGattCharacteristic) {
            if (!gatt.setCharacteristicNotification(tx, true)) throw IOException("cannot enable notifications")
            val cccd = tx.getDescriptor(FortressGatt.CCCD) ?: throw IOException("CCCD missing")
            val ack = CompletableDeferred<Int>().also { bridge.descriptorAck = it }
            val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.setValue(value) && gatt.writeDescriptor(cccd)
            }
            if (!started || ack.await() != BluetoothGatt.GATT_SUCCESS) throw IOException("CCCD write failed")
        }
    }
}

/** Bridges binder-thread GATT callbacks into coroutines. */
internal class GattBridge : BluetoothGattCallback() {
    val connected = CompletableDeferred<Unit>()
    val mtu = CompletableDeferred<Int>()
    val services = CompletableDeferred<Unit>()
    val messages = Channel<ByteArray>(Channel.BUFFERED)

    @Volatile
    var writeAck: CompletableDeferred<Int>? = null

    @Volatile
    var descriptorAck: CompletableDeferred<Int>? = null

    private val reassembler = Reassembler()

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
            connected.complete(Unit)
        } else {
            fail(IOException("GATT disconnected (status $status)"))
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        this.mtu.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else FortressGatt.DEFAULT_MTU)
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) services.complete(Unit) else services.completeExceptionally(IOException("discovery failed ($status)"))
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        writeAck?.complete(status)
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        descriptorAck?.complete(status)
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        onChunk(characteristic, value)
    }

    @Deprecated("Invoked only below API 33")
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) onChunk(characteristic, characteristic.value ?: return)
    }

    private fun onChunk(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != FortressGatt.TX) return
        try {
            reassembler.accept(value)?.let { messages.trySend(it) }
        } catch (e: IOException) {
            fail(e)
        }
    }

    fun fail(e: Throwable) {
        connected.completeExceptionally(e)
        mtu.completeExceptionally(e)
        services.completeExceptionally(e)
        writeAck?.completeExceptionally(e)
        descriptorAck?.completeExceptionally(e)
        messages.close(e)
    }
}
