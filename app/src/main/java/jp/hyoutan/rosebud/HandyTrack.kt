package jp.hyoutan.rosebud

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sin

/** Experimental command values, never measured position or a confirmed preference. */
internal data class HandySettings(val introMs: Long = 480000, val middleMs: Long = 420000, val finishMs: Long = 300000,
    val lower: Double = .45, val upper: Double = .55, val halfMs: Long = 3000,
    val weights: List<Int> = listOf(100,100,100)) {
    init {
        require(introMs in 60000..3600000 && middleMs in 60000..3600000 && finishMs in 60000..3600000)
        require(lower.isFinite() && upper.isFinite() && lower >= .1 && upper <= .9 && upper-lower in .02.. .2)
        require(halfMs in 2000..10000 && weights.size == 3 && weights.all { it in 10..200 })
    }
}
internal data class LinearMove(val target: Double, val durationMs: Long) {
    fun body(device: Int, feature: Int) = JSONObject().put("DeviceIndex",device).put("Vectors",JSONArray()
        .put(JSONObject().put("Index",feature).put("Duration",durationMs).put("Position",target)))
}
internal data class HandyFrame(val running: Boolean = false, val weak: Boolean = false, val elapsedMs: Long = 0,
    val stage: String = "待機", val cycle: Long = 0, val pattern: Int = 0, val ageMs: Long = 0,
    val move: LinearMove? = null, val segmentEndMs: Long = 0, val plannedRest: Boolean = false)

/** Own PRNG and progress clock; a delayed tick creates one fresh segment, never a backlog. */
internal class HandyTrack(seed: Long, val settings: HandySettings = HandySettings()) {
    companion object { val names = listOf("幅の波（仮）", "二連と余韻（仮）", "反復と間（仮）") }
    private val random = SeededRandom(seed xor 0x48414e4459L)
    private var last = 0L
    private var progress = 0L
    private var age = 0L
    private var segmentEnd = 0L
    private var selectedSound = SoundKind.UNCLEAR
    private var lastSound = SoundFeatures()
    private var pattern = 0
    private var forward = false
    private var step = 0L
    private var resumeAt = Long.MIN_VALUE / 2
    private var running = false
    private var weak = false
    var frame = HandyFrame(); private set
    fun start(time: Long) { running = true; last = time; progress = 0; age = 0; segmentEnd = time; step = 0; weak = false }
    fun stop() { running = false; weak = false; frame = frame.copy(running = false, weak = false, move = null) }
    fun weaken(value: Boolean, time: Long) { tick(time, lastSound); if (weak && !value) resumeAt = time; weak = value }
    fun finish(time: Long) { if (running && !weak) { tick(time, lastSound); progress = settings.introMs + settings.middleMs } }
    fun tick(time: Long, sound: SoundFeatures): HandyFrame {
        if (!running) return frame
        lastSound = sound
        val delta = (time-last).coerceAtLeast(0); last = time
        if (!weak) { progress += delta; age += delta }
        val stage = when { progress < settings.introMs -> "はじめ"; progress < settings.introMs+settings.middleMs -> "中間"; else -> "フィニッシュ" }
        var move = frame.move
        var rest = frame.plannedRest
        if (time >= segmentEnd) {
            if (step == 0L || (!weak && age >= 20000 && (sound.kind != selectedSound || age >= 80000))) {
                val scores = settings.weights.mapIndexed { i,w -> w * when(sound.kind) {
                    SoundKind.SMOOTH -> if(i==0) 3.0 else 1.0
                    SoundKind.REGULAR -> if(i==1) 3.0 else 1.0
                    SoundKind.BROKEN -> if(i==2) 3.0 else 1.0
                    else -> 1.0
                } * if(i==pattern && step>0) .35 else 1.0 }
                var draw = random.next()*scores.sum()
                pattern = scores.indices.firstOrNull { draw -= scores[it]; draw <= 0 } ?: 2
                selectedSound = sound.kind; age = 0
            }
            step++; forward = !forward
            rest = pattern == 2 && step % (if(stage=="はじめ") 4 else 8) == 0L && !weak
            val phaseGain = when(stage) { "はじめ" -> .4; "中間" -> .4+.3*(progress-settings.introMs).toDouble()/settings.middleMs; else -> 1.0 }
            val recovery = ((time-resumeAt)/3000.0).coerceIn(.15,1.0)
            val gain = if(weak) .15 else phaseGain*recovery
            val width = (settings.upper-settings.lower)*gain*(if(pattern==0) .65+.35*abs(sin(step*.45)) else 1.0)
            val center = (settings.lower+settings.upper)/2
            val target = (center + (if(forward) 1 else -1)*width/2).coerceIn(settings.lower,settings.upper)
            val base = settings.halfMs * (if(stage=="はじめ" || weak) 1.7 else 1.0) * (if(pattern==1 && step%3==0L) 1.6 else 1.0)
            // Limit generated legs to <= .05 normalized units/s. First real leg additionally needs a known position.
            val duration = maxOf(base.toLong(),(width*1000/.05).toLong(),2000L).coerceAtMost(20000)
            move = if(rest) null else LinearMove(target,duration)
            segmentEnd = time + if(rest) (if(stage=="はじめ") 6000 else 2500) else duration
        }
        frame = HandyFrame(true,weak,progress,stage,if(stage=="フィニッシュ") (progress-settings.introMs-settings.middleMs)/settings.finishMs+1 else 0,
            pattern,age,move,segmentEnd,rest)
        return frame
    }
}

/** One lane per device. Response acknowledgement and interval expiry are separate gates.
 * Not a stop implementation. Production never arms this lane while stop evidence is absent. */
internal class LinearLane(private val gapMs: Long) {
    private var generation = 0L
    private var pendingAt: Long? = null
    private var intervalEnd = 0L
    private var lastSend = Long.MIN_VALUE / 2
    private var previousTarget: Double? = null
    private var armed = false
    fun arm(knownPosition: Double) { require(knownPosition.isFinite() && knownPosition in 0.0..1.0); invalidate(); previousTarget = knownPosition; armed = true }
    fun invalidate() { generation++; armed = false; pendingAt = null; previousTarget = null; intervalEnd = 0 }
    fun timedOut(time: Long): Boolean {
        if (pendingAt?.let { time-it > 1500 } != true) return false
        invalidate()
        return true
    }
    fun offer(move: LinearMove, time: Long, send: (LinearMove, () -> Unit) -> Unit): Boolean {
        if (!armed || pendingAt != null || time < intervalEnd || time-lastSend < gapMs) return false
        require(move.target.isFinite() && move.target in .1.. .9 && move.durationMs in 2000..20000)
        require(abs(move.target-checkNotNull(previousTarget))*1000/move.durationMs <= .100001)
        val epoch = generation
        pendingAt = time; lastSend = time; intervalEnd = time+move.durationMs; previousTarget = move.target
        send(move) { if (generation==epoch) pendingAt = null }
        return true
    }
}
internal object HandyStopPolicy {
    const val reason = "Handy実機出力は無効：対応サーバーの専用停止経路・Firmware・移動途中停止が未確認です"
    // No persisted flag, server name, StopDeviceCmd advertisement or Ok can unlock this build.
    const val continuousOutputEnabled = false
}
