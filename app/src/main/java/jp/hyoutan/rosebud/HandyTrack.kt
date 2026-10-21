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
    val move: LinearMove? = null, val segmentEndMs: Long = 0, val plannedRest: Boolean = false,
    val speedMmPerSec: Double = 32.0)

/** Position windows and explicit physical speeds. The service derives duration from actual distance,
 * then completeSegment advances immediately on arrival. No scheduled endpoint dwell is required.
 * Own PRNG and progress clock; clockFrame never consumes movement steps or random values. */
internal class HandyTrack(seed: Long, val settings: HandySettings = HandySettings()) {
    companion object {
        val names = listOf("全体をゆっくり", "上側をゆっくり", "下側をゆっくり",
            "上側の加速", "全体の緩急", "下側の弱強", "小刻みな往復", "上側と下側", "二連の小刻み", "全体と小刻み",
            "全体を速く", "上下を速く", "小刻みを速く")
        fun candidates(stage: String): IntRange = when(stage) { "はじめ" -> 0..2; "中間" -> 3..9; else -> 10..12 }
    }
    private val random = SeededRandom(seed xor 0x48414e4459L)
    private val waveSeed = seed xor 0x53545245414dL
    private var wave: HandyWave? = null
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
    private var previousSpeed = 32.0
    private var speedAt = 0L
    var frame = HandyFrame(); private set
    fun start(time: Long) {
        running = true; last = time; progress = 0; age = 0; segmentEnd = time; step = 0; weak = false
        forward = false; resumeAt = null; previousTarget = (settings.lower+settings.upper)/2
        previousSpeed = 32.0; speedAt = time; wave = null
        frame = HandyFrame(running = true)
    }
    fun stop() { running = false; weak = false; frame = frame.copy(running = false, weak = false, move = null) }
    fun weaken(value: Boolean, time: Long) {
        if(!running || value == weak) return
        clockFrame(time)
        if(value) weakCenter = previousTarget else resumeAt = time
        weak = value
        segmentEnd = time
    }
    fun alignPosition(normalized: Double) {
        require(normalized.isFinite() && normalized in 0.0..1.0)
        previousTarget = normalized.coerceIn(settings.lower,settings.upper)
        wave = null
        if(weak) weakCenter = previousTarget
    }
    /** Caller first stops the physical leg and reads position before asking for a fresh segment. */
    fun interruptSegment(time: Long) { segmentEnd = time; frame = frame.copy(move = null, segmentEndMs = time) }
    /** Actual arrival is independent of an earlier software estimate. */
    fun completeSegment(time: Long) { if(running) interruptSegment(time) }
    fun selectStage(stage: String, time: Long): Boolean {
        if(!running || weak || stage !in listOf("はじめ","中間","フィニッシュ")) return false
        clockFrame(time)
        progress = when(stage) { "はじめ" -> 0; "中間" -> settings.introMs; else -> settings.introMs+settings.middleMs }
        age = 0; step = 0; resumeAt = time
        interruptSegment(time)
        clockFrame(time)
        return true
    }
    fun finish(time: Long) { selectStage("フィニッシュ",time) }
    private fun currentStage() = when {
        progress < settings.introMs -> "はじめ"
        progress < settings.introMs+settings.middleMs -> "中間"
        else -> "フィニッシュ"
    }
    /** Refresh only the clock/status; preserve the segment, direction, PRNG and last audio decision. */
    fun clockFrame(time: Long): HandyFrame {
        if(!running) return frame
        val delta = (time-last).coerceAtLeast(0); last = maxOf(time,last)
        if(!weak) { progress += delta; age += delta }
        val stage = currentStage()
        frame = frame.copy(running = true, weak = weak, elapsedMs = progress, ageMs = age, stage = stage,
            cycle = if(stage=="フィニッシュ") (progress-settings.introMs-settings.middleMs)/settings.finishMs+1 else 0)
        return frame
    }
    /** Streaming path: output is a continuous future sample, not an endpoint-and-wait leg.
     * Call alignPosition only after an explicit stop/resync; routine state polls must preserve phase. */
    fun sampleTrajectory(time: Long, sound: SoundFeatures, currentNormalized: Double,
        physicalSpanMm: Double=80.0, horizonMs: Long=200): HandyFrame {
        require(currentNormalized.isFinite() && currentNormalized in 0.0..1.0)
        if(!running) return frame
        lastSound=sound
        clockFrame(time)
        val stage=currentStage()
        val pool=candidates(stage)
        if(step==0L || !weak && (pattern !in pool || age>=20000 && (sound.kind!=selectedSound || age>=80000))) {
            val affinities=when(sound.kind) {
                SoundKind.SMOOTH -> setOf(0,3,4,7,11)
                SoundKind.REGULAR -> setOf(1,5,8,10)
                SoundKind.BROKEN -> setOf(2,6,9,12)
                SoundKind.QUIET -> setOf(0,2,4,6,11)
                else -> emptySet()
            }
            val scores=pool.map { i -> settings.normalizedWeights[i]*(if(i in affinities) 3.0 else 1.0)*(if(i==pattern && step>0) .35 else 1.0) }
            var draw=random.next()*scores.sum()
            pattern=pool.firstOrNull { draw-=scores[it-pool.first];draw<=0 } ?: pool.last
            age=0;selectedSound=sound.kind;step++
        }
        val speedWave=.5+.5*sin(age/4700.0+pattern*.41)
        val soundGain=if(sound.kind==SoundKind.QUIET) .9 else 1.0+.05*(sound.density.takeIf { it.isFinite() } ?: 0.0).coerceIn(0.0,1.0)
        val middleProgress=((progress-settings.introMs).toDouble()/settings.middleMs).coerceIn(0.0,1.0)
        var peak=when(stage) {
            "はじめ" -> (36+24*speedWave)*soundGain
            "中間" -> (75+75*speedWave)*(.85+.25*middleProgress)*soundGain
            else -> (180+80*speedWave)*soundGain
        }.coerceIn(32.0,if(stage=="はじめ") 60.0 else if(stage=="中間") 150.0 else 260.0)
        val recovery=resumeAt?.let { ((time-it)/3000.0).coerceIn(0.0,1.0) } ?: 1.0
        if(weak) peak=32.0 else peak=32+(peak-32)*recovery
        val planner=wave ?: HandyWave(waveSeed,currentNormalized.coerceIn(settings.lower,settings.upper),time).also { wave=it }
        val point=planner.sample(time,pattern,peak,physicalSpanMm,settings.lower,settings.upper,weak,horizonMs,currentNormalized)
        frame=frame.copy(pattern=pattern,ageMs=age,speedMmPerSec=point.speedMmPerSec,
            move=if(point.plannedRest) null else LinearMove(point.targetPosition,point.suggestedHorizonMs),
            segmentEndMs=time+point.suggestedHorizonMs,plannedRest=point.plannedRest)
        return frame
    }
    fun tick(time: Long, sound: SoundFeatures): HandyFrame {
        if(!running) return frame
        lastSound = sound
        clockFrame(time)
        val stage = currentStage()
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
            val seconds = age/1000.0
            // Position and speed have different clocks/phases: the same area can feel slow or fast,
            // while the same speed can visit upper/lower areas. Audio only nudges this combination.
            val placeWave = .5+.5*sin(seconds/11.0+pattern*.7)
            val speedWave = .5+.5*sin(seconds/4.7+pattern*.41)
            val motif = (seconds/7).toLong()
            // Every candidate defines a vertical window, independent of the speed envelope.
            // Indices stay stable so existing thirteen preference keys retain their positions.
            var windowLow: Double
            var windowHigh: Double
            var speed: Double
            when(pattern) {
                0 -> { windowLow=.05; windowHigh=.95; speed=32.0+8*speedWave }
                1 -> { windowLow=.60; windowHigh=.95; speed=35.0+15*speedWave }
                2 -> { windowLow=.05; windowHigh=.40; speed=32.0+28*speedWave }
                3 -> { windowLow=.60; windowHigh=.95; speed=70.0+60*speedWave }
                4 -> { windowLow=.05; windowHigh=.95; speed=60.0+45*speedWave }
                5 -> { windowLow=.05; windowHigh=.40; speed=70.0+70*speedWave }
                6 -> { val center=.2+.6*placeWave; windowLow=center-.09; windowHigh=center+.09; speed=90.0+45*speedWave }
                7 -> { windowLow=if(motif%4<2) .05 else .60; windowHigh=if(motif%4<2) .40 else .95; speed=80.0+45*speedWave }
                8 -> { val center=if(motif%4<2) .25 else .75; windowLow=center-.10; windowHigh=center+.10; speed=65.0+85*speedWave }
                9 -> { val small=motif%4<2; windowLow=if(small) .38 else .05; windowHigh=if(small) .62 else .95; speed=90.0+60*speedWave }
                10 -> { windowLow=.05; windowHigh=.95; speed=180.0+80*speedWave }
                11 -> { windowLow=if(motif%4<2) .05 else .60; windowHigh=if(motif%4<2) .40 else .95; speed=170.0+80*speedWave }
                else -> { val center=.2+.6*placeWave; windowLow=center-.10; windowHigh=center+.10; speed=160.0+100*speedWave }
            }
            val floor = when(stage) { "はじめ" -> 32.0; "中間" -> 60.0; else -> 160.0 }
            val ceiling = when(stage) { "はじめ" -> 60.0; "中間" -> 150.0; else -> 260.0 }
            val density = sound.density.takeIf { it.isFinite() }?.coerceIn(0.0,1.0) ?: 0.0
            val regularity = sound.regularity.takeIf { it.isFinite() }?.coerceIn(0.0,1.0) ?: 0.0
            val soundGain = if(sound.kind==SoundKind.QUIET) .9 else .96+.06*density+.04*regularity
            // The middle builds gradually, keeping its shorter independent rises and valleys.
            val middleProgress = ((progress-settings.introMs).toDouble()/settings.middleMs).coerceIn(0.0,1.0)
            val longTrend = if(stage=="中間") .85+.25*middleProgress else 1.0
            speed = (speed*soundGain*longTrend).coerceIn(floor,ceiling)
            val recovery = resumeAt?.let { ((time-it)/3000.0).coerceIn(0.0,1.0) } ?: 1.0
            if(!weak && recovery<1) speed = 32+(speed-32)*recovery
            // Smooth candidate/stage changes instead of switching instantly to a new velocity.
            val speedDelta = maxOf(1.0,(time-speedAt).coerceAtLeast(0)*.06)
            speed = speed.coerceIn(previousSpeed-speedDelta,previousSpeed+speedDelta)
            if(weak) speed = 32.0
            // Rest is an occasional short event, never an implicit wait after arriving.
            // Three candidates deliberately have no planned rest at all.
            val restChance = when {
                weak || pattern in setOf(0,4,10) -> 0.0
                stage=="はじめ" -> .06
                stage=="中間" -> .035
                else -> .022
            }
            rest = !frame.plannedRest && random.next()<restChance
            val restMs = when(stage) { "はじめ" -> 300L; "中間" -> 250L; else -> 200L } + (random.next()*when(stage) { "はじめ" -> 350; "中間" -> 200; else -> 100 }).toLong()
            step++
            if(!rest) forward = !forward
            val width = settings.upper-settings.lower
            val desired = if(weak) weakCenter+(if(forward) 1 else -1)*width*.07
                else settings.lower+width*(if(forward) windowHigh else windowLow)
            // Recover speed over three seconds without creating a zero-distance waiting leg.
            val target = desired.coerceIn(settings.lower,settings.upper)
            // UI/legacy estimate only (85mm scale). BLE always uses its current physical distance.
            val duration = (abs(target-previousTarget)*85/speed*1000).toLong().coerceIn(40,20000)
            move = if(rest) null else LinearMove(target,duration)
            if(move != null) { previousTarget = target; previousSpeed = speed; speedAt = time }
            segmentEnd = time+if(rest) restMs else duration
            frame = frame.copy(speedMmPerSec = speed)
        }
        frame = HandyFrame(true,weak,progress,stage,if(stage=="フィニッシュ") (progress-settings.introMs-settings.middleMs)/settings.finishMs+1 else 0,
            pattern,age,move,segmentEnd,rest,frame.speedMmPerSec)
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
