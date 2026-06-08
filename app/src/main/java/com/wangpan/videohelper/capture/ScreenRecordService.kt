package com.wangpan.videohelper.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.wangpan.videohelper.R
import com.wangpan.videohelper.VideoHelperApp
import com.wangpan.videohelper.data.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service (type=mediaProjection) that owns the [MediaProjection] for the lifetime of a
 * recording. Android 14+ requires the projection to be obtained while a typed foreground service is
 * running, so we start the notification before acquiring the projection.
 */
class ScreenRecordService : Service() {

    companion object {
        private const val TAG = "ScreenRecordService"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1001

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenRecordService::class.java).apply {
                action = RecordingConfig.ACTION_STOP
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var projection: MediaProjection? = null
    private var recorder: ScreenRecorder? = null
    private var outputFile: File? = null
    private var includeMic = false

    /** Dedicated thread for MediaProjection callbacks (Android 14+ requires a non-null handler). */
    private var callbackThread: HandlerThread? = null
    /** Guards against double teardown when both the stop action and the projection onStop fire. */
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            RecordingConfig.ACTION_STOP -> {
                stopRecording()
                return START_NOT_STICKY
            }
            else -> startRecording(intent)
        }
        return START_STICKY
    }

    private fun startRecording(intent: Intent?) {
        if (intent == null) {
            stopSelf()
            return
        }
        startForegroundNotification()

        val resultCode = intent.getIntExtra(RecordingConfig.EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(RecordingConfig.EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(RecordingConfig.EXTRA_RESULT_DATA)
        }
        if (resultData == null) {
            Log.e(TAG, "missing projection result data")
            stopSelf()
            return
        }

        includeMic = intent.getBooleanExtra(RecordingConfig.EXTRA_INCLUDE_MIC, false)
        // The consent activity is transparent and may report unreliable metrics, so resolve the real
        // display size from the service's window manager; only fall back to the passed extras (and a
        // last-resort default) when the live metrics look invalid.
        val metrics = currentDisplayMetrics()
        val extraW = intent.getIntExtra(RecordingConfig.EXTRA_SCREEN_WIDTH, 0)
        val extraH = intent.getIntExtra(RecordingConfig.EXTRA_SCREEN_HEIGHT, 0)
        val extraDpi = intent.getIntExtra(RecordingConfig.EXTRA_SCREEN_DPI, 0)
        val width = (metrics?.first ?: 0).takeIf { it >= 64 }
            ?: extraW.takeIf { it >= 64 } ?: 1080
        val height = (metrics?.second ?: 0).takeIf { it >= 64 }
            ?: extraH.takeIf { it >= 64 } ?: 1920
        val dpi = (metrics?.third ?: 0).takeIf { it > 0 }
            ?: extraDpi.takeIf { it > 0 } ?: 320

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val handlerThread = HandlerThread("vh-projection-cb").also { it.start() }
        callbackThread = handlerThread
        val callbackHandler = Handler(handlerThread.looper)
        projection = mpm.getMediaProjection(resultCode, resultData).apply {
            registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    // The user (or system) ended the projection from the status bar / lock screen.
                    stopRecording()
                }
            }, callbackHandler)
        }

        // Each recording session gets its own timestamp-named folder under /sdcard/videohelper,
        // so the mp4 (and later the exported article) live together. Falls back to the app-specific
        // external dir when "All files access" is not granted.
        val sessionName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))
        val dir = com.wangpan.videohelper.storage.AppStorage.sessionDir(this, sessionName)
        val file = File(dir, "rec_$sessionName.mp4")
        outputFile = file

        try {
            recorder = ScreenRecorder(
                projection = projection!!,
                width = width,
                height = height,
                dpi = dpi,
                includeMic = includeMic,
                outputFile = file
            ).also { it.start() }
            // Recorder is live — reflect it in the UI / floating button regardless of caller.
            VideoHelperApp.recordingActive.value = true
        } catch (e: Exception) {
            Log.e(TAG, "failed to start recorder", e)
            toastOnMain(getString(R.string.recording_start_failed))
            stopRecording()
        }
    }

    private fun stopRecording() {
        if (stopped) return
        stopped = true
        val durationMs = try {
            recorder?.stop() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "stop error", e)
            0L
        }
        recorder = null
        projection?.stop()
        projection = null
        callbackThread?.quitSafely()
        callbackThread = null

        val file = outputFile
        if (file != null && file.exists() && file.length() > 0) {
            VideoHelperApp.lastRecordingPath.value = file.absolutePath
            // Tell the user where the recording was saved.
            toastOnMain(getString(R.string.recording_saved_at, file.absolutePath))
            val repo = TaskRepository.get(applicationContext)
            val mic = includeMic
            scope.launch {
                repo.createFromRecording(file.absolutePath, durationMs, mic)
            }
        }

        VideoHelperApp.recordingActive.value = false
        // Tear down the floating control button once a recording session ends.
        FloatingControlService.stop(applicationContext)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.recording_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(getString(R.string.recording_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.action_stop), stopPendingIntent
                ).build()
            )
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Toasts are UI; stopRecording can run on the projection callback thread, so hop to main. */
    private fun toastOnMain(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    /** Real display (width, height, densityDpi) from the live default display, or null if unknown. */
    private fun currentDisplayMetrics(): Triple<Int, Int, Int>? {
        return try {
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            val dpi = resources.configuration.densityDpi
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                Triple(bounds.width(), bounds.height(), dpi)
            } else {
                val dm = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                Triple(dm.widthPixels, dm.heightPixels, if (dm.densityDpi > 0) dm.densityDpi else dpi)
            }
        } catch (e: Exception) {
            Log.w(TAG, "display metrics unavailable", e)
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        VideoHelperApp.recordingActive.value = false
    }
}
