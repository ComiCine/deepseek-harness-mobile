package ai.comicine.dshmobile.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import ai.comicine.dshmobile.HarnessViewModel
import ai.comicine.dshmobile.engine.PendingApproval

private data class Destination(val id: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val destinations = listOf(
    Destination("chat", "对话", Icons.Default.Chat),
    Destination("sessions", "会话", Icons.Default.History),
    Destination("workspace", "工作区", Icons.Default.FolderOpen),
    Destination("plugins", "插件", Icons.Default.Extension),
    Destination("logs", "日志", Icons.Default.BugReport),
    Destination("settings", "设置", Icons.Default.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarnessApp(viewModel: HarnessViewModel) {
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    val selectedSession by viewModel.selectedSessionId.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val approvals by viewModel.approvals.collectAsState()
    val notice by viewModel.notice.collectAsState()
    var destination by rememberSaveable { mutableStateOf("chat") }
    val snackbar = remember { SnackbarHostState() }

    val importSession = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(viewModel::importSession)
    }
    val installPlugin = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(viewModel::installPlugin)
    }
    val importWorkspace = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(viewModel::importWorkspaceFile)
    }
    val exportSession = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val sessionId = selectedSession
        if (uri != null && sessionId != null) viewModel.writeExport(uri, sessionId)
    }
    val exportWorkspace = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) viewModel.writeWorkspaceExport(uri)
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            snackbar.showSnackbar(notice!!)
            viewModel.clearNotice()
        }
    }
    BackHandler(destination != "chat") { destination = "chat" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(destinations.first { it.id == destination }.title) },
                actions = {
                    when (destination) {
                        "chat" -> {
                            IconButton(onClick = { viewModel.newSession() }) { Icon(Icons.Default.Add, "新建会话") }
                            if (selectedSession != null) IconButton(onClick = { exportSession.launch("harness-session.json") }) {
                                Icon(Icons.Default.FileDownload, "导出会话")
                            }
                        }
                        "sessions" -> {
                            IconButton(onClick = { viewModel.newSession { destination = "chat" } }) { Icon(Icons.Default.Add, "新建会话") }
                            IconButton(onClick = { importSession.launch(arrayOf("application/json", "text/json")) }) {
                                Icon(Icons.Default.FileUpload, "导入会话 JSON")
                            }
                        }
                        "plugins" -> IconButton(onClick = { installPlugin.launch(arrayOf("application/gzip", "application/x-gzip", "application/octet-stream")) }) {
                            Icon(Icons.Default.Add, "安装原版 npm 插件 .tgz 包")
                        }
                        "workspace" -> IconButton(onClick = { importWorkspace.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.FileUpload, "导入文件到工作区")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!wide) NavigationBar {
                destinations.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item.id,
                        onClick = { destination = item.id },
                        icon = { Icon(item.icon, contentDescription = item.title) },
                        label = { Text(item.title) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        Row(Modifier.fillMaxSize().padding(insets)) {
            if (wide) {
                NavigationRail {
                    destinations.forEach { item ->
                        NavigationRailItem(
                            selected = destination == item.id,
                            onClick = { destination = item.id },
                            icon = { Icon(item.icon, contentDescription = item.title) },
                            label = { Text(item.title) },
                            alwaysShowLabel = true,
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxSize()) {
                when (destination) {
                    "chat" -> ChatScreen(viewModel, selectedSession, sessions)
                    "sessions" -> SessionsScreen(viewModel, sessions) { id ->
                        viewModel.selectSession(id)
                        destination = "chat"
                    }
                    "workspace" -> WorkspaceScreen(viewModel) { path ->
                        viewModel.setWorkspaceExportPath(path)
                        exportWorkspace.launch(path.substringAfterLast('/'))
                    }
                    "plugins" -> PluginsScreen(viewModel)
                    "logs" -> LogsScreen(viewModel, selectedSession, sessions)
                    else -> SettingsScreen(viewModel)
                }
            }
        }
    }

    approvals.firstOrNull()?.let { approval ->
        ApprovalDialog(approval, onApprove = { viewModel.approve(approval.id, true) }, onDeny = { viewModel.approve(approval.id, false) })
    }
}

@Composable
private fun ApprovalDialog(approval: PendingApproval, onApprove: () -> Unit, onDeny: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text("批准本次工具调用？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("工具：${approval.toolName}")
                Text(approval.description)
                SelectionContainer {
                    Text(approval.argumentsJson, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
                }
            }
        },
        confirmButton = { Button(onClick = onApprove) { Text("批准并执行一次") } },
        dismissButton = { TextButton(onClick = onDeny) { Text("拒绝") } },
    )
}
