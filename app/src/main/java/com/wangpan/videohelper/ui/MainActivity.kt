package com.wangpan.videohelper.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.wangpan.videohelper.capture.RecordingConfig
import com.wangpan.videohelper.capture.ScreenRecordService
import com.wangpan.videohelper.VideoHelperApp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VideoHelperTheme {
                AppRoot(
                    onStartRecording = { includeMic -> requestProjection(includeMic) },
                    onStopRecording = { stopRecording() }
                )
            }
        }
    }

    // --- Recording orchestration -------------------------------------------------------------

    private var pendingIncludeMic = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            startRecordingService(result.resultCode, data, pendingIncludeMic)
        }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* even if denied, recording continues with system audio only */
        launchProjectionConsent()
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless; notification is best-effort */ }

    private fun requestProjection(includeMic: Boolean) {
        pendingIncludeMic = includeMic
        // Notification permission on Android 13+ so the foreground service notification shows.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (includeMic &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchProjectionConsent()
        }
    }

    private fun launchProjectionConsent() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startRecordingService(resultCode: Int, data: Intent, includeMic: Boolean) {
        val metrics = resources.displayMetrics
        val intent = Intent(this, ScreenRecordService::class.java).apply {
            action = RecordingConfig.ACTION_START
            putExtra(RecordingConfig.EXTRA_RESULT_CODE, resultCode)
            putExtra(RecordingConfig.EXTRA_RESULT_DATA, data)
            putExtra(RecordingConfig.EXTRA_INCLUDE_MIC, includeMic)
            putExtra(RecordingConfig.EXTRA_SCREEN_WIDTH, metrics.widthPixels)
            putExtra(RecordingConfig.EXTRA_SCREEN_HEIGHT, metrics.heightPixels)
            putExtra(RecordingConfig.EXTRA_SCREEN_DPI, metrics.densityDpi)
        }
        ContextCompat.startForegroundService(this, intent)
        VideoHelperApp.recordingActive.value = true
    }

    private fun stopRecording() {
        ContextCompat.startForegroundService(this, ScreenRecordService.stopIntent(this))
        VideoHelperApp.recordingActive.value = false
    }
}
