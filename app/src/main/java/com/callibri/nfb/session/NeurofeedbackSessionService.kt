package com.callibri.nfb.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.callibri.nfb.CallibriNfbApp
import com.callibri.nfb.MainActivity
import com.callibri.nfb.callibri.ElectrodeContact
import com.callibri.nfb.callibri.SessionPhase
import com.callibri.nfb.ui.MainUiState
import com.callibri.nfb.ui.NeurofeedbackSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.FlowPreview
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Keeps the already-running neurofeedback session in the foreground.
 * It does not own a Callibri connection. Start comes from the visible app.
 */
class NeurofeedbackSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null
    private var timeoutJob: Job? = null
    private var overlay: FeedbackOverlay? = null
    private var retired = false
    private var generation = 0
    private var lastNotificationKey: String? = null
    private var raisedDimmingGeneration = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = sessionOrNull()
        if (session == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            try {
                startInForeground(session.ui.value)
            } catch (error: Exception) {
                Log.e(TAG, "startForeground before stop failed", error)
            }
            session.stopEeg()
            retire(session, startId, generation)
            return START_NOT_STICKY
        }
        try {
            startInForeground(session.ui.value)
        } catch (error: Exception) {
            Log.e(TAG, "startForeground failed", error)
            session.markServiceRunning(false)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        session.markServiceRunning(true)
        beginWatching(session, startId)
        return START_NOT_STICKY
    }

    @OptIn(FlowPreview::class)
    private fun beginWatching(session: NeurofeedbackSession, startId: Int) {
        generation += 1
        val mine = generation
        retired = false
        collectJob?.cancel()
        timeoutJob?.cancel()
        collectJob = scope.launch {
            var watch = ServiceWatch(keepService = true, sawStreaming = false)
            var baselineMessage: String? = null
            var baselineSet = false
            session.ui.sample(VISUAL_PERIOD_MS.milliseconds).collect { state ->
                if (mine != generation) return@collect
                watch = watch.copy(keepService = session.lifetime.commanded.value)
                render(session, state)
                val freshFailure = SessionPolicy.eegStartFailed(
                    phase = state.phase,
                    streaming = state.streaming,
                    message = state.linkMessage,
                    messageIsError = state.linkMessageIsError,
                ) && baselineSet && state.linkMessage != baselineMessage
                val (next, retireNow) = SessionPolicy.onServiceTick(
                    watch = watch,
                    connected = state.phase == SessionPhase.Connected,
                    streaming = state.streaming,
                    startFailed = freshFailure,
                )
                watch = next
                if (!baselineSet) {
                    baselineMessage = state.linkMessage
                    baselineSet = true
                }
                if (state.streaming) timeoutJob?.cancel()
                if (retireNow && mine == generation) {
                    if (session.lifetime.commanded.value) session.lifetime.disarm()
                    retire(session, startId, mine)
                }
            }
        }
        timeoutJob = scope.launch {
            delay(START_TIMEOUT_MS)
            if (mine != generation || retired || session.ui.value.streaming) return@launch
            Log.i(TAG, "EEG did not start; stopping the foreground service")
            session.stopEeg()
            retire(session, startId, mine)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the activity away does not end the live session.
    }

    override fun onDestroy() {
        val unexpected = !retired
        collectJob?.cancel()
        timeoutJob?.cancel()
        overlay?.hide()
        overlay = null
        val session = sessionOrNull()
        session?.markServiceRunning(false)
        session?.noteOverlayPaint(visible = false, reward = null)
        if (unexpected) session?.stopEeg()
        scope.cancel()
        super.onDestroy()
    }

    private fun render(session: NeurofeedbackSession, state: MainUiState) {
        val copy = SessionPolicy.notificationCopy(
            connected = state.phase == SessionPhase.Connected,
            streaming = state.streaming,
            smoothedReward = state.rewardSmoothed,
        )
        val key = copy.text + "|" + copy.detail
        if (key != lastNotificationKey) {
            lastNotificationKey = key
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, buildNotification(copy))
        }
        val show = state.overlayWanted &&
            state.overlayPermissionGranted &&
            state.streaming &&
            state.phase == SessionPhase.Connected
        if (!show) {
            overlay?.hide()
            session.noteOverlayPaint(visible = false, reward = null)
            return
        }
        val model = SessionPolicy.overlayModel(
            connected = true,
            streaming = true,
            smoothedReward = state.rewardSmoothed,
            volumePercent = state.feedbackVolumePercent,
            elapsedMillis = state.elapsedMillis,
            electrode = electrodeLabel(state.electrode),
            manualOverride = state.manualFeedback,
            mediaOn = state.feedbackDestination == com.callibri.nfb.feedback.FeedbackDestination.ExternalMedia &&
                state.audioFeedbackEnabled,
            mediaLevelPercent = state.mediaLevelPercent,
            audioOn = state.audioFeedbackEnabled,
            visualOn = state.visualFeedbackEnabled,
            visualDimPercent = if (state.visualOverlayActive) {
                (state.visualRequestedAlpha * 100.0).roundToInt()
            } else {
                0
            },
        )
        val panel = overlay ?: FeedbackOverlay(
            context = applicationContext,
            onStop = { session.stopEeg() },
            onOpenApp = { openApp() },
            onToggleMedia = { session.toggleExternalMedia() },
            onToggleAudio = { session.setAudioFeedbackEnabled(!session.ui.value.audioFeedbackEnabled) },
            onToggleVisual = { session.setVisualFeedbackEnabled(!session.ui.value.visualFeedbackEnabled) },
        ).also { overlay = it }
        val shown = panel.render(model)
        if (shown && session.dimmingAttached()) {
            val generation = session.dimmingGeneration()
            if (generation != raisedDimmingGeneration) {
                panel.bringToFront()
                raisedDimmingGeneration = generation
            }
        }
        if (!shown) session.noteOverlayPermissionDenied()
        session.noteOverlayPaint(
            visible = shown && panel.isAttached(),
            reward = if (shown) model.displayedReward else null,
        )
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
        )
        startActivity(intent)
    }

    private fun retire(session: NeurofeedbackSession, startId: Int, mine: Int) {
        if (mine != generation || retired) return
        retired = true
        collectJob?.cancel()
        timeoutJob?.cancel()
        overlay?.hide()
        overlay = null
        session.markServiceRunning(false)
        session.noteOverlayPaint(visible = false, reward = null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun startInForeground(state: MainUiState) {
        ensureChannel()
        val copy = SessionPolicy.notificationCopy(
            connected = state.phase == SessionPhase.Connected,
            streaming = state.streaming,
            smoothedReward = state.rewardSmoothed,
        )
        val notification = buildNotification(copy)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(copy: NotificationCopy): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, NeurofeedbackSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.callibri.nfb.R.drawable.ic_stat_nfb)
            .setContentTitle(copy.title)
            .setContentText("${copy.text} · ${copy.detail}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(copy.text + "\n" + copy.detail))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(NotificationCompat.Action(0, "Stop", stop))
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Neurofeedback session",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows while a Callibri neurofeedback session is running."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun sessionOrNull(): NeurofeedbackSession? {
        val host = application as? CallibriNfbApp
        if (host == null) {
            Log.e(TAG, "application is not CallibriNfbApp")
            return null
        }
        return host.session()
    }

    companion object {
        private const val TAG = "CallibriNFB"
        private const val CHANNEL_ID = "nfb-session"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_STOP = "com.callibri.nfb.action.STOP_SESSION"
        private const val VISUAL_PERIOD_MS = 100L
        private const val START_TIMEOUT_MS = 15_000L

        fun start(context: Context) {
            val intent = Intent(context, NeurofeedbackSessionService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

private fun electrodeLabel(electrode: ElectrodeContact?): String? = when (electrode) {
    ElectrodeContact.Normal -> "Contact good"
    ElectrodeContact.HighResistance -> "High resistance"
    ElectrodeContact.Detached -> "Detached"
    null -> null
}
