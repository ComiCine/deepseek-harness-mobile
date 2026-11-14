package ai.comicine.dshmobile.data

import kotlinx.coroutines.flow.Flow
import java.util.UUID

class HarnessRepository(private val database: HarnessDatabase) {
    val sessions: Flow<List<SessionEntity>> = database.sessions().observeAll()
    val plugins: Flow<List<PluginEntity>> = database.plugins().observeAll()

    suspend fun createSession(provider: String, model: String, title: String = "新会话"): SessionEntity {
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
        if (existing.title != "新会话") return
        val title = firstPrompt.replace(Regex("\\s+"), " ").trim().take(36).ifBlank { "新会话" }
        database.sessions().rename(id, title, System.currentTimeMillis())
    }
}
