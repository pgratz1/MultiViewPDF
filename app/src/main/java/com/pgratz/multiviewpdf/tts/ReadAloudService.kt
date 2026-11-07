package com.pgratz.multiviewpdf.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import com.pgratz.multiviewpdf.MainActivity
import com.pgratz.multiviewpdf.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the reading controls need from the ViewModel. Registered in [ReadAloudService.controller]. */
interface ReadAloudController {
    /** Null when idle; the service stops itself then. */
    val playback: StateFlow<ReadAloudService.Playback?>
    fun pauseReading()
    fun resumeReading()
    fun stopReading()
    fun skipSentence(dir: Int)
}

/**
 * Keeps the process alive (unfrozen) while reading with the screen off, and exposes the
 * reader as a media session so the notification shade, lock screen and headset buttons
 * control it. Started by the ViewModel when reading begins; stops itself when reading ends.
 * The ViewModel keeps doing the actual work; this class only mirrors its state.
 */
class ReadAloudService : Service() {
    /** The slice of UI state the notification shows. */
    data class Playback(val title: String, val page: Int, val index: Int, val count: Int, val label: String?, val playing: Boolean)

    private lateinit var session: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "Read aloud", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Controls for reading a document aloud"
                setShowBadge(false)
            }
        )
        session = MediaSession(this, "MultiViewPDF").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { ReadAloudService.controller?.resumeReading() }
                override fun onPause() { ReadAloudService.controller?.pauseReading() }
                override fun onStop() { ReadAloudService.controller?.stopReading() }
                override fun onSkipToNext() { ReadAloudService.controller?.skipSentence(+1) }
                override fun onSkipToPrevious() { ReadAloudService.controller?.skipSentence(-1) }
            })
            setSessionActivity(contentIntent())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = controller
        val p = c?.playback?.value
        if (c == null || p == null) {
            // Reading ended before we came up (or the process was recreated without a ViewModel).
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PLAY -> c.resumeReading()
            ACTION_PAUSE -> c.pauseReading()
            ACTION_STOP -> c.stopReading()
            ACTION_NEXT -> c.skipSentence(+1)
            ACTION_PREV -> c.skipSentence(-1)
        }
        if (!started) {
            started = true
            session.isActive = true
            publish(p)
            startForeground(NOTIFICATION_ID, notification(p), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            scope.launch {
                c.playback.map { it }.distinctUntilChanged().collect { pb ->
                    if (pb == null) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        publish(pb)
                        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(pb))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private fun publish(p: Playback) {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, p.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle(p))
                .build()
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(
                    if (p.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (p.playing) 1f else 0f,
                )
                .build()
        )
    }

    private fun subtitle(p: Playback): String =
        "Page ${p.page + 1}" + (p.label?.let { " · $it" } ?: if (p.count > 0) " · ${p.index + 1} / ${p.count}" else "")

    private fun notification(p: Playback): Notification {
        val playPause = if (p.playing) action(R.drawable.ic_media_pause, "Pause", ACTION_PAUSE)
        else action(R.drawable.ic_media_play, "Play", ACTION_PLAY)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_read_aloud)
            .setContentTitle(p.title)
            .setContentText(subtitle(p))
            .setContentIntent(contentIntent())
            .setOngoing(p.playing)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(R.drawable.ic_media_prev, "Previous sentence", ACTION_PREV))
            .addAction(playPause)
            .addAction(action(R.drawable.ic_media_next, "Next sentence", ACTION_NEXT))
            .addAction(action(R.drawable.ic_media_stop, "Stop", ACTION_STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun action(icon: Int, title: String, act: String): Notification.Action =
        Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon), title,
            PendingIntent.getService(
                this, act.hashCode(), Intent(this, ReadAloudService::class.java).setAction(act),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val CHANNEL = "read_aloud"
        private const val NOTIFICATION_ID = 1
        const val ACTION_PLAY = "com.pgratz.multiviewpdf.PLAY"
        const val ACTION_PAUSE = "com.pgratz.multiviewpdf.PAUSE"
        const val ACTION_STOP = "com.pgratz.multiviewpdf.STOP"
        const val ACTION_NEXT = "com.pgratz.multiviewpdf.NEXT"
        const val ACTION_PREV = "com.pgratz.multiviewpdf.PREV"

        /** The live ViewModel, set while it exists. Process-wide because the service has no other way to reach it. */
        @Volatile var controller: ReadAloudController? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ReadAloudService::class.java))
        }
    }
}
