package com.dpro.lecturescribe

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * Foreground service that records the lecture for as long as needed (screen
 * off, app in background) and transcribes it on the device.
 *
 * Two threads:
 *  - capture: microphone -> AAC file + Silero VAD -> speech segments
 *  - recognize: speech segments -> Parakeet -> transcript.txt + live UI
 * The capture thread never waits for recognition, so no audio is dropped even
 * if recognition briefly falls behind; it just catches up afterwards.
 */
class TranscriptionService : Service() {

    private class Job(val startSample: Long, val samples: FloatArray)

    private val queue = LinkedBlockingQueue<Job>()
    private val endMarker = Job(-1, FloatArray(0))

    @Volatile private var stopRequested = false
    @Volatile private var running = false
    private var sessionId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var startedAtWall = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent.getBooleanExtra(EXTRA_SAVE_AUDIO, true))
            ACTION_STOP -> if (running) stopRequested = true else stopSelf()
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(saveAudio: Boolean) {
        if (running) return
        running = true
        stopRequested = false
        createChannel()
        startedAtWall = System.currentTimeMillis()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("Recording lecture"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LectureScribe:recording")
            .apply { acquire(12 * 60 * 60 * 1000L) }

        val id = SessionStore.create(this, startedAtWall)
        sessionId = id
        LiveSession.begin(id)
        queue.clear()
        Thread({ captureLoop(id, saveAudio) }, "capture").apply { priority = Thread.MAX_PRIORITY }.start()
        Thread({ recognizeLoop(id) }, "recognize").start()
    }

    // ---------------------------------------------------------------- capture

    @SuppressLint("MissingPermission") // checked by MainActivity before starting
    private fun openMic(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            Segmenter.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, Segmenter.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf, Segmenter.SAMPLE_RATE * 2 * 4), // 4 s of headroom
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Microphone unavailable")
        }
        rec.startRecording()
        return rec
    }

    private fun captureLoop(id: String, saveAudio: Boolean) {
        var mic: AudioRecord? = null
        var aac: AacWriter? = null
        var detector: SpeechDetector? = null
        try {
            mic = openMic()
            val vad = SpeechDetector(this).also { detector = it }
            if (saveAudio) {
                aac = runCatching { AacWriter(SessionStore.audioFile(this, id)) }
                    .onFailure { Log.w(TAG, "audio saving disabled", it) }
                    .getOrNull()
            }
            val segmenter = Segmenter()
            val chunk = ShortArray(Segmenter.SAMPLE_RATE / 10) // 100 ms
            val window = FloatArray(Segmenter.WINDOW)
            var filled = 0
            var total = 0L
            var silentSince = 0L
            var lastMetaSave = SystemClock.elapsedRealtime()
            var micFailures = 0

            while (!stopRequested) {
                val current = mic!!
                val n = current.read(chunk, 0, chunk.size)
                if (n <= 0) {
                    // Audio server hiccup: reopen the mic rather than ending the lecture.
                    Log.w(TAG, "mic read returned $n")
                    if (++micFailures > 20) throw IllegalStateException("Microphone stopped working")
                    runCatching { current.stop() }
                    current.release()
                    mic = null
                    SystemClock.sleep(300)
                    mic = openMic()
                    continue
                }
                micFailures = 0
                aac?.let { writer ->
                    try {
                        writer.write(chunk, n)
                    } catch (e: Exception) {
                        Log.w(TAG, "audio saving stopped", e)
                        runCatching { writer.close() }
                        aac = null
                    }
                }

                var peak = 0
                for (k in 0 until n) {
                    val s = chunk[k].toInt()
                    if (abs(s) > peak) peak = abs(s)
                    window[filled++] = s / 32768f
                    if (filled == Segmenter.WINDOW) {
                        filled = 0
                        val w = window.copyOf()
                        for (seg in segmenter.accept(w, vad.probability(w))) {
                            queue.put(Job(seg.startSample, seg.samples))
                        }
                    }
                }
                total += n

                val now = SystemClock.elapsedRealtime()
                // Silence for 10 s straight usually means the mic is blocked
                // (privacy toggle, another app, or a call).
                if (peak > 0) silentSince = 0 else if (silentSince == 0L) silentSince = now
                val warning = if (silentSince != 0L && now - silentSince > 10_000) {
                    "The microphone is giving no sound. Check the mic access toggle in quick settings."
                } else null
                LiveSession.setLevel(levelOf(peak))
                LiveSession.update {
                    it.copy(
                        elapsedMs = total * 1000 / Segmenter.SAMPLE_RATE,
                        speaking = segmenter.inSpeech,
                        backlog = queue.size,
                        warning = warning,
                    )
                }
                if (now - lastMetaSave > 30_000) {
                    lastMetaSave = now
                    SessionStore.update(this, id, durationMs = total * 1000 / Segmenter.SAMPLE_RATE)
                }
            }
            for (seg in segmenter.flush()) queue.put(Job(seg.startSample, seg.samples))
            SessionStore.update(this, id, durationMs = total * 1000 / Segmenter.SAMPLE_RATE)
        } catch (e: Throwable) {
            Log.e(TAG, "capture failed", e)
            LiveSession.update { it.copy(error = "Recording stopped: ${e.message}") }
        } finally {
            runCatching { mic?.stop() }
            runCatching { mic?.release() }
            runCatching { aac?.close() }
            runCatching { detector?.close() }
            LiveSession.setLevel(0f)
            LiveSession.update { it.copy(phase = Phase.Finishing, speaking = false) }
            updateNotification("Finishing transcription…")
            queue.put(endMarker)
        }
    }

    private fun levelOf(peak: Int): Float {
        if (peak <= 0) return 0f
        val db = 20 * log10(peak / 32768f) // -90..0
        return ((db + 60f) / 60f).coerceIn(0f, 1f)
    }

    // -------------------------------------------------------------- recognize

    private fun recognizeLoop(id: String) {
        var engine: SpeechEngine? = null
        try {
            engine = SpeechEngine(this)
            LiveSession.update { if (it.phase == Phase.LoadingModel) it.copy(phase = Phase.Recording) else it }
            while (true) {
                val job = queue.take()
                if (job === endMarker) break
                val text = try {
                    engine.transcribe(job.samples)
                } catch (e: Exception) {
                    Log.e(TAG, "recognition failed for a segment", e)
                    ""
                }
                if (text.isNotEmpty()) {
                    val line = Line(job.startSample * 1000 / Segmenter.SAMPLE_RATE, text)
                    SessionStore.appendLine(this, id, line)
                    LiveSession.addLine(line)
                }
                LiveSession.update { it.copy(backlog = queue.size) }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "recognizer failed", e)
            LiveSession.update {
                it.copy(
                    phase = if (it.phase == Phase.LoadingModel) Phase.Recording else it.phase,
                    error = "Speech model failed to load: ${e.message}. Audio is still being saved.",
                )
            }
            // Keep draining so the capture thread can finish and the audio is kept.
            while (queue.take() !== endMarker) Unit
        } finally {
            runCatching { engine?.close() }
            finish(id)
        }
    }

    private fun finish(id: String) {
        SessionStore.update(this, id, status = SessionStore.DONE)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        LiveSession.end() // last, so the UI only offers "Start" once the service is gone
    }

    override fun onDestroy() {
        stopRequested = true
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ----------------------------------------------------------- notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a lecture is being recorded"
            },
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TranscriptionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Lecture Scribe")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setWhen(startedAtWall)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    companion object {
        private const val TAG = "LectureScribe"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_SAVE_AUDIO = "save_audio"

        fun start(context: Context, saveAudio: Boolean) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TranscriptionService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_SAVE_AUDIO, saveAudio),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TranscriptionService::class.java).setAction(ACTION_STOP))
        }
    }
}
