package ai.comicine.dshmobile

import android.app.Application
import android.net.Uri
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
        require(file.isFile) { "不是文件" }
        require(file.length() <= MAX_WORKSPACE_FILE_BYTES) { "文件超过 2 MiB" }
        file.readText(Charsets.UTF_8).take(MAX_WORKSPACE_PREVIEW_CHARS)
    }

    fun createWorkspaceFile(path: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = workspacePath(path)
                require(!file.exists()) { "文件已存在" }
                require(content.toByteArray(Charsets.UTF_8).size <= MAX_WORKSPACE_FILE_BYTES) { "文件超过 2 MiB" }
                file.parentFile?.mkdirs()
                file.writeText(content, Charsets.UTF_8)
                _workspaceRevision.value++
                _notice.value = "已创建 ${file.name}"
            } catch (error: Exception) {
                _notice.value = "创建失败：${error.message ?: "未知错误"}"
            }
        }
    }

    fun deleteWorkspaceEntry(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = workspacePath(path)
                require(file != workspaceRoot()) { "不能删除工作区根目录" }
                if (file.isDirectory) require(file.listFiles().isNullOrEmpty()) { "文件夹非空，请先删除其中内容" }
                require(file.delete()) { "无法删除文件" }
                _workspaceRevision.value++
                _notice.value = "已删除 ${file.name}"
            } catch (error: Exception) {
                _notice.value = "删除失败：${error.message ?: "未知错误"}"
            }
        }
    }

    fun setWorkspaceExportPath(path: String) { _exportWorkspacePath.value = path }

    fun writeWorkspaceExport(uri: Uri) {
        val path = _exportWorkspacePath.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = workspacePath(path)
                require(file.length() <= MAX_WORKSPACE_FILE_BYTES) { "文件超过 2 MiB，不能通过工作区导出" }
                val content = file.readBytes()
                app.contentResolver.openOutputStream(uri)?.use { it.write(content) }
                    ?: throw IllegalStateException("无法写入所选文件")
                _notice.value = "工作区文件已导出"
            } catch (error: Exception) {
                _notice.value = "导出失败：${error.message ?: "未知错误"}"
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
                require(name.none { it == '\\' || it == '\n' || it == '\r' }) { "文件名包含不支持的字符" }
                val output = workspacePath(name)
                require(!output.exists()) { "工作区已存在同名文件，请先重命名或删除" }
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
                                require(total <= MAX_WORKSPACE_FILE_BYTES) { "文件超过 2 MiB" }
                                destination.write(buffer, 0, count)
                            }
                        }
                    } ?: throw IllegalStateException("无法读取所选文件")
                    require(temp.renameTo(output)) { "无法将文件写入工作区" }
                } finally {
                    temp.delete()
                }
                _workspaceRevision.value++
                _notice.value = "已导入 $name 到本机工作区"
            } catch (error: Exception) {
                _notice.value = "导入失败：${error.message ?: "未知错误"}"
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
                _notice.value = error.message ?: "无法启动任务"
            }
        }
    }

    fun retryLastTurn(sessionId: String) {
        viewModelScope.launch {
            try {
                val lastPrompt = app.repository.messagesNow(sessionId).lastOrNull { it.role == "user" }?.content
                    ?: throw IllegalStateException("当前会话没有可重试的用户请求")
                ExecutionService.start(app)
                app.engine.start(sessionId, lastPrompt)
            } catch (error: Exception) {
                _notice.value = "重试失败：${error.message ?: "未知错误"}"
            }
        }
    }

    fun cancel(sessionId: String) = app.engine.cancel(sessionId)
    fun approve(id: String, allow: Boolean) = app.engine.approve(id, allow)

    fun saveSettings(settings: HarnessSettings) {
        app.engine.updateSettings(settings)
        _notice.value = "设置已保存在此设备"
    }

    fun saveApiKey(key: String) {
        try {
            app.engine.setApiKey(key)
            _hasApiKey.value = app.engine.hasApiKey()
            _notice.value = if (key.isBlank()) "已从本设备移除 API Key" else "API Key 已加密保存在 Android Keystore"
        } catch (error: Exception) {
            _notice.value = "无法保存 API Key：${error.message ?: "Keystore error"}"
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
                    ?: throw IllegalStateException("无法写入所选文件")
                _notice.value = "会话已导出"
            } catch (error: Exception) {
                _notice.value = "导出失败：${error.message ?: "未知错误"}"
            }
        }
    }

    fun importSession(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val raw = app.contentResolver.openInputStream(uri)?.use { input -> readCapped(input, MAX_IMPORT_BYTES).toString(Charsets.UTF_8) }
                    ?: throw IllegalArgumentException("无法读取所选文件")
                val root = JSONObject(raw)
                require(root.optString("format") == "deepseek-harness-mobile-session-v1") { "不支持的会话导出格式" }
                val oldSession = root.getJSONObject("session")
                val messages = root.getJSONArray("messages")
                require(messages.length() <= MAX_IMPORT_MESSAGES) { "会话消息数量超过限制" }
                val session = app.database.withTransaction {
                    val created = app.repository.createSession(
                        provider = oldSession.optString("provider", "deepseek-messages"),
                        model = oldSession.optString("model", settings.value.model),
                        title = oldSession.optString("title", "导入会话").take(120),
                    )
                    for (index in 0 until messages.length()) {
                        val item = messages.getJSONObject(index)
                        val role = item.optString("role")
                        if (role !in setOf("user", "assistant", "tool")) continue
                        val content = item.optString("content")
                        require(content.length <= 2_000_000) { "单条消息超过 2 MB" }
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
                _notice.value = "已导入 ${messages.length()} 条记录"
            } catch (error: Exception) {
                _notice.value = "导入失败：${error.message ?: "文件格式错误"}"
            }
        }
    }

    fun renameSession(session: SessionEntity, title: String) {
        viewModelScope.launch { app.repository.rename(session.id, title.trim().take(120).ifBlank { "未命名会话" }) }
    }

    fun deleteSession(session: SessionEntity) {
        if (app.engine.isRunning(session.id)) app.engine.cancel(session.id)
        viewModelScope.launch {
            try {
                app.pluginManager.deleteSessionData(session.id)
                app.repository.delete(session)
                if (_selectedSessionId.value == session.id) _selectedSessionId.value = null
            } catch (error: Exception) {
                _notice.value = "删除会话数据失败：${error.message ?: "未知错误"}"
            }
        }
    }

    fun installPlugin(uri: Uri) {
        viewModelScope.launch {
            try {
                val plugin = app.pluginManager.install(uri)
                _notice.value = "已安装 ${plugin.displayName} ${plugin.version}；默认保持停用"
            } catch (error: Exception) {
                _notice.value = "插件安装失败：${error.message ?: "未知错误"}"
            }
        }
    }

    fun installPlugin(specifier: String) {
        viewModelScope.launch {
            try {
                val plugin = app.pluginManager.install(specifier)
                _notice.value = "已安装原版插件 ${plugin.packageName} ${plugin.version}；默认保持停用"
            } catch (error: Exception) {
                _notice.value = "插件安装失败：${error.message ?: "未知错误"}"
            }
        }
    }

    suspend fun enablePlugin(id: String, enabled: Boolean) = app.pluginManager.enable(id, enabled)
    suspend fun uninstallPlugin(id: String) = app.pluginManager.uninstall(id)
    suspend fun setPluginConfig(id: String, config: String) = app.pluginManager.setConfiguration(id, config)

    fun clearNotice() { _notice.value = null }
    fun reportNotice(message: String) { _notice.value = message }

    private fun workspaceRoot() = File(app.filesDir, "workspace").apply { mkdirs() }.canonicalFile

    private fun readCapped(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "导入文件超过 20 MiB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun workspacePath(relative: String): File {
        require(relative.isNotBlank() && !File(relative).isAbsolute) { "请输入工作区内的相对路径" }
        val root = workspaceRoot()
        val file = File(root, relative).canonicalFile
        require(file.path == root.path || file.path.startsWith(root.path + File.separator)) { "路径不能离开本机工作区" }
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
