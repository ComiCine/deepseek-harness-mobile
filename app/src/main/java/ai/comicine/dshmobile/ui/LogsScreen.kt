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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ai.comicine.dshmobile.HarnessViewModel
import ai.comicine.dshmobile.R
import ai.comicine.dshmobile.data.SessionEntity
import java.text.DateFormat
import java.util.Date

@Composable
fun LogsScreen(viewModel: HarnessViewModel, selectedId: String?, sessions: List<SessionEntity>) {
    val sessionId = selectedId ?: sessions.firstOrNull()?.id
    if (sessionId == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.logs_empty_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.logs_empty_message), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val logs by viewModel.logs(sessionId).collectAsState(initial = emptyList())
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(logs, key = { it.sequence }) { log ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(executionLogKindLabel(log.kind), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(log.createdAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(log.summary, style = MaterialTheme.typography.bodyMedium)
                    if (log.details.isNotBlank()) SelectionContainer { Text(log.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}
