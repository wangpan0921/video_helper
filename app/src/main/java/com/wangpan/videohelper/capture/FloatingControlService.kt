package com.wangpan.videohelper.capture

import android.app.Service
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.wangpan.videohelper.R
import com.wangpan.videohelper.VideoHelperApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

/**
 * Draws a small, draggable control button on top of other apps once the user sends Video Helper to
 * the background. A tap toggles the recording: when idle it launches the (transparent) projection
 * consent flow that starts [ScreenRecordService]; while recording it stops the recording. The
 * button's look reflects [VideoHelperApp.recordingActive].
 *
 * Requires the "draw over other apps" permission (SYSTEM_ALERT_WINDOW); callers must verify it with
 * [canShow] before starting this service.
 */
class FloatingControlService : Service() {

    companion object {
        fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun start(context: Context) {
            context.startService(Intent(context, FloatingControlService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingControlService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: ImageView? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null
    /** "Breathing" alpha pulse shown while recording is active. */
    private var breathingAnimator: ObjectAnimator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        addFloatingButton()
        VideoHelperApp.floatingActive.value = true

        observeJob = scope.launch {
            VideoHelperApp.recordingActive.collectLatest { recording ->
                floatingView?.let { view ->
                    view.setImageResource(
                        if (recording) R.drawable.ic_float_stop else R.drawable.ic_float_record
                    )
                    view.setBackgroundResource(
                        if (recording) R.drawable.bg_float_recording else R.drawable.bg_float_idle
                    )
                    if (recording) startBreathing(view) else stopBreathing(view)
                }
            }
        }
    }

    /** A slow, infinite alpha pulse (bright↔dim) to signal an active recording. */
    private fun startBreathing(view: View) {
        if (breathingAnimator?.isRunning == true) return
        breathingAnimator = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.35f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopBreathing(view: View) {
        breathingAnimator?.cancel()
        breathingAnimator = null
        view.alpha = 1f
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun addFloatingButton() {
        val sizePx = (56 * resources.displayMetrics.density).toInt()
        val padPx = (14 * resources.displayMetrics.density).toInt()

        val view = ImageView(this).apply {
            setImageResource(
                if (VideoHelperApp.recordingActive.value) R.drawable.ic_float_stop
                else R.drawable.ic_float_record
            )
            setBackgroundResource(
                if (VideoHelperApp.recordingActive.value) R.drawable.bg_float_recording
                else R.drawable.bg_float_idle
            )
            setPadding(padPx, padPx, padPx, padPx)
            contentDescription = getString(R.string.float_toggle_desc)
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - sizePx - padPx
            y = resources.displayMetrics.heightPixels / 3
        }

        attachTouchListener(view, params)
        windowManager.addView(view, params)
        floatingView = view
    }

    /** Distinguishes a tap (toggle recording) from a drag (reposition the button). */
    private fun attachTouchListener(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var dragged = false
        val touchSlop = (8 * resources.displayMetrics.density)

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) dragged = true
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) onTap()
                    true
                }
                else -> false
            }
        }
    }

    private fun onTap() {
        if (VideoHelperApp.recordingActive.value) {
            // Stop the active recording; ScreenRecordService updates state + last path.
            ContextCompat.startForegroundService(this, ScreenRecordService.stopIntent(this))
            // The floating flow ends once the user stops recording.
            stopSelf()
        } else {
            // Launch the transparent consent activity, which starts ScreenRecordService on approval.
            val intent = Intent(this, ProjectionRequestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(
                    RecordingConfig.EXTRA_INCLUDE_MIC,
                    VideoHelperApp.pendingIncludeMic
                )
            }
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        breathingAnimator?.cancel()
        breathingAnimator = null
        observeJob?.cancel()
        floatingView?.let { runCatching { windowManager.removeView(it) } }
        floatingView = null
        VideoHelperApp.floatingActive.value = false
    }
}
