package ai.comicine.dshmobile.plugins

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.comicine.dshmobile.data.HarnessDatabase
import ai.comicine.dshmobile.engine.ToolCall
import ai.comicine.dshmobile.engine.ToolExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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

@RunWith(AndroidJUnit4::class)
class PluginRuntimeInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: HarnessDatabase
    private lateinit var manager: PluginManager
    private lateinit var todoArchive: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, HarnessDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        manager = PluginManager(context, database.plugins())
        todoArchive = File(context.cacheDir, "dsh-tool-todo-0.2.2-rc.3.tgz")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("deepseek-ai-dsh-tool-todo-0.2.2-rc.3.tgz")
            .use { input -> todoArchive.outputStream().use(input::copyTo) }
    }

    @After
    fun tearDown() {
        runBlocking {
            runCatching { manager.uninstall("@deepseek-ai/dsh-repeat-tool-reminder") }
            runCatching { manager.uninstall("@deepseek-ai/dsh-tool-todo") }
            manager.close()
            database.close()
            todoArchive.delete()
        }
    }

    @Test
    fun installsAndRunsUnmodifiedUpstreamCordisPluginsOnDevice() = runBlocking {
        val cancellableRuntime = EmbeddedNodeRuntime(context)
        try {
            val pending = async {
                cancellableRuntime.evaluateAsync("new Promise(resolve => setTimeout(() => resolve('late'), 60000))")
            }
            delay(100)
            pending.cancelAndJoin()
            val timeout = runCatching {
                cancellableRuntime.evaluateAsync("(() => { while (true) {} })()", timeoutMs = 1_000)
            }.exceptionOrNull()
            assertNotNull("a synchronous plugin loop must be interrupted by the local watchdog", timeout)
            assertTrue(timeout!!.message.orEmpty().contains("exceeded"))
            assertEquals("42", cancellableRuntime.evaluate("40 + 2"))
        } finally {
            cancellableRuntime.close()
        }

        val repeat = manager.install("@deepseek-ai/dsh-repeat-tool-reminder@0.2.2-rc.3")
        assertEquals("@deepseek-ai/dsh-repeat-tool-reminder", repeat.packageName)
        assertEquals("0.2.2-rc.3", repeat.version)
        val originalManifest = JSONObject(repeat.manifestJson)
        assertEquals("module", originalManifest.getString("type"))
        assertTrue(originalManifest.getJSONObject("exports").getJSONObject(".").getString("default").endsWith("lib/index.js"))
        assertFalse(originalManifest.has("dsh-mobile.json"))
        assertTrue(repeat.sourceSha256.matches(Regex("[a-f0-9]{64}")))

        val todo = manager.install(Uri.fromFile(todoArchive))
        assertEquals("@deepseek-ai/dsh-tool-todo", todo.packageName)
        assertEquals("0.2.2-rc.3", todo.version)
        assertTrue(File(context.filesDir, "plugins/node_modules/@deepseek-ai/schemastery/package.json").isFile)
        assertTrue(File(context.filesDir, "plugins/node_modules/zod/package.json").isFile)

        manager.setConfiguration(repeat.id, """{"thresholds":[2]}""")
        manager.setConfiguration(todo.id, """{"allowParallelInProgress":false}""")
        manager.enable(repeat.id, true)
        manager.enable(todo.id, true)

        val sessionId = "android-upstream-plugin-session"
        val toolExecutor = ToolExecutor(context, manager)
        val todoSchema = toolExecutor.definitions(sessionId).single { it.name == "todo_write" }
        assertTrue(todoSchema.requiresApproval)
        assertTrue(todoSchema.description.contains("keep exactly one todo"))

        val singleTodo = """{"todos":[{"content":"on-device upstream execution","status":"in_progress"}]}"""
        val first = toolExecutor.execute(sessionId, todoSchema, ToolCall("upstream-call-1", "todo_write", singleTodo))
        assertFalse(first.isError)
        assertTrue(first.additionalContexts.isEmpty())
        val second = toolExecutor.execute(sessionId, todoSchema, ToolCall("upstream-call-2", "todo_write", singleTodo))
        assertFalse(second.isError)
        assertTrue(second.additionalContexts.any { it.contains("repeating the exact same tool call") })

        val persistedEvents = JSONArray(manager.sessionEvents(sessionId))
        assertTrue("original todo plugin should persist its real Session events", (0 until persistedEvents.length()).any {
            persistedEvents.getJSONObject(it).optString("type") == "todo/write"
        })
        manager.close()
        manager = PluginManager(context, database.plugins())
        manager.activeTools(sessionId)
        val recoveredEvents = JSONArray(manager.sessionEvents(sessionId))
        val recoveredJson = (0 until recoveredEvents.length()).map { recoveredEvents.getJSONObject(it).toString() }.toSet()
        for (index in 0 until persistedEvents.length()) {
            assertTrue(
                "original Session event $index survives embedded Node process recreation",
                persistedEvents.getJSONObject(index).toString() in recoveredJson,
            )
        }
        assertTrue((0 until recoveredEvents.length()).any {
            recoveredEvents.getJSONObject(it).optString("type") == "todo/write"
        })

        val invalidTodo = """{"todos":[{"content":"a","status":"in_progress"},{"content":"b","status":"in_progress"}]}"""
        val rejected = toolExecutor.execute(sessionId, todoSchema, ToolCall("upstream-call-invalid", "todo_write", invalidTodo))
        assertTrue(rejected.isError)
        assertTrue(rejected.output.isNotBlank())

        val invalidConfig = runCatching { manager.setConfiguration(repeat.id, """{"thresholds":[1]}""") }.exceptionOrNull()
        assertNotNull("Cordis must reject invalid original plugin configuration", invalidConfig)
        assertTrue(invalidConfig!!.message.orEmpty().contains("threshold"))

        manager.enable(repeat.id, false)
        toolExecutor.execute(sessionId, todoSchema, ToolCall("upstream-call-after-disable-1", "todo_write", singleTodo))
        val afterDisable = toolExecutor.execute(sessionId, todoSchema, ToolCall("upstream-call-after-disable-2", "todo_write", singleTodo))
        assertTrue(afterDisable.additionalContexts.isEmpty())

        manager.uninstall(repeat.id)
        assertTrue(database.plugins().get(repeat.id) == null)
        assertFalse(File(context.filesDir, "plugins/node_modules/@deepseek-ai/dsh-repeat-tool-reminder").exists())
        manager.uninstall(todo.id)
        assertTrue(database.plugins().get(todo.id) == null)
        manager.deleteSessionData(sessionId)
        assertEquals(0, JSONArray(manager.sessionEvents(sessionId)).length())
    }
}
