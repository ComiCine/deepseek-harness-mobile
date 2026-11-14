package ai.comicine.dshmobile.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun get(id: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(session: SessionEntity)

    @Query("UPDATE sessions SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    @Query("UPDATE sessions SET model = :model, provider = :provider WHERE id = :id")
    suspend fun setRoute(id: String, provider: String, model: String)

    @Query("UPDATE sessions SET executionState = :state, updatedAt = :now WHERE id = :id")
    suspend fun setState(id: String, state: String, now: Long)

    @Query("UPDATE sessions SET executionState = 'interrupted', updatedAt = :now WHERE executionState = 'running'")
    suspend fun recoverRunning(now: Long)

    @Delete
    suspend fun delete(session: SessionEntity)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY sequence")
    fun observeForSession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY sequence")
    suspend fun listForSession(sessionId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(message: MessageEntity)

    @Update
    suspend fun update(message: MessageEntity)

    @Transaction
    suspend fun upsertById(message: MessageEntity) {
        val existing = getById(message.id)
        if (existing == null) put(message) else update(message.copy(sequence = existing.sequence))
    }

    @Query("UPDATE messages SET content = :content, thinking = :thinking, toolCallsJson = :toolCallsJson, status = :status WHERE id = :id")
    suspend fun updateAssistant(id: String, content: String, thinking: String, toolCallsJson: String, status: String)

    @Query("UPDATE messages SET status = 'interrupted' WHERE status = 'streaming'")
    suspend fun markInterruptedStreams()

    @Query("UPDATE messages SET status = 'interrupted' WHERE sessionId = :sessionId AND status = 'streaming'")
    suspend fun markSessionInterrupted(sessionId: String)
}

@Dao
interface ExecutionLogDao {
    @Query("SELECT * FROM execution_logs WHERE sessionId = :sessionId ORDER BY sequence DESC LIMIT :limit")
    fun observeRecent(sessionId: String, limit: Int = 200): Flow<List<ExecutionLogEntity>>

    @Insert
    suspend fun append(log: ExecutionLogEntity)
}

@Dao
interface PluginDao {
    @Query("SELECT * FROM plugins ORDER BY displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<PluginEntity>>

    @Query("SELECT * FROM plugins WHERE id = :id LIMIT 1")
    suspend fun get(id: String): PluginEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(plugin: PluginEntity)

    @Query("UPDATE plugins SET enabled = :enabled, grantedPermissionsJson = :grantedPermissionsJson WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, grantedPermissionsJson: String)

    @Query("UPDATE plugins SET configJson = :configJson WHERE id = :id")
    suspend fun setConfig(id: String, configJson: String)

    @Delete
    suspend fun delete(plugin: PluginEntity)
}

@Database(
    entities = [SessionEntity::class, MessageEntity::class, ExecutionLogEntity::class, PluginEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class HarnessDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun messages(): MessageDao
    abstract fun logs(): ExecutionLogDao
    abstract fun plugins(): PluginDao

    companion object {
        fun open(context: Context): HarnessDatabase = Room.databaseBuilder(
            context,
            HarnessDatabase::class.java,
            "deepseek-harness.db",
        ).build()
    }
}
