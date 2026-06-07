package com.wangpan.videohelper.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Centralizes where Video Helper writes user-visible files. The product requirement is a fixed,
 * easy-to-find folder at `/sdcard/videohelper` for both recordings and exported articles.
 *
 * Writing to that public path needs "All files access" (MANAGE_EXTERNAL_STORAGE) on API 30+ or
 * legacy external storage on API 29. When access is not granted we fall back to the app-specific
 * external dir so recording never silently fails.
 */
object AppStorage {

    const val DIR_NAME = "videohelper"

    /** True when we may write directly to the public `/sdcard/videohelper`. */
    fun hasPublicAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // API 29 uses requestLegacyExternalStorage + WRITE_EXTERNAL_STORAGE.
            true
        }

    /** The public `/sdcard/videohelper` directory (may not be writable without permission). */
    fun publicDir(): File = File(Environment.getExternalStorageDirectory(), DIR_NAME)

    /**
     * Returns the directory to write into, creating it if needed. Prefers the public
     * `/sdcard/videohelper`; falls back to the app-specific external dir when access is missing.
     */
    fun outputDir(context: Context): File {
        if (hasPublicAccess()) {
            val dir = publicDir()
            if (dir.exists() || dir.mkdirs()) return dir
        }
        val fallback = File(context.getExternalFilesDir(null) ?: context.filesDir, DIR_NAME)
        fallback.mkdirs()
        return fallback
    }
}
