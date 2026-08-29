package no.bellaybestia.audex.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A device-local book (audio or ebook) referenced in place by a persisted URI. */
@Entity(tableName = "local_items")
data class LocalItemEntity(
    @PrimaryKey val id: String,
    val uri: String,
    val mime: String,
    val kind: String,          // "AUDIO" | "EBOOK"
    val title: String,
    val author: String?,
    val coverUri: String?,
    val durationS: Double?,
    val addedAt: Long,
)

@Dao
interface LocalItemDao {
    @Query("SELECT * FROM local_items ORDER BY addedAt DESC")
    fun flow(): Flow<List<LocalItemEntity>>

    @Query("SELECT * FROM local_items")
    suspend fun all(): List<LocalItemEntity>

    @Query("SELECT * FROM local_items WHERE id = :id")
    suspend fun get(id: String): LocalItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: LocalItemEntity)

    @Query("DELETE FROM local_items WHERE id = :id")
    suspend fun delete(id: String)
}
