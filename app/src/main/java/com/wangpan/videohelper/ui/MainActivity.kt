package com.wangpan.videohelper.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.wangpan.videohelper.R
import com.wangpan.videohelper.VideoHelperApp
import com.wangpan.videohelper.capture.FloatingControlService

class MainActivity : ComponentActivity() {

    private var pendingIncludeMic = false

    // Drives the "All files access" explanation dialog (request: show a real prompt, not a toast).
    private val showStorageDialog = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VideoHelperTheme {
                AppRoot(
                    onStartRecording = { includeMic -> beginFloatingFlow(includeMic) },
                    onStopRecording = { stopRecording() }
                )

                if (showStorageDialog.value) {
                    AlertDialog(
                        onDismissRequest = {
                            // Treat outside-tap like "暂不": continue with the fallback directory.
                            showStorageDialog.value = false
                            continueFloatingFlow()
                        },
                        title = { Text(stringResource(R.string.storage_dialog_title)) },
                        text = { Text(stringResource(R.string.storage_dialog_message)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showStorageDialog.value = false
                                openAllFilesAccessSettings()
                            }) { Text(stringResource(R.string.storage_dialog_grant)) }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showStorageDialog.value = false
                                continueFloatingFlow()
                            }) { Text(stringResource(R.string.storage_dialog_skip)) }
                        }
                    )
                }
            }
        }
    }

    // --- Floating-button recording flow ------------------------------------------------------

    private val notifPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless; notification is best-effort */ }

    private val micPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* even if denied, recording continues with system audio only */
        showFloatingButtonAndBackground()
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        if (FloatingControlService.canShow(this)) {
            requestMicThenFloat()
        } else {
            Toast.makeText(this, R.string.float_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Tapping "开始录屏": if "All files access" is missing we show an explanation dialog first
     * (so recordings can land in /sdcard/videohelper). Otherwise we go straight to the overlay
     * button + background flow.
     */
    private fun beginFloatingFlow(includeMic: Boolean) {
        pendingIncludeMic = includeMic
        VideoHelperApp.pendingIncludeMic = includeMic

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            // Show a real dialog (a toast was easy to miss); defer recording until the user chooses.
            showStorageDialog.value = true
            return
        }
        continueFloatingFlow()
    }

    /** Continues the recording flow after the storage decision: notif → overlay → mic → float. */
    private fun continueFloatingFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (!FloatingControlService.canShow(this)) {
            Toast.makeText(this, R.string.float_permission_needed, Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }
        requestMicThenFloat()
    }

    /** Opens the "All files access" settings page, with fallbacks across device variations. */
    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val candidates = listOf(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            ),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
        for (intent in candidates) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
        Toast.makeText(this, R.string.storage_open_failed, Toast.LENGTH_LONG).show()
    }

    private fun requestMicThenFloat() {
        if (pendingIncludeMic &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            showFloatingButtonAndBackground()
        }
    }

    private fun showFloatingButtonAndBackground() {
        VideoHelperApp.pendingIncludeMic = pendingIncludeMic
        FloatingControlService.start(this)
        // Send the app to the background so the user can navigate to the video they want to record.
        moveTaskToBack(true)
    }

    private fun stopRecording() {
        ContextCompat.startForegroundService(
            this,
            com.wangpan.videohelper.capture.ScreenRecordService.stopIntent(this)
        )
        VideoHelperApp.recordingActive.value = false
        FloatingControlService.stop(this)
    }
}
