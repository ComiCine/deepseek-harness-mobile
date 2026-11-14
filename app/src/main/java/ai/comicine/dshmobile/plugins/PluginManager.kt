package ai.comicine.dshmobile.plugins

import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import ai.comicine.dshmobile.R
import ai.comicine.dshmobile.data.PluginDao
import ai.comicine.dshmobile.data.PluginEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

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

class PluginCompatibilityException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Manages original npm packages using the unmodified Cordis Loader contract in the embedded Node runtime. */
class PluginManager(context: Context, private val dao: PluginDao) : AutoCloseable {
    private val app = context.applicationContext
    private val localizedContext = ContextCompat.getContextForLanguage(app)
    private val runtime = EmbeddedNodeRuntime(app)
    private val root = runtime.pluginsRoot
    private val stateMutex = Mutex()
    private var restoredRuntimeState = false
    val plugins: Flow<List<PluginEntity>> = dao.observeAll()

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

    suspend fun deleteSessionData(sessionId: String) = withContext(Dispatchers.IO) {
        invokeHost("deleteSessionData", sessionId)
    }

    private suspend fun restoreEnabledPluginsLocked() = stateMutex.withLock {
        if (restoredRuntimeState) return@withLock
        val enabled = dao.observeAll().first().filter { it.enabled }
        if (enabled.isNotEmpty()) {
            invokeHost("schemas", enabled.first().id)
            for (plugin in enabled) invokeHost("activate", plugin.packageName, plugin.configJson)
        }
        restoredRuntimeState = true
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

    override fun close() = runtime.close()

    private fun packageKey(packageName: String): String = MessageDigest.getInstance("SHA-256")
        .digest(packageName.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it) }
}
