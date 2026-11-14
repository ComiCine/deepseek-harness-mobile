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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ai.comicine.dshmobile.HarnessViewModel
import ai.comicine.dshmobile.R
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
        preview = runCatching { viewModel.readWorkspaceFile(selected) }.getOrElse {
            viewModel.localizedString(R.string.notice_workspace_read_failed, it.message ?: viewModel.localizedString(R.string.error_unknown))
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (path.isNotEmpty()) IconButton(onClick = { path = path.substringBeforeLast('/', "") }) {
                Icon(Icons.Default.ArrowBack, stringResource(R.string.action_go_to_parent))
            }
            Column(Modifier.weight(1f)) {
                Text(if (path.isEmpty()) stringResource(R.string.workspace_default_title) else path, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.workspace_private_notice), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { createDialog = true }) { Icon(Icons.Default.NoteAdd, stringResource(R.string.action_create_workspace_file)) }
        }

        if (entries.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.workspace_empty_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.workspace_empty_message), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Text(if (entry.isDirectory) stringResource(R.string.label_folder) else formatBytes(entry.sizeBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!entry.isDirectory) IconButton(onClick = { onExport(entry.relativePath) }) { Icon(Icons.Default.Download, stringResource(R.string.action_export_named, entry.name)) }
                            IconButton(onClick = { deletePath = entry.relativePath }) { Icon(Icons.Default.Delete, stringResource(R.string.action_delete_named, entry.name)) }
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
            title = { Text(stringResource(R.string.dialog_new_workspace_file)) },
            text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_relative_path)) }, singleLine = true) },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank()) {
                        val target = listOf(path, name.trim()).filter { it.isNotBlank() }.joinToString("/")
                        viewModel.createWorkspaceFile(target, "")
                        refresh++
                    }
                    createDialog = false
                }) { Text(stringResource(R.string.action_create)) }
            },
            dismissButton = { TextButton(onClick = { createDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    previewPath?.let { selected ->
        AlertDialog(
            onDismissRequest = { previewPath = null },
            title = { Text(selected.substringAfterLast('/')) },
            text = { SelectionContainer { Text(preview, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton(onClick = { previewPath = null }) { Text(stringResource(R.string.action_close)) } },
            dismissButton = { TextButton(onClick = { onExport(selected); previewPath = null }) { Text(stringResource(R.string.action_export)) } },
        )
    }
    deletePath?.let { selected ->
        AlertDialog(
            onDismissRequest = { deletePath = null },
            title = { Text(stringResource(R.string.dialog_delete_workspace_title)) },
            text = { Text(stringResource(R.string.dialog_delete_workspace_message, selected)) },
            confirmButton = { TextButton(onClick = { viewModel.deleteWorkspaceEntry(selected); deletePath = null; refresh++ }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { deletePath = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun formatBytes(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "${size / 1024} KiB"
    else -> "${size / (1024 * 1024)} MiB"
}
