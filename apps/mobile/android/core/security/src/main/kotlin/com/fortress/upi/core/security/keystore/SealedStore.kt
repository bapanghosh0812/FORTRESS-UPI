package com.fortress.upi.core.security.keystore

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * Small-secret storage: each slot is AES-256-GCM encrypted under a hardware
 * Keystore key, with the slot name as associated data (ciphertexts cannot be
 * swapped between slots). Files live in `noBackupFilesDir` and are written
 * atomically.
 */
class SealedStore(
    private val context: Context,
    private val keyAlias: String,
    private val unlockedDeviceRequired: Boolean,
) {
    private val dir: File by lazy { File(context.noBackupFilesDir, "sealed").apply { mkdirs() } }

    fun write(name: String, plaintext: ByteArray) {
        val key = HardwareKeys.getOrCreateAesKey(context, keyAlias, unlockedDeviceRequired)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(aad(name))
        }
        val ct = cipher.doFinal(plaintext)
        val iv = cipher.iv
        val blob = ByteArray(2 + iv.size + ct.size)
        blob[0] = VERSION
        blob[1] = iv.size.toByte()
        iv.copyInto(blob, destinationOffset = 2)
        ct.copyInto(blob, destinationOffset = 2 + iv.size)

        val file = AtomicFile(slot(name))
        val out = file.startWrite()
        try {
            out.write(blob)
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
    }

    /** Returns null when the slot is empty. Throws on tampering (AEADBadTagException). */
    fun read(name: String): ByteArray? {
        val f = slot(name)
        if (!f.exists()) return null
        val blob = AtomicFile(f).readFully()
        if (blob.size < 2 || blob[0] != VERSION) throw IOException("unsupported sealed blob")
        val ivLen = blob[1].toInt()
        if (ivLen !in 12..16 || blob.size < 2 + ivLen + TAG_BYTES) throw IOException("corrupt sealed blob")
        val key = HardwareKeys.getOrCreateAesKey(context, keyAlias, unlockedDeviceRequired)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, blob, 2, ivLen))
            updateAAD(aad(name))
        }
        return cipher.doFinal(blob, 2 + ivLen, blob.size - 2 - ivLen)
    }

    fun delete(name: String) {
        AtomicFile(slot(name)).delete()
    }

    private fun slot(name: String): File {
        require(SLOT_NAME.matches(name)) { "invalid slot name" }
        return File(dir, name)
    }

    private fun aad(name: String) = "fortress-sealed-v1:$name".toByteArray(StandardCharsets.US_ASCII)

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val TAG_BYTES = 16
        val SLOT_NAME = Regex("^[a-z0-9_.-]{1,48}$")
    }
}
