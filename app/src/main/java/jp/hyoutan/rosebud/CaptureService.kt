package jp.hyoutan.rosebud

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private lateinit var repo: ProbeRepository
    private var projection: MediaProjection? = null
    private var worker: Thread? = null
    private var ownedSession = ""
    private var finished = false
    private lateinit var controller: MotionController
    private var controllerClosed = false
    @Volatile private var stopReason: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var receiverRegistered = false
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { requestStop("projection_revoked_or_stopped_by_system") }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            repo.event("screen_event", mapOf("action" to (intent.action ?: "unknown"), "environment" to repo.environment()))
        }
    }

    override fun onCreate() {
        super.onCreate()
        (application as ProbeApplication).serviceActive = true
        repo = ProbeRepository.get(this)
        controller = MotionController(now = { SystemClock.elapsedRealtime() }, event = repo::controlEvent,
            publish = { (application as ProbeApplication).control = it },
            fatal = { reason -> main.post { requestStop("control_failure: $reason") } },
            closed = { main.post { controllerClosed = true; finish() } })
        val settings = getSharedPreferences("control_settings", MODE_PRIVATE)
        controller.configure(settings.getBoolean("follow", false), settings.getInt("vibrate_cap", 30), settings.getInt("flicker_cap", 30))
        controller.configureRest(settings.getInt("rest_intro",RestDefaults.intro),settings.getInt("rest_middle",RestDefaults.middle),settings.getInt("rest_finish",RestDefaults.finish))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { requestStop(intent.getStringExtra("reason") ?: "notification_stop"); return START_NOT_STICKY }
            ACTION_CONNECT -> {
                foreground(false)
                controller.configure(intent.getBooleanExtra("follow", false), intent.getIntExtra("vibrate_cap", 30), intent.getIntExtra("flicker_cap", 30))
                controller.configureRest(intent.getIntExtra("rest_intro",RestDefaults.intro),intent.getIntExtra("rest_middle",RestDefaults.middle),intent.getIntExtra("rest_finish",RestDefaults.finish))
                repo.controlEvent("environment", mapOf("environment" to repo.environment()))
                controller.connect(intent.getStringExtra("url") ?: "ws://192.168.40.78:12345")
                return START_NOT_STICKY
            }
            ACTION_SUCTION_SETTINGS -> {
                foreground(projection != null)
                controller.configureSuction(intent.getLongExtra("interval_ms",9000),intent.getLongExtra("duration_ms",300),intent.getIntExtra("target",5))
                return START_NOT_STICKY
            }
            ACTION_SUCTION -> {
                foreground(projection != null)
                controller.session(intent.getBooleanExtra("preview",true),intent.getBooleanExtra("classic",false))
                controller.operateSuction(level = if(intent.hasExtra("level")) intent.getIntExtra("level",0) else null,
                    automatic = if(intent.hasExtra("automatic")) intent.getBooleanExtra("automatic",false) else null)
                return START_NOT_STICKY
            }
            ACTION_SCAN -> { controller.scan(); return START_NOT_STICKY }
            ACTION_SELECT -> { controller.select(intent.getIntExtra("device", -1)); return START_NOT_STICKY }
            ACTION_SETTINGS -> {
                controller.configure(intent.getBooleanExtra("follow", false), intent.getIntExtra("vibrate_cap", 30), intent.getIntExtra("flicker_cap", 30))
                controller.configureRest(intent.getIntExtra("rest_intro",RestDefaults.intro),intent.getIntExtra("rest_middle",RestDefaults.middle),intent.getIntExtra("rest_finish",RestDefaults.finish))
                return START_NOT_STICKY
            }
            ACTION_MOTION_START -> {
                foreground(projection != null)
                controller.session(intent.getBooleanExtra("preview", true), intent.getBooleanExtra("classic", false))
                controller.configure(intent.getBooleanExtra("follow", false), intent.getIntExtra("vibrate_cap", 30), intent.getIntExtra("flicker_cap", 30))
                controller.configureRest(intent.getIntExtra("rest_intro",RestDefaults.intro),intent.getIntExtra("rest_middle",RestDefaults.middle),intent.getIntExtra("rest_finish",RestDefaults.finish))
                controller.start(SystemClock.elapsedRealtime()); return START_NOT_STICKY
            }
            ACTION_TRIAL -> {
                foreground(projection != null)
                controller.session(intent.getBooleanExtra("preview",true),false)
                controller.startTrial(intent.getIntExtra("trial_percent",0),intent.getLongExtra("trial_ms",0))
                return START_NOT_STICKY
            }
            ACTION_PATTERN -> { controller.selectPattern(intent.getStringExtra("track") ?: "",intent.getIntExtra("pattern",-2)); return START_NOT_STICKY }
            ACTION_SKIP -> { controller.skipTo(intent.getDoubleExtra("target_seconds",Double.NaN)); return START_NOT_STICKY }
            ACTION_PAUSE -> { controller.pause(); return START_NOT_STICKY }
            ACTION_RESUME -> { controller.resume(); return START_NOT_STICKY }
            ACTION_MOTION_STOP -> { controller.stopMotion("motion_ui_stop"); return START_NOT_STICKY }
            ACTION_START -> if (worker != null || projection != null) return START_NOT_STICKY
            else -> { requestStop("invalid_service_action"); return START_NOT_STICKY }
        }
        ownedSession = repo.snapshot.session
        try {
            @Suppress("DEPRECATION")
            val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("projection", Intent::class.java)
                else intent.getParcelableExtra("projection")
            check(data != null) { "missing_projection_token" }
            check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "record_audio_permission_missing" }
            foreground(true)
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(android.app.Activity.RESULT_OK, data)
            checkNotNull(projection).registerCallback(callback, main)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
            else registerReceiver(screenReceiver, filter)
            receiverRegistered = true
            if (intent.getBooleanExtra("wake_lock", false)) acquireWakeLock()
            repo.event("projection_ready", mapOf("environment" to repo.environment(), "virtual_display" to false))
            repo.update(repo.snapshot.copy(phase = "starting"), save = true)
            running.set(true)
            val token = checkNotNull(projection)
            worker = Thread({ capture(token) }, "playback-probe").also { it.start() }
        } catch (e: Exception) {
            repo.event("start_error", mapOf("error" to "${e.javaClass.simpleName}: ${e.message}"))
            requestStop("start_error: ${e.javaClass.simpleName}: ${e.message}")
        }
        return START_NOT_STICKY // Never reuse a permission token or restart capture after process death.
    }

    @SuppressLint("MissingPermission") // Runtime check immediately before building AudioRecord; no microphone source.
    private fun capture(token: MediaProjection) {
        var recorder: AudioRecord? = null
        val stats = AudioStats()
        val began = SystemClock.elapsedRealtime()
        var lastRead = began
        var maxGap = 0L
        var zeroReads = 0L
        var errors = 0L
        var lastUi = began
        var lastCheckpoint = began
        var lastWakeRenew = began
        var gapReported = false
        var silenceBegan: Long? = null
        var silenceReported = false
        fun publish(now: Long, save: Boolean = false) {
            repo.update(repo.snapshot.copy(
                elapsedMs = now - began, samples = stats.samples, rms = stats.rms, peak = stats.peak,
                totalRms = stats.totalRms(), maxPeak = stats.maxPeak,
                silentMs = stats.durationMs(stats.silentSamples), longestSilenceMs = stats.durationMs(stats.longestSilenceSamples),
                errors = errors, lastReadElapsed = lastRead, maxReadGapMs = maxGap,
                screenOn = getSystemService(PowerManager::class.java).isInteractive,
                wakeLock = wakeLock?.isHeld == true,
            ), save)
        }
        fun checkpoint(now: Long, final: Boolean = false) {
            publish(now, save = true)
            val window = stats.takeWindow()
            repo.event(if (final) "final_audio_window" else "audio_window", mapOf(
                "summary" to repo.snapshot.json(), "window_samples" to window.samples,
                "window_rms_dbfs" to AudioStats.db(window.rms), "window_peak_dbfs" to AudioStats.db(window.peak),
                "captured_audio_ms" to stats.durationMs(), "no_data_age_ms" to (now - lastRead),
                "zero_reads_total" to zeroReads, "environment" to repo.environment(),
            ))
        }
        try {
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(stats.sampleRate).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val config = AudioPlaybackCaptureConfiguration.Builder(token)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val minBuffer = AudioRecord.getMinBufferSize(stats.sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBuffer > 0) { "unsupported_format: minBuffer=$minBuffer" }
            recorder = AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(maxOf(minBuffer * 2, 38400))
                .setAudioPlaybackCaptureConfig(config).build()
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "audio_record_uninitialized" }
            if (!running.get()) return
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "audio_record_not_recording" }
            repo.update(repo.snapshot.copy(phase = "running"), save = true)
            controller.audioStarted()
            repo.event("audio_record_started", mapOf("buffer_frames" to recorder.bufferSizeInFrames))
            val pcm = ShortArray(9600) // At most 100 ms of interleaved stereo; never retained or written.
            while (running.get()) {
                val count = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                val now = SystemClock.elapsedRealtime()
                if (!running.get()) break
                maxGap = maxOf(maxGap, now - lastRead)
                if (count > 0) {
                    if (gapReported) repo.event("read_resumed", mapOf("gap_ms" to (now - lastRead)))
                    gapReported = false
                    lastRead = now
                    val oldSilence = stats.silenceRunSamples
                    stats.add(pcm, count)
                    controller.audio(stats.rms, now)
                    if (stats.silenceRunSamples > 0) {
                        if (silenceBegan == null) silenceBegan = now - stats.durationMs(count.toLong())
                        if (!silenceReported && stats.durationMs(stats.silenceRunSamples) >= 1000) {
                            silenceReported = true
                            repo.event("silence_begin", mapOf("estimated_start_elapsed_ms" to checkNotNull(silenceBegan)))
                        }
                    } else {
                        if (silenceReported) repo.event("silence_end", mapOf(
                            "start_elapsed_ms" to checkNotNull(silenceBegan), "audio_silence_ms" to stats.durationMs(oldSilence),
                        ))
                        silenceBegan = null; silenceReported = false
                    }
                } else if (count < 0) {
                    errors++
                    repo.event("read_error", mapOf("code" to count, "meaning" to readErrorName(count)))
                    requestStop("read_error_${readErrorName(count)}")
                } else {
                    zeroReads++
                    if (now - lastRead >= 2000 && !gapReported) {
                        gapReported = true
                        repo.event("no_samples_for_2s", mapOf("gap_ms" to (now - lastRead)))
                        requestStop("no_samples_for_2s")
                    }
                    Thread.sleep(20)
                }
                if (now - lastUi >= 200) { publish(now); lastUi = now }
                if (now - lastCheckpoint >= 5000) { checkpoint(now); lastCheckpoint = now }
                if (wakeLock != null && now - lastWakeRenew >= 300000) {
                    acquireWakeLock(); lastWakeRenew = now
                }
            }
        } catch (e: Exception) {
            repo.event("capture_error", mapOf("error" to "${e.javaClass.simpleName}: ${e.message}"))
            requestStop("capture_error: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
                .onFailure { repo.event("audio_stop_error", mapOf("error" to it.toString())) }
            runCatching { recorder?.release() }.onFailure { repo.event("audio_release_error", mapOf("error" to it.toString())) }
            if (silenceReported) repo.event("silence_open_at_stop", mapOf(
                "start_elapsed_ms" to checkNotNull(silenceBegan), "audio_silence_ms" to stats.durationMs(stats.silenceRunSamples),
            ))
            checkpoint(SystemClock.elapsedRealtime(), final = true)
            main.post { worker = null; finish() }
        }
    }

    @Synchronized private fun requestStop(reason: String) {
        if (finished) return
        if (stopReason == null) {
            stopReason = reason
            repo.controlEvent("stop_requested", mapOf("reason" to reason))
            if (repo.snapshot.busy && repo.snapshot.session == ownedSession) repo.event("stop_requested", mapOf("reason" to reason))
            if (repo.snapshot.busy) repo.update(repo.snapshot.copy(phase = "stopping"))
        }
        running.set(false)
        controller.shutdown(reason)
        finish()
    }

    private fun foreground(capture: Boolean) {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "音声・機器制御", NotificationManager.IMPORTANCE_LOW))
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (capture) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        startForeground(NOTIFICATION, notification(), types)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rosebud:playback-probe").apply { setReferenceCounted(false) }
        // Bounded lease renewed only while the capture worker runs. Doze/OEM policies can still intervene.
        wakeLock?.acquire(15 * 60 * 1000L)
        repo.event("wake_lock_acquired_or_renewed", mapOf("lease_ms" to 900000))
    }

    private fun cleanup() {
        if (receiverRegistered) { unregisterReceiver(screenReceiver); receiverRegistered = false }
        projection?.let { token ->
            runCatching { token.unregisterCallback(callback) }
            runCatching { token.stop() }.onFailure { repo.event("projection_stop_error", mapOf("error" to it.toString())) }
        }
        projection = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun finish() {
        if (finished || !controllerClosed || worker != null) return
        finished = true
        running.set(false)
        cleanup()
        worker = null
        if (repo.snapshot.busy) repo.end(stopReason ?: "service_finished")
        stopSelf()
    }

    override fun onDestroy() {
        if (!finished) {
            requestStop("service_destroyed")
            cleanup()
            if (worker == null && repo.snapshot.busy && repo.snapshot.session == ownedSession) repo.end(stopReason ?: "service_destroyed")
        }
        (application as ProbeApplication).serviceActive = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP)
            .putExtra("reason", "notification_stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_capture)
            .setContentTitle("Rosebud 音声・機器制御").setContentText("停止で機器・音声取得・接続を終了します")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CONNECT = "jp.hyoutan.rosebud.CONNECT"
        const val ACTION_SUCTION = "jp.hyoutan.rosebud.SUCTION"
        const val ACTION_SUCTION_SETTINGS = "jp.hyoutan.rosebud.SUCTION_SETTINGS"
        const val ACTION_SCAN = "jp.hyoutan.rosebud.SCAN"
        const val ACTION_SELECT = "jp.hyoutan.rosebud.SELECT"
        const val ACTION_SETTINGS = "jp.hyoutan.rosebud.SETTINGS"
        const val ACTION_TRIAL = "jp.hyoutan.rosebud.FLICKER_TRIAL"
        const val ACTION_PATTERN = "jp.hyoutan.rosebud.SELECT_PATTERN"
        const val ACTION_SKIP = "jp.hyoutan.rosebud.SKIP_STAGE"
        const val ACTION_PAUSE = "jp.hyoutan.rosebud.PAUSE"
        const val ACTION_RESUME = "jp.hyoutan.rosebud.RESUME"
        const val ACTION_MOTION_START = "jp.hyoutan.rosebud.MOTION_START"
        const val ACTION_MOTION_STOP = "jp.hyoutan.rosebud.MOTION_STOP"
        const val ACTION_START = "jp.hyoutan.rosebud.START"
        const val ACTION_STOP = "jp.hyoutan.rosebud.STOP"
        private const val CHANNEL = "playback-probe"
        private const val NOTIFICATION = 1
        private fun readErrorName(code: Int) = when (code) {
            AudioRecord.ERROR_DEAD_OBJECT -> "dead_object"
            AudioRecord.ERROR_INVALID_OPERATION -> "invalid_operation"
            AudioRecord.ERROR_BAD_VALUE -> "bad_value"
            else -> "unknown_$code"
        }
    }
}
