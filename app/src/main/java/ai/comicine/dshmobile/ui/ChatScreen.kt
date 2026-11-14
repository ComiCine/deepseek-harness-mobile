package ai.comicine.dshmobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.comicine.dshmobile.HarnessViewModel
import ai.comicine.dshmobile.R
import ai.comicine.dshmobile.data.MessageEntity
import ai.comicine.dshmobile.data.SessionEntity
import org.json.JSONArray

internal const val CHAT_PROMPT_TEST_TAG = "chat-prompt-editor"

@Composable
fun ChatScreen(viewModel: HarnessViewModel, sessionId: String?, sessions: List<SessionEntity>) {
    val session = sessions.firstOrNull { it.id == sessionId }
    if (sessionId == null || session == null) {
        EmptyChat(viewModel)
        return
    }
    val messages by viewModel.messages(sessionId).collectAsState(initial = emptyList())
    val liveBySession by viewModel.liveAssistants.collectAsState()
    val live = liveBySession[sessionId]
    val running = session.executionState == "running" || session.executionState == "queued"
    var prompt by rememberSaveable(sessionId, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var promptRevision by rememberSaveable(sessionId) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, live?.text?.length, live?.thinking?.length) {
        val count = messages.size + if (live != null && messages.none { it.id == live.messageId }) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(session.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    stringResource(R.string.session_metadata, session.provider, session.model, executionStateLabel(session.executionState)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (running) {
                IconButton(onClick = { viewModel.cancel(sessionId) }) { Icon(Icons.Default.Stop, stringResource(R.string.action_cancel_current_task)) }
            } else if (session.executionState in setOf("error", "cancelled", "interrupted")) {
                TextButton(onClick = { viewModel.retryLastTurn(sessionId) }) { Text(stringResource(R.string.action_retry_last_task)) }
            }
        }
        HorizontalDivider()

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                val streamed = if (message.id == live?.messageId) live else null
                MessageCard(message, streamed?.text ?: message.content, streamed?.thinking ?: message.thinking)
            }
            if (live != null && messages.none { it.id == live.messageId }) {
                item(key = live.messageId) {
                    MessageCard(
                        MessageEntity(id = live.messageId, sessionId = sessionId, role = "assistant", content = live.text, thinking = live.thinking, status = "streaming", createdAt = System.currentTimeMillis()),
                        live.text,
                        live.thinking,
                    )
                }
            }
        }

        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                ChatPluginInputActivities(
                    viewModel = viewModel,
                    sessionId = sessionId,
                    draft = prompt.text,
                    revision = promptRevision,
                    locked = running,
                    onInsert = { text, start, end, revision, pluginSessionId ->
                        if (pluginSessionId != sessionId || running || revision != promptRevision
                            || start !in 0..prompt.text.length || end !in start..prompt.text.length) {
                            false
                        } else {
                            val inserted = prompt.text.substring(0, start) + text + prompt.text.substring(end)
                            prompt = TextFieldValue(inserted, TextRange(start + text.length))
                            promptRevision++
                            true
                        }
                    },
                    onSetDraft = { text, pluginSessionId ->
                        if (pluginSessionId != sessionId || running) false
                        else {
                            prompt = TextFieldValue(text, TextRange(text.length))
                            promptRevision++
                            true
                        }
                    },
                    onSubmit = {
                        val submitted = prompt.text
                        if (submitted.isNotBlank() && !running) {
                            prompt = TextFieldValue("")
                            promptRevision++
                            viewModel.send(submitted)
                        }
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                ) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it; promptRevision++ },
                    modifier = Modifier.weight(1f).testTag(CHAT_PROMPT_TEST_TAG),
                    placeholder = { Text(stringResource(R.string.chat_prompt_placeholder)) },
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    keyboardActions = KeyboardActions.Default,
                )
                IconButton(
                    enabled = prompt.text.isNotBlank() && !running,
                    onClick = {
                        val submitted = prompt.text
                        prompt = TextFieldValue("")
                        promptRevision++
                        viewModel.send(submitted)
                    },
                ) {
                    Icon(Icons.Default.Send, stringResource(R.string.action_send_message))
                }
                }
            }
        }
    }
}

@Composable
private fun EmptyChat(viewModel: HarnessViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.chat_empty_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.chat_empty_message), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        AssistChip(onClick = { viewModel.newSession() }, label = { Text(stringResource(R.string.action_new_session)) })
    }
}

@Composable
private fun MessageCard(message: MessageEntity, content: String, thinking: String) {
    val isUser = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.88f else 0.96f),
            shape = RoundedCornerShape(18.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                val label = when (message.role) {
                    "user" -> stringResource(R.string.message_role_user)
                    "context" -> stringResource(R.string.message_role_plugin_context)
                    "tool" -> stringResource(R.string.message_role_tool, message.toolName ?: stringResource(R.string.message_role_tool_result))
                    else -> stringResource(R.string.message_role_assistant) + if (message.status == "streaming") " · ${stringResource(R.string.status_generating)}" else ""
                }
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (content.isNotBlank()) {
                    if (message.role == "tool") {
                        SelectionContainer { Text(content, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                    } else MarkdownText(content)
                } else if (message.status == "streaming") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.status_connecting_model), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (thinking.isNotBlank()) ThinkingSection(thinking)
                ToolCallsSection(message.toolCallsJson)
                if (message.status in setOf("error", "interrupted")) {
                    Text(
                        stringResource(if (message.status == "error") R.string.error_request_failed else R.string.error_task_interrupted),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThinkingSection(content: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.thinking_process, if (expanded) "▲" else "▼"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
    }
    if (expanded) SelectionContainer { Text(content, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun ToolCallsSection(raw: String) {
    val tools = remember(raw) { runCatching { JSONArray(raw) }.getOrNull() } ?: return
    for (index in 0 until tools.length()) {
        val call = runCatching { tools.getJSONObject(index) }.getOrNull() ?: continue
        val name = call.optString("name", "tool")
        val args = call.optString("arguments", "{}")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.tool_call_label, name), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            SelectionContainer {
                Text(args, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MarkdownText(source: String) {
    val lines = source.split('\n')
    var inCode = false
    val codeLines = mutableListOf<String>()
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        lines.forEach { line ->
            if (line.trimStart().startsWith("```")) {
                if (inCode) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                        SelectionContainer {
                            Text(codeLines.joinToString("\n"), Modifier.fillMaxWidth().padding(10.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    codeLines.clear()
                    inCode = false
                } else inCode = true
            } else if (inCode) {
                codeLines += line
            } else if (line.isBlank()) {
                Spacer(Modifier.height(3.dp))
            } else {
                val headingLevel = line.takeWhile { it == '#' }.length
                val body = if (headingLevel > 0) line.drop(headingLevel).trimStart() else line
                val bullet = body.startsWith("- ") || body.startsWith("* ")
                val text = if (bullet) "• ${body.drop(2)}" else body
                SelectionContainer {
                    Text(
                        text,
                        style = when (headingLevel) {
                            1, 2 -> MaterialTheme.typography.titleMedium
                            3 -> MaterialTheme.typography.titleSmall
                            else -> MaterialTheme.typography.bodyMedium
                        },
                    )
                }
            }
        }
        if (inCode && codeLines.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                SelectionContainer { Text(codeLines.joinToString("\n"), Modifier.fillMaxWidth().padding(10.dp), fontFamily = FontFamily.Monospace) }
            }
        }
    }
}
