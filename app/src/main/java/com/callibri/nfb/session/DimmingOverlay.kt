package com.callibri.nfb.session

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.callibri.nfb.feedback.DimmingWindowSpec
import com.callibri.nfb.feedback.TouchObscuringLimit
import kotlin.math.abs

/**
 * Full-screen black overlay, separate from the small status bubble.
 *
 * [WindowManager.LayoutParams.alpha] is the only opacity control, so the value
 * Android uses for touch-obscuring matches what the user sees. The window does
 * not take focus or touches. [WindowManager.LayoutParams.screenBrightness] stays
 * at [WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE].
 */
class DimmingOverlay(
    private val context: Context,
    safeMaximumAlpha: Float = TouchObscuringLimit.FALLBACK_SAFE_MAX.toFloat(),
) {
    private val safeMaximum = safeMaximumAlpha.coerceIn(
        0f,
        TouchObscuringLimit.REQUESTED_CAP.toFloat(),
    )
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var animator: ValueAnimator? = null
    private var pending: Runnable? = null
    private var applied = 0f
    private var lastSent = -1f
    private var lastApplyMs = 0L
    private var epoch = 0

    /** Increases each time the window is added, so the status bubble can be raised above it. */
    var generation: Int = 0
        private set

    var onApplied: ((Double) -> Unit)? = null
    var onUnavailable: ((String) -> Unit)? = null

    fun isAttached(): Boolean = view != null

    fun setTargetAlpha(alpha: Double) {
        val target = alpha.toFloat().coerceIn(0f, safeMaximum)
        val token = epoch
        main.post {
            if (token != epoch) return@post
            schedule(target, token)
        }
    }

    fun hide() {
        epoch++
        val token = epoch
        val action = Runnable {
            if (token != epoch) return@Runnable
            pending?.let { main.removeCallbacks(it) }
            pending = null
            animator?.cancel()
            animator = null
            removeWindow()
            applied = 0f
            lastSent = -1f
            onApplied?.invoke(0.0)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else main.post(action)
    }

    private fun schedule(target: Float, token: Int) {
        if (token != epoch) return
        pending?.let { main.removeCallbacks(it) }
        pending = null
        if (view != null && abs(target - lastSent) < 0.004f) return
        val wait = MIN_INTERVAL_MS - (SystemClock.uptimeMillis() - lastApplyMs)
        if (wait > 0L) {
            val task = Runnable { schedule(target, token) }
            pending = task
            main.postDelayed(task, wait)
            return
        }
        if (!ensureAdded()) return
        lastSent = target
        lastApplyMs = SystemClock.uptimeMillis()
        retarget(target, token)
    }

    private fun retarget(target: Float, token: Int) {
        val start = params?.alpha ?: 0f
        animator?.cancel()
        if (abs(start - target) < 0.004f) {
            apply(target)
            onApplied?.invoke(target.toDouble())
            return
        }
        animator = ValueAnimator.ofFloat(start, target).apply {
            duration = 150L
            addUpdateListener { animation ->
                if (token != epoch) {
                    cancel()
                    return@addUpdateListener
                }
                apply(animation.animatedValue as Float)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (token == epoch) onApplied?.invoke(applied.toDouble())
                }
            })
            start()
        }
    }

    private fun ensureAdded(): Boolean {
        if (view != null) return true
        val manager = windowManager
        if (manager == null) {
            onUnavailable?.invoke("Screen dimming needs a display.")
            return false
        }
        if (!Settings.canDrawOverlays(context)) {
            onUnavailable?.invoke("Allow display over other apps to dim the screen.")
            return false
        }
        val shade = View(context).apply {
            setBackgroundColor(Color.BLACK)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            DimmingWindowSpec.TYPE,
            DimmingWindowSpec.FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0f
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        return try {
            manager.addView(shade, layout)
            view = shade
            params = layout
            generation += 1
            true
        } catch (error: Exception) {
            Log.w(TAG, "dim overlay was not added", error)
            onUnavailable?.invoke("Screen dimming could not be shown over other apps.")
            false
        }
    }

    private fun apply(alpha: Float) {
        val layout = params ?: return
        val child = view ?: return
        val manager = windowManager ?: return
        layout.alpha = alpha.coerceIn(0f, safeMaximum)
        applied = layout.alpha
        try {
            manager.updateViewLayout(child, layout)
        } catch (error: Exception) {
            Log.w(TAG, "dim overlay update failed", error)
        }
    }

    private fun removeWindow() {
        val child = view ?: return
        val manager = windowManager
        try {
            if (child.isAttachedToWindow) manager?.removeView(child)
        } catch (error: Exception) {
            Log.w(TAG, "dim overlay remove failed", error)
        }
        view = null
        params = null
    }

    private companion object {
        const val TAG = "CallibriNFB"
        const val MIN_INTERVAL_MS = 100L
    }
}
