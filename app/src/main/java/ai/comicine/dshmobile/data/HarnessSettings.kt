package ai.comicine.dshmobile.data

import android.app.ActivityManager
import android.content.Context

data class HarnessSettings(
    val baseUrl: String = "https://api.deepseek.com/anthropic",
    val model: String = "deepseek-flash",
    val reasoningEffort: String = "high",
    val maxTokens: Int = 8192,
    val maxConcurrentTasks: Int = 2,
    val maxAgentSteps: Int = 32,
    val retryCount: Int = 3,
    val workspacePath: String? = null,
    val systemPrompt: String = "You are a helpful software engineering assistant. Work carefully, explain actions, and use available local tools when they help.",
    val maxContextCharacters: Int = 240_000,
) {
    fun safeConcurrencyLimit(context: Context): Int {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryCap = (manager.memoryClass / 128).coerceAtLeast(1)
        val processors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return minOf(memoryCap, processors)
    }

    fun validated(context: Context): HarnessSettings {
        val max = safeConcurrencyLimit(context)
        return copy(
            baseUrl = baseUrl.trim().removeSuffix("/"),
            model = model.trim().ifBlank { "deepseek-flash" },
            reasoningEffort = reasoningEffort.takeIf { it in setOf("off", "low", "high", "max") } ?: "high",
            maxTokens = maxTokens.coerceIn(1, 256_000),
            maxConcurrentTasks = maxConcurrentTasks.coerceIn(1, max),
            maxAgentSteps = maxAgentSteps.coerceIn(1, 256),
            retryCount = retryCount.coerceIn(0, 8),
            maxContextCharacters = maxContextCharacters.coerceIn(16_000, 1_000_000),
        )
    }
}

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("harness_settings", Context.MODE_PRIVATE)

    fun load(): HarnessSettings = HarnessSettings(
        baseUrl = preferences.getString("baseUrl", null) ?: HarnessSettings().baseUrl,
        model = preferences.getString("model", null) ?: HarnessSettings().model,
        reasoningEffort = preferences.getString("reasoningEffort", null) ?: HarnessSettings().reasoningEffort,
        maxTokens = preferences.getInt("maxTokens", HarnessSettings().maxTokens),
        maxConcurrentTasks = preferences.getInt("maxConcurrentTasks", HarnessSettings().maxConcurrentTasks),
        maxAgentSteps = preferences.getInt("maxAgentSteps", HarnessSettings().maxAgentSteps),
        retryCount = preferences.getInt("retryCount", HarnessSettings().retryCount),
        workspacePath = preferences.getString("workspacePath", null),
        systemPrompt = preferences.getString("systemPrompt", null) ?: HarnessSettings().systemPrompt,
        maxContextCharacters = preferences.getInt("maxContextCharacters", HarnessSettings().maxContextCharacters),
    )

    fun save(settings: HarnessSettings) {
        preferences.edit()
            .putString("baseUrl", settings.baseUrl.trim())
            .putString("model", settings.model.trim())
            .putString("reasoningEffort", settings.reasoningEffort)
            .putInt("maxTokens", settings.maxTokens)
            .putInt("maxConcurrentTasks", settings.maxConcurrentTasks)
            .putInt("maxAgentSteps", settings.maxAgentSteps)
            .putInt("retryCount", settings.retryCount)
            .putString("workspacePath", settings.workspacePath)
            .putString("systemPrompt", settings.systemPrompt)
            .putInt("maxContextCharacters", settings.maxContextCharacters)
            .apply()
    }
}
