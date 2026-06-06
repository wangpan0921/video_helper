package com.wangpan.videohelper.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.wangpan.videohelper.VideoHelperApp

/**
 * Invisible, no-UI activity used to obtain the [android.media.projection.MediaProjection] consent
 * when recording is triggered from the floating control button (i.e. while Video Helper is in the
 * background). MediaProjection consent must be requested from an Activity, so the overlay button
 * launches this transparent shim, which immediately shows the system consent dialog and then starts
 * [ScreenRecordService] before finishing.
 */
class ProjectionRequestActivity : ComponentActivity() {

    private var includeMic = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            startRecordingService(result.resultCode, data)
        }
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        includeMic = intent.getBooleanExtra(RecordingConfig.EXTRA_INCLUDE_MIC, false)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startRecordingService(resultCode: Int, data: Intent) {
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
}
