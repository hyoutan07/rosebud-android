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
    private var streamCadence: HandyStreamCadence? = null
    private var lastTargetMm: Double? = null
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
                "device_minimum_mm" to (value.minimumMm ?: org.json.JSONObject.NULL),
                "device_maximum_mm" to (value.maximumMm ?: org.json.JSONObject.NULL),
                "use_device_maximum" to value.useDeviceMaximum,
                "stage" to value.frame.stage, "elapsed_ms" to value.frame.elapsedMs,
                "pattern" to value.frame.pattern,
                "requested_speed_mm_s" to (if (track != null) value.frame.speedMmPerSec else org.json.JSONObject.NULL),
                "actual_speed_mm_s" to (state?.speedMmPerSec ?: org.json.JSONObject.NULL),
                "last_target_mm" to (lastTargetMm ?: org.json.JSONObject.NULL)))
        }
    }
    private fun update(transform: (HandySnapshot) -> HandySnapshot) = publish(transform(app.handyState))

    override fun onCreate() {
        super.onCreate()
        app.handyServiceActive = true
        val upper = getSharedPreferences("handy_connection", MODE_PRIVATE).getFloat("upper_mm", HandyLimits.DEFAULT_UPPER_MM).toDouble()
        publish(HandySnapshot(upperMm = upper,useDeviceMaximum = useDeviceMaximum()))
        ble = HandyBleTransport(this,
            onState = { message -> update { it.copy(status = message) } },
            onFailure = { message -> transportEnded(message) })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || destroyed || leaving) return START_NOT_STICKY
        try {
            foreground()
            when (intent.action) {
                ACTION_CONNECT -> connect()
                ACTION_DISCONNECT -> stopMotion("切断中", disconnect = true)
                ACTION_START -> if (track == null && !app.handyState.stopping) beginScenario(intent)
                ACTION_TRIAL -> beginTrial(intent)
                ACTION_STOP -> stopMotion("停止要求を送りました")
                ACTION_WEAK -> changeScenario(weak = true)
                ACTION_FINISH -> changeScenario(weak = false, stage = "フィニッシュ")
                ACTION_STAGE -> changeScenario(weak = false, stage = intent.getStringExtra("stage"))
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
        ble.connect(useDeviceMaximum()) { received ->
            if (epoch != generation || destroyed) return@connect
            state = received
            val upper = HandyBounds.create(received.minimumMm,received.maximumMm,readUpper(),useDeviceMaximum()).upperMm
            update { it.copy(connected = true, connecting = false, stopping = false, positionMm = received.positionMm,
                minimumMm = received.minimumMm, maximumMm = received.maximumMm, upperMm = upper,useDeviceMaximum = useDeviceMaximum(),
                status = "接続しました。開始するまで動きません", error = null) }
        }
    }

    private fun useDeviceMaximum() = getSharedPreferences("handy_connection",MODE_PRIVATE).getBoolean("use_device_maximum",true)
    private fun readUpper(): Double = getSharedPreferences("handy_connection", MODE_PRIVATE).getFloat("upper_mm", HandyLimits.DEFAULT_UPPER_MM).toDouble()
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
        trial = null; streamCadence = null
        prepare(epoch) {
            track?.start(now())
            alignTrack()
            update { it.copy(running = true, trialActive = false, trialStatus = "未実施", error = null, status = "シナリオ実行中") }
            acquireWake()
            streamCadence = HandyStreamCadence(checkNotNull(state).sampledAt)
            startClock(epoch)
            stream(epoch)
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
        update { it.copy(stopping = true, status = "開始前の位置と停止状態を確認中", error = null) }
        fun poll() {
            if (epoch != generation || destroyed || leaving) return
            ble.readState {
                received ->
                if (epoch != generation || destroyed || leaving) return@readState
                try {
                    val envelope = HandyBounds.create(received.minimumMm, received.maximumMm, readUpper(),useDeviceMaximum())
                    val position = HandyStartReadiness.position(envelope,received,now())
                    ProbeRepository.get(this).controlEvent("handy_prepare_sample", mapOf(
                        "position_mm" to received.positionMm,"speed_mm_s" to received.speedMmPerSec,
                        "sample_age_ms" to (now()-received.sampledAt),"ready" to (position!=null),
                        "minimum_mm" to envelope.minimumMm,"upper_mm" to envelope.upperMm))
                    if(position==null) {
                        update { it.copy(status = "位置と停止状態を再確認しています。接続は維持しています", error = null) }
                        main.postDelayed({ poll() },250)
                        return@readState
                    }
                    state = received.copy(positionMm = position); bounds = envelope
                    update { it.copy(stopping = false, positionMm = position, upperMm = envelope.upperMm,useDeviceMaximum = useDeviceMaximum(),
                        minimumMm = received.minimumMm, maximumMm = received.maximumMm) }
                    alignTrack()
                    done()
                } catch (e: Exception) { fail(e.message ?: "開始設定を確認できませんでした") }
            }
        }
        ble.stop { accepted ->
            if(epoch!=generation || destroyed || leaving) return@stop
            if(!accepted) { fail("開始前の停止応答を確認できませんでした");return@stop }
            ble.refreshStroke(useDeviceMaximum()) {
                if(epoch==generation && !destroyed && !leaving) poll()
            }
        }
    }

    private fun freshSound() = app.serviceActive && app.handySoundAt > 0 && now() - app.handySoundAt in 0..2000
    private fun alignTrack() {
        val envelope = bounds ?: return
        val received = state ?: return
        track?.alignPosition(envelope.normalized(received.positionMm))
    }

    /** UI progress and stale-audio checks continue while BLE acknowledgement/arrival is pending. */
    private fun startClock(epoch: Long) {
        fun advance() {
            if (epoch != generation || destroyed || track == null || !app.handyState.running) return
            if (follow && !freshSound()) { fail("音声の更新が途切れたため停止しました"); return }
            renewWake()
            val frame = checkNotNull(track).clockFrame(now())
            update { it.copy(frame = frame) }
            main.postDelayed({ advance() }, 200)
        }
        advance()
    }

    /** One acknowledged request at a time; late responses select the current trajectory, never replay old points. */
    private fun stream(epoch: Long) {
        if (epoch != generation || track == null || destroyed || app.handyState.stopping) return
        val cadence = streamCadence ?: return
        if (follow && !freshSound()) { fail("音声の更新が途切れたため停止しました"); return }
        renewWake()
        if (state == null || cadence.stateDue(now())) {
            val readBegan = now()
            ble.readState(unavailable = { unavailablePosition(epoch) }) { received ->
                if (epoch != generation || destroyed || track == null) return@readState
                val envelope = bounds ?: return@readState
                val position = HandyTelemetry.position(received.positionMm, envelope.minimumMm, envelope.upperMm)
                if (position == null || !received.speedMmPerSec.isFinite() || !cadence.stateFresh(received.sampledAt, now())) {
                    ProbeRepository.get(this).controlEvent("handy_position_recheck", mapOf(
                        "raw_position_mm" to received.positionMm, "minimum_mm" to envelope.minimumMm,
                        "upper_mm" to envelope.upperMm, "last_target_mm" to (lastTargetMm ?: org.json.JSONObject.NULL)))
                    unavailablePosition(epoch)
                    return@readState
                }
                state = received.copy(positionMm = position)
                cadence.readCompleted(now(), now() - readBegan)
                update { it.copy(positionMm = position, error = null) }
                sendLatestTrajectory(epoch)
            }
        } else sendLatestTrajectory(epoch)
    }

    private fun unavailablePosition(epoch: Long) {
        if (epoch != generation || track == null || destroyed || app.handyState.stopping) return
        val cadence = streamCadence ?: return
        cadence.readCompleted(now()) // Throttle retries without refreshing the last trusted position.
        val received = state
        if (received != null && cadence.stateFresh(received.sampledAt, now())) sendLatestTrajectory(epoch)
        else {
            update { it.copy(status = "位置を再確認中です。接続とシナリオを維持しています", error = null) }
            // Last command stops at its bounded target. Read recovery resumes the same session automatically.
            main.postDelayed({ stream(epoch) }, 250)
        }
    }

    private fun sendLatestTrajectory(epoch: Long) {
        if (epoch != generation || track == null || destroyed || app.handyState.stopping) return
        val cadence = streamCadence ?: return
        try {
            check(!follow || freshSound()) { "音声の更新が途切れたため停止しました" }
            val envelope = checkNotNull(bounds)
            val received = checkNotNull(state)
            if (!cadence.stateFresh(received.sampledAt, now())) {
                unavailablePosition(epoch)
                return
            }
            val sound = if (follow) app.handySound else SoundFeatures()
            val frame = checkNotNull(track).sampleTrajectory(now(), sound, envelope.normalized(received.positionMm),
                envelope.targetMaximumMm - envelope.targetMinimumMm, cadence.horizonMs())
            update { it.copy(frame = frame, status = if (frame.weak) "Handyだけ弱めています" else "${frame.stage}：${HandyTrack.names[frame.pattern]}") }
            val move = frame.move
            if (move == null) {
                // Explicit pattern rests let the last bounded command finish; no mode stop/restart.
                main.postDelayed({ stream(epoch) }, 100)
                return
            }
            val command = envelope.streamCommand(received.positionMm, move.target, move.durationMs, minOf(received.maximumSpeedMmPerSec, frame.speedLimitMmPerSec))
            lastTargetMm = command.targetMm
            cadence.commandSent(now())
            // Each command still stops at its bounded target if the stream is interrupted.
            ble.move(command.targetMm, command.durationMs) {
                if (epoch != generation || destroyed || track == null) return@move
                cadence.commandCompleted(now())
                main.postDelayed({ stream(epoch) }, cadence.nextDelay(now()))
            }
        } catch (e: Exception) { fail(e.message ?: "軌道を継続できませんでした") }
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
        val gate = HandyArrivalGate(target, duration)
        lastTargetMm = target
        val began = now()
        var finished = false
        main.postDelayed({ if (epoch == generation && !finished) fail("目標への到達を確認できなかったため停止しました") }, duration + 5000)
        ble.move(target, duration) {
            if (epoch != generation || destroyed) return@move
            gate.acknowledge(now())
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
                    if (gate.canContinue(received.positionMm, received.speedMmPerSec, now())) {
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

    private fun changeScenario(weak: Boolean, stage: String? = null) {
        val current = track ?: return
        if (!app.handyState.running || app.handyState.stopping) return
        if (weak) current.weaken(!app.handyState.frame.weak, now())
        else if (!current.selectStage(stage ?: return, now())) return
        // Existing acknowledged stream takes the new stage at the next sample/turn. No stop/re-arm race.
        ProbeRepository.get(this).controlEvent("handy_stage_change",mapOf("stage" to current.frame.stage,"weak" to current.frame.weak))
        update { it.copy(frame = current.clockFrame(now()), error = null, status = "${current.frame.stage}へ切り替えました") }
    }

    private fun stopMotion(message: String, disconnect: Boolean = false, completedTrial: Boolean = false, error: String? = null) {
        val epoch = ++generation
        val wasConnecting = app.handyState.connecting
        val closeAfterStop = disconnect || wasConnecting
        val wasTrial = app.handyState.trialActive
        track?.stop(); track = null; trial = null; bounds = null; streamCadence = null
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

    private fun transportEnded(message: String) {
        if (destroyed || leaving) return
        ++generation
        // Transport owns its single bounded stop/close. Re-entering stop here caused duplicate failure chains.
        ProbeRepository.get(this).controlEvent("handy_session_transport_end",mapOf("message" to message,
            "was_running" to app.handyState.running,"was_connecting" to app.handyState.connecting))
        state = null; bounds = null; streamCadence = null
        track?.stop(); track = null; trial = null
        releaseWake()
        update { it.copy(connected = false,connecting = false,running = false,trialActive = false,stopping = false,
            frame = it.frame.copy(running = false,weak = false,move = null),status = message,error = message) }
    }

    private fun fail(message: String) {
        if (destroyed || leaving) return
        val connected = app.handyState.connected
        ProbeRepository.get(this).controlEvent("handy_session_failure",mapOf("message" to message,
            "connected" to connected,"running" to app.handyState.running,"preparing" to app.handyState.stopping))
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
        track?.stop(); track = null; trial = null; streamCadence = null
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
        const val ACTION_STAGE = "jp.hyoutan.rosebud.handy.STAGE"
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
