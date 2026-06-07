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
import androidx.core.content.ContextCompat
import com.wangpan.videohelper.R
import com.wangpan.videohelper.VideoHelperApp
import com.wangpan.videohelper.capture.FloatingControlService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VideoHelperTheme {
                AppRoot(
                    onStartRecording = { includeMic -> beginFloatingFlow(includeMic) },
                    onStopRecording = { stopRecording() }
                )
            }
        }
    }

    // --- Floating-button recording flow ------------------------------------------------------

    private var pendingIncludeMic = false

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
     * Step 4 of the requested flow: tapping "开始录屏" sends the app to the background and shows a
     * floating control button. Tapping that button (handled by the overlay service) starts/stops
     * the actual recording. We first make sure the "draw over other apps" permission is granted.
     */
    private fun beginFloatingFlow(includeMic: Boolean) {
        pendingIncludeMic = includeMic
        VideoHelperApp.pendingIncludeMic = includeMic

        // Ask for "All files access" once so recordings land in the public /sdcard/videohelper folder.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Toast.makeText(this, R.string.storage_permission_hint, Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            // Continue regardless; if the user declines, files fall back to the app-specific dir.
        }

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
