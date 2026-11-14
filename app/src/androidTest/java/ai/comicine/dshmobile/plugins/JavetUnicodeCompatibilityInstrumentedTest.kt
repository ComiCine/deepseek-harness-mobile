package ai.comicine.dshmobile.plugins

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JavetUnicodeCompatibilityInstrumentedTest {
    @Test
    fun reportsNodeAndUnicodePropertyEscapeSupport() = runBlocking {
        val runtime = EmbeddedNodeRuntime(ApplicationProvider.getApplicationContext<Context>())
        try {
            val nodeVersion = runtime.evaluate("process.version")
            val v8Version = runtime.evaluate("process.versions.v8")
            assertFalse(nodeVersion.isBlank())
            assertFalse(v8Version.isBlank())

            val literal = runCatching {
                runtime.evaluate("/[\\p{L}\\p{N}_-]/u.test('漢')") == "true"
            }
            val constructor = runCatching {
                runtime.evaluate("new RegExp('[\\\\p{L}\\\\p{N}_-]', 'u').test('漢')") == "true"
            }
            android.util.Log.i(
                "JavetRuntimeProbe",
                "node=$nodeVersion v8=$v8Version literal=${literal.getOrNull()} literalError=${literal.exceptionOrNull()?.message} constructor=${constructor.getOrNull()} constructorError=${constructor.exceptionOrNull()?.message}",
            )
            assertTrue("Node must parse Unicode property escapes in regex literals", literal.getOrNull() == true)
            assertTrue("Node must parse Unicode property escapes in RegExp constructors", constructor.getOrNull() == true)
        } finally {
            runtime.close()
        }
    }
}
