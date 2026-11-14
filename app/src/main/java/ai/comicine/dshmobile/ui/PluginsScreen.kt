package ai.comicine.dshmobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ai.comicine.dshmobile.HarnessViewModel
import ai.comicine.dshmobile.R
import ai.comicine.dshmobile.data.PluginEntity
import kotlinx.coroutines.launch

@Composable
fun PluginsScreen(viewModel: HarnessViewModel, onOpenUi: (String) -> Unit = {}) {
    val plugins by viewModel.plugins.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var enabling by remember { mutableStateOf<PluginEntity?>(null) }
    var configuring by remember { mutableStateOf<PluginEntity?>(null) }
    var uninstalling by remember { mutableStateOf<PluginEntity?>(null) }
    var packageSpecifier by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.plugins_upstream_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.plugins_install_instructions), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = packageSpecifier,
                        onValueChange = { packageSpecifier = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(stringResource(R.string.field_npm_package_specifier)) },
                    )
                    Button(
                        enabled = packageSpecifier.isNotBlank(),
                        onClick = {
                            val value = packageSpecifier.trim()
                            packageSpecifier = ""
                            viewModel.installPlugin(value)
                        },
                    ) { Text(stringResource(R.string.action_install_plugin)) }
                }
                Text(stringResource(R.string.plugins_security_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (plugins.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.plugins_empty_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.plugins_empty_message), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(plugins, key = { it.id }) { plugin ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(plugin.displayName, style = MaterialTheme.typography.titleMedium)
                                    Text("${plugin.packageName} · ${plugin.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = plugin.enabled,
                                    onCheckedChange = { enabled ->
                                        if (enabled) enabling = plugin
                                        else scope.launch {
                                            runCatching { viewModel.enablePlugin(plugin.id, false) }
                                                .onFailure { viewModel.reportLocalizedFailure(R.string.notice_plugin_disable_failed, it) }
                                        }
                                    },
                                )
                                IconButton(onClick = { configuring = plugin }) { Icon(Icons.Default.Settings, stringResource(R.string.action_edit_plugin_config)) }
                                IconButton(onClick = { uninstalling = plugin }) { Icon(Icons.Default.Delete, stringResource(R.string.action_uninstall_plugin)) }
                            }
                            Text(stringResource(R.string.plugin_original_runtime_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.plugin_fingerprint, plugin.sourceSha256.take(16)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (plugin.manifestJson.contains("\"platform\":\"web\"")) {
                                TextButton(onClick = { onOpenUi(plugin.packageName) }) {
                                    Text(stringResource(R.string.action_open_plugin_ui))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    enabling?.let { plugin ->
        AlertDialog(
            onDismissRequest = { enabling = null },
            title = { Text(stringResource(R.string.dialog_enable_plugin_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.dialog_enable_plugin_message, plugin.displayName))
                    Text(stringResource(R.string.plugin_process_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    enabling = null
                    scope.launch {
                        runCatching { viewModel.enablePlugin(plugin.id, true) }
                            .onFailure { viewModel.reportLocalizedFailure(R.string.notice_plugin_enable_failed, it) }
                    }
                }) { Text(stringResource(R.string.action_trust_enable_plugin)) }
            },
            dismissButton = { TextButton(onClick = { enabling = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    configuring?.let { plugin ->
        var config by remember(plugin.id) { mutableStateOf(plugin.configJson) }
        AlertDialog(
            onDismissRequest = { configuring = null },
            title = { Text(stringResource(R.string.dialog_plugin_config_title, plugin.displayName)) },
            text = {
                OutlinedTextField(
                    value = config,
                    onValueChange = { config = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 12,
                    label = { Text(stringResource(R.string.field_json)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    configuring = null
                    scope.launch {
                        runCatching { viewModel.setPluginConfig(plugin.id, config) }
                            .onFailure { viewModel.reportLocalizedFailure(R.string.notice_plugin_config_failed, it) }
                    }
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { configuring = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    uninstalling?.let { plugin ->
        AlertDialog(
            onDismissRequest = { uninstalling = null },
            title = { Text(stringResource(R.string.dialog_uninstall_plugin_title)) },
            text = { Text(stringResource(R.string.dialog_uninstall_plugin_message, plugin.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    uninstalling = null
                    scope.launch {
                        runCatching { viewModel.uninstallPlugin(plugin.id) }
                            .onFailure { viewModel.reportLocalizedFailure(R.string.notice_plugin_uninstall_failed, it) }
                    }
                }) { Text(stringResource(R.string.action_uninstall_plugin)) }
            },
            dismissButton = { TextButton(onClick = { uninstalling = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
