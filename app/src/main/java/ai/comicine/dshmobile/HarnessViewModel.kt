package ai.comicine.dshmobile

import android.app.Application
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.comicine.dshmobile.data.HarnessSettings
import ai.comicine.dshmobile.data.MessageEntity
import ai.comicine.dshmobile.data.SessionEntity
import ai.comicine.dshmobile.execution.ExecutionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.io.File
import org.json.JSONException

class HarnessViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HarnessApplication
    private val localizedContext = ContextCompat.getContextForLanguage(application)
    private val _sessions = MutableStateFlow<List<SessionEntity>>(emptyList())
    val sessions: StateFlow<List<SessionEntity>> = _sessions.asStateFlow()
    private val _selectedSessionId = MutableStateFlow<String?>(null)
    val selectedSessionId: StateFlow<String?> = _selectedSessionId.asStateFlow()
    private val _hasApiKey = MutableStateFlow(app.engine.hasApiKey())
    val hasApiKey: StateFlow<Boolean> = _hasApiKey.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    private val _exportWorkspacePath = MutableStateFlow<String?>(null)
    val exportWorkspacePath: StateFlow<String?> = _exportWorkspacePath.asStateFlow()
    private val _workspaceRevision = MutableStateFlow(0)
    val workspaceRevision: StateFlow<Int> = _workspaceRevision.asStateFlow()

    val settings = app.engine.settings
    val approvals = app.engine.approvals
    val liveAssistants = app.engine.liveAssistants
    val runningSessions = app.engine.runningSessions
    val plugins = app.pluginManager.plugins
    val pluginManager = app.pluginManager

    init {
        viewModelScope.launch {
            app.repository.sessions.collectLatest { rows ->
                _sessions.value = rows
                if (_selectedSessionId.value != null && rows.none { it.id == _selectedSessionId.value }) {
                    _selectedSessionId.value = rows.firstOrNull()?.id
                }
            }
        }
    }

    fun selectSession(id: String?) {
        _selectedSessionId.value = id
    }

    fun messages(sessionId: String) = app.repository.messages(sessionId)
    fun logs(sessionId: String) = app.database.logs().observeRecent(sessionId)

    suspend fun listWorkspace(path: String): List<WorkspaceEntry> = withContext(Dispatchers.IO) {
        val dir = workspacePath(path)
        if (!dir.isDirectory) return@withContext emptyList()
        dir.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))?.map {
            WorkspaceEntry(
                name = it.name,
                relativePath = it.relativeTo(workspaceRoot()).path,
                isDirectory = it.isDirectory,
                sizeBytes = if (it.isFile) it.length() else 0,
            )
        }.orEmpty()
    }

    suspend fun readWorkspaceFile(path: String): String = withContext(Dispatchers.IO) {
        val file = workspacePath(path)
        require(file.isFile) { localizedString(R.string.error_not_a_file) }
        require(file.length() <= MAX_WORKSPACE_FILE_BYTES) { localizedString(R.string.error_file_too_large) }
        file.readText(Charsets.UTF_8).take(MAX_WORKSPACE_PREVIEW_CHARS)
    }

    fun createWorkspaceFile(path: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = workspacePath(path)
                require(!file.exists()) { localizedString(R.string.error_file_exists) }
                require(content.toByteArray(Charsets.UTF_8).size <= MAX_WORKSPACE_FILE_BYTES) { localizedString(R.string.error_file_too_large) }
                file.parentFile?.mkdirs()
                file.writeText(content, Charsets.UTF_8)
                _workspaceRevision.value++
                _notice.value = localizedString(R.string.notice_file_created, file.name)
            } catch (error: Exception) {
                _notice.value = localizedString(R.string.notice_create_failed, error.message ?: localizedString(R.string.error_unknown))
            }
        }
    }

    fun deleteWorkspaceEntry(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = workspacePath(path)
                require(file != workspaceRoot()) { localizedString(R.string.error_workspace_root_delete) }
                if (file.isDirectory) require(file.listFiles().isNullOrEmpty()) { localizedString(R.string.error_folder_not_empty) }
                require(file.delete()) { localizedString(R.string.error_file_delete_failed) }
                _workspaceRevision.value++
                _notice.value = localizedString(R.string.notice_file_deleted, file.name)
            } catch (error: Exception) {
                _notice.value = localizedString(R.string.notice_delete_failed, error.message ?: localizedString(R.string.error_unknown))
            }
        }
    }

    fun setWorkspaceExportPath(path: String) { _exportWorkspacePath.value = path }

    fun writeWorkspaceExport(uri: Uri) {
        val path = _exportWorkspacePath.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = workspacePath(path)
                require(file.length() <= MAX_WORKSPACE_FILE_BYTES) { localizedString(R.string.error_file_export_too_large) }
                val content = file.readBytes()
                app.contentResolver.openOutputStream(uri)?.use { it.write(content) }
                    ?: throw IllegalStateException(localizedString(R.string.error_selected_file_write))
                _notice.value = localizedString(R.string.notice_workspace_file_exported)
            } catch (error: Exception) {
                _notice.value = localizedString(R.string.notice_workspace_export_failed, error.message ?: localizedString(R.string.error_unknown))
            } finally {
                _exportWorkspacePath.value = null
            }
        }
    }

    fun importWorkspaceFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?.substringAfterLast('/')
                    ?.takeIf { it.isNotBlank() && it != "." && it != ".." }
                    ?: "imported-file"
                require(name.none { it == '\\' || it == '\n' || it == '\r' }) { localizedString(R.string.error_filename_unsupported) }
                val output = workspacePath(name)
                require(!output.exists()) { localizedString(R.string.error_duplicate_workspace_name) }
                val temp = File(output.parentFile, ".${output.name}.${UUID.randomUUID()}.import")
                try {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { destination ->
                            val buffer = ByteArray(16 * 1024)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_WORKSPACE_FILE_BYTES) { localizedString(R.string.error_file_too_large) }
                                destination.write(buffer, 0, count)
                            }
                        }
                    } ?: throw IllegalStateException(localizedString(R.string.error_selected_file_read))
                    require(temp.renameTo(output)) { localizedString(R.string.error_workspace_write) }
                } finally {
                    temp.delete()
                }
                _workspaceRevision.value++
                _notice.value = localizedString(R.string.notice_workspace_file_imported, name)
            } catch (error: Exception) {
                _notice.value = localizedString(R.string.notice_import_failed, error.message ?: localizedString(R.string.error_unknown))
            }
        }
    }

    fun newSession(onCreated: (() -> Unit)? = null) {
        viewModelScope.launch {
            val session = app.engine.newSession()
            _selectedSessionId.value = session.id
            onCreated?.invoke()
        }
    }

    fun send(prompt: String) {
        viewModelScope.launch {
            try {
                val sessionId = selectedSessionId.value ?: app.engine.newSession().id.also { _selectedSessionId.value = it }
                ExecutionService.start(app)
                app.engine.start(sessionId, prompt)
            } catch (error: Exception) {
                _notice.value = error.message ?: localizedString(R.string.notice_task_start_failed)
            }
        }
    }

    fun retryLastTurn(sessionId: String) {
        viewModelScope.launch {
            try {
                val lastPrompt = app.repository.messagesNow(sessionId).lastOrNull { it.role == "user" }?.content
                    ?: throw IllegalStateException(localizedString(R.string.error_no_retry_prompt))
                ExecutionService.start(app)
                app.engine.start(sessionId, lastPrompt)
            } catch (error: Exception) {
                _notice.value = localizedString(R.string.notice_retry_failed, error.message ?: localizedString(R.string.error_unknown))
            }
        }
    }

    fun cancel(sessionId: String) = app.engine.cancel(sessionId)
    fun approve(id: String, allow: Boolean) = app.engine.approve(id, allow)

    fun saveSettings(settings: HarnessSettings) {
        app.engine.updateSettings(settings)
        _notice.value = localizedString(R.string.notice_settings_saved)
    }

    fun saveApiKey(key: String) {
        try {
            app.engine.setApiKey(key)
            _hasApiKey.value = app.engine.hasApiKey()
            _notice.value = localizedString(if (key.isBlank()) R.string.notice_api_key_removed else R.string.notice_api_key_saved)
        } catch (error: Exception) {
            _notice.value = localizedString(R.string.notice_api_key_save_failed, error.message ?: localizedString(R.string.error_unknown))
        }
    }

    suspend fun exportSession(sessionId: String): String = withContext(Dispatchers.IO) {
        app.engine.exportSession(sessionId)
    }

    fun writeExport(uri: Uri, sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val content = exportSession(sessionId)
                app.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    ?: throw IllegalStateException(localizedString(R.string.error_selected_file_write))
                _notice.value = localizedString(R.string.notice_session_exported)
            } catch (error: Exception) {
                _notice.value = localizedString(R.string.notice_session_export_failed, error.message ?: localizedString(R.string.error_unknown))
            }
        }
    }

    fun importSession(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val raw = app.contentResolver.openInputStream(uri)?.use { input -> readCapped(input, MAX_IMPORT_BYTES).toString(Charsets.UTF_8) }
                    ?: throw IllegalArgumentException(localizedString(R.string.error_selected_file_read))
                val root = JSONObject(raw)
                require(root.optString("format") == "deepseek-harness-mobile-session-v1") { localizedString(R.string.error_session_export_format) }
                val oldSession = root.getJSONObject("session")
                val messages = root.getJSONArray("messages")
                require(messages.length() <= MAX_IMPORT_MESSAGES) { localizedString(R.string.error_session_message_count) }
                val session = app.database.withTransaction {
                    val created = app.repository.createSession(
                        provider = oldSession.optString("provider", "deepseek-messages"),
                        model = oldSession.optString("model", settings.value.model),
                        title = oldSession.optString("title", localizedString(R.string.session_imported_title)).take(120),
                    )
                    for (index in 0 until messages.length()) {
                        val item = messages.getJSONObject(index)
                        val role = item.optString("role")
                        if (role !in setOf("user", "assistant", "tool")) continue
                        val content = item.optString("content")
                        require(content.length <= 2_000_000) { localizedString(R.string.error_session_message_size) }
                        app.repository.saveMessage(MessageEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = created.id,
                            role = role,
                            content = content,
                            thinking = item.optString("thinking").take(1_000_000),
                            toolCallsJson = item.optJSONArray("toolCalls")?.toString() ?: "[]",
                            toolCallId = item.optString("toolCallId").takeIf { it.isNotBlank() && it != "null" },
                            toolName = item.optString("toolName").takeIf { it.isNotBlank() && it != "null" },
                            isError = item.optBoolean("isError"),
                            status = if (item.optString("status") == "error") "error" else "complete",
                            createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                        ))
                    }
                    created
                }
                _selectedSessionId.value = session.id
                _notice.value = localizedString(R.string.notice_session_imported, messages.length())
            } catch (error: Exception) {
                _notice.value = localizedString(R.string.notice_import_failed, error.message ?: localizedString(R.string.error_unknown))
            }
        }
    }

    fun renameSession(session: SessionEntity, title: String) {
        viewModelScope.launch { app.repository.rename(session.id, title.trim().take(120).ifBlank { localizedString(R.string.session_untitled_title) }) }
    }

    fun deleteSession(session: SessionEntity) {
        if (app.engine.isRunning(session.id)) app.engine.cancel(session.id)
        viewModelScope.launch {
            try {
                app.pluginManager.deleteSessionData(session.id)
                app.repository.delete(session)
                if (_selectedSessionId.value == session.id) _selectedSessionId.value = null
            } catch (error: Exception) {
                _notice.value = localizedString(R.string.notice_session_deleted_error, error.message ?: localizedString(R.string.error_unknown))
            }
        }
    }

    fun installPlugin(uri: Uri) {
        viewModelScope.launch {
            try {
                val plugin = app.pluginManager.install(uri)
                _notice.value = localizedString(R.string.notice_plugin_installed, plugin.displayName, plugin.version)
            } catch (error: Exception) {
                _notice.value = localizedString(R.string.notice_plugin_install_failed, error.message ?: localizedString(R.string.error_unknown))
            }
        }
    }

    fun installPlugin(specifier: String) {
        viewModelScope.launch {
            try {
                val plugin = app.pluginManager.install(specifier)
                _notice.value = localizedString(R.string.notice_upstream_plugin_installed, plugin.packageName, plugin.version)
            } catch (error: Exception) {
                _notice.value = localizedString(R.string.notice_plugin_install_failed, error.message ?: localizedString(R.string.error_unknown))
            }
        }
    }

    suspend fun enablePlugin(id: String, enabled: Boolean) = app.pluginManager.enable(id, enabled)
    suspend fun uninstallPlugin(id: String) = app.pluginManager.uninstall(id)
    suspend fun setPluginConfig(id: String, config: String) = app.pluginManager.setConfiguration(id, config)

    fun clearNotice() { _notice.value = null }
    fun reportNotice(message: String) { _notice.value = message }
    fun reportLocalizedNotice(resourceId: Int, vararg formatArgs: Any) {
        _notice.value = localizedString(resourceId, *formatArgs)
    }
    fun reportLocalizedFailure(resourceId: Int, error: Throwable) {
        reportLocalizedNotice(resourceId, error.message?.takeIf { it.isNotBlank() } ?: localizedString(R.string.error_unknown))
    }
    fun localizedString(resourceId: Int, vararg formatArgs: Any): String =
        localizedContext.getString(resourceId, *formatArgs)

    private fun workspaceRoot() = File(app.filesDir, "workspace").apply { mkdirs() }.canonicalFile

    private fun readCapped(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { localizedString(R.string.error_import_file_too_large) }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun workspacePath(relative: String): File {
        require(relative.isNotBlank() && !File(relative).isAbsolute) { localizedString(R.string.error_relative_path_required) }
        val root = workspaceRoot()
        val file = File(root, relative).canonicalFile
        require(file.path == root.path || file.path.startsWith(root.path + File.separator)) { localizedString(R.string.error_path_outside_workspace) }
        return file
    }

    override fun onCleared() {
        super.onCleared()
    }

    companion object {
        private const val MAX_IMPORT_BYTES = 20 * 1024 * 1024
        private const val MAX_IMPORT_MESSAGES = 100_000
        private const val MAX_WORKSPACE_FILE_BYTES = 2L * 1024 * 1024
        private const val MAX_WORKSPACE_PREVIEW_CHARS = 100_000
    }
}

data class WorkspaceEntry(val name: String, val relativePath: String, val isDirectory: Boolean, val sizeBytes: Long)
