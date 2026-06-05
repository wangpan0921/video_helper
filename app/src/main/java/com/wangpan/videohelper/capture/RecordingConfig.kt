package com.wangpan.videohelper.capture

import android.media.projection.MediaProjection

/**
 * Holds recording parameters handed from the Activity (which owns the MediaProjection consent
 * result) to the foreground service. The Intent only carries the consent result code/data; the
 * extra flags below describe how to record.
 */
object RecordingConfig {
    const val EXTRA_RESULT_CODE = "extra_result_code"
    const val EXTRA_RESULT_DATA = "extra_result_data"
    const val EXTRA_INCLUDE_MIC = "extra_include_mic"
    const val EXTRA_SCREEN_WIDTH = "extra_screen_width"
    const val EXTRA_SCREEN_HEIGHT = "extra_screen_height"
    const val EXTRA_SCREEN_DPI = "extra_screen_dpi"

    const val ACTION_START = "com.wangpan.videohelper.START"
    const val ACTION_STOP = "com.wangpan.videohelper.STOP"
}

/** Callback used so the service can notify the app layer when a recording finishes. */
fun interface RecordingCompletionListener {
    fun onFinished(videoPath: String, durationMs: Long, micIncluded: Boolean)
}

/** Simple holder for the active MediaProjection lifecycle callbacks. */
abstract class ProjectionCallback : MediaProjection.Callback()
