package ai.comicine.dshmobile.engine

import android.content.Context
import android.os.SystemClock
import androidx.core.content.ContextCompat
import ai.comicine.dshmobile.R
import ai.comicine.dshmobile.data.HarnessRepository
import ai.comicine.dshmobile.data.HarnessSettings
import ai.comicine.dshmobile.data.MessageEntity
import ai.comicine.dshmobile.data.SessionEntity
import ai.comicine.dshmobile.data.SettingsStore
import ai.comicine.dshmobile.plugins.PluginManager
import ai.comicine.dshmobile.security.CredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PendingApproval(
    val id: String,
    val sessionId: String,
    val toolName: String,
    val description: String,
    val argumentsJson: String,
)

data class LiveAssistant(
    val messageId: String,
    val text: String,
    val thinking: String,
    val step: Int,
)

class HarnessEngine(
    private val context: Context,
    private val repository: HarnessRepository,
    private val credentials: CredentialStore,
    private val pluginManager: PluginManager,
) {
    private val localizedContext = ContextCompat.getContextForLanguage(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settingsStore = SettingsStore(context)
    private val messagesClient = DeepSeekMessagesClient(localizedContext)
    private val toolExecutor = ToolExecutor(context, pluginManager)
    private val taskMap = ConcurrentHashMap<String, Job>()
    private val approvalWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val limiter = Mutex()
    private val runningCount = MutableStateFlow(0)
    private val _settings = MutableStateFlow(settingsStore.load().validated(context))
    val settings: StateFlow<HarnessSettings> = _settings.asStateFlow()
    private val _approvals = MutableStateFlow<List<PendingApproval>>(emptyList())
    val approvals: StateFlow<List<PendingApproval>> = _approvals.asStateFlow()
    private val _liveAssistants = MutableStateFlow<Map<String, LiveAssistant>>(emptyMap())
    val liveAssistants: StateFlow<Map<String, LiveAssistant>> = _liveAssistants.asStateFlow()
    private val _runningSessions = MutableStateFlow<Set<String>>(emptySet())
    val runningSessions: StateFlow<Set<String>> = _runningSessions.asStateFlow()

    init {
        scope.launch { repository.recoverAfterProcessDeath() }
    }

    fun updateSettings(settings: HarnessSettings) {
        val validated = settings.validated(context)
        settingsStore.save(validated)
        _settings.value = validated
    }

    fun setApiKey(key: String) = credentials.setApiKey(key.trim())
    fun hasApiKey(): Boolean = credentials.getApiKey()?.isNotBlank() == true
    fun isRunning(sessionId: String): Boolean = taskMap[sessionId]?.isActive == true

    fun start(sessionId: String, prompt: String): Job = synchronized(taskMap) {
        val cleanPrompt = prompt.trim()
        require(cleanPrompt.isNotEmpty()) { localizedContext.getString(R.string.error_prompt_required) }
        check(taskMap[sessionId]?.isActive != true) { localizedContext.getString(R.string.error_session_task_running) }
        val task = scope.launch(start = CoroutineStart.LAZY) { runTurn(sessionId, cleanPrompt) }
        taskMap[sessionId] = task
        _runningSessions.update { it + sessionId }
        task.invokeOnCompletion {
            synchronized(taskMap) { taskMap.remove(sessionId, task) }
            _runningSessions.update { it - sessionId }
            _liveAssistants.update { it - sessionId }
            approvals.value.filter { it.sessionId == sessionId }.forEach(::resolveApproval)
        }
        task.start()
        task
    }

    fun cancel(sessionId: String) {
        taskMap[sessionId]?.cancel(CancellationException("Cancelled by user"))
        approvals.value.filter { it.sessionId == sessionId }.forEach { resolveApproval(it, false) }
    }

    fun cancelAll() {
        taskMap.keys.toList().forEach(::cancel)
    }

    fun approve(id: String, allow: Boolean) {
        val pending = approvals.value.firstOrNull { it.id == id } ?: return
        resolveApproval(pending, allow)
    }

    suspend fun newSession(): SessionEntity {
        val current = settings.value
        return repository.createSession("deepseek-messages", current.model)
    }

    fun observeMessages(sessionId: String) = repository.messages(sessionId)

    suspend fun exportSession(sessionId: String): String {
        val session = repository.session(sessionId) ?: throw IllegalArgumentException(localizedContext.getString(R.string.error_session_not_found))
        val messages = repository.messagesNow(sessionId)
        return JSONObject()
            .put("format", "deepseek-harness-mobile-session-v1")
            .put("session", JSONObject()
                .put("id", session.id)
                .put("title", session.title)
                .put("provider", session.provider)
                .put("model", session.model)
                .put("createdAt", session.createdAt))
            .put("messages", JSONArray().apply {
                messages.forEach { message ->
                    put(JSONObject()
                        .put("role", message.role)
                        .put("content", message.content)
                        .put("thinking", message.thinking)
                        .put("toolCalls", JSONArray(message.toolCallsJson))
                        .put("toolCallId", message.toolCallId)
                        .put("toolName", message.toolName)
                        .put("isError", message.isError)
                        .put("status", message.status)
                        .put("createdAt", message.createdAt))
                }
            }).toString(2)
    }

    private suspend fun runTurn(sessionId: String, prompt: String) {
        val capturedSettings = settings.value
        val available = capturedSettings.safeConcurrencyLimit(context)
        val concurrency = capturedSettings.maxConcurrentTasks.coerceIn(1, available)
        val hasSlot = takeSlot(concurrency)
        if (!hasSlot) return
        var activeMessageId: String? = null
        try {
            val session = repository.session(sessionId) ?: throw IllegalStateException(localizedContext.getString(R.string.error_session_not_found))
            repository.setSessionState(sessionId, "running")
            repository.appendLog(sessionId, "turn.start", localizedContext.getString(R.string.engine_log_turn_started))
            repository.saveMessage(MessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = "user",
                content = prompt,
                createdAt = System.currentTimeMillis(),
            ))
            repository.updateTitleIfDefault(sessionId, prompt)
            val apiKey = credentials.getApiKey()?.takeIf { it.isNotBlank() }
                ?: throw ProviderException(localizedContext.getString(R.string.error_api_key_required))

            var step = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                step++
                repository.appendLog(sessionId, "step.start", localizedContext.getString(R.string.engine_log_model_step, step), "model=${capturedSettings.model}")
                pluginManager.beforeStep(sessionId, step == 1)
                val toolDefinitions = toolExecutor.definitions(sessionId)
                val messageId = UUID.randomUUID().toString()
                activeMessageId = messageId
                repository.saveMessage(MessageEntity(
                    id = messageId,
                    sessionId = sessionId,
                    role = "assistant",
                    content = "",
                    status = "streaming",
                    createdAt = System.currentTimeMillis(),
                ))
                val history = repository.messagesNow(sessionId)
                val assistantText = StringBuilder()
                val reasoning = StringBuilder()
                var lastPersist = 0L
                _liveAssistants.update { it + (sessionId to LiveAssistant(messageId, "", "", step)) }

                val assistantTurn = messagesClient.generate(
                    settings = sessionToSettings(capturedSettings, session),
                    apiKey = apiKey,
                    history = history,
                    tools = toolDefinitions,
                ) { kind, text ->
                    if (kind == "thinking") reasoning.append(text) else assistantText.append(text)
                    _liveAssistants.update { it + (sessionId to LiveAssistant(messageId, assistantText.toString(), reasoning.toString(), step)) }
                    val elapsed = SystemClock.elapsedRealtime()
                    if (elapsed - lastPersist >= STREAM_CHECKPOINT_MS) {
                        repository.updateAssistant(messageId, assistantText.toString(), reasoning.toString(), "[]", "streaming")
                        lastPersist = elapsed
                    }
                }
                val callsJson = JSONArray().apply {
                    assistantTurn.toolCalls.forEach { call ->
                        put(JSONObject().put("id", call.id).put("name", call.name).put("arguments", call.argumentsJson))
                    }
                }.toString()
                repository.updateAssistant(messageId, assistantTurn.text, assistantTurn.thinking, callsJson, "complete")
                _liveAssistants.update { it - sessionId }
                activeMessageId = null
                repository.appendLog(
                    sessionId,
                    "assistant.message",
                    if (assistantTurn.toolCalls.isEmpty()) localizedContext.getString(R.string.engine_log_model_completed)
                    else localizedContext.getString(R.string.engine_log_tool_count, assistantTurn.toolCalls.size),
                    "stopReason=${assistantTurn.stopReason}; inputTokens=${assistantTurn.inputTokens}; outputTokens=${assistantTurn.outputTokens}",
                )
                if (assistantTurn.toolCalls.isEmpty()) break

                if (step >= capturedSettings.maxAgentSteps) {
                    for (call in assistantTurn.toolCalls) {
                        val message = localizedContext.getString(R.string.tool_result_not_run)
                        repository.saveMessage(toolMessage(sessionId, call, message, true))
                        repository.appendLog(sessionId, "tool.denied", localizedContext.getString(R.string.engine_log_step_limit, call.name), call.argumentsJson)
                    }
                    repository.setSessionState(sessionId, "limit")
                    return
                }

                val definitionByName = toolDefinitions.associateBy { it.name }
                val results = kotlinx.coroutines.coroutineScope {
                    assistantTurn.toolCalls.map { call -> async {
                        executeTool(sessionId, call, definitionByName[call.name])
                    } }.map { it.await() }
                }
                results.forEach { result ->
                    repository.saveMessage(toolMessage(sessionId, result.call, result.output, result.isError))
                    result.additionalContexts.forEach { contextMessage ->
                        repository.saveMessage(MessageEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            role = "context",
                            content = contextMessage,
                            createdAt = System.currentTimeMillis(),
                        ))
                    }
                    repository.appendLog(
                        sessionId,
                        if (result.isError) "tool.error" else "tool.result",
                        localizedContext.getString(if (result.isError) R.string.engine_log_tool_failed else R.string.engine_log_tool_completed, result.call.name),
                        result.output.take(20_000),
                    )
                }
            }
            repository.setSessionState(sessionId, "idle")
            repository.appendLog(sessionId, "turn.end", localizedContext.getString(R.string.engine_log_turn_completed))
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                activeMessageId?.let { id ->
                    val current = repository.messagesNow(sessionId).firstOrNull { message -> message.id == id }
                    if (current != null) repository.updateAssistant(id, current.content, current.thinking, current.toolCallsJson, "interrupted")
                }
                repository.setSessionState(sessionId, "cancelled")
                repository.appendLog(sessionId, "turn.cancelled", localizedContext.getString(R.string.engine_log_turn_cancelled))
            }
            throw cancelled
        } catch (error: Exception) {
            activeMessageId?.let {
                val current = repository.messagesNow(sessionId).firstOrNull { message -> message.id == it }
                if (current != null) repository.updateAssistant(it, current.content, current.thinking, current.toolCallsJson, "error")
            }
            repository.setSessionState(sessionId, "error")
            val detail = error.message ?: error.javaClass.simpleName
            val redacted = runCatching { credentials.getApiKey() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { detail.replace(it, "[REDACTED]") }
                ?: detail
            repository.appendLog(sessionId, "turn.error", redacted)
        } finally {
            releaseSlot()
        }
    }

    private fun sessionToSettings(settings: HarnessSettings, session: SessionEntity) =
        if (session.model == settings.model) settings else settings.copy(model = session.model)

    private suspend fun executeTool(
        sessionId: String,
        call: ToolCall,
        definition: ToolDefinition?,
    ): ToolExecution {
        if (definition == null) return ToolExecution(call, "Tool is not available in this Android profile.", isError = true)
        repository.appendLog(sessionId, "tool.call", localizedContext.getString(R.string.engine_log_execute_tool, call.name), call.argumentsJson.take(20_000))
        if (definition.requiresApproval) {
            val description = when (call.name) {
                "shell" -> localizedContext.getString(R.string.approval_shell_description)
                "write_file", "edit_file" -> localizedContext.getString(R.string.approval_file_description)
                else -> localizedContext.getString(R.string.approval_plugin_description)
            }
            if (!requestApproval(sessionId, call, description)) {
                return ToolExecution(call, "The user denied this tool call. Do not retry it without new authorization.", isError = true)
            }
        }
        return toolExecutor.execute(sessionId, definition, call)
    }

    private suspend fun requestApproval(sessionId: String, call: ToolCall, description: String): Boolean {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        val item = PendingApproval(id, sessionId, call.name, description, call.argumentsJson)
        approvalWaiters[id] = deferred
        _approvals.update { it + item }
        repository.appendLog(sessionId, "tool.approval", localizedContext.getString(R.string.engine_log_waiting_approval, call.name), call.argumentsJson.take(20_000))
        return try {
            deferred.await()
        } finally {
            approvalWaiters.remove(id)
            _approvals.update { items -> items.filterNot { it.id == id } }
        }
    }

    private fun resolveApproval(pending: PendingApproval, allowed: Boolean = false) {
        approvalWaiters[pending.id]?.complete(allowed)
        _approvals.update { items -> items.filterNot { it.id == pending.id } }
    }

    private suspend fun takeSlot(limit: Int): Boolean {
        while (true) {
            currentCoroutineContext().ensureActive()
            val acquired = limiter.withLock {
                if (runningCount.value < limit) {
                    runningCount.value += 1
                    true
                } else false
            }
            if (acquired) return true
            runningCount.first { it < limit }
        }
    }

    private suspend fun releaseSlot() {
        limiter.withLock { runningCount.value = (runningCount.value - 1).coerceAtLeast(0) }
    }

    private fun toolMessage(sessionId: String, call: ToolCall, result: String, isError: Boolean) = MessageEntity(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        role = "tool",
        content = result,
        toolCallId = call.id,
        toolName = call.name,
        isError = isError,
        createdAt = System.currentTimeMillis(),
    )

    companion object {
        private const val STREAM_CHECKPOINT_MS = 250L
    }
}
