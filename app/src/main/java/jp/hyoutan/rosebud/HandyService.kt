package jp.hyoutan.rosebud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import kotlin.math.abs

/** Owns the Handy BLE connection; the Intiface/Capture service continues to own Rosebud. */
class HandyService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val app get() = application as ProbeApplication
    private lateinit var ble: HandyBleTransport
    private var generation = 0L
    private var state: HandyDeviceState? = null
    private var bounds: HandyBounds? = null
    private var track: HandyTrack? = null
    private var trial: HandyTrialPlan? = null
    private var trialIndex = -1
    private var lastSegment = Long.MIN_VALUE
    private var follow = false
    private var wake: PowerManager.WakeLock? = null
    private var nextWakeRenew = 0L
    private var leaving = false
    private var destroyed = false
    private fun now() = SystemClock.elapsedRealtime()
    private var lastEventAt = Long.MIN_VALUE / 2
    private fun publish(value: HandySnapshot) {
        val old = app.handyState
        app.handyState = value
        val changed = old.connected != value.connected || old.connecting != value.connecting || old.running != value.running ||
            old.trialActive != value.trialActive || old.stopping != value.stopping || old.status != value.status || old.error != value.error
        if (changed || now() - lastEventAt >= 1000) {
            lastEventAt = now()
            ProbeRepository.get(this).controlEvent("handy_state", mapOf(
                "connected" to value.connected, "connecting" to value.connecting, "running" to value.running,
                "trial_active" to value.trialActive, "stopping" to value.stopping, "status" to value.status,
                "error" to (value.error ?: ""), "position_mm" to (value.positionMm ?: org.json.JSONObject.NULL), "upper_mm" to value.upperMm,
                "stage" to value.frame.stage, "elapsed_ms" to value.frame.elapsedMs))
        }
    }
    private fun update(transform: (HandySnapshot) -> HandySnapshot) = publish(transform(app.handyState))

    override fun onCreate() {
        super.onCreate()
        app.handyServiceActive = true
        val upper = getSharedPreferences("handy_connection", MODE_PRIVATE).getFloat("upper_mm", 85f).toDouble()
        publish(HandySnapshot(upperMm = upper))
        ble = HandyBleTransport(this,
            onState = { message -> update { it.copy(status = message) } },
            onFailure = { message ->
                update { it.copy(connected = false, connecting = false) }
                fail(message)
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || destroyed || leaving) return START_NOT_STICKY
        try {
            foreground()
            when (intent.action) {
                ACTION_CONNECT -> connect()
                ACTION_DISCONNECT -> stopMotion("切断中", disconnect = true)
                ACTION_START -> beginScenario(intent)
                ACTION_TRIAL -> beginTrial(intent)
                ACTION_STOP -> stopMotion("停止要求を送りました")
                ACTION_WEAK -> changeScenario(weak = true)
                ACTION_FINISH -> changeScenario(weak = false)
                ACTION_ALL_STOP -> {
                    if (app.serviceActive) startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)
                        .putExtra("reason", "handy_notification_all_stop"))
                    stopMotion("全停止要求を送りました")
                }
                else -> stopMotion("不明な操作のため停止しました")
            }
        } catch (e: Exception) { fail(e.message ?: "Handyの操作に失敗しました") }
        return START_NOT_STICKY // No reconnect, motion replay or restart after process death.
    }

    private fun connect() {
        if (app.handyState.running || app.handyState.trialActive || app.handyState.stopping || app.handyState.connecting) return
        val epoch = ++generation
        update { it.copy(connecting = true, connected = false, error = null, status = "Handyを探しています") }
        ble.connect { received ->
            if (epoch != generation || destroyed) return@connect
            state = received
            val upper = readUpper()
            update { it.copy(connected = true, connecting = false, stopping = false, positionMm = received.positionMm,
                minimumMm = received.minimumMm, maximumMm = received.maximumMm, upperMm = upper,
                status = "接続しました。開始するまで動きません", error = null) }
        }
    }

    private fun readUpper(): Double = getSharedPreferences("handy_connection", MODE_PRIVATE).getFloat("upper_mm", 85f).toDouble()
    private fun ready() {
        require(app.handyState.connected && !app.handyState.connecting && !app.handyState.stopping) { "Handyに接続してから開始してください" }
        require(!app.handyState.running && !app.handyState.trialActive) { "先に現在の動作を停止してください" }
        require(!app.control.trialActive) { "ROSEBUDの入力テストを止めてから開始してください" }
    }
    private fun settings(intent: Intent) = HandySettings(
        introMs = intent.getLongExtra("intro_ms", 480000), middleMs = intent.getLongExtra("middle_ms", 420000),
        finishMs = intent.getLongExtra("finish_ms", 300000), lower = intent.getDoubleExtra("lower", 0.0),
        upper = intent.getDoubleExtra("upper", 1.0), halfMs = intent.getLongExtra("half_ms", 3000),
        weights = intent.getIntArrayExtra("weights")?.toList() ?: List(13) { 100 })

    private fun beginScenario(intent: Intent) {
        ready()
        val configuration = settings(intent)
        follow = intent.getBooleanExtra("follow", false)
        require(!follow || freshSound()) { "音声連動には音声取得を開始してください" }
        val epoch = ++generation
        track = HandyTrack(now(), configuration)
        trial = null; lastSegment = Long.MIN_VALUE
        prepare(epoch) {
            track?.start(now())
            alignTrack()
            update { it.copy(running = true, trialActive = false, trialStatus = "未実施", error = null, status = "シナリオ実行中") }
            acquireWake()
            pump(epoch)
        }
    }

    private fun beginTrial(intent: Intent) {
        ready()
        val targets = requireNotNull(intent.getDoubleArrayExtra("points_percent")) { "位置列がありません" }
        val times = requireNotNull(intent.getLongArrayExtra("durations_ms")) { "秒数がありません" }
        require(targets.size == times.size)
        val plan = HandyTrialPlan(intent.getStringExtra("mode") ?: "sequence", intent.getDoubleExtra("initial_percent", Double.NaN),
            targets.indices.map { HandyTrialPoint(targets[it], times[it]) })
        val epoch = ++generation
        track = null; trial = plan; trialIndex = -1
        prepare(epoch) {
            update { it.copy(running = false, trialActive = true, trialStatus = "途中終了", error = null,
                frame = HandyFrame(stage = "入力テスト"), status = "入力テスト：開始位置へ移動中") }
            acquireWake()
            nextTrial(epoch)
        }
    }

    private fun prepare(epoch: Long, done: () -> Unit) {
        update { it.copy(stopping = true, status = "開始前の位置を確認中", error = null) }
        ble.readState { received ->
            if (epoch != generation || destroyed) return@readState
            try {
                val envelope = HandyBounds.create(received.minimumMm, received.maximumMm, readUpper())
                envelope.requireStart(received.positionMm, received.speedMmPerSec, received.sampledAt, now())
                state = received; bounds = envelope
                update { it.copy(stopping = false, positionMm = received.positionMm, upperMm = envelope.upperMm,
                    minimumMm = received.minimumMm, maximumMm = received.maximumMm) }
                alignTrack()
                done()
            } catch (e: Exception) { fail(e.message ?: "開始位置を確認できませんでした") }
        }
    }

    private fun freshSound() = app.serviceActive && app.handySoundAt > 0 && now() - app.handySoundAt in 0..2000
    private fun alignTrack() {
        val envelope = bounds ?: return
        val received = state ?: return
        track?.alignPosition(envelope.normalized(received.positionMm))
    }

    private fun pump(epoch: Long) {
        if (epoch != generation || track == null || destroyed) return
        renewWake()
        try {
            check(!follow || freshSound()) { "音声の更新が途切れたため停止しました" }
            val sound = if (follow) app.handySound else SoundFeatures()
            val frame = checkNotNull(track).tick(now(), sound)
            update { it.copy(frame = frame, status = if (frame.weak) "Handyだけ弱めています" else "${frame.stage}：${HandyTrack.names[frame.pattern]}") }
            if (frame.segmentEndMs != lastSegment) {
                lastSegment = frame.segmentEndMs
                if (frame.move != null) {
                    leg(epoch, checkNotNull(bounds).target(frame.move.target), frame.move.durationMs) { pump(epoch) }
                    return
                }
            }
            main.postDelayed({ pump(epoch) }, 100)
        } catch (e: Exception) { fail(e.message ?: "シナリオを継続できませんでした") }
    }

    private fun nextTrial(epoch: Long) {
        if (epoch != generation || destroyed) return
        val plan = trial ?: return
        if (trialIndex >= plan.points.size) {
            stopMotion("入力テスト完了：停止要求を確認中", completedTrial = true)
            return
        }
        val initial = trialIndex < 0
        val point = if (initial) HandyTrialPoint(plan.initialPercent, 3000) else plan.points[trialIndex]
        update { it.copy(status = if (initial) "入力テスト：開始位置へ移動中" else "入力テスト：${trialIndex + 1} / ${plan.points.size}") }
        leg(epoch, checkNotNull(bounds).target(point.percent / 100), point.durationMs) {
            trialIndex++
            nextTrial(epoch)
        }
    }

    /** Only one move, then one acknowledged state poll at a time. Nothing accumulates behind a slow device. */
    private fun leg(epoch: Long, target: Double, plannedMs: Long, done: () -> Unit) {
        val envelope = checkNotNull(bounds)
        val previous = checkNotNull(state)
        check(envelope.contains(previous.positionMm) && envelope.contains(target)) { "設定した上限の外へは動かせません" }
        val duration = envelope.duration(previous.positionMm, target, plannedMs, previous.maximumSpeedMmPerSec)
        val began = now()
        var finished = false
        main.postDelayed({ if (epoch == generation && !finished) fail("目標への到達を確認できなかったため停止しました") }, duration + 5000)
        ble.move(target, duration) {
            if (epoch != generation || destroyed) return@move
            val end = now() + duration
            fun poll() {
                if (epoch != generation || destroyed || finished) return
                renewWake()
                if (track != null && follow && !freshSound()) { fail("音声の更新が途切れたため停止しました"); return }
                ble.readState { received ->
                    if (epoch != generation || destroyed || finished) return@readState
                    if (!received.positionMm.isFinite() || !received.speedMmPerSec.isFinite() ||
                        !envelope.contains(received.positionMm) || now() - received.sampledAt !in 0..2000) {
                        fail("動作位置が設定範囲内か確認できなかったため停止しました")
                        return@readState
                    }
                    state = received
                    update { it.copy(positionMm = received.positionMm) }
                    val arrived = abs(received.positionMm - target) <= 1.0 && abs(received.speedMmPerSec) <= .1
                    if (arrived && now() >= end) {
                        finished = true
                        done()
                    } else if (now() - began > duration + 4000) {
                        fail("移動の応答が遅れたため停止しました")
                    } else main.postDelayed({ poll() }, 250)
                }
            }
            poll()
        }
    }

    private fun changeScenario(weak: Boolean) {
        val current = track ?: return
        if (!app.handyState.running || app.handyState.stopping) return
        val epoch = ++generation
        if (weak) current.weaken(!app.handyState.frame.weak, now()) else current.finish(now())
        current.interruptSegment(now())
        lastSegment = Long.MIN_VALUE
        update { it.copy(stopping = true, status = "動作を切り替えるため停止中") }
        ble.stop { accepted ->
            if (epoch != generation || destroyed) return@stop
            if (!accepted) { fail("停止応答を確認できません。本体の停止ボタンを押してください"); return@stop }
            prepare(epoch) { pump(epoch) }
        }
    }

    private fun stopMotion(message: String, disconnect: Boolean = false, completedTrial: Boolean = false, error: String? = null) {
        val epoch = ++generation
        val wasConnecting = app.handyState.connecting
        val closeAfterStop = disconnect || wasConnecting
        val wasTrial = app.handyState.trialActive
        track?.stop(); track = null; trial = null; bounds = null
        releaseWake()
        if (closeAfterStop) leaving = true
        update { it.copy(running = false, trialActive = false, stopping = true, connecting = false,
            frame = it.frame.copy(running = false, weak = false, move = null), status = message, error = error,
            trialStatus = if (wasTrial) (if (error != null) "エラー" else if (completedTrial) "完了" else "途中終了") else it.trialStatus) }
        ble.stop { accepted ->
            if (epoch != generation || destroyed) return@stop
            update { it.copy(stopping = false, status = if (accepted) "$message。停止要求は受理されました" else "停止応答未確認。本体の停止ボタンを押してください",
                trialStatus = if (!accepted && wasTrial) "エラー" else it.trialStatus,
                error = error ?: if (!accepted) "Bluetooth経由の停止を確認できません" else null) }
            if (closeAfterStop) {
                ble.close()
                update { it.copy(connected = false, connecting = false, stopping = false, status = "切断しました") }
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
    }

    private fun fail(message: String) {
        if (destroyed || leaving) return
        val connected = app.handyState.connected
        stopMotion(message, error = message)
        // Transport failures close BLE themselves. A protocol/application failure still gets a stop request.
        if (!connected) update { it.copy(connected = false, connecting = false) }
    }

    private fun acquireWake() {
        if (wake == null) wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rosebud:handy")
            .apply { setReferenceCounted(false) }
        wake?.acquire(60000)
        nextWakeRenew = now() + 30000
    }
    private fun renewWake() { if (now() >= nextWakeRenew && (track != null || trial != null)) acquireWake() }
    private fun releaseWake() { wake?.let { if (it.isHeld) it.release() }; wake = null }

    private fun foreground() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Handy直接接続", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 20, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 21, Intent(this, HandyService::class.java).setAction(ACTION_ALL_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notice = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_capture).setContentTitle("Handy直接接続")
            .setContentText("全停止でHandy・ROSEBUD・音声取得を止めます").setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "全停止", stop).build()).build()
        startForeground(NOTIFICATION, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    override fun onDestroy() {
        destroyed = true; generation++
        track?.stop(); track = null; trial = null
        releaseWake()
        app.handyServiceActive = false
        publish(app.handyState.copy(connected = false, connecting = false, running = false, trialActive = false,
            stopping = false, frame = app.handyState.frame.copy(running = false, weak = false, move = null), status = "切断しました"))
        ble.stop { ble.close() }
        main.postDelayed({ ble.close() }, 3200)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CONNECT = "jp.hyoutan.rosebud.handy.CONNECT"
        const val ACTION_DISCONNECT = "jp.hyoutan.rosebud.handy.DISCONNECT"
        const val ACTION_START = "jp.hyoutan.rosebud.handy.START"
        const val ACTION_STOP = "jp.hyoutan.rosebud.handy.STOP"
        const val ACTION_WEAK = "jp.hyoutan.rosebud.handy.WEAK"
        const val ACTION_FINISH = "jp.hyoutan.rosebud.handy.FINISH"
        const val ACTION_TRIAL = "jp.hyoutan.rosebud.handy.TRIAL"
        private const val ACTION_ALL_STOP = "jp.hyoutan.rosebud.handy.ALL_STOP"
        private const val CHANNEL = "handy-direct"
        private const val NOTIFICATION = 2
        fun requestStop(context: Context) {
            if ((context.applicationContext as ProbeApplication).handyServiceActive)
                context.startService(Intent(context, HandyService::class.java).setAction(ACTION_STOP))
        }
        fun requestAllStop(context: Context) {
            val app = context.applicationContext as ProbeApplication
            if (app.serviceActive) context.startService(Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)
                .putExtra("reason", "global_stop"))
            requestStop(context)
        }
    }
}
