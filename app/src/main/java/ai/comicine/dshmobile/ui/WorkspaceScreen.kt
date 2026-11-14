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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ai.comicine.dshmobile.HarnessViewModel
import ai.comicine.dshmobile.WorkspaceEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun WorkspaceScreen(viewModel: HarnessViewModel, onExport: (String) -> Unit) {
    val revision by viewModel.workspaceRevision.collectAsState()
    var path by rememberSaveable { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<WorkspaceEntry>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var createDialog by remember { mutableStateOf(false) }
    var previewPath by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf("") }
    var deletePath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path, refresh, revision) {
        entries = withContext(Dispatchers.IO) { viewModel.listWorkspace(path) }
    }
    LaunchedEffect(previewPath) {
        val selected = previewPath ?: return@LaunchedEffect
        preview = runCatching { viewModel.readWorkspaceFile(selected) }.getOrElse { "读取失败：${it.message}" }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (path.isNotEmpty()) IconButton(onClick = { path = path.substringBeforeLast('/', "") }) {
                Icon(Icons.Default.ArrowBack, "返回上一级")
            }
            Column(Modifier.weight(1f)) {
                Text(if (path.isEmpty()) "本机工作区" else path, style = MaterialTheme.typography.titleMedium)
                Text("应用私有目录；文件不会自动上传。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { createDialog = true }) { Icon(Icons.Default.NoteAdd, "新建文件") }
        }

        if (entries.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("此文件夹为空", style = MaterialTheme.typography.titleMedium)
                Text("可导入文件，或让 Agent 创建文件。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(entries, key = { it.relativePath }) { entry ->
                    Card(onClick = {
                        if (entry.isDirectory) path = entry.relativePath
                        else previewPath = entry.relativePath
                    }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                                Text(if (entry.isDirectory) "文件夹" else formatBytes(entry.sizeBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!entry.isDirectory) IconButton(onClick = { onExport(entry.relativePath) }) { Icon(Icons.Default.Download, "导出 ${entry.name}") }
                            IconButton(onClick = { deletePath = entry.relativePath }) { Icon(Icons.Default.Delete, "删除 ${entry.name}") }
                        }
                    }
                }
            }
        }
    }

    if (createDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { createDialog = false },
            title = { Text("新建工作区文件") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("相对路径") }, singleLine = true) },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank()) {
                        val target = listOf(path, name.trim()).filter { it.isNotBlank() }.joinToString("/")
                        viewModel.createWorkspaceFile(target, "")
                        refresh++
                    }
                    createDialog = false
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { createDialog = false }) { Text("取消") } },
        )
    }
    previewPath?.let { selected ->
        AlertDialog(
            onDismissRequest = { previewPath = null },
            title = { Text(selected.substringAfterLast('/')) },
            text = { SelectionContainer { Text(preview, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton(onClick = { previewPath = null }) { Text("关闭") } },
            dismissButton = { TextButton(onClick = { onExport(selected); previewPath = null }) { Text("导出") } },
        )
    }
    deletePath?.let { selected ->
        AlertDialog(
            onDismissRequest = { deletePath = null },
            title = { Text("删除工作区项目？") },
            text = { Text("$selected 将从应用私有工作区删除。") },
            confirmButton = { TextButton(onClick = { viewModel.deleteWorkspaceEntry(selected); deletePath = null; refresh++ }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deletePath = null }) { Text("取消") } },
        )
    }
}

private fun formatBytes(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "${size / 1024} KiB"
    else -> "${size / (1024 * 1024)} MiB"
}
