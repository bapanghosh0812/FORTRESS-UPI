package com.fortress.upi.core.offline.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT COALESCE(SUM(denominationPaise), 0) FROM notes WHERE status = 'AVAILABLE' AND expiresAtEpochSec > :nowSec")
    fun observeSpendable(nowSec: Long): Flow<Long>

    @Query("SELECT * FROM notes WHERE status = 'AVAILABLE' AND expiresAtEpochSec > :nowSec ORDER BY denominationPaise DESC")
    suspend fun spendable(nowSec: Long): List<NoteEntity>

    @Query("SELECT denominationPaise FROM notes WHERE status = 'AVAILABLE' AND expiresAtEpochSec > :nowSec ORDER BY denominationPaise DESC")
    fun observeDenominations(nowSec: Long): Flow<List<Long>>

    @Query("SELECT * FROM notes WHERE noteId IN (:ids)")
    suspend fun byIds(ids: List<String>): List<NoteEntity>

    /** Compare-and-set transition; returns the number of rows moved. */
    @Query("UPDATE notes SET status = :to, updatedAtMillis = :now WHERE noteId IN (:ids) AND status = :from")
    suspend fun transition(ids: List<String>, from: NoteStatus, to: NoteStatus, now: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(notes: List<NoteEntity>)
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: OutboxEntity): Long

    @Query("SELECT * FROM outbox WHERE syncState = 'PENDING' ORDER BY createdAtMillis LIMIT :limit")
    suspend fun pending(limit: Int): List<OutboxEntity>

    @Query("SELECT * FROM outbox ORDER BY createdAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<OutboxEntity>>

    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM outbox WHERE direction = 'RECEIVED' AND syncState = 'PENDING'")
    fun observePendingIncoming(): Flow<Long>

    @Query("UPDATE outbox SET syncState = 'SYNCED', attempts = attempts + 1, lastError = NULL WHERE id = :id")
    suspend fun markSynced(id: Long)

    @Query("UPDATE outbox SET syncState = 'REJECTED', attempts = attempts + 1, lastError = :code WHERE id = :id")
    suspend fun markRejected(id: Long, code: String)

    @Query("UPDATE outbox SET attempts = attempts + 1, lastError = :code WHERE id = :id")
    suspend fun markAttempt(id: Long, code: String)
}

@Dao
interface ReceivedNoteDao {
    @Query("SELECT COUNT(*) FROM received_notes WHERE noteId IN (:ids)")
    suspend fun countExisting(ids: List<String>): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(rows: List<ReceivedNoteEntity>)
}
