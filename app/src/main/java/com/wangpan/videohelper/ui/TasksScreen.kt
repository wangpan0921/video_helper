package com.wangpan.videohelper.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wangpan.videohelper.R
import com.wangpan.videohelper.data.db.StageStatus
import com.wangpan.videohelper.data.db.TaskEntity
import com.wangpan.videohelper.storage.AppStorage

@Composable
fun TasksScreen(
    onOpen: (String) -> Unit,
    viewModel: TasksViewModel = viewModel()
) {
    val tasks by viewModel.tasks.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Whether we can read the public /sdcard/videohelper folder. Recomputed on resume so it
    // reflects a permission the user may have just granted in system settings.
    var hasStorageAccess by remember { mutableStateOf(AppStorage.hasPublicAccess()) }

    // Holds the task pending deletion while the confirmation dialog is shown.
    var pendingDelete by remember { mutableStateOf<TaskEntity?>(null) }

    // Re-scan the recording directory whenever the screen resumes (e.g. after returning from the
    // "All files access" settings page), so newly readable recordings show up without a restart.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasStorageAccess = AppStorage.hasPublicAccess()
                viewModel.rescanRecordings()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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

    Column(Modifier.fillMaxSize()) {
        // Header: storage-permission prompt (when needed) + manual rescan.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasStorageAccess) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.tasks_storage_needed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Button(onClick = { openAllFilesAccess(context) }) {
                            Text(stringResource(R.string.tasks_grant_access))
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { viewModel.rescanRecordings() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Text(
                        stringResource(R.string.tasks_rescan),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }

        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.tasks_empty),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
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
                            val itemProgress by viewModel.progress(task.id).collectAsState(initial = null)
                            val running = itemProgress
                            if (running != null) {
                                Text(
                                    text = running.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text(
                                    text = progressLabel(task),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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
}

/** Opens the system "All files access" settings page, with fallbacks across device variations. */
private fun openAllFilesAccess(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val pkg = Uri.parse("package:${context.packageName}")
    val candidates = listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    )
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
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
