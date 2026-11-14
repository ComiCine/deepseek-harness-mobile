package ai.comicine.dshmobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ai.comicine.dshmobile.R

@Composable
fun executionStateLabel(state: String): String = stringResource(
    when (state) {
        "queued" -> R.string.status_queued
        "running" -> R.string.status_running
        "waiting_approval" -> R.string.status_waiting_approval
        "completed" -> R.string.status_completed
        "limit" -> R.string.status_step_limit
        "error" -> R.string.status_error
        "cancelled" -> R.string.status_cancelled
        "interrupted" -> R.string.status_interrupted
        else -> R.string.status_idle
    },
)

@Composable
fun executionLogKindLabel(kind: String): String = stringResource(
    when (kind) {
        "turn.start" -> R.string.log_kind_turn_start
        "step.start" -> R.string.log_kind_model_step
        "assistant.message" -> R.string.log_kind_model_response
        "tool.call" -> R.string.log_kind_tool_call
        "tool.approval" -> R.string.log_kind_tool_approval
        "tool.denied" -> R.string.log_kind_tool_denied
        "tool.result" -> R.string.log_kind_tool_result
        "tool.error" -> R.string.log_kind_tool_error
        "turn.end" -> R.string.log_kind_turn_end
        "turn.cancelled" -> R.string.log_kind_turn_cancelled
        "turn.error" -> R.string.log_kind_turn_error
        else -> R.string.log_kind_tool_call
    },
)
