package com.fortress.upi.core.offline.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.fortress.upi.core.crypto.SecureBytes
import com.fortress.upi.core.crypto.wipe
import com.fortress.upi.core.security.keystore.SealedStore
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [NoteEntity::class, OutboxEntity::class, ReceivedNoteEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class OfflineDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun outbox(): OutboxDao
    abstract fun receivedNotes(): ReceivedNoteDao

    companion object {
        private const val NAME = "fortress-offline.db"

        /**
         * Opens the SQLCipher-encrypted database. Call off the main thread.
         *
         * Key handling: a random 256-bit key is generated once and stored sealed
         * (AES-256-GCM under a StrongBox/TEE key that is usable only while the
         * device is unlocked). It is handed to SQLCipher in raw-key form
         * (`x'<64 hex>'`), skipping PBKDF2 — the key is already full-entropy —
         * which keeps cold open fast. SQLCipher must retain the key for the
         * lifetime of the connection pool, so it is not wiped after open.
         */
        fun open(context: Context): OfflineDatabase {
            System.loadLibrary("sqlcipher")
            val sealed = SealedStore(context, keyAlias = "fortress.db.kek.v1", unlockedDeviceRequired = true)
            val rawKey = sealed.read(KEY_SLOT) ?: SecureBytes.random(32).also { sealed.write(KEY_SLOT, it) }
            val passphrase = try {
                rawKeyPassphrase(rawKey)
            } finally {
                rawKey.wipe()
            }
            return Room.databaseBuilder(context.applicationContext, OfflineDatabase::class.java, NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
        }

        private const val KEY_SLOT = "offline-db-key"
        private val HEX = "0123456789ABCDEF".toByteArray(Charsets.US_ASCII)

        /** Builds `x'<hex>'` as bytes without ever materializing an immutable String. */
        private fun rawKeyPassphrase(key: ByteArray): ByteArray {
            val out = ByteArray(3 + key.size * 2)
            out[0] = 'x'.code.toByte()
            out[1] = '\''.code.toByte()
            key.forEachIndexed { i, b ->
                out[2 + i * 2] = HEX[(b.toInt() shr 4) and 0x0F]
                out[3 + i * 2] = HEX[b.toInt() and 0x0F]
            }
            out[out.size - 1] = '\''.code.toByte()
            return out
        }
    }
}
