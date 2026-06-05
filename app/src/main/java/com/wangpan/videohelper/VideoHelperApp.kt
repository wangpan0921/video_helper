package com.wangpan.videohelper

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow

class VideoHelperApp : Application() {
    companion object {
        /** Observed by the UI so the record button reflects the active recording session. */
        val recordingActive = MutableStateFlow(false)
    }
}
