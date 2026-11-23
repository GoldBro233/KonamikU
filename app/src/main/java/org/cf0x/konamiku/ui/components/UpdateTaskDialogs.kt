package org.cf0x.konamiku.ui.components

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.cf0x.konamiku.R
import org.cf0x.konamiku.system.UpdateDownloadService
import org.cf0x.konamiku.system.UpdateTask
import org.cf0x.konamiku.system.UpdateTask.Phase

/**
 * Renders the in-app update task on every page, so its progress, the
 * confirmation and a failure stay reachable after leaving the settings screen.
 */
@Composable
fun UpdateTaskDialogs() {
    val context = LocalContext.current
    val task by UpdateTask.state.collectAsState()

    when (task.phase) {
        Phase.IDLE -> Unit

        Phase.DOWNLOADING, Phase.INSTALLING -> AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.update_title)) },
            text = {
                Column {
                    val text = when {
                        task.phase == Phase.INSTALLING -> stringResource(R.string.update_installing)
                        task.retryingDirect && task.percent >= 0 ->
                            stringResource(R.string.update_downloading_retry, task.percent)
                        task.retryingDirect -> stringResource(R.string.update_downloading_retry_unknown)
                        task.percent >= 0 -> stringResource(R.string.update_downloading, task.percent)
                        else -> stringResource(R.string.update_downloading_unknown)
                    }
                    Text(text)
                    Spacer(Modifier.height(12.dp))
                    if (task.phase == Phase.DOWNLOADING && task.percent >= 0) {
                        LinearProgressIndicator(
                            progress = { task.percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                if (task.cancellable) {
                    TextButton(onClick = { UpdateDownloadService.cancel(context, task.taskId) }) {
                        Text(stringResource(R.string.update_cancel))
                    }
                } else {
                    // Committed: only the system decides now. Hiding the
                    // dialog does not affect the install.
                    TextButton(onClick = { UpdateTask.hide() }) {
                        Text(stringResource(R.string.update_hide))
                    }
                }
            }
        )

        Phase.AWAITING_USER -> AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.update_confirm_title)) },
            text = {
                Text(stringResource(R.string.update_confirm_desc, task.request?.version.orEmpty()))
            },
            confirmButton = {
                TextButton(onClick = {
                    val confirm = task.confirmIntent ?: return@TextButton
                    runCatching {
                        context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure { Log.w("KonamikU-Update", "Cannot open confirmation: ${it.message}") }
                }) { Text(stringResource(R.string.update_continue_install)) }
            },
            dismissButton = {
                TextButton(onClick = { UpdateTask.discard(context) }) {
                    Text(stringResource(R.string.update_dismiss))
                }
            }
        )

        Phase.FAILED -> AlertDialog(
            onDismissRequest = { UpdateTask.discard(context) },
            title = { Text(stringResource(R.string.update_install_failed)) },
            text = {
                val kind = task.kind
                val message = when {
                    kind == UpdateTask.FailureKind.INSTALL && !task.detail.isNullOrBlank() ->
                        "${stringResource(kind.messageRes)}\n${task.detail}"
                    kind != null -> stringResource(kind.messageRes)
                    else -> stringResource(R.string.update_install_failed_desc)
                }
                Text(message)
            },
            confirmButton = {
                val request = task.request
                TextButton(
                    enabled = request != null,
                    onClick = {
                        if (request != null) UpdateDownloadService.start(context, request)
                    }
                ) { Text(stringResource(R.string.update_retry)) }
            },
            dismissButton = {
                Row {
                    TextButton(
                        enabled = !task.sourceUrl.isNullOrBlank(),
                        onClick = {
                            val url = task.sourceUrl
                            UpdateTask.discard(context)
                            if (!url.isNullOrBlank()) {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        }
                    ) { Text(stringResource(R.string.update_open_browser)) }
                    TextButton(onClick = { UpdateTask.discard(context) }) {
                        Text(stringResource(R.string.update_dismiss))
                    }
                }
            }
        )
    }
}
