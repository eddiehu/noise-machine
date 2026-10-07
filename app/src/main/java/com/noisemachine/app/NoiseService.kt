package com.noisemachine.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import java.util.concurrent.atomic.AtomicBoolean

class NoiseService : Service() {

    companion object {
        const val ACTION_TOGGLE = "com.noisemachine.app.TOGGLE"
        const val ACTION_PLAY_PAUSE = "com.noisemachine.app.PLAY_PAUSE"
        const val ACTION_STOP_ALL = "com.noisemachine.app.STOP_ALL"
        const val ACTION_QUERY_STATE = "com.noisemachine.app.QUERY_STATE"
        const val ACTION_SET_TIMER = "com.noisemachine.app.SET_TIMER"
        const val ACTION_STATE = "com.noisemachine.app.STATE"
        const val EXTRA_TYPE = "type"
        const val EXTRA_ACTIVE = "active"
        const val EXTRA_TIMER_MINUTES = "timer_minutes"
        const val EXTRA_TIMER_END = "timer_end"
        const val CHANNEL_ID = "noise_playback"
        const val NOTIF_ID = 1
        /** Fade-out duration when the sleep timer expires. */
        const val FADE_MS = 30_000L
    }

    private data class Voice(
        val track: AudioTrack,
        val running: AtomicBoolean,
        val thread: Thread
    )

    // Single-sound model: at most one voice plays at a time.
    private var voice: Voice? = null
    private var currentType: NoiseType? = null
    private var lastType: NoiseType? = null
    private val buffers = mutableMapOf<NoiseType, ShortArray>()

    // Sleep timer: armed duration (minutes, 0 = none) + countdown end
    // (null = armed but not counting; counting only runs while a sound plays).
    private var armedTimerMinutes = 0
    private var timerEndMillis: Long? = null
    private var fadeFactor = 1f

    private lateinit var mediaSession: MediaSessionCompat
    private var lastNotifSignature: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            tickTimer()
            if (timerEndMillis != null) handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "NoiseService").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resumeLast()
                override fun onPause() = pausePlayback()
                override fun onStop() = stopAll()
            })
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> {
                val type = runCatching {
                    NoiseType.valueOf(intent.getStringExtra(EXTRA_TYPE) ?: "")
                }.getOrNull() ?: return START_STICKY
                if (currentType == type) pausePlayback() else playType(type)
            }
            ACTION_PLAY_PAUSE -> {
                if (voice != null) pausePlayback() else resumeLast()
            }
            ACTION_STOP_ALL -> stopAll()
            ACTION_QUERY_STATE -> broadcastState()
            ACTION_SET_TIMER -> setTimer(intent.getIntExtra(EXTRA_TIMER_MINUTES, -1))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVoiceNow()
        handler.removeCallbacks(timerTick)
        runCatching { mediaSession.release() }
        super.onDestroy()
    }

    // ---- playback ----

    /**
     * Start (or swap to) a sound. A swap keeps a running sleep-timer
     * countdown going untouched.
     */
    private fun playType(type: NoiseType) {
        if (currentType == type) return
        stopVoiceNow()
        val pcm = buffers.getOrPut(type) { NoiseGenerator.generate(type) }
        val minBuf = AudioTrack.getMinBufferSize(
            NoiseGenerator.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(NoiseGenerator.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf * 4, pcm.size * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.setVolume(fadeFactor)
        val running = AtomicBoolean(true)
        val thread = Thread({
            track.play()
            var ioError = false
            while (running.get() && !ioError) {
                var offset = 0
                while (offset < pcm.size && running.get()) {
                    val written = track.write(pcm, offset, pcm.size - offset)
                    if (written < 0) {
                        ioError = true
                        break
                    }
                    offset += written
                }
            }
        }, "noise-${type.name}").apply {
            isDaemon = true
            start()
        }
        voice = Voice(track, running, thread)
        currentType = type
        lastType = type
        // A started sound kicks off an armed timer's countdown.
        if (armedTimerMinutes > 0 && timerEndMillis == null) startCountdown()
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "${type.displayName} noise")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Noise Machine")
                .build()
        )
        mediaSession.isActive = true
        setPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        startForegroundInternal()
        broadcastState()
    }

    private fun resumeLast() {
        lastType?.let { playType(it) }
    }

    /**
     * User-initiated stop/pause: stops audio immediately and resets a
     * running timer countdown to its full (armed) duration.
     */
    private fun pausePlayback() {
        stopVoiceNow()
        timerEndMillis = null
        handler.removeCallbacks(timerTick)
        fadeFactor = 1f
        setPlaybackState(PlaybackStateCompat.STATE_PAUSED)
        // Leave a dismissible notification so playback can resume.
        stopForeground(STOP_FOREGROUND_DETACH)
        updateNotification(force = true)
        broadcastState()
    }

    /**
     * Immediate stop: halt the track first (unblocks the writer thread),
     * then join briefly and release.
     */
    private fun stopVoiceNow() {
        val v = voice ?: return
        voice = null
        currentType = null
        v.running.set(false)
        runCatching {
            v.track.pause()
            v.track.flush()
            v.track.stop()
        }
        v.thread.join(400)
        v.track.release()
    }

    private fun stopAll() {
        stopVoiceNow()
        handler.removeCallbacks(timerTick)
        timerEndMillis = null
        armedTimerMinutes = 0
        fadeFactor = 1f
        lastType = null
        setPlaybackState(PlaybackStateCompat.STATE_STOPPED)
        mediaSession.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        broadcastState()
        stopSelf()
    }

    // ---- sleep timer ----

    /**
     * Arm (or disarm) the timer. The countdown only runs while a sound is
     * playing: tapping a pill while idle just arms it.
     */
    private fun setTimer(minutes: Int) {
        handler.removeCallbacks(timerTick)
        timerEndMillis = null
        fadeFactor = 1f
        voice?.track?.setVolume(1f)
        armedTimerMinutes = maxOf(0, minutes)
        if (armedTimerMinutes > 0 && voice != null) startCountdown()
        updateNotification(force = true)
        broadcastState()
    }

    private fun startCountdown() {
        timerEndMillis = System.currentTimeMillis() + armedTimerMinutes * 60_000L
        handler.post(timerTick)
    }

    private fun tickTimer() {
        val end = timerEndMillis ?: return
        val remaining = end - System.currentTimeMillis()
        if (remaining <= 0) {
            timerEndMillis = null
            stopAll()
            return
        }
        // Gentle fade over the last FADE_MS before stopping.
        fadeFactor = if (remaining < FADE_MS) {
            (remaining / FADE_MS.toFloat()).coerceIn(0f, 1f)
        } else {
            1f
        }
        voice?.track?.setVolume(fadeFactor)
        updateNotification()
    }

    private fun formatDuration(ms: Long): String {
        val s = (ms / 1000).toInt().coerceAtLeast(0)
        val m = s / 60
        val h = m / 60
        fun p(n: Int) = if (n < 10) "0$n" else "$n"
        return if (h > 0) "$h:${p(m % 60)}:${p(s % 60)}" else "$m:${p(s % 60)}"
    }

    private fun broadcastState() {
        Intent(ACTION_STATE)
            .putStringArrayListExtra(EXTRA_ACTIVE, ArrayList(listOfNotNull(currentType?.name)))
            .putExtra(EXTRA_TIMER_MINUTES, armedTimerMinutes)
            .putExtra(EXTRA_TIMER_END, timerEndMillis ?: 0L)
            .setPackage(packageName)
            .let { sendBroadcast(it) }
    }

    // ---- media notification ----

    private fun setPlaybackState(state: Int) {
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, 0L, 1f)
                .build()
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Noise playback",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }

    private fun timerText(): String? {
        val end = timerEndMillis ?: return null
        val left = end - System.currentTimeMillis()
        return if (left > 0) "${formatDuration(left)} left" else null
    }

    private fun buildNotification(): Notification {
        val playing = voice != null
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggleIntent = PendingIntent.getService(
            this, 2,
            Intent(this, NoiseService::class.java).setAction(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, NoiseService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val style = MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(
                if (playing) "${currentType?.displayName} noise" else "Noise Machine"
            )
            .setContentText(timerText())
            .setSmallIcon(R.drawable.ic_wave)
            .setContentIntent(openIntent)
            .setColor((currentType ?: lastType)?.notifTint ?: 0xFF14243D.toInt())
            .setColorized(true)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                toggleIntent
            )
            .addAction(R.drawable.ic_wave, "Stop", stopIntent)
            .setStyle(style)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startForegroundInternal() {
        createChannel()
        updateNotification(force = true)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun updateNotification(force: Boolean = false) {
        val sig = "${currentType?.name}:${voice != null}:${timerText()}"
        if (!force && sig == lastNotifSignature) return
        lastNotifSignature = sig
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }
}
