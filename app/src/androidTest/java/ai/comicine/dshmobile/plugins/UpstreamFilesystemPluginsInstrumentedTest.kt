package ai.comicine.dshmobile.plugins

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.comicine.dshmobile.data.HarnessDatabase
import ai.comicine.dshmobile.engine.ToolCall
import ai.comicine.dshmobile.engine.ToolExecutor
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** End-to-end proof that original upstream filesystem plugins execute in the on-device Node runtime. */
@RunWith(AndroidJUnit4::class)
class UpstreamFilesystemPluginsInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: HarnessDatabase
    private lateinit var manager: PluginManager
    private lateinit var workspace: File
    private val installedIds = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, HarnessDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        manager = PluginManager(context, database.plugins())
        workspace = File(context.filesDir, "workspace/upstream-fs-${UUID.randomUUID()}").apply {
            check(mkdirs() || isDirectory) { "Could not create app-private test workspace: $absolutePath" }
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            for (id in installedIds.asReversed()) {
                runCatching {
                    if (database.plugins().get(id)?.enabled == true) manager.enable(id, false)
                }
                runCatching { manager.uninstall(id) }
            }
            manager.close()
            database.close()
            workspace.deleteRecursively()
        }
    }

    @Test
    fun installsAndExecutesUnmodifiedUpstreamFilesystemPluginsLocally() = runBlocking {
        val fixture = File(workspace, "fixture.txt").apply {
            writeText("first line\nupstream filesystem result\nthird line\n", Charsets.UTF_8)
        }
        assertTrue(fixture.isFile)
        assertTrue(fixture.canonicalPath.startsWith(workspace.canonicalPath + File.separator))

        // Install the exact published upstream packages through the app's normal PluginManager.
        val fsLocal = manager.install("@deepseek-ai/dsh-fs-local@0.2.2-rc.3").also { installedIds += it.id }
        val workingDirectory = manager.install("@deepseek-ai/dsh-working-directory@0.2.2-rc.3").also { installedIds += it.id }
        val toolFs = manager.install("@deepseek-ai/dsh-tool-fs@0.2.2-rc.3").also { installedIds += it.id }

        for (plugin in listOf(fsLocal, workingDirectory, toolFs)) {
            assertEquals("0.2.2-rc.3", plugin.version)
            assertEquals(plugin.id, plugin.packageName)
            assertTrue("upstream package integrity/source metadata must be recorded", plugin.sourceSha256.matches(Regex("[a-f0-9]{64}")))
            val manifest = JSONObject(plugin.manifestJson)
            assertEquals("module", manifest.getString("type"))
            assertEquals("./lib/index.js", manifest.getJSONObject("exports").getJSONObject(".").getString("default"))
            assertFalse("upstream package must not contain an Android-specific manifest", manifest.has("dsh-mobile.json"))
            assertTrue(File(context.filesDir, "plugins/${plugin.entryPath}/lib/index.js").isFile)
        }

        // The original fs-local service resolves relative paths from this app-private workspace.
        manager.setConfiguration(fsLocal.id, JSONObject().put("cwd", workspace.canonicalPath).toString())
        manager.setConfiguration(
            workingDirectory.id,
            JSONObject().put("defaultDirectory", workspace.canonicalPath).toString(),
        )
        manager.setConfiguration(
            toolFs.id,
            JSONObject()
                .put("read", true)
                .put("readImage", false)
                .put("write", false)
                .put("edit", false)
                .put("applyPatch", false)
                .toString(),
        )

        // Mount services in dependency order: ctx.fs, session working directory, then model tools.
        manager.enable(fsLocal.id, true)
        manager.enable(workingDirectory.id, true)
        manager.enable(toolFs.id, true)

        assertTrue(database.plugins().get(fsLocal.id)!!.enabled)
        assertTrue(database.plugins().get(workingDirectory.id)!!.enabled)
        assertTrue(database.plugins().get(toolFs.id)!!.enabled)

        val sessionId = "upstream-fs-${UUID.randomUUID()}"
        val toolExecutor = ToolExecutor(context, manager)
        val pluginTools = manager.activeTools(sessionId)
        assertEquals("only the configured original read tool should be registered", listOf("read"), pluginTools.map { it.name }.sorted())
        val read = toolExecutor.definitions(sessionId).single { it.name == "read" }
        val arguments = """{"file_path":"fixture.txt","offset":2,"limit":1}"""
        val result = toolExecutor.execute(sessionId, read, ToolCall("upstream-fs-read-1", "read", arguments))
        assertFalse("the original read tool should return a successful result: ${result.output}", result.isError)
        assertTrue(result.output.contains("<path>${fixture.canonicalPath}</path>"))
        assertTrue(result.output.contains("2: upstream filesystem result"))
        assertTrue(result.output.contains("of 3."))

        val missingPath = """{"file_path":"does-not-exist.txt"}"""
        val missing = toolExecutor.execute(sessionId, read, ToolCall("upstream-fs-read-missing", "read", missingPath))
        assertTrue("an absent workspace path must be returned as an upstream tool error", missing.isError)
        assertTrue("the error should retain useful path diagnostics", missing.output.contains("does-not-exist.txt"))

        val invalidWindow = """{"file_path":"fixture.txt","offset":0,"limit":1}"""
        val invalid = toolExecutor.execute(sessionId, read, ToolCall("upstream-fs-read-invalid", "read", invalidWindow))
        assertTrue("upstream read argument validation must reject offset zero", invalid.isError)
        assertTrue("argument failures should include the rejected field", invalid.output.contains("offset"))

        // Loader deactivation must remove the tool registration before provider teardown.
        manager.enable(toolFs.id, false)
        assertTrue(manager.activeTools(sessionId).none { it.name == "read" })
        manager.enable(workingDirectory.id, false)
        manager.enable(fsLocal.id, false)
        assertFalse(database.plugins().get(toolFs.id)!!.enabled)
        assertFalse(database.plugins().get(workingDirectory.id)!!.enabled)
        assertFalse(database.plugins().get(fsLocal.id)!!.enabled)

        // Remove in reverse dependency order and verify both Room state and local npm contents.
        for (plugin in listOf(toolFs, workingDirectory, fsLocal)) {
            manager.uninstall(plugin.id)
            assertEquals(null, database.plugins().get(plugin.id))
            assertFalse(File(context.filesDir, "plugins/node_modules/${plugin.packageName}").exists())
        }
        installedIds.clear()
    }
}
