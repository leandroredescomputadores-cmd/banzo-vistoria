package br.com.banzo.fiscalasfalto.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OccurrenceDao {
    @Query("SELECT * FROM occurrences ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Occurrence>>

    @Query("SELECT * FROM occurrences WHERE createdAt >= :start AND createdAt < :end ORDER BY createdAt ASC")
    suspend fun forPeriod(start: Long, end: Long): List<Occurrence>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: Occurrence)

    @Query("DELETE FROM occurrences WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE occurrences SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("SELECT * FROM occurrences WHERE synced = 0 ORDER BY createdAt ASC")
    suspend fun pendingSync(): List<Occurrence>

    @Query("SELECT COUNT(*) FROM occurrences WHERE status != 'Corrigida e verificada'")
    fun observeOpenCount(): Flow<Int>
}
