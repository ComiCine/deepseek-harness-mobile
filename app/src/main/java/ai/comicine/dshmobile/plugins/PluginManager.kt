package ai.comicine.dshmobile.plugins

import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import ai.comicine.dshmobile.R
import ai.comicine.dshmobile.data.PluginDao
import ai.comicine.dshmobile.data.PluginEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PluginTool(
    val pluginId: String,
    val name: String,
    val description: String,
    val parametersJson: String,
)

data class PluginToolResult(
    val output: String,
    val isError: Boolean,
    val additionalContexts: List<String>,
)

data class OriginalAgentInfo(
    val sessionId: String,
    val restored: Boolean,
    val status: String,
)

sealed interface OriginalAgentEvent {
    val sessionId: String

    data class SessionEvent(
        override val sessionId: String,
        val eventType: String,
        val eventJson: String,
    ) : OriginalAgentEvent

    data class AssistantFrame(
        override val sessionId: String,
        val frameJson: String,
    ) : OriginalAgentEvent

    data class ApprovalRequested(
        override val sessionId: String,
        val requestId: String,
        val toolName: String,
        val callId: String?,
        val reason: String?,
        val displayReasonJson: String?,
        val argumentsJson: String,
    ) : OriginalAgentEvent

    data class ApprovalResolved(
        override val sessionId: String,
        val requestId: String,
        val outcome: String,
    ) : OriginalAgentEvent

    data class StepLimitReached(
        override val sessionId: String,
        val step: Int,
        val limit: Int,
    ) : OriginalAgentEvent
}

class PluginCompatibilityException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Manages original npm packages using the unmodified Cordis Loader contract in the embedded Node runtime. */
class PluginManager(context: Context, private val dao: PluginDao) : AutoCloseable {
    private val app = context.applicationContext
    private val localizedContext = ContextCompat.getContextForLanguage(app)
    private val runtime = EmbeddedNodeRuntime(app)
    private val root = runtime.pluginsRoot
    val pluginsRoot: File get() = root
    private val stateMutex = Mutex()
    private val agentMutexes = ConcurrentHashMap<String, Mutex>()
    private val pendingOriginalApprovals = ConcurrentHashMap<String, String>()
    private val _originalAgentEvents = MutableSharedFlow<OriginalAgentEvent>(replay = 64, extraBufferCapacity = 256)
    private var restoredRuntimeState = false
    val plugins: Flow<List<PluginEntity>> = dao.observeAll()
    val originalAgentEvents: SharedFlow<OriginalAgentEvent> = _originalAgentEvents.asSharedFlow()

    suspend fun install(specifier: String): PluginEntity = withContext(Dispatchers.IO) {
        val metadata = invokeHost("installPackage", specifier.trim(), "", "")
        storeInstalledPackage(JSONObject(metadata))
    }

    suspend fun install(uri: Uri): PluginEntity = withContext(Dispatchers.IO) {
        val temporary = File(root, "packages/.${UUID.randomUUID()}.tgz").apply { parentFile?.mkdirs() }
        var archive: File? = null
        var backup: File? = null
        try {
            val packageName = app.contentResolver.openInputStream(uri)?.use(EmbeddedNodeRuntime::packageNameFromTarball)
                ?: throw PluginCompatibilityException(localizedContext.getString(R.string.plugin_error_read_package))
            archive = File(root, "packages/${packageKey(packageName)}.tgz")
            backup = if (archive.isFile) File(root, "packages/.${UUID.randomUUID()}.backup.tgz").also { archive.copyTo(it, overwrite = true) } else null
            app.contentResolver.openInputStream(uri)?.use { input -> temporary.outputStream().buffered().use { input.copyTo(it) } }
                ?: throw PluginCompatibilityException(localizedContext.getString(R.string.plugin_error_read_package))
            if (archive.exists()) archive.delete()
            temporary.renameTo(archive)
            val metadata = invokeHost("installPackage", packageName, archive.absolutePath, packageName)
            storeInstalledPackage(JSONObject(metadata))
        } catch (error: Exception) {
            archive?.delete()
            backup?.let { if (it.exists()) it.renameTo(archive!!) }
            if (error is PluginCompatibilityException) throw error
            if (error is PluginPackageException) {
                throw PluginCompatibilityException(localizedContext.getString(error.messageResourceId), error)
            }
            throw PluginCompatibilityException(
                localizedContext.getString(R.string.plugin_error_install, error.message ?: error.javaClass.simpleName),
                error,
            )
        } finally {
            temporary.delete()
            backup?.delete()
        }
    }

    suspend fun refreshInstalledMetadata() = withContext(Dispatchers.IO) {
        restoreEnabledPluginsLocked()
    }

    suspend fun enable(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val plugin = dao.get(id) ?: throw PluginCompatibilityException(localizedContext.getString(R.string.plugin_error_not_installed))
        restoreEnabledPluginsLocked()
        if (enabled) {
            try {
                invokeHost("activate", plugin.packageName, plugin.configJson)
                dao.setEnabled(id, true, "[]")
            } catch (error: Exception) {
                throw PluginCompatibilityException(localizedContext.getString(R.string.plugin_error_activate, error.message ?: error.javaClass.simpleName), error)
            }
        } else {
            try {
                invokeHost("deactivate", plugin.packageName)
                dao.setEnabled(id, false, "[]")
            } catch (error: Exception) {
                throw PluginCompatibilityException(localizedContext.getString(R.string.plugin_error_deactivate, error.message ?: error.javaClass.simpleName), error)
            }
        }
    }

    suspend fun uninstall(id: String) = withContext(Dispatchers.IO) {
        val plugin = dao.get(id) ?: return@withContext
        restoreEnabledPluginsLocked()
        try {
            if (plugin.enabled) invokeHost("deactivate", plugin.packageName)
            dao.setEnabled(id, false, "[]")
            invokeHost("uninstall", plugin.packageName)
            dao.delete(plugin)
            File(root, "packages/${packageKey(plugin.packageName)}.tgz").delete()
        } catch (error: Exception) {
            throw PluginCompatibilityException(localizedContext.getString(R.string.plugin_error_uninstall, error.message ?: error.javaClass.simpleName), error)
        }
    }

    suspend fun setConfiguration(id: String, json: String) = withContext(Dispatchers.IO) {
        require(json.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { localizedContext.getString(R.string.plugin_error_config_size) }
        val config = try { JSONObject(json).toString() }
        catch (error: Exception) { throw PluginCompatibilityException(localizedContext.getString(R.string.plugin_error_config_object), error) }
        val plugin = dao.get(id) ?: throw PluginCompatibilityException(localizedContext.getString(R.string.plugin_error_not_installed))
        restoreEnabledPluginsLocked()
        if (plugin.enabled) {
            try {
                invokeHost("updateConfig", plugin.packageName, config)
                dao.setConfig(id, config)
            } catch (error: Exception) {
                runCatching { invokeHost("updateConfig", plugin.packageName, plugin.configJson) }
                throw PluginCompatibilityException(localizedContext.getString(R.string.plugin_error_config_update, error.message ?: error.javaClass.simpleName), error)
            }
        } else {
            dao.setConfig(id, config)
        }
    }

    suspend fun activeTools(sessionId: String): List<PluginTool> {
        val active = dao.observeAll().first().any { it.enabled }
        if (!active) return emptyList()
        restoreEnabledPluginsLocked()
        val schemas = JSONArray(invokeHost("schemas", sessionId))
        return buildList {
            for (index in 0 until schemas.length()) {
                val schema = schemas.getJSONObject(index)
                val name = schema.getString("name")
                val parameters = schema.optJSONObject("parameters") ?: JSONObject()
                add(PluginTool("upstream", name, schema.optString("description"), parameters.toString()))
            }
        }
    }

    suspend fun invoke(sessionId: String, callId: String, toolName: String, argumentsJson: String): PluginToolResult {
        restoreEnabledPluginsLocked()
        val raw = JSONObject(invokeHost("executeTool", sessionId, callId, toolName, argumentsJson))
        val rows = raw.optJSONArray("results") ?: throw PluginCompatibilityException(localizedContext.getString(R.string.plugin_error_missing_tool_result))
        val result = rows.optJSONObject(0)?.optJSONObject("result")
            ?: throw PluginCompatibilityException(localizedContext.getString(R.string.plugin_error_empty_tool_batch))
        val content = result.optJSONArray("content") ?: JSONArray()
        val text = buildList {
            for (index in 0 until content.length()) {
                val block = content.optJSONObject(index) ?: continue
                if (block.optString("type") == "text") add(block.optString("text"))
                else if (block.has("text")) add(block.optString("text"))
            }
        }.joinToString("\n").ifBlank {
            result.optJSONObject("value")?.toString() ?: result.opt("value")?.toString().orEmpty()
        }
        val contextRows = result.optJSONArray("additionalContexts") ?: raw.optJSONArray("additionalContexts") ?: JSONArray()
        val contexts = buildList<String> {
            for (index in 0 until contextRows.length()) {
                val message = contextRows.optJSONObject(index) ?: continue
                val blocks = message.optJSONArray("content") ?: JSONArray()
                val body = buildList {
                    for (blockIndex in 0 until blocks.length()) {
                        val block = blocks.optJSONObject(blockIndex) ?: continue
                        if (block.optString("type") == "text") add(block.optString("text"))
                    }
                }.joinToString("\n")
                if (body.isNotBlank()) add(body)
            }
        }
        return PluginToolResult(text.ifBlank { localizedContext.getString(R.string.plugin_error_empty_result) }, result.optBoolean("isError"), contexts)
    }

    suspend fun beforeStep(sessionId: String, hasUserMessage: Boolean) {
        val active = dao.observeAll().first().any { it.enabled }
        if (active) {
            restoreEnabledPluginsLocked()
            invokeHost("preStep", sessionId, hasUserMessage.toString())
        }
    }

    suspend fun sessionEvents(sessionId: String): String = withContext(Dispatchers.IO) {
        invokeHost("sessionEvents", sessionId)
    }

    /** Converts an existing Room transcript to a local upstream Session seed exactly once. */
    suspend fun importLegacySession(
        sessionId: String,
        provider: String,
        model: String,
        messagesJson: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val input = File(app.cacheDir, "dsh-session-migration-${UUID.randomUUID()}.json")
        try {
            input.writeText(messagesJson, Charsets.UTF_8)
            JSONObject(invokeAgentHost(
                "importLegacySessionFile",
                sessionId,
                input.absolutePath,
                provider,
                model,
                timeoutMs = EmbeddedNodeRuntime.DEFAULT_TIMEOUT_MS,
            )).optBoolean("imported")
        } finally {
            input.delete()
        }
    }

    suspend fun createOrRestoreOriginalAgent(
        sessionId: String,
        provider: String,
        model: String,
        baseURL: String,
        configurationJson: String = "{}",
    ): OriginalAgentInfo = withContext(Dispatchers.IO) {
        require(sessionId.isNotBlank()) { "Session id is required" }
        require(provider.isNotBlank()) { "Provider id is required" }
        require(model.isNotBlank()) { "Model id is required" }
        require(baseURL.isNotBlank()) { "Messages base URL is required" }
        restoreEnabledPluginsLocked()
        val result = JSONObject(invokeAgentHost(
            "createOrRestoreAgent",
            sessionId,
            provider,
            model,
            baseURL,
            configurationJson,
            timeoutMs = EmbeddedNodeRuntime.DEFAULT_TIMEOUT_MS,
        ))
        OriginalAgentInfo(
            sessionId = result.getString("sessionId"),
            restored = result.getBoolean("restored"),
            status = result.getString("status"),
        )
    }

    /** Submits one real upstream user message and returns the original Session event array after idle. */
    suspend fun submitUserMessageAndAwaitIdle(
        sessionId: String,
        text: String,
        provider: String,
        model: String,
        baseURL: String,
        apiKey: String,
        configurationJson: String = "{}",
    ): String = withContext(Dispatchers.IO) {
        require(text.isNotBlank()) { "User message is required" }
        require(apiKey.isNotBlank()) { "DeepSeek API key is required" }
        require(provider.isNotBlank()) { "Provider id is required" }
        require(model.isNotBlank()) { "Model id is required" }
        require(baseURL.isNotBlank()) { "Messages base URL is required" }
        restoreEnabledPluginsLocked()
        val sessionMutex = agentMutexes.getOrPut(sessionId) { Mutex() }
        sessionMutex.withLock {
            invokeAgentHost(
                "createOrRestoreAgent",
                sessionId,
                provider,
                model,
                baseURL,
                configurationJson,
                timeoutMs = EmbeddedNodeRuntime.DEFAULT_TIMEOUT_MS,
            )
            var started = false
            try {
                started = true
                runtime.callWithSensitiveArgument(
                    operation = "submitUserMessage",
                    arguments = listOf(sessionId, text, apiKey, provider, model, baseURL, configurationJson),
                    sensitiveArgumentIndex = 2,
                    timeoutMs = AGENT_CONTROL_TIMEOUT_MS,
                )
                awaitOriginalAgentIdle(sessionId)
                invokeAgentHost("sessionEvents", sessionId)
            } catch (cancelled: CancellationException) {
                if (started) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { invokeAgentHost("cancelAgent", sessionId) }
                        runCatching { awaitOriginalAgentIdle(sessionId) }
                    }
                }
                throw cancelled
            } catch (error: Exception) {
                if (started) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { invokeAgentHost("cancelAgent", sessionId) }
                        runCatching { awaitOriginalAgentIdle(sessionId) }
                    }
                }
                val detail = (error.message ?: error.javaClass.simpleName).replace(apiKey, "[REDACTED]")
                throw PluginCompatibilityException("Original Agent submission failed: $detail", error)
            }
        }
    }

    suspend fun readOriginalSessionEvents(sessionId: String): String = withContext(Dispatchers.IO) {
        invokeAgentHost("sessionEvents", sessionId, timeoutMs = EmbeddedNodeRuntime.DEFAULT_TIMEOUT_MS)
    }

    suspend fun cancelOriginalAgent(sessionId: String) = withContext(Dispatchers.IO) {
        invokeAgentHost("cancelAgent", sessionId)
        awaitOriginalAgentIdle(sessionId)
    }

    /** Resolves one original dsh-user-approval request; false is a default-deny decision. */
    suspend fun resolveOriginalApproval(requestId: String, allow: Boolean) = withContext(Dispatchers.IO) {
        require(requestId.isNotBlank()) { "Approval request id is required" }
        val sessionId = pendingOriginalApprovals[requestId]
            ?: throw PluginCompatibilityException("The original approval request is no longer pending")
        val outcome = if (allow) "allowed-once" else "rejected"
        val result = JSONObject(invokeAgentHost("resolveApproval", requestId, outcome))
        if (!result.optBoolean("resolved")) {
            pendingOriginalApprovals.remove(requestId, sessionId)
            throw PluginCompatibilityException("The original approval request is no longer pending")
        }
    }

    suspend fun releaseOriginalAgent(sessionId: String) = withContext(Dispatchers.IO) {
        runCatching { cancelOriginalAgent(sessionId) }
        invokeAgentHost("releaseAgent", sessionId)
        agentMutexes.remove(sessionId)
    }

    suspend fun deleteSessionData(sessionId: String) = withContext(Dispatchers.IO) {
        invokeHost("deleteSessionData", sessionId)
    }

    private suspend fun restoreEnabledPluginsLocked() = stateMutex.withLock {
        if (restoredRuntimeState) return@withLock
        val enabled = dao.observeAll().first().filter { it.enabled }
        for (plugin in enabled) invokeHost("activate", plugin.packageName, plugin.configJson)
        restoredRuntimeState = true
    }

    private suspend fun awaitOriginalAgentIdle(sessionId: String) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val state = JSONObject(invokeAgentHost("agentRunState", sessionId))
            emitOriginalAgentEvents(sessionId, state.optJSONArray("events") ?: JSONArray())
            if (state.optBoolean("eventOverflow")) {
                throw PluginCompatibilityException("Original Agent event stream exceeded its local buffer")
            }
            when (state.optString("status")) {
                "running" -> delay(AGENT_POLL_INTERVAL_MS)
                "failed" -> throw PluginCompatibilityException(
                    state.optString("error").ifBlank { "Original AgentLoop failed" },
                )
                else -> return
            }
        }
    }

    private suspend fun emitOriginalAgentEvents(sessionId: String, events: JSONArray) {
        for (index in 0 until events.length()) {
            val event = events.optJSONObject(index) ?: continue
            when (event.optString("kind")) {
                "session" -> {
                    val raw = event.optJSONObject("event") ?: continue
                    _originalAgentEvents.emit(
                        OriginalAgentEvent.SessionEvent(
                            sessionId = sessionId,
                            eventType = event.optString("eventType"),
                            eventJson = raw.toString(),
                        ),
                    )
                }
                "assistant-stream" -> {
                    val frame = event.optJSONObject("frame") ?: continue
                    _originalAgentEvents.emit(OriginalAgentEvent.AssistantFrame(sessionId, frame.toString()))
                }
                "approval-request" -> {
                    val requestId = event.optString("requestId")
                    if (requestId.isBlank()) continue
                    pendingOriginalApprovals[requestId] = sessionId
                    _originalAgentEvents.emit(
                        OriginalAgentEvent.ApprovalRequested(
                            sessionId = sessionId,
                            requestId = requestId,
                            toolName = event.optString("toolName"),
                            callId = event.optString("callId").takeIf(String::isNotBlank),
                            reason = event.optString("reason").takeIf(String::isNotBlank),
                            displayReasonJson = event.optJSONObject("displayReason")?.toString(),
                            argumentsJson = event.optString("argumentsJson", "{}"),
                        ),
                    )
                }
                "approval-resolved" -> {
                    val requestId = event.optString("requestId")
                    if (requestId.isBlank()) continue
                    pendingOriginalApprovals.remove(requestId, sessionId)
                    _originalAgentEvents.emit(
                        OriginalAgentEvent.ApprovalResolved(
                            sessionId = sessionId,
                            requestId = requestId,
                            outcome = event.optString("outcome", "unavailable"),
                        ),
                    )
                }
                "step-limit" -> {
                    _originalAgentEvents.emit(
                        OriginalAgentEvent.StepLimitReached(
                            sessionId = sessionId,
                            step = event.optInt("step"),
                            limit = event.optInt("limit"),
                        ),
                    )
                }
            }
        }
    }

    private suspend fun storeInstalledPackage(metadata: JSONObject): PluginEntity {
        val packageName = metadata.getString("packageName")
        val manifest = JSONObject(metadata.getString("manifestJson"))
        val existing = dao.get(packageName)
        if (existing?.enabled == true) invokeHost("deactivate", existing.packageName)
        val entity = PluginEntity(
            id = packageName,
            packageName = packageName,
            displayName = manifest.optString("displayName", packageName),
            version = metadata.getString("version"),
            entryPath = metadata.getString("entryPath"),
            manifestJson = metadata.getString("manifestJson"),
            configJson = existing?.configJson ?: "{}",
            grantedPermissionsJson = "[]",
            enabled = false,
            sourceSha256 = metadata.getString("sourceSha256"),
            installedAt = System.currentTimeMillis(),
        )
        dao.put(entity)
        restoredRuntimeState = false
        return entity
    }

    private suspend fun invokeHost(operation: String, vararg arguments: String): String = try {
        runtime.call(operation, *arguments)
    } catch (error: Exception) {
        throw PluginCompatibilityException(error.message ?: error.javaClass.simpleName, error)
    }

    private suspend fun invokeAgentHost(
        operation: String,
        vararg arguments: String,
        timeoutMs: Long = AGENT_CONTROL_TIMEOUT_MS,
    ): String = try {
        runtime.callNonCancellable(operation, *arguments, timeoutMs = timeoutMs)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        val detail = error.message ?: error.javaClass.simpleName
        throw PluginCompatibilityException("Cordis host operation '$operation' failed: $detail", error)
    }

    override fun close() = runtime.close()

    private fun packageKey(packageName: String): String = MessageDigest.getInstance("SHA-256")
        .digest(packageName.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val AGENT_CONTROL_TIMEOUT_MS = 30_000L
        const val AGENT_POLL_INTERVAL_MS = 40L
    }
}
