package jp.hyoutan.rosebud

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sin

/** Normalized positions are mapped to the user's physical limit by the BLE service. */
internal data class HandySettings(val introMs: Long = 480000, val middleMs: Long = 420000, val finishMs: Long = 300000,
    val lower: Double = 0.0, val upper: Double = 1.0, val halfMs: Long = 3000,
    val weights: List<Int> = List(13) { 100 }) {
    init {
        require(introMs in 60000..3600000 && middleMs in 60000..3600000 && finishMs in 60000..3600000)
        require(lower.isFinite() && upper.isFinite() && lower >= 0 && upper <= 1 && upper > lower)
        require(halfMs in 250..20000 && weights.size in listOf(3,13) && weights.all { it in 10..200 })
    }
    /** Old three-pattern preferences retain their wave / double / pause affinities. */
    val normalizedWeights: List<Int> get() = if(weights.size == 13) weights else
        listOf(0,1,2,0,0,2,2,0,1,1,1,0,2).map { weights[it] }
}
internal data class LinearMove(val target: Double, val durationMs: Long) {
    fun body(device: Int, feature: Int) = JSONObject().put("DeviceIndex",device).put("Vectors",JSONArray()
        .put(JSONObject().put("Index",feature).put("Duration",durationMs).put("Position",target)))
}
internal data class HandyFrame(val running: Boolean = false, val weak: Boolean = false, val elapsedMs: Long = 0,
    val stage: String = "待機", val cycle: Long = 0, val pattern: Int = 0, val ageMs: Long = 0,
    val move: LinearMove? = null, val segmentEndMs: Long = 0, val plannedRest: Boolean = false)

/** Each segment is a software schedule, not a claim that hardware moves for the entire duration.
 * Own PRNG and progress clock; a delayed tick produces one segment without replaying a backlog. */
internal class HandyTrack(seed: Long, val settings: HandySettings = HandySettings()) {
    companion object {
        val names = listOf("ゆっくり幅の波", "やさしい反復", "動きと長めの間",
            "上り坂と短い戻り", "大きな山と谷", "弱強のまとまり", "反復と抜き", "幅の行き来", "二連と余韻", "変形する反復",
            "安定した反復", "幅の波", "反復密度の波")
        fun candidates(stage: String): IntRange = when(stage) { "はじめ" -> 0..2; "中間" -> 3..9; else -> 10..12 }
    }
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
    private var resumeAt: Long? = null
    private var running = false
    private var weak = false
    private var weakCenter = (settings.lower+settings.upper)/2
    private var previousTarget = weakCenter
    var frame = HandyFrame(); private set
    fun start(time: Long) {
        running = true; last = time; progress = 0; age = 0; segmentEnd = time; step = 0; weak = false
        forward = false; resumeAt = null; previousTarget = (settings.lower+settings.upper)/2
        frame = HandyFrame(running = true)
    }
    fun stop() { running = false; weak = false; frame = frame.copy(running = false, weak = false, move = null) }
    fun weaken(value: Boolean, time: Long) {
        if(!running || value == weak) return
        tick(time,lastSound)
        if(value) weakCenter = previousTarget else resumeAt = time
        weak = value
        segmentEnd = time
    }
    fun alignPosition(normalized: Double) {
        require(normalized.isFinite() && normalized in 0.0..1.0)
        previousTarget = normalized.coerceIn(settings.lower,settings.upper)
        if(weak) weakCenter = previousTarget
    }
    /** Caller first stops the physical leg and reads position before asking for a fresh segment. */
    fun interruptSegment(time: Long) { segmentEnd = time; frame = frame.copy(move = null, segmentEndMs = time) }
    fun finish(time: Long) {
        if(running && !weak) { tick(time,lastSound); progress = settings.introMs+settings.middleMs }
    }
    fun tick(time: Long, sound: SoundFeatures): HandyFrame {
        if(!running) return frame
        lastSound = sound
        val delta = (time-last).coerceAtLeast(0); last = maxOf(time,last)
        if(!weak) { progress += delta; age += delta }
        val stage = when { progress < settings.introMs -> "はじめ"; progress < settings.introMs+settings.middleMs -> "中間"; else -> "フィニッシュ" }
        var move = frame.move
        var rest = frame.plannedRest
        if(time >= segmentEnd) {
            val pool = candidates(stage)
            if(step == 0L || (!weak && (pattern !in pool || age >= 20000 && (sound.kind != selectedSound || age >= 80000)))) {
                // Audio selects the kind of movement, rather than directly copying loudness.
                val affinities = when(sound.kind) {
                    SoundKind.SMOOTH -> setOf(0,3,4,7,11)
                    SoundKind.REGULAR -> setOf(1,5,8,10)
                    SoundKind.BROKEN -> setOf(2,6,9,12)
                    SoundKind.QUIET -> setOf(0,2,4,6,11)
                    SoundKind.UNCLEAR -> emptySet()
                }
                val scores = pool.map { i -> settings.normalizedWeights[i] * (if(i in affinities) 3.0 else 1.0) * (if(i==pattern && step>0) .35 else 1.0) }
                var draw = random.next()*scores.sum()
                pattern = pool.firstOrNull { draw -= scores[it-pool.first]; draw <= 0 } ?: pool.last
                selectedSound = sound.kind; age = 0
            }
            step++; forward = !forward
            val wave = .5+.5*sin(step*.45)
            val middleProgress = ((progress-settings.introMs).toDouble()/settings.middleMs).coerceIn(0.0,1.0)
            var gain = when(stage) { "はじめ" -> .22+.18*wave; "中間" -> .45+.45*middleProgress; else -> .85 }
            var pace = when(stage) { "はじめ" -> 2.5; "中間" -> 1.4-.5*middleProgress; else -> .7 }
            rest = false
            var restMs = 2000L
            when(pattern) {
                0 -> gain *= .35+.65*wave
                1 -> { gain *= if(step%6<3) .55 else 1.0; pace *= 1.25 }
                2 -> { rest = step%4==0L; restMs = if(random.next()<.5) 2500 else 7000 }
                3 -> { gain *= .45+.55*(step%8)/7.0; if(step%8==0L) gain *= .5 }
                4 -> { gain *= .25+.75*wave; pace *= 1.0+.7*(1-wave) }
                5 -> { val strong=step%10>=4; gain *= if(strong) 1.0 else .35; pace *= if(strong) .8 else 1.7 }
                6 -> { rest = step%8==0L; restMs = 1800+(sound.density.coerceIn(0.0,1.0)*2200).toLong() }
                7 -> gain *= .2+.8*wave
                8 -> { if(step%3==0L) { gain *= .55; pace *= 2.4 } else pace *= .8 }
                9 -> { gain *= .4+.6*((step%7)/6.0); pace *= .7+.6*((step%5)/4.0) }
                10 -> { gain *= .9+.1*wave; pace *= if(step%12<8) 1.0 else 1.3 }
                11 -> { gain *= .55+.45*wave; pace *= .85+.3*(1-wave) }
                12 -> { pace *= if(step%12<8) .75 else 1.7; gain *= .8+.2*wave }
            }
            // Quiet audio remains a gentle movement; explicit rests are independent of silence.
            if(sound.kind == SoundKind.QUIET) { gain *= .65; pace *= 1.35 }
            val width = settings.upper-settings.lower
            val center = (settings.lower+settings.upper)/2
            val desired = if(weak) weakCenter+(if(forward) 1 else -1)*width*.035
                else center+(if(forward) 1 else -1)*width*gain.coerceIn(0.0,1.0)/2
            val recovery = resumeAt?.let { ((time-it)/3000.0).coerceIn(0.0,1.0) } ?: 1.0
            val target = (if(!weak && recovery<1) previousTarget+(desired-previousTarget)*recovery else desired).coerceIn(settings.lower,settings.upper)
            if(weak) { rest = false; pace = 2.5 }
            val duration = (settings.halfMs*pace).toLong().coerceIn(250,20000).let {
                if(!weak && recovery<1) minOf(it,500L) else it
            }
            move = if(rest) null else LinearMove(target,duration)
            if(move != null) previousTarget = target
            segmentEnd = time+if(rest) restMs else duration
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
    const val reason = "Handyは専用タブからスマホへ直接接続してください（Intiface経由のHandy出力は無効）"
    // No persisted flag, server name, StopDeviceCmd advertisement or Ok can unlock this build.
    const val continuousOutputEnabled = false
}
