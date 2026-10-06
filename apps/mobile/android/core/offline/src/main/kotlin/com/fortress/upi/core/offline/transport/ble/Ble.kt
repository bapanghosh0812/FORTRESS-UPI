package com.fortress.upi.core.offline.transport.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.util.UUID

/** Fortress offline GATT profile. Advertising carries the service UUID only — no name, no PII. */
internal object FortressGatt {
    val SERVICE: UUID = UUID.fromString("6f1d0001-8f5a-4c3e-9b8e-3a1f0c7d2b10")
    val RX: UUID = UUID.fromString("6f1d0002-8f5a-4c3e-9b8e-3a1f0c7d2b10") // payer → payee (write)
    val TX: UUID = UUID.fromString("6f1d0003-8f5a-4c3e-9b8e-3a1f0c7d2b10") // payee → payer (notify)
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val TARGET_MTU = 517
    const val DEFAULT_MTU = 23
    const val ATT_OVERHEAD = 3
    /** GATT caps attribute values at 512 bytes regardless of the negotiated MTU. */
    private const val MAX_ATTRIBUTE = 512

    fun chunkSize(mtu: Int): Int = minOf(mtu - ATT_OVERHEAD, MAX_ATTRIBUTE)
}

/** Runtime-permission gate for the Android 12+ Bluetooth permissions (no location needed). */
object BlePermissions {
    val PAYER = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    val PAYEE = arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)

    fun granted(context: Context, permissions: Array<String>): Boolean = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun require(context: Context, permissions: Array<String>) {
        if (!granted(context, permissions)) throw SecurityException("Bluetooth permissions not granted")
    }
}
