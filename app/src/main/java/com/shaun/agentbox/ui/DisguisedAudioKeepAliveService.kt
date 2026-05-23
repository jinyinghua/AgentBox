package com.shaun.agentbox.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground keep-alive helper that masquerades as a tiny audio playback service.
 *
 * It writes silent PCM frames through AudioTrack in streaming mode. This gives the
 * option real runtime behaviour instead of being a no-op toggle, while avoiding
 * audible output for the user.
 */
class DisguisedAudioKeepAliveService : Service() {
    companion object {
        var isRunning = false
            private set

        private const val CHANNEL_ID = "agentbox_audio_keep_alive"
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var audioTrack: AudioTrack? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AgentBox Audio Session")
            .setContentText("Background audio session is keeping AgentBox available")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(3, notification)
        startSilentPlaybackLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startSilentPlaybackLoop() {
        scope.launch {
            val sampleRate = 8_000
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(sampleRate / 2)

            val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuffer)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    android.media.AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer,
                    AudioTrack.MODE_STREAM
                )
            }

            audioTrack = track
            val silence = ByteArray(minBuffer) { 0 }
            try {
                track.play()
                while (isActive) {
                    track.write(silence, 0, silence.size)
                    delay(250)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    stopAudioTrack()
                }
            }
        }
    }

    private fun stopAudioTrack() {
        audioTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        audioTrack = null
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scope.coroutineContext[Job]?.cancel()
        stopAudioTrack()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "AgentBox Audio Keep Alive", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

}
