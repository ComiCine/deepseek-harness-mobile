package ai.comicine.dshmobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ai.comicine.dshmobile.HarnessViewModel
import ai.comicine.dshmobile.data.HarnessSettings

@Composable
fun SettingsScreen(viewModel: HarnessViewModel) {
    val settings by viewModel.settings.collectAsState()
    val hasKey by viewModel.hasApiKey.collectAsState()
    val context = LocalContext.current
    var baseUrl by rememberSaveable { mutableStateOf(settings.baseUrl) }
    var model by rememberSaveable { mutableStateOf(settings.model) }
    var reasoning by rememberSaveable { mutableStateOf(settings.reasoningEffort) }
    var maxTokens by rememberSaveable { mutableStateOf(settings.maxTokens.toString()) }
    var concurrency by rememberSaveable { mutableStateOf(settings.maxConcurrentTasks.toString()) }
    var maxSteps by rememberSaveable { mutableStateOf(settings.maxAgentSteps.toString()) }
    var retries by rememberSaveable { mutableStateOf(settings.retryCount.toString()) }
    var contextLimit by rememberSaveable { mutableStateOf(settings.maxContextCharacters.toString()) }
    var systemPrompt by rememberSaveable { mutableStateOf(settings.systemPrompt) }
    var apiKey by remember { mutableStateOf("") }

    LaunchedEffect(settings) {
        baseUrl = settings.baseUrl
        model = settings.model
        reasoning = settings.reasoningEffort
        maxTokens = settings.maxTokens.toString()
        concurrency = settings.maxConcurrentTasks.toString()
        maxSteps = settings.maxAgentSteps.toString()
        retries = settings.retryCount.toString()
        contextLimit = settings.maxContextCharacters.toString()
        systemPrompt = settings.systemPrompt
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("模型与凭据", style = MaterialTheme.typography.titleLarge)
        Text("默认使用上游 Harness 的 DeepSeek Messages 路由。模型 API 只负责推理；工具、会话和插件仍在本机运行。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(baseUrl, { baseUrl = it }, modifier = Modifier.fillMaxWidth(), label = { Text("HTTPS API 地址") }, singleLine = true)
        OutlinedTextField(model, { model = it }, modifier = Modifier.fillMaxWidth(), label = { Text("模型 ID") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { model = "deepseek-flash" }) { Text("deepseek-flash") }
            TextButton(onClick = { model = "deepseek-v4-pro" }) { Text("deepseek-v4-pro") }
        }
        Text("思考强度", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("off", "low", "high", "max").forEach { option ->
                if (reasoning == option) Button(onClick = { reasoning = option }) { Text(option) }
                else TextButton(onClick = { reasoning = option }) { Text(option) }
            }
        }
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("DeepSeek API Key") },
            placeholder = { Text(if (hasKey) "本机已保存；留空不更改" else "粘贴 API Key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (apiKey.isNotBlank()) { viewModel.saveApiKey(apiKey); apiKey = "" } }) {
                Text("加密保存密钥")
            }
            if (hasKey) TextButton(onClick = { viewModel.saveApiKey(""); apiKey = "" }) { Text("删除已保存密钥") }
        }
        Text(if (hasKey) "凭据由 Android Keystore 加密保护。" else "尚未保存 API Key。密钥不会写入 Harness 日志。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text("Agent 与资源", style = MaterialTheme.typography.titleLarge)
        NumericSetting("最大输出 token", maxTokens, { maxTokens = it })
        val safeMax = settings.safeConcurrencyLimit(context)
        NumericSetting("并发会话 (设备安全上限 $safeMax)", concurrency, { concurrency = it })
        NumericSetting("每轮最大模型步骤", maxSteps, { maxSteps = it })
        NumericSetting("请求重试次数", retries, { retries = it })
        NumericSetting("历史请求字符预算", contextLimit, { contextLimit = it })
        OutlinedTextField(
            value = systemPrompt,
            onValueChange = { systemPrompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("System prompt") },
            minLines = 3,
            maxLines = 8,
        )
        Button(onClick = {
            viewModel.saveSettings(settings.copy(
                baseUrl = baseUrl,
                model = model,
                reasoningEffort = reasoning,
                maxTokens = maxTokens.toIntOrNull() ?: settings.maxTokens,
                maxConcurrentTasks = concurrency.toIntOrNull() ?: settings.maxConcurrentTasks,
                maxAgentSteps = maxSteps.toIntOrNull() ?: settings.maxAgentSteps,
                retryCount = retries.toIntOrNull() ?: settings.retryCount,
                maxContextCharacters = contextLimit.toIntOrNull() ?: settings.maxContextCharacters,
                systemPrompt = systemPrompt,
            ))
        }) { Text("保存设置") }

        Text("Android 本地工具", style = MaterialTheme.typography.titleLarge)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("文件工具只访问应用私有 workspace。shell 使用 /system/bin/sh，以应用 UID 运行，不需要 root。", style = MaterialTheme.typography.bodyMedium)
                Text("Android 不提供桌面 Bash、PowerShell、PTY、macOS/Windows 系统工具或桌面 GUI。此版本没有把这些工作转交给远端机器。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NumericSetting(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { newValue -> if (newValue.all(Char::isDigit)) onChange(newValue) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}
