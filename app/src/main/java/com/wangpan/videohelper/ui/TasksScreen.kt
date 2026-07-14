package com.wangpan.videohelper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wangpan.videohelper.R
import com.wangpan.videohelper.data.db.StageStatus
import com.wangpan.videohelper.data.db.TaskEntity

@Composable
fun TasksScreen(
    onOpen: (String) -> Unit,
    viewModel: TasksViewModel = viewModel()
) {
    val tasks by viewModel.tasks.collectAsState()

    // Holds the task pending deletion while the confirmation dialog is shown.
    var pendingDelete by remember { mutableStateOf<TaskEntity?>(null) }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_message, target.title)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.delete_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.delete_confirm_cancel))
                }
            }
        )
    }

    if (tasks.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "还没有录制任务。去「录制」页开始，或把已保存的录制文件放到 videohelper 目录后重新进入本页。",
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp)
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(tasks, key = { it.id }) { task ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(task.id) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(task.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = progressLabel(task),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!task.anyRunning() && task.summarizeStatus != StageStatus.DONE) {
                        TextButton(onClick = { viewModel.runAll(task.id) }) {
                            Text(stringResource(R.string.action_run_all))
                        }
                    }
                    IconButton(onClick = { pendingDelete = task }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                    }
                }
            }
        }
    }
}

private fun TaskEntity.anyRunning(): Boolean =
    audioStatus == StageStatus.RUNNING ||
        transcribeStatus == StageStatus.RUNNING ||
        summarizeStatus == StageStatus.RUNNING

private fun progressLabel(task: TaskEntity): String {
    val steps = listOf(
        "音频" to task.audioStatus,
        "转写" to task.transcribeStatus,
        "文章" to task.summarizeStatus
    )
    return steps.joinToString("  ") { (name, status) ->
        val mark = when (status) {
            StageStatus.DONE -> "✓"
            StageStatus.RUNNING -> "…"
            StageStatus.FAILED -> "✗"
            StageStatus.IDLE -> "·"
        }
        "$name$mark"
    }
}
