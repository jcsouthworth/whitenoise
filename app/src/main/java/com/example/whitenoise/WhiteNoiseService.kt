package com.example.whitenoise

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import kotlin.math.sin
import kotlin.random.Random

class WhiteNoiseService : Service() {

    enum class NoiseType { WHITE, PINK, BROWN, OCEAN, RAIN, FAN, FIRE }

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_VOLUME = "EXTRA_VOLUME"
        const val EXTRA_NOISE_TYPE_1 = "EXTRA_NOISE_TYPE_1"
        const val EXTRA_NOISE_TYPE_2 = "EXTRA_NOISE_TYPE_2"
        private const val CHANNEL_ID = "WhiteNoiseChannel"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 44100
        private const val BUFFER_SIZE_SECONDS = 1

        @Volatile var isRunning = false
            private set
    }

    private inner class NoiseGenerator(val type: NoiseType) {
        // Pink state
        private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0
        private var b3 = 0.0; private var b4 = 0.0; private var b5 = 0.0; private var b6 = 0.0
        // Brown / Ocean / Storm / Fire base
        private var brownOut = 0.0
        // Ocean
        private var sampleIndex = 0L
        // Rain / Storm high-pass
        private var hiPassLP = 0.0
        // Fan
        private var fanLP1 = 0.0; private var fanLP2 = 0.0
        // Fire crackle
        private var fireCrackle = 0.0

        fun nextSample(): Float {
            val white = Random.nextDouble() * 2.0 - 1.0
            return when (type) {
                NoiseType.WHITE -> (white * 0.2).toFloat()

                NoiseType.PINK -> {
                    b0 = 0.99886 * b0 + white * 0.0555179
                    b1 = 0.99332 * b1 + white * 0.0750759
                    b2 = 0.96900 * b2 + white * 0.1538520
                    b3 = 0.86650 * b3 + white * 0.3104856
                    b4 = 0.55000 * b4 + white * 0.5329522
                    b5 = -0.7616 * b5 - white * 0.0168980
                    val pink = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362) / 12.0
                    b6 = white * 0.115926
                    pink.toFloat().coerceIn(-1f, 1f)
                }

                NoiseType.BROWN -> {
                    brownOut = (brownOut + 0.02 * white) / 1.02
                    (brownOut * 3.5).toFloat().coerceIn(-1f, 1f)
                }

                NoiseType.OCEAN -> {
                    brownOut = (brownOut + 0.02 * white) / 1.02
                    val surf = (brownOut * 3.5).coerceIn(-1.0, 1.0)
                    val t = sampleIndex.toDouble() / SAMPLE_RATE
                    val wave1 = (sin(2.0 * Math.PI * t / 9.0) + 1.0) / 2.0
                    val wave2 = (sin(2.0 * Math.PI * t / 13.0) + 1.0) / 2.0
                    val envelope = 0.15 + 0.85 * Math.pow((wave1 * 0.6 + wave2 * 0.4), 1.8)
                    sampleIndex++
                    (surf * envelope).toFloat().coerceIn(-1f, 1f)
                }

                NoiseType.RAIN -> {
                    // Soft high-pass filtered white noise — gentle rain hiss
                    hiPassLP = 0.92 * hiPassLP + 0.08 * white
                    ((white - hiPassLP) * 0.35).toFloat().coerceIn(-1f, 1f)
                }

NoiseType.FAN -> {
                    // Double low-pass — steady mechanical broadband hum
                    fanLP1 = 0.6 * fanLP1 + 0.4 * white
                    fanLP2 = 0.6 * fanLP2 + 0.4 * fanLP1
                    (fanLP2 * 1.2).toFloat().coerceIn(-1f, 1f)
                }

                NoiseType.FIRE -> {
                    // Warm brown base + random crackle pops
                    brownOut = (brownOut + 0.02 * white) / 1.02
                    val warmth = brownOut * 3.5
                    if (Random.nextDouble() < 0.001) fireCrackle = Random.nextDouble() * 0.6 + 0.15
                    fireCrackle *= 0.984
                    (warmth + fireCrackle * white).toFloat().coerceIn(-1f, 1f)
                }
            }
        }
    }

    private var audioTrack: AudioTrack? = null
    private var playThread: Thread? = null
    @Volatile private var playing = false
    private var volume = 1.0f
    private var noiseType1 = NoiseType.WHITE
    private var noiseType2: NoiseType? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                volume = intent.getFloatExtra(EXTRA_VOLUME, 1.0f)
                noiseType1 = NoiseType.valueOf(
                    intent.getStringExtra(EXTRA_NOISE_TYPE_1) ?: NoiseType.WHITE.name
                )
                val type2str = intent.getStringExtra(EXTRA_NOISE_TYPE_2)
                noiseType2 = if (type2str.isNullOrEmpty() || type2str == "NONE") null
                             else NoiseType.valueOf(type2str)
                if (playing) stopPlayback()
                startForeground(NOTIFICATION_ID, buildNotification())
                startPlayback()
            }
            ACTION_STOP -> {
                stopPlayback()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startPlayback() {
        playing = true
        isRunning = true

        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE * BUFFER_SIZE_SECONDS * 4)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.setVolume(volume)
        audioTrack?.play()

        val chunkSamples = SAMPLE_RATE / 4
        val buffer = FloatArray(chunkSamples)

        playThread = Thread {
            val gen1 = NoiseGenerator(noiseType1)
            val gen2 = noiseType2?.let { NoiseGenerator(it) }

            while (playing) {
                for (i in buffer.indices) {
                    val s1 = gen1.nextSample()
                    val s2 = gen2?.nextSample()
                    buffer[i] = if (s2 != null) (s1 + s2) / 2f else s1
                }
                audioTrack?.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
            }
        }.also { it.start() }
    }

    private fun stopPlayback() {
        playing = false
        isRunning = false
        playThread?.join(500)
        playThread = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    private fun labelFor(type: NoiseType) = when (type) {
        NoiseType.WHITE -> getString(R.string.noise_white)
        NoiseType.PINK  -> getString(R.string.noise_pink)
        NoiseType.BROWN -> getString(R.string.noise_brown)
        NoiseType.OCEAN -> getString(R.string.noise_ocean)
        NoiseType.RAIN  -> getString(R.string.noise_rain)
        NoiseType.FAN   -> getString(R.string.noise_fan)
        NoiseType.FIRE  -> getString(R.string.noise_fire)
    }

    private fun buildNotification(): Notification {
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE else 0

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, WhiteNoiseService::class.java).apply { action = ACTION_STOP },
            pendingFlags
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            pendingFlags
        )

        val label = if (noiseType2 != null)
            "${labelFor(noiseType1)} + ${labelFor(noiseType2!!)}"
        else
            labelFor(noiseType1)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("$label ${getString(R.string.notification_text)}")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_pause,
                    getString(R.string.stop),
                    stopIntent
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }
}
