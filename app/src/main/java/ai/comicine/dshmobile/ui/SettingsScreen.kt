package ai.comicine.dshmobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import ai.comicine.dshmobile.HarnessViewModel
import ai.comicine.dshmobile.R
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
    var expandedLegalDocument by remember { mutableStateOf<Pair<Int, String>?>(null) }

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
        Text(stringResource(R.string.settings_model_credentials), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.settings_provider_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(baseUrl, { baseUrl = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.field_https_api_url)) }, singleLine = true)
        OutlinedTextField(model, { model = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.field_model_id)) }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { model = "deepseek-flash" }) { Text("deepseek-flash") }
            TextButton(onClick = { model = "deepseek-v4-pro" }) { Text("deepseek-v4-pro") }
        }
        Text(stringResource(R.string.settings_reasoning_effort), style = MaterialTheme.typography.titleSmall)
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
            label = { Text(stringResource(R.string.field_api_key)) },
            placeholder = { Text(stringResource(if (hasKey) R.string.api_key_saved_placeholder else R.string.api_key_new_placeholder)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (apiKey.isNotBlank()) { viewModel.saveApiKey(apiKey); apiKey = "" } }) {
                Text(stringResource(R.string.action_save_api_key))
            }
            if (hasKey) TextButton(onClick = { viewModel.saveApiKey(""); apiKey = "" }) { Text(stringResource(R.string.action_remove_saved_api_key)) }
        }
        Text(stringResource(if (hasKey) R.string.api_key_protected else R.string.api_key_missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text(stringResource(R.string.settings_agent_resources), style = MaterialTheme.typography.titleLarge)
        NumericSetting(stringResource(R.string.setting_max_output_tokens), maxTokens, { maxTokens = it })
        val safeMax = settings.safeConcurrencyLimit(context)
        NumericSetting(stringResource(R.string.setting_max_concurrent_sessions, safeMax), concurrency, { concurrency = it })
        NumericSetting(stringResource(R.string.setting_max_model_steps), maxSteps, { maxSteps = it })
        NumericSetting(stringResource(R.string.setting_retry_count), retries, { retries = it })
        NumericSetting(stringResource(R.string.setting_context_budget), contextLimit, { contextLimit = it })
        OutlinedTextField(
            value = systemPrompt,
            onValueChange = { systemPrompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.field_system_prompt)) },
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
        }) { Text(stringResource(R.string.action_save_settings)) }

        Text(stringResource(R.string.settings_android_tools), style = MaterialTheme.typography.titleLarge)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.settings_local_tools_message), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.settings_android_platform_message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleLarge)
        LanguageSetting()

        Text(stringResource(R.string.settings_legal), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.legal_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                expandedLegalDocument = R.string.dialog_license_title to context.assets.open("legal/LICENSE").bufferedReader().use { it.readText() }
            }) { Text(stringResource(R.string.action_view_license)) }
            TextButton(onClick = {
                expandedLegalDocument = R.string.dialog_additional_terms_title to context.assets.open("legal/ADDITIONAL_TERMS.md").bufferedReader().use { it.readText() }
            }) { Text(stringResource(R.string.action_view_additional_terms)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                expandedLegalDocument = R.string.dialog_copyright_title to context.assets.open("legal/COPYRIGHT.md").bufferedReader().use { it.readText() }
            }) { Text(stringResource(R.string.action_view_copyright)) }
            TextButton(onClick = {
                expandedLegalDocument = R.string.dialog_third_party_notices_title to context.assets.open("legal/THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() }
            }) { Text(stringResource(R.string.action_view_third_party_notices)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                expandedLegalDocument = R.string.dialog_upstream_license_title to context.assets.open("legal/LICENSE.upstream-deepseek-harness").bufferedReader().use { it.readText() }
            }) { Text(stringResource(R.string.action_view_upstream_license)) }
            TextButton(onClick = {
                expandedLegalDocument = R.string.dialog_security_title to context.assets.open("legal/SECURITY.md").bufferedReader().use { it.readText() }
            }) { Text(stringResource(R.string.action_view_security)) }
        }
    }

    expandedLegalDocument?.let { (titleRes, contents) ->
        AlertDialog(
            onDismissRequest = { expandedLegalDocument = null },
            title = { Text(stringResource(titleRes)) },
            text = {
                SelectionContainer {
                    Text(
                        contents,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { expandedLegalDocument = null }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

@Composable
private fun LanguageSetting() {
    var expanded by remember { mutableStateOf(false) }
    val selectedLanguage = when (AppCompatDelegate.getApplicationLocales().toLanguageTags()) {
        "en" -> R.string.language_english
        "zh-CN", "zh-Hans-CN" -> R.string.language_simplified_chinese
        else -> R.string.language_system_default
    }
    Column {
        OutlinedButton(onClick = { expanded = true }) { Text(stringResource(selectedLanguage)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.language_system_default)) },
                onClick = {
                    expanded = false
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.language_english)) },
                onClick = {
                    expanded = false
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.language_simplified_chinese)) },
                onClick = {
                    expanded = false
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN"))
                },
            )
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
