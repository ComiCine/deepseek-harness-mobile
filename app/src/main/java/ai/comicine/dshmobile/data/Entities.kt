package ai.comicine.dshmobile.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sessions", indices = [Index("updatedAt")])
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val provider: String,
    val model: String,
    val createdAt: Long,
    val updatedAt: Long,
    val executionState: String = "idle",
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId"), Index(value = ["sessionId", "sequence"]), Index(value = ["id"], unique = true)],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val thinking: String = "",
    val toolCallsJson: String = "[]",
    val toolCallId: String? = null,
    val toolName: String? = null,
    val isError: Boolean = false,
    val status: String = "complete",
    val createdAt: Long,
)

@Entity(
    tableName = "execution_logs",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId"), Index("createdAt")],
)
data class ExecutionLogEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val sessionId: String,
    val kind: String,
    val summary: String,
    val details: String = "",
    val createdAt: Long,
)

@Entity(tableName = "plugins")
data class PluginEntity(
    @PrimaryKey val id: String,
    val packageName: String,
    val displayName: String,
    val version: String,
    val entryPath: String,
    val manifestJson: String,
    val configJson: String = "{}",
    val grantedPermissionsJson: String = "[]",
    val enabled: Boolean = false,
    val sourceSha256: String,
    val installedAt: Long,
)
