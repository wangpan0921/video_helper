package com.wangpan.videohelper.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import com.wangpan.videohelper.R
import com.wangpan.videohelper.data.db.StageStatus
import com.wangpan.videohelper.data.db.TaskEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    taskId: String,
    onBack: () -> Unit,
    viewModel: TaskDetailViewModel = viewModel()
) {
    val task by viewModel.task(taskId).collectAsState(initial = null)
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // Holds the exported file path so we can show it in full (a toast truncates long paths).
    var exportedPath by remember { mutableStateOf<String?>(null) }

    exportedPath?.let { path ->
        AlertDialog(
            onDismissRequest = { exportedPath = null },
            title = { Text(stringResource(R.string.export_success)) },
            text = { Text(path) },
            confirmButton = {
                TextButton(onClick = { exportedPath = null }) {
                    Text(stringResource(R.string.dialog_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(path))
                    Toast.makeText(context, R.string.path_copied, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.copy_path)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(task?.title ?: "任务详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        val t = task
        if (t == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("加载中…") }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { viewModel.runAll(taskId) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !t.anyRunning()
            ) { Text(stringResource(R.string.action_run_all)) }

            val exportFailPrefix = stringResource(R.string.export_failed)
            OutlinedButton(
                onClick = {
                    viewModel.exportArticle(taskId) { result ->
                        result.fold(
                            onSuccess = { path -> exportedPath = path },
                            onFailure = { e ->
                                Toast.makeText(
                                    context,
                                    e.message ?: exportFailPrefix,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = t.article?.isNotBlank() == true
            ) { Text(stringResource(R.string.action_export_md)) }

            t.errorMessage?.let { err ->
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "错误：$err",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            StageCard(
                title = stringResource(R.string.detail_video),
                status = StageStatus.DONE,
                body = "已录制 · 时长 ${t.durationMs / 1000}s · 麦克风${if (t.micIncluded) "开" else "关"}",
                actionLabel = null,
                onAction = {},
                enabled = false
            )

            StageCard(
                title = stringResource(R.string.detail_audio),
                status = t.audioStatus,
                body = t.audioPath?.let { "已抽取音频" } ?: "尚未抽取",
                actionLabel = actionLabelFor(t.audioStatus, stringResource(R.string.action_extract_audio)),
                onAction = { viewModel.extractAudio(taskId) },
                enabled = !t.anyRunning()
            )

            val transcriptText = t.transcript?.takeIf { it.isNotBlank() }
            val transcriptCopiedMsg = stringResource(R.string.transcript_copied)
            StageCard(
                title = stringResource(R.string.detail_transcript),
                status = t.transcribeStatus,
                body = transcriptText ?: "尚未转写",
                actionLabel = actionLabelFor(t.transcribeStatus, stringResource(R.string.action_transcribe)),
                onAction = { viewModel.transcribe(taskId) },
                enabled = !t.anyRunning() && t.audioStatus == StageStatus.DONE,
                onLongPressBody = transcriptText?.let {
                    {
                        clipboard.setText(AnnotatedString(it))
                        Toast.makeText(context, transcriptCopiedMsg, Toast.LENGTH_SHORT).show()
                    }
                },
                bodyHint = transcriptText?.let { stringResource(R.string.transcript_copy_hint) }
            )

            StageCard(
                title = stringResource(R.string.detail_article),
                status = t.summarizeStatus,
                body = t.article?.takeIf { it.isNotBlank() } ?: "尚未生成",
                actionLabel = actionLabelFor(t.summarizeStatus, stringResource(R.string.action_summarize)),
                onAction = { viewModel.summarize(taskId) },
                enabled = !t.anyRunning() && t.transcribeStatus == StageStatus.DONE
            )
        }
    }
}

private fun TaskEntity.anyRunning(): Boolean =
    audioStatus == StageStatus.RUNNING ||
        transcribeStatus == StageStatus.RUNNING ||
        summarizeStatus == StageStatus.RUNNING

private fun actionLabelFor(status: StageStatus, defaultLabel: String): String =
    if (status == StageStatus.FAILED || status == StageStatus.DONE) "重试" else defaultLabel

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StageCard(
    title: String,
    status: StageStatus,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
    enabled: Boolean,
    onLongPressBody: (() -> Unit)? = null,
    bodyHint: String? = null
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(statusLabel(status), style = MaterialTheme.typography.labelMedium)
            }
            val bodyModifier = if (onLongPressBody != null) {
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = {}, onLongClick = onLongPressBody)
            } else {
                Modifier
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, modifier = bodyModifier)
            if (bodyHint != null) {
                Text(
                    bodyHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (actionLabel != null) {
                OutlinedButton(
                    onClick = onAction,
                    enabled = enabled && status != StageStatus.RUNNING
                ) { Text(actionLabel) }
            }
        }
    }
}

private fun statusLabel(status: StageStatus): String = when (status) {
    StageStatus.IDLE -> "待处理"
    StageStatus.RUNNING -> "处理中…"
    StageStatus.DONE -> "完成"
    StageStatus.FAILED -> "失败"
}
