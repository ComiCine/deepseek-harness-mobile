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
import androidx.compose.ui.unit.dp
import ai.comicine.dshmobile.HarnessViewModel
import ai.comicine.dshmobile.data.PluginEntity
import kotlinx.coroutines.launch

@Composable
fun PluginsScreen(viewModel: HarnessViewModel) {
    val plugins by viewModel.plugins.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var enabling by remember { mutableStateOf<PluginEntity?>(null) }
    var configuring by remember { mutableStateOf<PluginEntity?>(null) }
    var uninstalling by remember { mutableStateOf<PluginEntity?>(null) }
    var packageSpecifier by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("原版 DeepSeek Harness 插件", style = MaterialTheme.typography.titleMedium)
                Text("在此输入 npm 包名和可选版本，或从右上角安装原版 .tgz 包。依赖由设备内 npm 解析，插件由原版 Cordis Loader 与内嵌 Node.js 在本机加载和执行。", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = packageSpecifier,
                        onValueChange = { packageSpecifier = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("npm 包名，例如 @scope/plugin@1.2.3") },
                    )
                    Button(
                        enabled = packageSpecifier.isNotBlank(),
                        onClick = {
                            val value = packageSpecifier.trim()
                            packageSpecifier = ""
                            viewModel.installPlugin(value)
                        },
                    ) { Text("安装") }
                }
                Text("插件代码在应用 Node 进程内运行，并可使用本应用沙箱中的文件及网络能力。只安装并启用你信任的原版包。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (plugins.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("尚未安装插件", style = MaterialTheme.typography.titleMedium)
                Text("安装后默认停用；停用和卸载会通过原版 Cordis Loader 执行插件生命周期清理。", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                                .onFailure { viewModel.reportNotice("停用失败：${it.message ?: "未知错误"}") }
                                        }
                                    },
                                )
                                IconButton(onClick = { configuring = plugin }) { Icon(Icons.Default.Settings, "编辑插件配置") }
                                IconButton(onClick = { uninstalling = plugin }) { Icon(Icons.Default.Delete, "卸载插件") }
                            }
                            Text("原版包 · Cordis Loader · Node.js", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text("安装目录指纹 SHA-256 ${plugin.sourceSha256.take(16)}…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    enabling?.let { plugin ->
        AlertDialog(
            onDismissRequest = { enabling = null },
            title = { Text("启用插件代码？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${plugin.displayName} 将通过原版 Cordis Loader 在设备内的 Node.js 运行。模型调用其工具时仍需逐次批准。")
                    Text("插件与 Harness 共用应用进程和 UID。Node 文件/网络能力没有按插件隔离，Android 不会把它运行在独立进程。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    enabling = null
                    scope.launch {
                        runCatching { viewModel.enablePlugin(plugin.id, true) }
                            .onFailure { viewModel.reportNotice("启用失败：${it.message ?: "插件不兼容"}") }
                    }
                }) { Text("我信任此代码，启用") }
            },
            dismissButton = { TextButton(onClick = { enabling = null }) { Text("取消") } },
        )
    }

    configuring?.let { plugin ->
        var config by remember(plugin.id) { mutableStateOf(plugin.configJson) }
        AlertDialog(
            onDismissRequest = { configuring = null },
            title = { Text("${plugin.displayName} 配置") },
            text = {
                OutlinedTextField(
                    value = config,
                    onValueChange = { config = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 12,
                    label = { Text("JSON") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    configuring = null
                    scope.launch {
                        runCatching { viewModel.setPluginConfig(plugin.id, config) }
                            .onFailure { viewModel.reportNotice("保存插件配置失败：${it.message ?: "未知错误"}") }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { configuring = null }) { Text("取消") } },
        )
    }

    uninstalling?.let { plugin ->
        AlertDialog(
            onDismissRequest = { uninstalling = null },
            title = { Text("卸载插件？") },
            text = { Text("${plugin.displayName} 的代码与本地配置将从设备移除。") },
            confirmButton = {
                TextButton(onClick = {
                    uninstalling = null
                    scope.launch {
                        runCatching { viewModel.uninstallPlugin(plugin.id) }
                            .onFailure { viewModel.reportNotice("卸载失败：${it.message ?: "未知错误"}") }
                    }
                }) { Text("卸载") }
            },
            dismissButton = { TextButton(onClick = { uninstalling = null }) { Text("取消") } },
        )
    }
}
