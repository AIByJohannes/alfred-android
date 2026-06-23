package com.aibyjohannes.alfred.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(
    tableName = "note_search_index",
    indices = [Index(value = ["path"], unique = true)]
)
internal data class NoteSearchIndexEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String, // Relative path inside the vault
    val title: String,
    val content: String,
    val modifiedAt: Long,
    val sizeBytes: Long,
    val indexedAt: Long
)

@Fts4(contentEntity = NoteSearchIndexEntity::class)
@Entity(tableName = "note_search_index_fts")
internal data class NoteSearchIndexFtsEntity(
    val title: String,
    val content: String
)

internal data class NoteMetadataSummary(
    val path: String,
    val modifiedAt: Long,
    val sizeBytes: Long
)

@Dao
internal interface NoteSearchIndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(note: NoteSearchIndexEntity)

    @Query("DELETE FROM note_search_index WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Query("SELECT * FROM note_search_index WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): NoteSearchIndexEntity?

    @Query("SELECT path, modifiedAt, sizeBytes FROM note_search_index")
    suspend fun getAllMetadata(): List<NoteMetadataSummary>

    @Query("""
        SELECT note_search_index.*
        FROM note_search_index
        JOIN note_search_index_fts ON note_search_index.id = note_search_index_fts.rowid
        WHERE note_search_index_fts MATCH :ftsQuery
        LIMIT :limit
    """)
    suspend fun search(ftsQuery: String, limit: Int): List<NoteSearchIndexEntity>

    @Query("DELETE FROM note_search_index WHERE path IN (:paths)")
    suspend fun deletePaths(paths: List<String>)

    @Query("DELETE FROM note_search_index")
    suspend fun clearAll()

    @Query("INSERT INTO note_search_index_fts(note_search_index_fts) VALUES('rebuild')")
    suspend fun rebuildFts()
}

@Database(
    entities = [NoteSearchIndexEntity::class, NoteSearchIndexFtsEntity::class],
    version = 1,
    exportSchema = false
)
internal abstract class NoteSearchIndexDatabase : RoomDatabase() {
    abstract fun dao(): NoteSearchIndexDao

    companion object {
        @Volatile private var instance: NoteSearchIndexDatabase? = null

        fun get(context: Context): NoteSearchIndexDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                NoteSearchIndexDatabase::class.java,
                "note-search-index.db"
            ).fallbackToDestructiveMigration(dropAllTables = true).build().also { instance = it }
        }
    }
}
