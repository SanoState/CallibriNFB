package com.callibri.nfb.session

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A small draggable [WindowManager] panel. It holds the application context only.
 * Position lasts until this controller is discarded with the service.
 */
class FeedbackOverlay(
    private val context: Context,
    private val onStop: () -> Unit,
    private val onOpenApp: () -> Unit,
    private val onToggleMedia: () -> Unit = {},
    private val onToggleAudio: () -> Unit = {},
    private val onToggleVisual: () -> Unit = {},
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var expandedPanel: LinearLayout? = null
    private var rewardView: TextView? = null
    private var dotView: TextView? = null
    private var callibriView: TextView? = null
    private var eegView: TextView? = null
    private var smoothedView: TextView? = null
    private var volumeView: TextView? = null
    private var elapsedView: TextView? = null
    private var electrodeView: TextView? = null
    private var mediaStateView: TextView? = null
    private var mediaLevelView: TextView? = null
    private var mediaToggle: TextView? = null
    private var audioStateView: TextView? = null
    private var visualStateView: TextView? = null
    private var visualDimView: TextView? = null
    private var audioToggle: TextView? = null
    private var visualToggle: TextView? = null
    private var manualView: TextView? = null
    private var expanded = false
    private var posX = dp(12)
    private var posY = dp(96)

    fun isAttached(): Boolean = root != null

    fun hide() {
        val view = root ?: return
        try {
            if (view.isAttachedToWindow) windowManager?.removeView(view)
        } catch (error: Exception) {
            Log.w(TAG, "overlay remove failed", error)
        }
        root = null
        params = null
        expandedPanel = null
    }

    /** Puts this window above the full-screen dim layer after that layer is added. */
    fun bringToFront() {
        val view = root ?: return
        val layout = params ?: return
        val manager = windowManager ?: return
        try {
            if (view.isAttachedToWindow) manager.removeView(view)
            manager.addView(view, layout)
        } catch (error: Exception) {
            Log.w(TAG, "overlay raise failed", error)
        }
    }

    fun render(model: OverlayModel): Boolean {
        val manager = windowManager
        if (manager == null) {
            Log.w(TAG, "overlay skipped; no window manager")
            return false
        }
        try {
            ensureAdded(manager)
        } catch (error: SecurityException) {
            Log.w(TAG, "overlay permission missing", error)
            hide()
            return false
        } catch (error: Exception) {
            Log.e(TAG, "overlay add failed", error)
            hide()
            return false
        }
        dotView?.setTextColor(model.dotArgb)
        rewardView?.text = model.collapsedReward
        callibriView?.text = "Callibri: ${model.callibriLabel}"
        eegView?.text = "EEG: ${model.eegLabel}"
        smoothedView?.text = "Smoothed reward: ${model.smoothedLabel}"
        volumeView?.text = "Feedback volume: ${model.volumeLabel}"
        mediaStateView?.text = "Media feedback: ${if (model.mediaOn) "ON" else "OFF"}"
        mediaLevelView?.text = "Media level: ${model.mediaLevelLabel}"
        audioStateView?.text = "Audio feedback: ${if (model.audioOn) "ON" else "OFF"}"
        visualStateView?.text = "Visual feedback: ${if (model.visualOn) "ON" else "OFF"}"
        visualDimView?.text = "Visual dimming: ${model.visualDimLabel}"
        mediaToggle?.text = if (model.mediaOn) "Media feedback off" else "Media feedback on"
        audioToggle?.text = if (model.audioOn) "Audio off" else "Audio on"
        visualToggle?.text = if (model.visualOn) "Visual off" else "Visual on"
        elapsedView?.text = "Elapsed: ${model.elapsedLabel}"
        val electrode = model.electrodeLabel
        electrodeView?.text = if (electrode.isNullOrBlank()) "Electrodes: —" else "Electrodes: $electrode"
        manualView?.text = "Manual test is overriding the reward."
        manualView?.visibility = if (model.manualOverride) View.VISIBLE else View.GONE
        return root != null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureAdded(manager: WindowManager) {
        if (root != null) return
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = cardBackground()
            elevation = 8f * density
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = text("●", 18f, bold = true)
        val reward = text("—", 20f, bold = true).apply {
            setPadding(dp(8), 0, dp(8), 0)
        }
        val caption = text("NFB", 11f, bold = false).apply {
            setTextColor(0xFFD3EBE7.toInt())
        }
        val titles = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(reward)
            addView(caption)
        }
        val stop = chip("Stop")
        stop.setOnClickListener { onStop() }
        header.addView(dot)
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(stop)
        titles.setOnTouchListener(dragListener())
        dot.setOnTouchListener(dragListener())

        val details = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        val callibri = line()
        val eeg = line()
        val smoothed = line()
        val volume = line()
        val elapsed = line()
        val electrode = line()
        val mediaState = line()
        val mediaLevel = line()
        val mediaButton = chip("Media feedback on").apply {
            setOnClickListener { onToggleMedia() }
        }
        val audioLine = line()
        val visualLine = line()
        val dimLine = line()
        val audioButton = chip("Audio on").apply {
            setOnClickListener { onToggleAudio() }
        }
        val visualButton = chip("Visual on").apply {
            setOnClickListener { onToggleVisual() }
        }
        val manual = line().apply {
            text = "Manual test is overriding the reward."
            visibility = View.GONE
        }
        val stopSession = chip("Stop session").apply {
            setOnClickListener { onStop() }
        }
        val open = chip("Open Callibri NFB").apply {
            setOnClickListener { onOpenApp() }
        }
        val collapse = chip("Collapse").apply {
            setOnClickListener { setExpanded(false) }
        }
        details.addView(callibri)
        details.addView(eeg)
        details.addView(smoothed)
        details.addView(volume)
        details.addView(mediaState)
        details.addView(mediaLevel)
        details.addView(audioLine)
        details.addView(visualLine)
        details.addView(dimLine)
        details.addView(audioButton.paddedTop())
        details.addView(visualButton.paddedTop())
        details.addView(mediaButton.paddedTop())
        details.addView(elapsed)
        details.addView(electrode)
        details.addView(manual)
        details.addView(stopSession.paddedTop())
        details.addView(open.paddedTop())
        details.addView(collapse.paddedTop())

        panel.addView(header)
        panel.addView(details)
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = posX
            y = posY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        manager.addView(panel, layout)
        root = panel
        params = layout
        expandedPanel = details
        rewardView = reward
        dotView = dot
        callibriView = callibri
        eegView = eeg
        smoothedView = smoothed
        volumeView = volume
        mediaStateView = mediaState
        mediaLevelView = mediaLevel
        mediaToggle = mediaButton
        audioStateView = audioLine
        visualStateView = visualLine
        visualDimView = dimLine
        audioToggle = audioButton
        visualToggle = visualButton
        elapsedView = elapsed
        electrodeView = electrode
        manualView = manual
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun dragListener(): View.OnTouchListener {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        return View.OnTouchListener { _, event ->
            val layout = params ?: return@OnTouchListener false
            val view = root ?: return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = layout.x
                    startY = layout.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).roundToInt()
                    val dy = (event.rawY - downRawY).roundToInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    layout.x = startX + dx
                    layout.y = startY + dy
                    clamp(layout, view)
                    posX = layout.x
                    posY = layout.y
                    try {
                        windowManager?.updateViewLayout(view, layout)
                    } catch (error: Exception) {
                        Log.w(TAG, "overlay move failed", error)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) setExpanded(!expanded)
                    true
                }
                else -> false
            }
        }
    }

    private fun setExpanded(show: Boolean) {
        expanded = show
        expandedPanel?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun clamp(layout: WindowManager.LayoutParams, view: View) {
        val metrics = context.resources.displayMetrics
        val maxX = (metrics.widthPixels - view.width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - view.height).coerceAtLeast(0)
        if (view.width > 0) layout.x = layout.x.coerceIn(0, maxX)
        if (view.height > 0) layout.y = layout.y.coerceIn(0, maxY)
    }

    private fun cardBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(0xF0062E2C.toInt())
        cornerRadius = 18f * density
    }

    private fun text(value: String, sizeSp: Float, bold: Boolean): TextView =
        TextView(context).apply {
            text = value
            setTextColor(0xFFFFFFFF.toInt())
            textSize = sizeSp
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun line(): TextView = text("", 13f, bold = false).apply {
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun chip(label: String): TextView = text(label, 13f, bold = true).apply {
        background = GradientDrawable().apply {
            setColor(0xFF0E6B66.toInt())
            cornerRadius = 12f * density
        }
        setPadding(dp(10), dp(6), dp(10), dp(6))
        isClickable = true
    }

    private fun TextView.paddedTop(): TextView = apply {
        val top = dp(6)
        setPadding(paddingLeft, paddingTop + top, paddingRight, paddingBottom)
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private companion object {
        const val TAG = "CallibriNFB"
    }
}
