package jp.hyoutan.rosebud

import kotlin.math.*

/** Adjustable experiment defaults. Fractions describe command ranges, not physical movement. */
internal object MotionDefaults {
    const val holdMin = 30.0
    const val holdMax = 60.0
    const val introEnd = 300.0
    const val middleEnd = 720.0
    const val endlessStart = 900.0
    const val mixSettled = 2700.0
    const val repeatWeight = 0.2
    const val blendSeconds = 0.45
    const val resumeSeconds = 0.7
    const val quietDb = -60.0
    const val onsetDb = 4.0
    const val onsetGapMs = 180L
    const val featureWindowMs = 6000L
    const val classificationHoldMs = 1200L
    const val attackMs = 150.0
    const val releaseMs = 650.0
    const val regularityThreshold = .75
    const val smoothDbDifference = 2.0
    const val adaptationMs = 20000.0
    const val adaptiveLowDb = -36.0
    const val adaptiveHighDb = -12.0
    const val periodSlow = 4.8
    const val periodFast = 1.6
    const val periodSmoothing = 2.0
    const val quietGain = .22
    const val onsetPhaseNudge = .12
    const val initialCeiling = .35
    const val positiveFloor = .003
    const val restAVibration = .025
    const val restAMin = 0.8
    const val restAMax = 2.5
    const val restBMin = 0.3
    const val restBMax = 1.2
    val strongShare = doubleArrayOf(0.0, 0.2, 0.6, 0.9)
    val vibrationRanges = mapOf(Strength.ULTRA to (0.03..0.12), Strength.WEAK to (0.13..0.35), Strength.STRONG to (0.42..0.95))
    val ranges = mapOf(Strength.ULTRA to (0.035..0.15), Strength.WEAK to (0.16..0.40), Strength.STRONG to (0.48..1.0))
    val mix15 = doubleArrayOf(0.0, .10, .40, .50)
    val mix45 = doubleArrayOf(0.0, .05, .20, .75)
    val recipeStyleWeights = arrayOf(
        doubleArrayOf(1.5,1.3,1.2,1.0,.8,.8,1.3,.8),
        doubleArrayOf(1.3,1.2,1.2,1.0,1.0,1.0,1.2,1.0),
        doubleArrayOf(1.0,1.0,1.0,1.3,1.3,1.2,1.0,1.2),
        doubleArrayOf(.8,.8,1.0,1.3,1.2,1.4,1.0,1.4))
    val audioWeights = mapOf(
        SoundKind.SMOOTH to doubleArrayOf(4.0,1.0,1.0,1.0,3.0,1.0,4.0,1.0),
        SoundKind.REGULAR to doubleArrayOf(1.0,1.0,4.0,5.0,1.0,3.0,1.0,2.0),
        SoundKind.BROKEN to doubleArrayOf(1.0,5.0,3.0,2.0,1.0,3.0,1.0,5.0),
        SoundKind.QUIET to doubleArrayOf(4.0,1.0,2.0,1.0,2.0,2.0,4.0,1.0),
        SoundKind.UNCLEAR to doubleArrayOf(3.0,1.0,2.0,2.0,2.0,1.0,3.0,3.0))
}
internal enum class Strength(val label: String) { REST_A("レストA"), REST_B("レストB"), ULTRA("極弱"), WEAK("弱め"), STRONG("強め") }
internal enum class SoundKind(val label: String) { QUIET("静かな区間"), SMOOTH("滑らかな音"), REGULAR("規則的な音"), BROKEN("断続的な音"), UNCLEAR("曖昧な音") }
internal data class SoundFeatures(val kind: SoundKind = SoundKind.UNCLEAR, val level: Double = .5, val density: Double = 0.0,
    val regularity: Double = 0.0, val onsetAt: Long = -1, val db: Double = -120.0)

/** Consumes each short PCM block's RMS, retaining only levels and onset timestamps. */
internal class AudioFeatures {
    private val onsets = ArrayDeque<Long>()
    private var lastAt = -1L
    private var previousDb = -120.0
    private var level = 0.0
    private var highDb = -18.0
    private var kind = SoundKind.UNCLEAR
    private var candidate = SoundKind.UNCLEAR
    private var candidateAt = 0L
    var snapshot = SoundFeatures(); private set
    fun add(rms: Double, now: Long): SoundFeatures {
        require(rms.isFinite() && rms >= 0)
        val db = AudioStats.db(rms)
        val dt = if (lastAt < 0) 100.0 else (now - lastAt).coerceIn(0, 2000).toDouble()
        highDb += ((db.coerceIn(MotionDefaults.adaptiveLowDb, MotionDefaults.adaptiveHighDb)) - highDb) * (1-exp(-dt/MotionDefaults.adaptationMs))
        val target = ((db-MotionDefaults.quietDb)/(highDb-MotionDefaults.quietDb)).coerceIn(0.0,1.0)
        level += (target-level)*(1-exp(-dt/if(target>level) MotionDefaults.attackMs else MotionDefaults.releaseMs))
        if (db > MotionDefaults.quietDb && db-previousDb >= MotionDefaults.onsetDb &&
            (onsets.isEmpty() || now-onsets.last() >= MotionDefaults.onsetGapMs)) onsets.addLast(now)
        while (onsets.isNotEmpty() && now-onsets.first() > MotionDefaults.featureWindowMs) onsets.removeFirst()
        val intervals = onsets.zipWithNext { a,b -> (b-a).toDouble() }
        val mean = intervals.average()
        val regularity = if(intervals.size >= 3) (1-sqrt(intervals.sumOf { (it-mean).pow(2) }/intervals.size)/mean).coerceIn(0.0,1.0) else 0.0
        val next = when {
            db < MotionDefaults.quietDb -> SoundKind.QUIET
            intervals.size >= 3 && regularity > MotionDefaults.regularityThreshold -> SoundKind.REGULAR
            onsets.size >= 2 -> SoundKind.BROKEN
            abs(db-previousDb) < MotionDefaults.smoothDbDifference -> SoundKind.SMOOTH
            else -> SoundKind.UNCLEAR
        }
        if (next != candidate) { candidate = next; candidateAt = now }
        // Quieting bypasses classification hysteresis; a brief sound never replaces a held recipe.
        if (next == SoundKind.QUIET || now-candidateAt >= MotionDefaults.classificationHoldMs) kind = next
        previousDb = db; lastAt = now
        snapshot = SoundFeatures(kind, level, (onsets.size/6.0).coerceIn(0.0,1.0), regularity, onsets.lastOrNull() ?: -1, db)
        return snapshot
    }
}
internal enum class Shape { WAVE, TOUCH, SPARSE, DOUBLE, RISE, HOLD, UNRAVEL, CLUSTER }
internal data class Recipe(val title: String, val description: String, val sequence: String, val shape: Shape)
internal val recipes = listOf(
    Recipe("小さな揺らぎ","小さく波打って、広がり、また戻る。","極弱 → 弱め → 極弱",Shape.WAVE),
    Recipe("触れて離れる","短く動いて、間を置いて戻る。","極弱 → レストA/B → 弱め",Shape.TOUCH),
    Recipe("まばらな反復","一つの山と長い間、ときどき二連。","小パルス → 極弱 → 単発 / 二連",Shape.SPARSE),
    Recipe("ずれた二連","少し違う二つの山と、揺れる間隔。","小パルス → 間 → 別のパルス",Shape.DOUBLE),
    Recipe("上げて戻す","ゆっくり上げて、小さく戻る。","極弱 → 弱め → 短い強め → 極弱",Shape.RISE),
    Recipe("続けそうで戻る","反復の途中で、ふっと小さく。","反復 → 少し強め → 極弱 / レスト",Shape.HOLD),
    Recipe("途中でほどける波","波の途中で力を抜いて、また別の波。","波 → 極弱 → 長さを変えて波",Shape.UNRAVEL),
    Recipe("不規則なまとまり","数回の動きと長短の間。","2〜4回の断続 → 極弱 / レスト",Shape.CLUSTER))
internal data class EngineFrame(val output: Output, val recipe: Int, val strength: Strength, val style: Int,
    val recipeStart: Double, val recipeEnd: Double, val phase: String)
internal data class MotionSegment(val end: Double, val strength: Strength)

/** Randomness occurs only at boundaries; elapsed time is supplied by the monotonic session clock. */
internal class ScenarioEngine(seed: Long) {
    private val random = SeededRandom(seed)
    private var recipe = -1
    private var repeats = 0
    private var start = 0.0
    private var end = 0.0
    private var style = 0
    private var segments = emptyList<MotionSegment>()
    private var lastTime = 0.0
    private var smoothed = Output()
    private var period = 3.0
    private var phase = 0.0
    private var lastOnset = -1L
    private var progressOffset = 0.0
    /** Progress may jump forward; recipe holds and waveform timing always use actual elapsed time. */
    fun progressSeconds(elapsedSeconds: Double): Double {
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0.0)
        return elapsedSeconds + progressOffset
    }
    /** The controller owns scenario eligibility, pause state and stop/error interlocks. */
    fun skipTo(elapsedSeconds: Double, targetProgressSeconds: Double, sound: SoundFeatures): Boolean {
        if (recipe < 0 || !elapsedSeconds.isFinite() || elapsedSeconds < maxOf(lastTime, start) ||
            !targetProgressSeconds.isFinite() ||
            targetProgressSeconds !in listOf(MotionDefaults.introEnd, MotionDefaults.endlessStart) ||
            targetProgressSeconds <= progressSeconds(elapsedSeconds)) return false
        progressOffset = targetProgressSeconds - elapsedSeconds
        // A manual stage change is the sole exception to the current hold. Do not draw for skipped time.
        choose(elapsedSeconds, sound)
        return true
    }
    private fun draw(weights: DoubleArray): Int {
        var value = random.next()*weights.sum()
        for(i in weights.indices) { value -= weights[i]; if(value < 0) return i }
        return weights.lastIndex
    }
    private fun choose(at: Double, sound: SoundFeatures) {
        start = at
        end = at + MotionDefaults.holdMin + random.next()*(MotionDefaults.holdMax-MotionDefaults.holdMin)
        style = draw(styleWeights(progressSeconds(at)))
        val weights = MotionDefaults.audioWeights.getValue(sound.kind).mapIndexed { i,w -> w*MotionDefaults.recipeStyleWeights[style][i] }.toDoubleArray()
        if(recipe >= 0) weights[recipe] *= if(repeats >= 2) 0.0 else MotionDefaults.repeatWeight
        val next = draw(weights)
        repeats = if(next == recipe) repeats+1 else 1
        recipe = next
        val duration = end-start
        val cycles = 3 + (random.next()*3).toInt()
        val cycle = duration/cycles
        val strong = MotionDefaults.strongShare[style]*cycle
        val list = mutableListOf<MotionSegment>()
        var cursor = 0.0
        fun add(seconds: Double, state: Strength) { if(seconds > 0) { cursor+=seconds; list.add(MotionSegment(cursor,state)) } }
        repeat(cycles) { i ->
            val soft = cycle-strong
            val restKind = if((i+recipe)%2==0) Strength.REST_A else Strength.REST_B
            val restCandidate = if(recipe in listOf(1,5,7) || i==1) min(soft*.45, if(restKind==Strength.REST_A)
                MotionDefaults.restAMin+random.next()*(MotionDefaults.restAMax-MotionDefaults.restAMin)
                else MotionDefaults.restBMin+random.next()*(MotionDefaults.restBMax-MotionDefaults.restBMin)) else 0.0
            val minimumRest = if(restKind==Strength.REST_A) MotionDefaults.restAMin else MotionDefaults.restBMin
            val rest = if(restCandidate >= minimumRest) restCandidate else 0.0
            val weak = (soft-rest)*(.35+random.next()*.3)
            add(soft-rest-weak, Strength.ULTRA)
            add(weak, Strength.WEAK)
            add(strong, Strength.STRONG)
            add(rest,restKind)
        }
        segments = list
    }
    fun frame(seconds: Double, sound: SoundFeatures): EngineFrame {
        require(seconds.isFinite() && seconds >= maxOf(lastTime, start))
        if(recipe < 0) choose(0.0,sound)
        while(seconds >= end) choose(end,sound)
        val dt = (seconds-lastTime).coerceAtLeast(0.0)
        val targetPeriod = MotionDefaults.periodSlow+(MotionDefaults.periodFast-MotionDefaults.periodSlow)*sound.density
        period += (targetPeriod-period)*(1-exp(-dt/MotionDefaults.periodSmoothing))
        phase += dt/period
        // Onsets modestly advance the current pulse, without replacing recipe or deadline.
        if(sound.onsetAt != lastOnset && sound.onsetAt >= 0) { phase += MotionDefaults.onsetPhaseNudge; lastOnset = sound.onsetAt }
        val state = segments.firstOrNull { seconds-start < it.end }?.strength ?: segments.last().strength
        val wave = shape(recipes[recipe].shape, phase)
        val range = MotionDefaults.ranges[state]
        val progress = progressSeconds(seconds)
        val ceiling = MotionDefaults.initialCeiling+(1-MotionDefaults.initialCeiling)*(progress/MotionDefaults.endlessStart).coerceIn(0.0,1.0)
        val audioGain = if(sound.kind==SoundKind.QUIET) MotionDefaults.quietGain else .5+.5*sound.level
        val value = if(range==null) 0.0 else (range.start+(range.endInclusive-range.start)*wave)*ceiling*audioGain
        val vrange = MotionDefaults.vibrationRanges[state]
        val vibration = if(vrange==null) 0.0 else (vrange.start+(vrange.endInclusive-vrange.start)*(.5+.5*sin(phase*2*PI+.8)))*ceiling*audioGain
        val target = Output(vibration,value)
        val blend = 1-exp(-dt/MotionDefaults.blendSeconds)
        smoothed = Output(smoothed.vibrate+(target.vibrate-smoothed.vibrate)*blend,smoothed.flicker+(target.flicker-smoothed.flicker)*blend)
        lastTime = seconds
        // Scheduled rests apply after smoothing. Positive floor survives quantization, including at t=0.
        val out = when(state) {
            Strength.REST_A -> Output(MotionDefaults.restAVibration*ceiling,0.0)
            Strength.REST_B -> Output()
            else -> Output(smoothed.vibrate.coerceAtLeast(MotionDefaults.positiveFloor),smoothed.flicker.coerceAtLeast(MotionDefaults.positiveFloor))
        }
        return EngineFrame(out,recipe,state,style,start,end,phaseName(progress))
    }
    companion object {
        val styleNames = listOf("導入","中盤弱め","中盤強め","強め")
        fun phaseName(t: Double) = when { t<MotionDefaults.introEnd -> "導入"; t<MotionDefaults.middleEnd -> "中盤"; t<MotionDefaults.endlessStart -> "強めへ"; else -> "エンドレス" }
        fun styleWeights(t: Double): DoubleArray = when {
            t<MotionDefaults.introEnd -> doubleArrayOf(1.0,0.0,0.0,0.0)
            t<MotionDefaults.middleEnd -> { val p=(t-MotionDefaults.introEnd)/(MotionDefaults.middleEnd-MotionDefaults.introEnd); doubleArrayOf(0.0,.8-.4*p,.2+.4*p,0.0) }
            t<MotionDefaults.endlessStart -> { val p=(t-MotionDefaults.middleEnd)/(MotionDefaults.endlessStart-MotionDefaults.middleEnd); doubleArrayOf(0.0,.4-.3*p,.6-.2*p,.5*p) }
            else -> { val p=((t-MotionDefaults.endlessStart)/(MotionDefaults.mixSettled-MotionDefaults.endlessStart)).coerceIn(0.0,1.0)
                DoubleArray(4) { MotionDefaults.mix15[it]+(MotionDefaults.mix45[it]-MotionDefaults.mix15[it])*p } }
        }
        fun shape(shape: Shape, t: Double): Double {
            val u=t-floor(t)
            return when(shape) {
                Shape.WAVE -> .5+.32*sin(t*2*PI)+.12*sin(t*7.9)
                Shape.TOUCH -> if(u<.3) sin(u/.3*PI) else .03
                Shape.SPARSE -> exp(-((u-.2)/.075).pow(2))+.35*exp(-((u-.7)/.06).pow(2))
                Shape.DOUBLE -> exp(-((u-.25)/.09).pow(2))+.7*exp(-((u-.5)/.07).pow(2))
                Shape.RISE -> if(u<.72) u/.72 else (1-u)/.28
                Shape.HOLD -> if(u<.72) .6+.3*sin(t*9*PI) else .03
                Shape.UNRAVEL -> if(u in .38.. .6) .04 else .5+.4*sin(t*2*PI)
                Shape.CLUSTER -> (.45+.3*sin(t*17)+.22*sin(t*7.3)).coerceAtLeast(.02)
            }.coerceIn(0.0,1.0)
        }
    }
}
