package jp.hyoutan.rosebud

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class ControlDevice(val index: Int, val name: String, val gapMs: Long, val vibrateSteps: Int, val flickerSteps: Int, val problem: String) {
    val compatible get() = problem.isEmpty()
    companion object {
        fun parse(j: JSONObject): ControlDevice {
            val messages = j.getJSONObject("DeviceMessages")
            val scalars = messages.optJSONArray("ScalarCmd")
            val v = scalars?.optJSONObject(0)
            val f = scalars?.optJSONObject(1)
            val vs = v?.optInt("StepCount", 0) ?: 0
            val fs = f?.optInt("StepCount", 0) ?: 0
            val problem = when {
                v?.optString("ActuatorType") != "Vibrate" -> "0番がVibrateではありません"
                f?.optString("ActuatorType") != "Rotate" -> "1番がRotateではありません"
                !f.optString("FeatureDescriptor").contains("Flicker", ignoreCase = true) -> "1番のFlicker定義を確認できません"
                vs !in 1..65535 || fs !in 1..65535 -> "StepCountが範囲外です"
                !messages.has("StopDeviceCmd") -> "停止機能がありません"
                else -> ""
            }
            return ControlDevice(j.getInt("DeviceIndex"), j.optString("DeviceDisplayName").ifBlank { j.getString("DeviceName") },
                j.optLong("DeviceMessageTimingGap", 0).coerceAtLeast(0), vs, fs, problem)
        }
    }
}

internal data class DeviceOutput(val sent: Output = Output(), val acknowledged: Output = Output(), val stop: String = "未要求") {
    fun json(index: Int) = JSONObject().put("device_index", index).put("sent_vibrate", sent.vibrate).put("sent_flicker", sent.flicker)
        .put("ack_vibrate", acknowledged.vibrate).put("ack_flicker", acknowledged.flicker).put("stop", stop)
}

internal data class ControlSnapshot(
    val introRestPercent: Int = RestDefaults.intro, val middleRestPercent: Int = RestDefaults.middle, val finishRestPercent: Int = RestDefaults.finish,
    val trialActive: Boolean = false, val trialPercent: Int = 0, val trialDurationMs: Long = 0,
    val trialRemainingMs: Long = 0, val trialStatus: String = "未実施", val progressMs: Long = 0,
    val paused: Boolean = false, val preview: Boolean = false, val classic: Boolean = true,
    val manualVibrationPattern: Int = -1, val manualFlickerPattern: Int = -1, val flickerPendingPattern: Boolean = false,
    val flickerPattern: Int = -1, val flickerStyle: String = "", val flickerPhase: String = "開始前",
    val flickerPercent: Int = 0, val flickerUnitEndMs: Long = 0, val flickerPatternEndMs: Long = 0, val flickerPendingStage: Boolean = false,
    val recipe: Int = -1, val strength: String = "開始前", val style: String = "", val recipeEndMs: Long = 0,
    val sound: String = "曖昧な音", val density: Double = 0.0, val regularity: Double = 0.0,
    val connection: String = "未接続", val server: String = "", val devices: List<ControlDevice> = emptyList(),
    val selected: List<Int> = emptyList(), val outputs: Map<Int, DeviceOutput> = emptyMap(), val active: Boolean = false, val stopping: Boolean = false,
    val audioReady: Boolean = false, val follow: Boolean = false, val vibrateCap: Int = 30, val flickerCap: Int = 30,
    val elapsedMs: Long = 0, val stage: String = "待機", val raw: Output = Output(), val sent: Output = Output(),
    val acknowledged: Output = Output(), val audioDb: Double = -120.0, val envelope: Double = 0.0,
    val multiplier: Double = 1.0, val plannedRest: Boolean = false, val seed: Long = 0,
    val sends: Long = 0, val maxAckMs: Long = 0, val maxTickGapMs: Long = 0,
    val stopStatus: String = "未要求", val error: String = "", val maxPingMs: Long = 0,
) {
    fun json() = JSONObject().apply {
        put("connection", connection); put("server", server); put("selected", JSONArray(selected)); put("device_outputs", JSONArray(outputs.map { (index, output) -> output.json(index) }))
        put("devices", JSONArray(devices.map { "${it.index}: ${it.name}; steps=${it.vibrateSteps}/${it.flickerSteps}; gap=${it.gapMs}; ${it.problem}" }))
        put("intro_rest_percent", introRestPercent); put("middle_rest_percent", middleRestPercent); put("finish_rest_percent", finishRestPercent)
        put("trial_active", trialActive); put("trial_percent", trialPercent); put("trial_duration_ms", trialDurationMs)
        put("trial_remaining_ms", trialRemainingMs); put("trial_status", trialStatus); put("progress_ms", progressMs)
        put("paused", paused); put("preview", preview); put("classic", classic); put("recipe", recipe); put("strength", strength); put("style", style)
        put("manual_vibration_pattern", manualVibrationPattern); put("manual_flicker_pattern", manualFlickerPattern); put("flicker_pending_pattern", flickerPendingPattern)
        put("flicker_pattern", flickerPattern); put("flicker_style", flickerStyle); put("flicker_phase", flickerPhase)
        put("flicker_percent", flickerPercent); put("flicker_unit_end_ms", flickerUnitEndMs); put("flicker_pattern_end_ms", flickerPatternEndMs)
        put("flicker_pending_stage", flickerPendingStage)
        put("recipe_end_ms", recipeEndMs); put("sound", sound); put("density", density); put("regularity", regularity)
        put("active", active); put("stopping", stopping); put("audio_ready", audioReady); put("follow", follow)
        put("vibrate_cap_percent", vibrateCap); put("flicker_cap_percent", flickerCap)
        put("elapsed_ms", elapsedMs); put("stage", stage); put("raw_vibrate", raw.vibrate); put("raw_flicker", raw.flicker)
        put("sent_vibrate", sent.vibrate); put("sent_flicker", sent.flicker)
        put("ack_vibrate", acknowledged.vibrate); put("ack_flicker", acknowledged.flicker)
        put("audio_dbfs", audioDb); put("envelope", envelope); put("multiplier", multiplier)
        put("planned_flicker_rest", plannedRest); put("seed", seed); put("scalar_sends", sends)
        put("max_ack_ms", maxAckMs); put("max_tick_gap_ms", maxTickGapMs); put("stop_status", stopStatus)
        put("error", error); put("max_ping_ms", maxPingMs)
    }
}

/** Service-owned serial actor. At most one ScalarCmd per device is awaiting acknowledgement; no historical output queue. */
internal class MotionController(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val event: (String, Map<String, Any>) -> Unit,
    private val publish: (ControlSnapshot) -> Unit,
    private val fatal: (String) -> Unit,
    private val closed: () -> Unit,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build(),
) {
    private val actor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "rosebud-control") }
    private var state = ControlSnapshot()
    private var socket: WebSocket? = null
    private var ready = false
    private var closing = false
    private var disposed = false
    private var nextId = 1
    private var run = 0L
    private var began = 0L
    private var lastGenerate = -100L
    private val lastSend = mutableMapOf<Int, Long>()
    private val stopWaiting = mutableSetOf<Int>()
    private val stopFailures = mutableMapOf<Int, String>()
    private var stopReason = ""
    private var lastSummary = 0L
    private var lastPing = 0L
    private var connectAt = 0L
    private var stopDeadline = 0L
    private var scenario = BoilingFrog(SeededRandom(0)::next)
    private val envelope = AudioEnvelope()
    private var features = AudioFeatures()
    private var restSettings = RestSettings()
    private var engine = ScenarioEngine(0)
    private var resumeAt = 0L
    private var pauseRequested = false
    private var trialEndsAt = 0L
    private var trialSentAt = 0L
    private var trialStop: java.util.concurrent.ScheduledFuture<*>? = null
    private data class Pending(val type: String, val expected: String, val at: Long, val device: Int?, val done: (JSONObject) -> Unit)
    private val pending = mutableMapOf<Int, Pending>()
    init { actor.scheduleWithFixedDelay({ guarded { tick() } }, 0, 25, TimeUnit.MILLISECONDS) }

    private fun post(action: () -> Unit) {
        if (!actor.isShutdown) runCatching { actor.execute { guarded(action) } }
    }
    private fun guarded(action: () -> Unit) {
        if (disposed) return
        try { action() } catch (e: Exception) { fail("${e.javaClass.simpleName}: ${e.message}") }
    }
    private fun update(s: ControlSnapshot) { state = s; publish(s) }
    private fun log(type: String, fields: Map<String, Any> = emptyMap()) = event(type, fields)

    fun configure(follow: Boolean, vibrateCap: Int, flickerCap: Int) = post {
        if(state.trialActive || state.stopping) return@post
        update(state.copy(follow = follow, vibrateCap = vibrateCap.coerceIn(5, 100), flickerCap = flickerCap.coerceIn(5, 100)))
        log("control_settings", mapOf("state" to state.json()))
        if (state.active && follow && !freshAudio()) stopMotionInternal("audio_required")
    }
    fun configureRest(intro: Int, middle: Int, finish: Int) = post {
        if(state.trialActive || state.stopping || closing) return@post
        val next = RestSettings(intro.coerceIn(0,RestDefaults.maxPercent),middle.coerceIn(0,RestDefaults.maxPercent),finish.coerceIn(0,RestDefaults.maxPercent))
        if(next == restSettings) return@post
        restSettings = next
        engine.setRestSettings(next)
        update(state.copy(introRestPercent = next.intro, middleRestPercent = next.middle, finishRestPercent = next.finish))
        log("rest_settings",mapOf("state" to state.json()))
    }
    fun connect(url: String) = post {
        check(socket == null && !closing) { "接続中です。終了してから接続してください" }
        // LAN ws:// is deliberately enabled by the manifest; do not rewrite the user's host.
        require(url.startsWith("ws://") || url.startsWith("wss://")) { "ws:// または wss:// を指定してください" }
        connectAt = now()
        update(state.copy(connection = "接続中", server = url, error = ""))
        log("control_connect", mapOf("url" to url))
        socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = post {
                if (socket !== webSocket || closing) return@post
                update(state.copy(connection = "v3確認中"))
                send("RequestServerInfo", JSONObject().put("ClientName", "Rosebud Android ${BuildConfig.VERSION_NAME}").put("MessageVersion", 3), "ServerInfo") { body ->
                    check(body.getInt("MessageVersion") == 3) { "Buttplug v3が必要です" }
                    val ping = body.getLong("MaxPingTime")
                    require(ping == 0L || ping >= 100) { "MaxPingTimeが短すぎます: $ping" }
                    lastPing = now()
                    update(state.copy(connection = "機器一覧取得中", maxPingMs = ping))
                    log("server_info", mapOf("body" to body))
                    send("RequestDeviceList", JSONObject(), "DeviceList") { list ->
                        val devices = list.getJSONArray("Devices")
                        ready = true
                        update(state.copy(connection = "接続済み", devices = (0 until devices.length()).map { ControlDevice.parse(devices.getJSONObject(it)) }))
                        log("device_list", mapOf("state" to state.json()))
                    }
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) = post {
                if (socket !== webSocket) return@post
                val batch = JSONArray(text)
                for (i in 0 until batch.length()) receive(batch.getJSONObject(i))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = post {
                if (socket !== webSocket) return@post
                socket = null
                fail("websocket_failure: ${t.javaClass.simpleName}: ${t.message}")
                if (closing) finishClose()
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = post {
                if (socket !== webSocket) return@post
                webSocket.close(code, reason)
                if (!closing) { socket = null; fail("server_closing: $code $reason") }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = post {
                if (socket !== webSocket) return@post
                socket = null
                if (closing) finishClose() else fail("server_closed: $code $reason")
            }
        })
    }
    fun scan() = post {
        check(ready && !closing) { "先に接続してください" }
        send("StartScanning", JSONObject()) { log("scan_started") }
    }
    fun select(index: Int) = post {
        check(!state.active && !state.paused && !state.trialActive && !state.stopping && !closing) { "停止完了後に対象を変更してください" }
        val targets = if (index == -1) state.devices.filter { it.compatible } else state.devices.filter { it.index == index }
        check(targets.isNotEmpty()) { "対応機器が見つかりません" }
        check(targets.all { it.compatible }) { "対応しない機能定義です" }
        update(state.copy(selected = targets.map { it.index }, outputs = targets.associate { it.index to DeviceOutput() }, error = ""))
        log("devices_selected", mapOf("state" to state.json()))
    }
    fun audioStarted() = post { envelope.reset(); features = AudioFeatures(); update(state.copy(audioReady = true)) }
    fun audio(rms: Double, at: Long) = post { if (state.audioReady && !closing) { envelope.add(rms, at); features.add(rms, at) } }
    private fun freshAudio() = state.audioReady && envelope.lastAt >= 0 && now() - envelope.lastAt <= 2000
    fun session(preview: Boolean, classic: Boolean) = post {
        if (!state.active && !state.paused && !state.trialActive && !state.stopping && !closing) update(state.copy(preview = preview, classic = classic))
    }
    fun resume() = post {
        if (!state.paused || state.trialActive || state.stopping || closing || (state.follow && !freshAudio())) return@post
        if (!state.preview && (!ready || state.selected.any { i -> state.devices.none { it.index == i } })) return@post
        run++
        if (!state.classic) engine.resumeFromPause(state.elapsedMs/1000.0)
        began = now()-state.elapsedMs; resumeAt = now(); lastGenerate = now()-100
        update(state.copy(active = true, paused = false, stopStatus = if(state.preview) "プレビュー・送信なし" else "動作中",
            outputs = state.outputs.mapValues { (_,v) -> v.copy(stop = if(state.preview) "プレビュー・送信なし" else "動作中") }))
        log("motion_resumed", mapOf("state" to state.json()))
    }
    fun pause() = post {
        if (!state.active || state.stopping || closing) return@post
        pauseRequested = true
        val elapsed = (now()-began)/1000.0
        if (!state.classic) engine.frame(elapsed, if(state.follow) features.snapshot else SoundFeatures())
        update(state.copy(elapsedMs = (elapsed*1000).toLong()))
        stopMotionInternal("pause")
    }
    fun start(seed: Long) = post {
        if (closing || (!ready && !state.preview) || state.active || state.paused || state.trialActive || state.stopping) { log("motion_start_rejected", mapOf("reason" to "not_ready_or_stopping")); return@post }
        val targets = state.devices.filter { it.index in state.selected }
        if ((!state.preview && (targets.isEmpty() || targets.size != state.selected.size || targets.any { !it.compatible })) || (state.follow && !freshAudio())) {
            update(state.copy(error = "対象機器を決定し、追従ONなら音声取得を開始してください")); return@post
        }
        if(!state.preview && targets.any { (state.vibrateCap*it.vibrateSteps/100)==0 || (state.flickerCap*it.flickerSteps/100)==0 }) {
            update(state.copy(error = "上限が機器の最小1段未満です。上限を調整してください")); return@post
        }
        if (!state.classic && state.flickerCap < 7) {
            update(state.copy(error = "新しいシナリオはFlicker上限を7%以上にしてください")); return@post
        }
        if (!state.classic && !state.preview && targets.any { 7*it.flickerSteps/100 == 0 }) {
            update(state.copy(error = "対象機器ではFlickerの7%を再現できません")); return@post
        }
        run++
        scenario = BoilingFrog(SeededRandom(seed)::next)
        engine = ScenarioEngine(seed).also { it.setRestSettings(restSettings) }
        pauseRequested = false; resumeAt = now()
        began = now(); lastGenerate = began - 100
        lastSend.clear(); targets.forEach { lastSend[it.index] = began - maxOf(100, it.gapMs) }
        update(state.copy(active = true, paused = false, recipe = -1, manualVibrationPattern = -1, manualFlickerPattern = -1, flickerPendingPattern = false, flickerPattern = -1, flickerPhase = "開始前", flickerPercent = 0, flickerPendingStage = false, elapsedMs = 0, progressMs = 0, raw = Output(), sent = Output(), acknowledged = Output(),
            outputs = targets.associate { it.index to DeviceOutput(stop = "動作中") },
            seed = seed, error = "", stopStatus = if(state.preview) "プレビュー・送信なし" else "動作中", maxTickGapMs = 0))
        log("motion_started", mapOf("state" to state.json()))
    }
    fun skipTo(targetSeconds: Double) = post {
        if(closing || state.stopping || state.trialActive || state.classic || (!state.active && !state.paused)) return@post
        val elapsed = if(state.active) (now()-began)/1000.0 else state.elapsedMs/1000.0
        val sound = if(state.follow) features.snapshot else SoundFeatures()
        if(!engine.skipTo(elapsed,targetSeconds,sound)) return@post
        val frame = engine.frame(elapsed,sound)
        update(state.copy(elapsedMs = (elapsed*1000).toLong(), progressMs = (engine.progressSeconds(elapsed)*1000).toLong(),
            stage = frame.phase, recipe = frame.recipe, strength = frame.strength.label,
            style = ScenarioEngine.styleNames[frame.style], manualVibrationPattern = frame.manualVibrationPattern, recipeEndMs = (frame.recipeEnd*1000).toLong()).withFlicker(frame.flicker))
        log("stage_skipped",mapOf("target_progress_seconds" to targetSeconds,"state" to state.json()))
    }

    fun selectPattern(track: String, index: Int) = post {
        if(closing || state.stopping || state.trialActive || state.classic || state.error.isNotEmpty() ||
            (!state.active && !state.paused) || index !in -1..7) return@post
        val elapsed = if(state.active) (now()-began)/1000.0 else state.elapsedMs/1000.0
        val sound = if(state.follow) features.snapshot else SoundFeatures()
        val accepted = when(track) {
            "vibration" -> engine.selectVibrationPattern(elapsed,index,sound)
            "flicker" -> engine.selectFlickerPattern(elapsed,index,sound)
            else -> false
        }
        if(!accepted) return@post
        val frame = engine.frame(elapsed,sound)
        update(state.copy(elapsedMs = (elapsed*1000).toLong(), progressMs = (engine.progressSeconds(elapsed)*1000).toLong(),
            stage = frame.phase, recipe = frame.recipe, strength = frame.strength.label,
            style = ScenarioEngine.styleNames[frame.style], manualVibrationPattern = frame.manualVibrationPattern,
            recipeEndMs = (frame.recipeEnd*1000).toLong()).withFlicker(frame.flicker))
        log("pattern_selected",mapOf("track" to track,"pattern" to index,"state" to state.json()))
    }

    private fun ControlSnapshot.withFlicker(f: FlickerFrame) = copy(
        manualFlickerPattern = f.manualPattern, flickerPendingPattern = f.pendingPattern,
        flickerPattern = f.pattern, flickerStyle = ScenarioEngine.styleNames[f.style], flickerPhase = when(f.phase) {
            FlickerPhase.ON -> "動作中"; FlickerPhase.REST_A -> "間A · 振動は極弱"; FlickerPhase.REST_B -> "間B · 両方休止"
        }, flickerPercent = f.percent, flickerUnitEndMs = (f.unitEnd*1000).toLong(),
        flickerPatternEndMs = (f.patternEnd*1000).toLong(), flickerPendingStage = f.pendingStage)

    fun startTrial(percent: Int, durationMs: Long) = post {
        fun reject(reason: String) {
            log("flicker_trial_rejected",mapOf("reason" to reason))
            if(!state.active && !state.paused && !state.trialActive && !state.stopping) update(state.copy(error = reason))
        }
        if(closing || state.active || state.paused || state.trialActive || state.stopping) { reject("停止完了後に1回ずつ試してください"); return@post }
        if(!ready || state.preview || state.selected.size!=1) { reject("実機モードで対象を1台だけ決定してください"); return@post }
        val device = state.devices.singleOrNull { it.index==state.selected.single() && it.compatible }
        if(device==null) { reject("選択した機器の接続を確認してください"); return@post }
        if(durationMs !in FlickerTrialLimits.minMs..FlickerTrialLimits.maxMs) { reject("動かす長さは0.1〜10.0秒で設定してください"); return@post }
        val value = FlickerTrialLimits.output(percent,state.flickerCap,device.flickerSteps)
        if(value==null) { reject("出力は上限以内・機器の最小1段以上で設定してください"); return@post }
        if(pending.values.any { it.type=="ScalarCmd" && it.device==device.index }) { reject("前の送信応答を確認中です"); return@post }
        run++; val generation = run
        trialSentAt = now(); trialEndsAt = trialSentAt+durationMs
        val output = Output(0.0,value)
        update(state.copy(trialActive = true,trialPercent = percent,trialDurationMs = durationMs,trialRemainingMs = durationMs,
            trialStatus = "1回だけ動作中", stage = "Flickerの単発確認", strength = "単発確認", raw = Output(0.0,percent/100.0),
            multiplier = 1.0, sent = output, acknowledged = Output(), error = "", stopStatus = "単発確認中",
            outputs = mapOf(device.index to DeviceOutput(sent = output,stop = "単発確認中")), sends = state.sends+1))
        val scalars = JSONArray().put(JSONObject().put("Index",0).put("ActuatorType","Vibrate").put("Scalar",0.0))
            .put(JSONObject().put("Index",1).put("ActuatorType","Rotate").put("Scalar",value))
        send("ScalarCmd",JSONObject().put("DeviceIndex",device.index).put("Scalars",scalars),device = device.index) {
            if(run==generation && state.trialActive && !state.stopping && !closing) {
                update(state.copy(acknowledged = output, outputs = state.outputs + (device.index to state.outputs.getValue(device.index).copy(acknowledged = output))))
                log("flicker_trial_acknowledged",mapOf("state" to state.json()))
            }
        }
        trialSentAt = now(); trialEndsAt = trialSentAt+durationMs
        // Start the interval after queuing the command, not after its acknowledgement.
        // Scheduled stop is independent of audio input, normal generation ticks and response latency.
        trialStop = actor.schedule({ guarded {
            if(run==generation && state.trialActive && !state.stopping) stopMotionInternal("trial_complete")
        } },durationMs,TimeUnit.MILLISECONDS)
        log("flicker_trial_started",mapOf("state" to state.json(),"sent_at_ms" to trialSentAt,"end_at_ms" to trialEndsAt))
    }

    fun stopMotion(reason: String) = post { stopMotionInternal(reason) }
    fun shutdown(reason: String) = post {
        if (closing) return@post
        closing = true; pauseRequested = false
        log("control_shutdown", mapOf("reason" to reason))
        stopMotionInternal(reason)
        if (!state.stopping) finishClose()
    }

    private fun stopMotionInternal(reason: String) {
        trialStop?.cancel(false); trialStop = null
        val trialWasActive = state.trialActive
        if(trialWasActive) log("flicker_trial_stop_requested",mapOf("reason" to reason,"duration_until_request_ms" to (now()-trialSentAt),"requested_duration_ms" to state.trialDurationMs))
        run++ // Invalidate every outstanding ScalarCmd callback before sending stops to every selected device.
        update(state.copy(active = false, paused = false, trialActive = false, trialRemainingMs = 0,
            trialStatus = if(trialWasActive) "停止応答を確認中" else state.trialStatus,
            raw = Output(), sent = Output(), audioReady = state.audioReady && !closing,
            outputs = state.outputs.mapValues { (_, value) -> value.copy(sent = Output()) }))
        if (state.stopping) return
        if (state.preview) {
            update(state.copy(paused = pauseRequested && !closing, stopStatus = "プレビュー・送信なし"))
            pauseRequested = false
            return
        }
        if (!ready || socket == null || state.selected.isEmpty()) {
            update(state.copy(stopStatus = "送信不可（接続・対象を確認）",
                outputs = state.outputs.mapValues { (_, v) -> v.copy(stop = "送信不可・停止未確認") }))
            log("stop_unavailable", mapOf("reason" to reason))
            return
        }
        stopReason = reason; stopFailures.clear()
        stopWaiting.clear(); stopWaiting.addAll(state.selected)
        update(state.copy(stopping = true, stopStatus = "全対象の停止応答待ち"))
        stopDeadline = now() + 3000
        for (index in state.selected) {
            update(state.copy(outputs = state.outputs + (index to state.outputs.getValue(index).copy(stop = "停止応答待ち"))))
            send("StopDeviceCmd", JSONObject().put("DeviceIndex", index), device = index) {
                stopResult(index, null)
            }
            log("stop_sent", mapOf("reason" to reason, "device_index" to index))
        }
    }
    private fun stopResult(index: Int, error: String?) {
        if (!stopWaiting.remove(index)) return
        if (error != null) stopFailures[index] = error
        else {
            // A confirmed stop supersedes older scalar requests for this device. Their late
            // responses cannot revive motion or time out an already completed pause.
            pending.entries.removeAll { it.value.type == "ScalarCmd" && it.value.device == index }
        }
        val output = state.outputs.getValue(index)
        update(state.copy(outputs = state.outputs + (index to output.copy(
            acknowledged = if (error == null) Output() else output.acknowledged,
            stop = if (error == null) "停止応答OK" else "停止未確認: $error"))))
        log(if (error == null) "stop_device_acknowledged" else "stop_device_error",
            mapOf("reason" to stopReason, "device_index" to index, "error" to (error ?: "")))
        if (stopWaiting.isNotEmpty()) return
        val failed = stopFailures.isNotEmpty()
        update(state.copy(stopping = false,
            trialStatus = if(state.trialStatus=="停止応答を確認中") (if(failed) "停止未確認・次の試運転不可" else "単発確認終了・停止応答OK") else state.trialStatus,
            paused = pauseRequested && !failed && !closing, acknowledged = if (failed) state.acknowledged else Output(),
            stopStatus = if (failed) "一部の停止未確認（${stopFailures.keys.joinToString()}）" else "停止応答OK（全対象）",
            error = if (failed) "stop_error: $stopFailures" else state.error))
        log(if (failed) "stop_incomplete" else "stop_acknowledged", mapOf("reason" to stopReason, "state" to state.json()))
        pauseRequested = false
        if (closing) finishClose() else if (failed) fatal("stop_error: $stopFailures")
    }

    private fun fail(message: String) {
        if (disposed) return
        log("control_error", mapOf("error" to message, "state" to state.json()))
        run++
        pauseRequested = false
        update(state.copy(active = false, paused = false, trialActive = false, trialRemainingMs = 0,
            trialStatus = if(state.trialActive) "停止応答を確認中" else state.trialStatus,
            raw = Output(), sent = Output(), error = message))
        // The Service funnels network faults and capture termination into one shutdown path.
        if (!closing) fatal(message)
        else {
            update(state.copy(stopping = false, stopStatus = "停止応答未確認: $message",
                outputs = state.outputs.mapValues { (index, value) -> if (index in stopWaiting) value.copy(stop = "停止未確認: $message") else value }))
            finishClose()
        }
    }
    private fun finishClose() {
        if (disposed) return
        disposed = true; ready = false; pending.clear()
        socket?.close(1000, "stopped"); socket = null
        update(state.copy(connection = "未接続", active = false, paused = false, trialActive = false, trialRemainingMs = 0, stopping = false, audioReady = false, raw = Output(), sent = Output()))
        log("control_closed", mapOf("state" to state.json()))
        actor.shutdown()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        closed()
    }
    private fun send(type: String, body: JSONObject, expected: String = "Ok", device: Int? = null, done: (JSONObject) -> Unit) {
        check(pending.size < 16) { "応答待ちが多すぎます" }
        val id = nextId++
        body.put("Id", id)
        pending[id] = Pending(type, expected, now(), device, done)
        check(socket?.send(JSONArray().put(JSONObject().put(type, body)).toString()) == true) { "websocket_send_failed: $type" }
    }
    private fun receive(message: JSONObject) {
        check(message.length() == 1) { "invalid_message" }
        val type = message.keys().next()
        val body = message.getJSONObject(type)
        when (type) {
            "DeviceAdded" -> {
                val d = ControlDevice.parse(body)
                update(state.copy(devices = state.devices.filter { it.index != d.index } + d))
                log("device_added", mapOf("state" to state.json()))
                return
            }
            "DeviceRemoved" -> {
                val index = body.getInt("DeviceIndex")
                update(state.copy(devices = state.devices.filter { it.index != index }))
                if (index in state.selected) fail("selected_device_removed")
                return
            }
            "ScanningFinished" -> { log("scan_finished"); return }
            "Error" -> {
                val error = "server_error_${body.optInt("ErrorCode")}: ${body.optString("ErrorMessage")}"
                val request = pending.remove(body.optInt("Id", 0))
                if (request?.type == "StopDeviceCmd" && request.device != null && state.stopping) {
                    stopResult(request.device, error)
                } else if (closing && request != null) {
                    log("late_command_error", mapOf("error" to error, "type" to request.type))
                } else fail(error)
                return
            }
        }
        val id = body.getInt("Id")
        val p = pending.remove(id) ?: run { log("late_or_unknown_response", mapOf("id" to id, "type" to type)); return }
        check(type == p.expected) { "unexpected_response: ${p.type} -> $type" }
        update(state.copy(maxAckMs = maxOf(state.maxAckMs, now() - p.at)))
        p.done(body)
    }
    private fun tick() {
        val time = now()
        if (closing) {
            if (state.stopping && time >= stopDeadline) {
                update(state.copy(stopping = false, stopStatus = "停止応答タイムアウト", error = "stop_timeout",
                    outputs = state.outputs.mapValues { (index, value) -> if (index in stopWaiting) value.copy(stop = "停止応答タイムアウト") else value }))
                log("stop_timeout"); finishClose()
            }
            return
        }
        if (socket == null && !state.preview) return
        if (!state.preview && !ready && time - connectAt > 5000) { fail("connect_or_handshake_timeout"); return }
        val expired = pending.values.firstOrNull { time - it.at > if (it.type == "ScalarCmd") 1500 else 3000 }
        if (expired != null) { pending.clear(); fail("response_timeout: ${expired.type}"); return }
        if (ready && state.maxPingMs > 0 && time - lastPing >= state.maxPingMs / 3 && pending.values.none { it.type == "Ping" }) {
            lastPing = time
            send("Ping", JSONObject()) { }
        }
        if(state.trialActive && !state.stopping) {
            if(time>=trialEndsAt) stopMotionInternal("trial_complete")
            else if(state.trialRemainingMs/100 != (trialEndsAt-time)/100) update(state.copy(trialRemainingMs = trialEndsAt-time))
            if(state.audioReady && !freshAudio()) { fail("audio_samples_stale"); return }
        }
        if (state.paused && state.audioReady && !freshAudio()) { fail("audio_samples_stale"); return }
        if (state.active && time - lastGenerate >= 100) {
            if (state.audioReady && !freshAudio()) { fail("audio_samples_stale"); return }
            val targets = state.devices.filter { it.index in state.selected }
            if (!state.preview && (targets.size != state.selected.size || targets.isEmpty())) { fail("device_missing"); return }
            val elapsed = (time-began)/1000.0
            val sound = if(state.follow) features.snapshot else SoundFeatures()
            val generated = if(state.classic) null else engine.frame(elapsed, sound)
            val frame = if(state.classic) scenario.frame(elapsed) else ScenarioFrame(0, generated!!.phase, elapsed, generated.output)
            val ramp = ((time-resumeAt)/1000.0/MotionDefaults.resumeSeconds).coerceIn(.01,1.0)
            val gain = (if (state.classic && state.follow) envelope.multiplier else 1.0)*ramp
            update(state.copy(elapsedMs = time - began, progressMs = if(state.classic) time-began else (engine.progressSeconds(elapsed)*1000).toLong(), stage = frame.name, raw = frame.output,
                recipe = generated?.recipe ?: -1, manualVibrationPattern = generated?.manualVibrationPattern ?: -1, strength = generated?.strength?.label ?: "既存19分",
                style = generated?.let { ScenarioEngine.styleNames[it.style] } ?: "比較用",
                recipeEndMs = generated?.let { (it.recipeEnd*1000).toLong() } ?: 0,
                sound = sound.kind.label, density = sound.density, regularity = sound.regularity,
                audioDb = envelope.rmsDb, envelope = envelope.level, multiplier = gain, plannedRest = frame.plannedFlickerRest,
                maxTickGapMs = maxOf(state.maxTickGapMs, time - lastGenerate)).let { snapshot ->
                    if(generated != null) snapshot.withFlicker(generated.flicker) else snapshot
                })
            lastGenerate = time
            if (state.preview) update(state.copy(sent = Output(), acknowledged = Output()))
            for (d in if(state.preview) emptyList() else targets) {
                val output = if(state.classic) Output(
                    quantize(frame.output.vibrate * gain, state.vibrateCap / 100.0, d.vibrateSteps),
                    quantize(frame.output.flicker * gain, state.flickerCap / 100.0, d.flickerSteps)) else Output(
                    quantizeAbsolute(frame.output.vibrate * ramp, state.vibrateCap / 100.0, d.vibrateSteps),
                    quantizeAbsolute(frame.output.flicker, state.flickerCap / 100.0, d.flickerSteps, positiveFloor = false))
                val deviceState = state.outputs.getValue(d.index)
                if (time - (lastSend[d.index] ?: 0) >= maxOf(100, d.gapMs) &&
                    pending.values.none { it.type == "ScalarCmd" && it.device == d.index } && output != deviceState.acknowledged) {
                    val generation = run
                    val scalars = JSONArray().put(JSONObject().put("Index", 0).put("ActuatorType", "Vibrate").put("Scalar", output.vibrate))
                        .put(JSONObject().put("Index", 1).put("ActuatorType", "Rotate").put("Scalar", output.flicker))
                    send("ScalarCmd", JSONObject().put("DeviceIndex", d.index).put("Scalars", scalars), device = d.index) {
                        if (run == generation && state.active && !closing) {
                            update(state.copy(acknowledged = if (d.index == state.selected.first()) output else state.acknowledged,
                                outputs = state.outputs + (d.index to state.outputs.getValue(d.index).copy(acknowledged = output))))
                        }
                    }
                    lastSend[d.index] = time
                    update(state.copy(sent = if (d.index == state.selected.first()) output else state.sent, sends = state.sends + 1,
                        outputs = state.outputs + (d.index to deviceState.copy(sent = output))))
                    log("scalar_sent", mapOf("device_index" to d.index, "state" to state.json()))
                }
            }
        }
        if (time - lastSummary >= 1000) {
            lastSummary = time
            log("control_window", mapOf("state" to state.json()))
        }
    }
}
