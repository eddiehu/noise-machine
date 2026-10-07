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
import java.util.concurrent.atomic.AtomicBoolean

class NoiseService : Service() {

    companion object {
        const val ACTION_TOGGLE = "com.noisemachine.app.TOGGLE"
        const val ACTION_SET_VOLUME = "com.noisemachine.app.SET_VOLUME"
        const val ACTION_STOP_ALL = "com.noisemachine.app.STOP_ALL"
        const val ACTION_QUERY_STATE = "com.noisemachine.app.QUERY_STATE"
        const val ACTION_SET_TIMER = "com.noisemachine.app.SET_TIMER"
        const val ACTION_STATE = "com.noisemachine.app.STATE"
        const val EXTRA_TYPE = "type"
        const val EXTRA_VOLUME = "volume"
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

    private val voices = mutableMapOf<NoiseType, Voice>()
    private val buffers = mutableMapOf<NoiseType, ShortArray>()
    private var volume = 0.7f

    private var timerEndMillis: Long? = null
    private var lastNotifText: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            tickTimer()
            if (timerEndMillis != null) handler.postDelayed(this, 5_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> {
                val type = runCatching {
                    NoiseType.valueOf(intent.getStringExtra(EXTRA_TYPE) ?: "")
                }.getOrNull()
                if (type != null) {
                    if (voices.containsKey(type)) stopType(type) else startType(type)
                }
            }
            ACTION_SET_VOLUME -> {
                volume = intent.getFloatExtra(EXTRA_VOLUME, volume)
                voices.values.forEach { it.track.setVolume(volume) }
            }
            ACTION_STOP_ALL -> stopAll()
            ACTION_QUERY_STATE -> broadcastState()
            ACTION_SET_TIMER -> setTimer(intent.getIntExtra(EXTRA_TIMER_MINUTES, -1))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        voices.keys.toList().forEach { stopType(it) }
        super.onDestroy()
    }

    private fun startType(type: NoiseType) {
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
        track.setVolume(volume)
        val running = AtomicBoolean(true)
        val thread = Thread({
            track.play()
            while (running.get()) {
                var offset = 0
                while (offset < pcm.size && running.get()) {
                    val written = track.write(pcm, offset, pcm.size - offset)
                    if (written < 0) break
                    offset += written
                }
            }
        }, "noise-${type.name}").apply {
            isDaemon = true
            start()
        }
        voices[type] = Voice(track, running, thread)
        startForegroundInternal()
        broadcastState()
    }

    private fun stopType(type: NoiseType) {
        voices.remove(type)?.let { (track, running, thread) ->
            running.set(false)
            thread.join(2000)
            runCatching { track.stop() }
            track.release()
        }
        if (voices.isEmpty()) {
            timerEndMillis = null
            handler.removeCallbacks(timerTick)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            updateNotification()
        }
        broadcastState()
    }

    private fun stopAll() {
        voices.keys.toList().forEach { stopType(it) }
    }

    private fun setTimer(minutes: Int) {
        handler.removeCallbacks(timerTick)
        timerEndMillis =
            if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else null
        // Restore full volume in case a previous fade was in progress.
        voices.values.forEach { it.track.setVolume(volume) }
        if (timerEndMillis != null) handler.post(timerTick)
        updateNotification(force = true)
        broadcastState()
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
        val factor = if (remaining < FADE_MS) {
            (remaining / FADE_MS.toFloat()).coerceIn(0f, 1f)
        } else {
            1f
        }
        voices.values.forEach { it.track.setVolume(volume * factor) }
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
        val intent = Intent(ACTION_STATE)
            .putStringArrayListExtra(EXTRA_ACTIVE, ArrayList(voices.keys.map { it.name }))
            .putExtra(EXTRA_TIMER_END, timerEndMillis ?: 0L)
            .setPackage(packageName)
        sendBroadcast(intent)
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

    private fun notificationText(): String {
        val base = if (voices.isEmpty()) {
            "Ready"
        } else {
            "Playing: " + voices.keys.sortedBy { it.ordinal }
                .joinToString(" + ") { it.displayName }
        }
        val end = timerEndMillis ?: return base
        val left = end - System.currentTimeMillis()
        return if (left > 0) "$base · ${formatDuration(left)} left" else base
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, NoiseService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Noise Machine")
            .setContentText(notificationText())
            .setSmallIcon(R.drawable.ic_wave)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_wave, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundInternal() {
        createChannel()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(force: Boolean = false) {
        val text = notificationText()
        if (!force && text == lastNotifText) return
        lastNotifText = text
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification())
    }
}
