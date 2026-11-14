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
import ai.comicine.dshmobile.plugins.OriginalAgentEvent
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
import kotlinx.coroutines.flow.collect
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
import java.io.File
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

private data class OriginalStreamBuffer(
    val attemptId: String,
    val messageId: String,
    val step: Int,
    val text: StringBuilder = StringBuilder(),
    val thinking: StringBuilder = StringBuilder(),
)

private data class OriginalToolCall(
    val name: String,
    val arguments: String,
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
    private val originalApprovalIds = ConcurrentHashMap.newKeySet<String>()
    private val resolvingOriginalApprovalIds = ConcurrentHashMap.newKeySet<String>()
    private val originalLimitSessions = ConcurrentHashMap.newKeySet<String>()
    private val originalStreams = ConcurrentHashMap<String, OriginalStreamBuffer>()
    private val originalToolCalls = ConcurrentHashMap<String, ConcurrentHashMap<String, OriginalToolCall>>()
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
        scope.launch {
            pluginManager.originalAgentEvents.collect { event ->
                when (event) {
                    is OriginalAgentEvent.SessionEvent -> {
                        try {
                            projectOriginalSessionEvent(event, recordLog = true)
                        } catch (error: Exception) {
                            runCatching {
                                repository.appendLog(
                                    event.sessionId,
                                    "session.projection.error",
                                    localizedContext.getString(R.string.engine_log_original_event, event.eventType),
                                    error.message ?: error.javaClass.simpleName,
                                )
                            }
                        }
                    }
                    is OriginalAgentEvent.AssistantFrame -> handleOriginalAssistantFrame(event)
                    is OriginalAgentEvent.StepLimitReached -> {
                        originalLimitSessions += event.sessionId
                        runCatching {
                            repository.appendLog(
                                event.sessionId,
                                "step.limit",
                                localizedContext.getString(R.string.engine_log_step_limit_reached, event.limit),
                                "step=${event.step}; limit=${event.limit}",
                            )
                        }
                    }
                    is OriginalAgentEvent.ApprovalRequested -> {
                        originalApprovalIds += event.requestId
                        val displayReason = event.displayReasonJson?.let { raw ->
                            runCatching {
                                val values = JSONObject(raw)
                                val language = localizedContext.resources.configuration.locales[0]?.language.orEmpty()
                                values.optString(language).ifBlank { values.optString("en") }
                            }.getOrNull()
                        }
                        val pending = PendingApproval(
                            id = event.requestId,
                            sessionId = event.sessionId,
                            toolName = event.toolName,
                            description = displayReason?.takeIf { it.isNotBlank() }
                                ?: event.reason?.takeIf { it.isNotBlank() }
                                ?: localizedContext.getString(R.string.approval_plugin_description),
                            argumentsJson = event.argumentsJson,
                        )
                        _approvals.update { current -> current.filterNot { it.id == pending.id } + pending }
                        runCatching { repository.setSessionState(event.sessionId, "waiting_approval") }
                    }
                    is OriginalAgentEvent.ApprovalResolved -> {
                        originalApprovalIds.remove(event.requestId)
                        resolvingOriginalApprovalIds.remove(event.requestId)
                        _approvals.update { current -> current.filterNot { it.id == event.requestId } }
                        if (event.outcome == "allowed-once") {
                            runCatching { repository.setSessionState(event.sessionId, "running") }
                        }
                    }
                }
            }
        }
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
            approvals.value.filter { it.sessionId == sessionId }.forEach { pending ->
                if (originalApprovalIds.remove(pending.id)) {
                    resolvingOriginalApprovalIds.remove(pending.id)
                    _approvals.update { current -> current.filterNot { it.id == pending.id } }
                } else {
                    resolveApproval(pending)
                }
            }
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

    fun close() {
        cancelAll()
        scope.cancel()
    }

    fun approve(id: String, allow: Boolean) {
        val pending = approvals.value.firstOrNull { it.id == id } ?: return
        if (originalApprovalIds.contains(id)) {
            if (!resolvingOriginalApprovalIds.add(id)) return
            scope.launch {
                try {
                    pluginManager.resolveOriginalApproval(id, allow)
                } catch (error: Exception) {
                    resolvingOriginalApprovalIds.remove(id)
                    originalApprovalIds.remove(id)
                    _approvals.update { current -> current.filterNot { it.id == id } }
                    runCatching {
                        repository.appendLog(
                            pending.sessionId,
                            "approval.error",
                            localizedContext.getString(R.string.engine_log_approval_failed),
                            error.message ?: error.javaClass.simpleName,
                        )
                    }
                }
            }
            return
        }
        resolveApproval(pending, allow)
    }

    suspend fun newSession(): SessionEntity {
        val current = settings.value
        return repository.createSession(ORIGINAL_PROVIDER_ID, current.model)
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
            if (session.provider == ORIGINAL_PROVIDER_ID || session.provider == LEGACY_MESSAGES_PROVIDER_ID) {
                runOriginalTurn(session, prompt, capturedSettings)
                return
            }
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

    private suspend fun runOriginalTurn(
        session: SessionEntity,
        prompt: String,
        capturedSettings: HarnessSettings,
    ) {
        val sessionId = session.id
        val apiKey = credentials.getApiKey()?.takeIf { it.isNotBlank() }
            ?: throw ProviderException(localizedContext.getString(R.string.error_api_key_required))
        val model = session.model.ifBlank { capturedSettings.model }
        val route = ORIGINAL_PROVIDER_ID

        repository.setSessionState(sessionId, "running")
        repository.updateTitleIfDefault(sessionId, prompt)
        val storedEvents = JSONArray(pluginManager.readOriginalSessionEvents(sessionId))
        if (storedEvents.length() > 0) {
            syncOriginalSessionSnapshot(sessionId, storedEvents.toString())
        } else {
            val legacyMessages = repository.messagesNow(sessionId)
            if (legacyMessages.isNotEmpty()) {
                pluginManager.importLegacySession(
                    sessionId = sessionId,
                    provider = route,
                    model = model,
                    messagesJson = legacyMessagesJson(legacyMessages),
                )
            }
        }
        if (session.provider != route) repository.setSessionRoute(sessionId, route, model)

        val workspace = File(context.filesDir, "workspace").apply { mkdirs() }.canonicalPath
        val agentConfiguration = JSONObject()
            .put("reasoningEffort", capturedSettings.reasoningEffort)
            .put("maxTokens", capturedSettings.maxTokens)
            .put("maxAgentSteps", capturedSettings.maxAgentSteps)
            .put("systemPrompt", capturedSettings.systemPrompt)
            .put("cwd", workspace)
            .toString()

        pluginManager.createOrRestoreOriginalAgent(
            sessionId = sessionId,
            provider = route,
            model = model,
            baseURL = capturedSettings.baseUrl,
            configurationJson = agentConfiguration,
        )
        repository.setSessionState(sessionId, "running")
        val finalEvents = pluginManager.submitUserMessageAndAwaitIdle(
            sessionId = sessionId,
            text = prompt,
            provider = route,
            model = model,
            baseURL = capturedSettings.baseUrl,
            apiKey = apiKey,
            configurationJson = agentConfiguration,
        )
        syncOriginalSessionSnapshot(sessionId, finalEvents)
    }

    private fun legacyMessagesJson(messages: List<MessageEntity>): String = JSONArray().apply {
        messages.forEach { message ->
            put(JSONObject()
                .put("id", message.id)
                .put("role", message.role)
                .put("content", message.content)
                .put("thinking", message.thinking)
                .put("toolCallsJson", message.toolCallsJson)
                .put("toolCallId", message.toolCallId)
                .put("toolName", message.toolName)
                .put("isError", message.isError)
                .put("status", message.status)
                .put("createdAt", message.createdAt))
        }
    }.toString()

    private suspend fun syncOriginalSessionSnapshot(sessionId: String, serializedEvents: String) {
        val events = JSONArray(serializedEvents)
        for (index in 0 until events.length()) {
            val raw = events.optJSONObject(index) ?: continue
            projectOriginalSessionEvent(
                OriginalAgentEvent.SessionEvent(
                    sessionId = sessionId,
                    eventType = raw.optString("type"),
                    eventJson = raw.toString(),
                ),
                recordLog = false,
            )
        }
        repository.setSessionState(sessionId, originalExecutionState(sessionId, events))
    }

    private fun originalExecutionState(sessionId: String, events: JSONArray): String {
        var latestReason: String? = null
        for (index in 0 until events.length()) {
            val event = events.optJSONObject(index) ?: continue
            if (event.optString("type") != "turn/end") continue
            latestReason = event.optJSONObject("data")?.optJSONObject("reason")?.optString("kind")
        }
        return when (latestReason) {
            "max-tokens" -> "limit"
            "aborted" -> "cancelled"
            "error" -> "error"
            "interrupted" -> "interrupted"
            "blocked" -> if (originalLimitSessions.remove(sessionId)) "limit" else "blocked"
            else -> "idle"
        }
    }

    private suspend fun projectOriginalSessionEvent(
        event: OriginalAgentEvent.SessionEvent,
        recordLog: Boolean,
    ) {
        val raw = JSONObject(event.eventJson)
        val data = raw.optJSONObject("data") ?: JSONObject()
        val eventTime = raw.optLong("time", System.currentTimeMillis())
        val toolCalls = originalToolCalls.getOrPut(event.sessionId) { ConcurrentHashMap() }
        var completedToolCallId: String? = null
        when (event.eventType) {
            "user/message" -> {
                val role = if (data.optJSONObject("source")?.optString("kind") == "runtime-context") "context" else "user"
                upsertOriginalMessage(event.sessionId, data, role, eventTime)
            }
            "assistant/message" -> {
                val message = data.optJSONObject("message") ?: return
                upsertOriginalMessage(event.sessionId, message, "assistant", eventTime, data.optBoolean("interrupted"))
            }
            "tool/result" -> {
                val message = data.optJSONObject("message") ?: return
                upsertOriginalMessage(event.sessionId, message, "tool", eventTime)
                completedToolCallId = message.optString("toolCallId").ifBlank {
                    message.optJSONObject("source")?.optString("callId").orEmpty()
                }.takeIf { it.isNotBlank() }
            }
            "system/message", "developer/message" -> {
                val message = data.optJSONObject("message") ?: return
                upsertOriginalMessage(event.sessionId, message, "context", eventTime)
            }
            "tool/call" -> {
                val callId = data.optString("callId")
                if (callId.isNotBlank()) {
                    toolCalls[callId] = OriginalToolCall(
                        name = data.optString("name", "tool"),
                        arguments = data.optString("arguments", "{}"),
                    )
                }
            }
            "turn/start" -> repository.setSessionState(event.sessionId, "running")
            "turn/end" -> {
                val kind = data.optJSONObject("reason")?.optString("kind")
                val limited = kind == "blocked" && originalLimitSessions.remove(event.sessionId)
                repository.setSessionState(
                    event.sessionId,
                    when {
                        limited || kind == "max-tokens" -> "limit"
                        kind == "aborted" -> "cancelled"
                        kind == "error" -> "error"
                        kind == "interrupted" -> "interrupted"
                        kind == "blocked" -> "blocked"
                        else -> "idle"
                    },
                )
            }
        }
        if (recordLog) appendOriginalExecutionLog(event, raw, data)
        completedToolCallId?.let(toolCalls::remove)
    }

    private suspend fun upsertOriginalMessage(
        sessionId: String,
        message: JSONObject,
        role: String,
        createdAt: Long,
        interrupted: Boolean = false,
    ) {
        val text = StringBuilder()
        val thinking = StringBuilder()
        val toolCalls = JSONArray()
        val blocks = message.optJSONArray("content") ?: JSONArray()
        for (index in 0 until blocks.length()) {
            val block = blocks.optJSONObject(index) ?: continue
            when (block.optString("type")) {
                "text" -> text.append(block.optString("text"))
                "reasoning" -> thinking.append(block.optString("text"))
                "tool-call" -> {
                    val arguments = block.opt("arguments")
                    toolCalls.put(JSONObject()
                        .put("id", block.optString("id"))
                        .put("name", block.optString("name"))
                        .put("arguments", if (arguments is String) arguments else arguments?.toString() ?: "{}" ))
                }
                else -> {
                    val rawText = block.optString("text")
                    if (rawText.isNotBlank()) text.append(rawText) else text.append(block.toString())
                }
            }
        }
        val source = message.optJSONObject("source") ?: JSONObject()
        val toolCallId = message.optString("toolCallId").ifBlank { source.optString("callId") }
            .takeIf { it.isNotBlank() }
        val toolName = toolCallId?.let { originalToolCalls[sessionId]?.get(it)?.name }
            ?: message.optString("toolName").takeIf { it.isNotBlank() }
        val messageStatus = when {
            interrupted -> "interrupted"
            message.optBoolean("isError") -> "error"
            else -> "complete"
        }
        val id = message.optString("id").ifBlank { UUID.randomUUID().toString() }
        repository.upsertMessage(MessageEntity(
            id = id,
            sessionId = sessionId,
            role = role,
            content = text.toString(),
            thinking = thinking.toString(),
            toolCallsJson = toolCalls.toString(),
            toolCallId = toolCallId,
            toolName = toolName,
            isError = message.optBoolean("isError"),
            status = messageStatus,
            createdAt = createdAt,
        ))
    }

    private suspend fun appendOriginalExecutionLog(
        event: OriginalAgentEvent.SessionEvent,
        raw: JSONObject,
        data: JSONObject,
    ) {
        if (event.eventType in setOf("user/message", "system/message", "developer/message")) return
        val details = redactOriginalSecrets(raw.toString()).take(20_000)
        val callName = when (event.eventType) {
            "tool/call" -> data.optString("name", "tool")
            "tool/result" -> {
                val message = data.optJSONObject("message")
                val callId = message?.optString("toolCallId")
                    ?: message?.optJSONObject("source")?.optString("callId")
                callId?.let { originalToolCalls[event.sessionId]?.get(it)?.name } ?: "tool"
            }
            else -> ""
        }
        val summary = when (event.eventType) {
            "turn/start" -> localizedContext.getString(R.string.engine_log_turn_started)
            "step/start" -> localizedContext.getString(R.string.engine_log_model_step, data.optInt("step", 1))
            "assistant/message" -> {
                val message = data.optJSONObject("message")
                val calls = message?.optJSONArray("content")?.let { blocks ->
                    (0 until blocks.length()).count { blocks.optJSONObject(it)?.optString("type") == "tool-call" }
                } ?: 0
                if (calls == 0) localizedContext.getString(R.string.engine_log_model_completed)
                else localizedContext.getString(R.string.engine_log_tool_count, calls)
            }
            "tool/call" -> localizedContext.getString(R.string.engine_log_execute_tool, callName)
            "tool/result" -> localizedContext.getString(
                if (data.optJSONObject("message")?.optBoolean("isError") == true) R.string.engine_log_tool_failed else R.string.engine_log_tool_completed,
                callName,
            )
            "approval/asked" -> localizedContext.getString(R.string.engine_log_waiting_approval, data.optString("toolName", "plugin"))
            "turn/end" -> when (data.optJSONObject("reason")?.optString("kind")) {
                "error" -> localizedContext.getString(R.string.engine_log_original_event, "turn/error")
                "aborted" -> localizedContext.getString(R.string.engine_log_turn_cancelled)
                else -> localizedContext.getString(R.string.engine_log_turn_completed)
            }
            else -> localizedContext.getString(R.string.engine_log_original_event, event.eventType)
        }
        repository.appendLog(event.sessionId, event.eventType, summary, details)
    }

    private fun redactOriginalSecrets(value: String): String =
        credentials.getApiKey()?.takeIf { it.isNotBlank() }?.let { value.replace(it, "[REDACTED]") } ?: value

    private fun handleOriginalAssistantFrame(event: OriginalAgentEvent.AssistantFrame) {
        val frame = runCatching { JSONObject(event.frameJson) }.getOrNull() ?: return
        val type = frame.optString("type")
        val attemptId = frame.optString("attemptId")
        if (attemptId.isBlank()) return
        when (type) {
            "start" -> {
                val buffer = OriginalStreamBuffer(
                    attemptId = attemptId,
                    messageId = "original-$attemptId",
                    step = frame.optInt("step", 1),
                )
                originalStreams[event.sessionId] = buffer
                _liveAssistants.update {
                    it + (event.sessionId to LiveAssistant(buffer.messageId, "", "", buffer.step))
                }
            }
            "chunk" -> {
                val buffer = originalStreams[event.sessionId]
                    ?.takeIf { it.attemptId == attemptId }
                    ?: OriginalStreamBuffer(attemptId, "original-$attemptId", frame.optInt("step", 1)).also {
                        originalStreams[event.sessionId] = it
                    }
                val chunk = frame.optJSONObject("chunk") ?: return
                val value = chunk.optString("text")
                synchronized(buffer) {
                    when (chunk.optString("type")) {
                        "text-delta" -> buffer.text.append(value)
                        "reasoning-delta" -> buffer.thinking.append(value)
                    }
                    _liveAssistants.update {
                        it + (event.sessionId to LiveAssistant(
                            buffer.messageId,
                            buffer.text.toString(),
                            buffer.thinking.toString(),
                            buffer.step,
                        ))
                    }
                }
            }
            "end" -> {
                val buffer = originalStreams[event.sessionId]
                if (buffer == null || buffer.attemptId == attemptId) {
                    if (buffer != null) originalStreams.remove(event.sessionId, buffer)
                    _liveAssistants.update { it - event.sessionId }
                }
            }
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
        private const val ORIGINAL_PROVIDER_ID = "deepseek-official"
        private const val LEGACY_MESSAGES_PROVIDER_ID = "deepseek-messages"
    }
}
