package ai.comicine.dshmobile.data

import android.content.Context
import androidx.core.content.ContextCompat
import ai.comicine.dshmobile.R
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class HarnessRepository(private val database: HarnessDatabase, context: Context) {
    private val localizedContext = ContextCompat.getContextForLanguage(context.applicationContext)
    val sessions: Flow<List<SessionEntity>> = database.sessions().observeAll()
    val plugins: Flow<List<PluginEntity>> = database.plugins().observeAll()

    suspend fun createSession(
        provider: String,
        model: String,
        title: String = localizedContext.getString(R.string.session_default_title),
    ): SessionEntity {
        val now = System.currentTimeMillis()
        return SessionEntity(UUID.randomUUID().toString(), title, provider, model, now, now).also {
            database.sessions().put(it)
        }
    }

    suspend fun session(id: String) = database.sessions().get(id)
    fun messages(sessionId: String) = database.messages().observeForSession(sessionId)
    suspend fun messagesNow(sessionId: String) = database.messages().listForSession(sessionId)
    suspend fun saveMessage(message: MessageEntity) = database.messages().put(message)
    suspend fun updateMessage(message: MessageEntity) = database.messages().update(message)
    suspend fun updateAssistant(id: String, content: String, thinking: String, toolCallsJson: String, status: String) =
        database.messages().updateAssistant(id, content, thinking, toolCallsJson, status)
    suspend fun setSessionState(id: String, state: String) = database.sessions().setState(id, state, System.currentTimeMillis())
    suspend fun appendLog(sessionId: String, kind: String, summary: String, details: String = "") =
        database.logs().append(ExecutionLogEntity(sessionId = sessionId, kind = kind, summary = summary, details = details, createdAt = System.currentTimeMillis()))
    suspend fun rename(id: String, title: String) = database.sessions().rename(id, title, System.currentTimeMillis())
    suspend fun delete(session: SessionEntity) = database.sessions().delete(session)

    suspend fun recoverAfterProcessDeath() {
        database.messages().markInterruptedStreams()
        database.sessions().recoverRunning(System.currentTimeMillis())
    }

    suspend fun updateTitleIfDefault(id: String, firstPrompt: String) {
        val existing = database.sessions().get(id) ?: return
        val defaultTitle = localizedContext.getString(R.string.session_default_title)
        if (existing.title != defaultTitle) return
        val title = firstPrompt.replace(Regex("\\s+"), " ").trim().take(36).ifBlank { defaultTitle }
        database.sessions().rename(id, title, System.currentTimeMillis())
    }
}
