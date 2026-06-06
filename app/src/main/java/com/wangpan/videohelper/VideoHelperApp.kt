package com.wangpan.videohelper

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow

class VideoHelperApp : Application() {
    companion object {
        /** Observed by the UI so the record button reflects the active recording session. */
        val recordingActive = MutableStateFlow(false)

        /** True while the floating control button is shown on top of other apps. */
        val floatingActive = MutableStateFlow(false)

        /** Absolute path of the most recently finished recording (null until one completes). */
        val lastRecordingPath = MutableStateFlow<String?>(null)

        /**
         * Whether the next recording should mix in the microphone. Set when the user starts the
         * floating flow and read back when the projection consent activity finally launches the
         * recording service.
         */
        @Volatile
        var pendingIncludeMic: Boolean = false
    }
}
