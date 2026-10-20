package jp.hyoutan.rosebud

import kotlin.math.floor
import kotlin.math.roundToInt

internal enum class FlickerPhase { ON, REST_A, REST_B }
internal data class FlickerFrame(val output: Double, val pattern: Int, val patternStart: Double,
    val patternEnd: Double, val style: Int, val percent: Int, val unitStart: Double,
    val unitEnd: Double, val phase: FlickerPhase, val pendingStage: Boolean,
    val manualPattern: Int = -1, val pendingPattern: Boolean = false)

internal val flickerPatterns = listOf(
    Recipe("ゆっくり往復", "小さな往復の形へ、育つ強弱を重ねる。", "小 → 谷 → 少し上 → 小／2・2・3・2秒", Shape.WAVE),
    Recipe("触れて、間を置く", "触れる一動作から、動く谷へ。", "少し上 → 谷 → 小 → 谷／各2秒", Shape.TOUCH),
    Recipe("まばらな小波", "小さな反復へ、ときどき山を置く。", "小 → 谷 → 山 → 小／各2秒", Shape.SPARSE),
    Recipe("ずらした二連", "少し違う二つの山を、谷で区切る。", "山 → 谷 → 小さい山 → 小 → 谷／2・2・3・2・2秒", Shape.DOUBLE),
    Recipe("階段と戻り", "一動作ずつ上り、最初の小ささへ戻る。", "小 → 少し上 → 山 → 高い山 → 小／2・3・2・2・2秒", Shape.RISE),
    Recipe("短い山と余韻", "1秒の山のあとに、弱い余韻を残す。", "短い山1秒 → 余韻2秒 → 小2秒 → 谷2秒", Shape.HOLD),
    Recipe("ほどける波", "長めの動きがほどけ、谷から戻る。", "山 → 中 → 小 → 谷 → 別の山／3・2・2・2・2秒", Shape.UNRAVEL),
    Recipe("変わるまとまり", "長短の山と谷を、まとまりで連ねる。", "山2秒 → 小2秒 → 短い山1秒 → 中3秒 → 谷2秒", Shape.CLUSTER))

private data class FlickerUnit(val percent: Int, val seconds: Double, val phase: FlickerPhase = FlickerPhase.ON,
    val gapBias: Int = 0)
private fun on(percent: Int, seconds: Double) = FlickerUnit(percent, seconds)
private fun gap(phase: FlickerPhase = FlickerPhase.REST_A, bias: Int = 0) = FlickerUnit(0, 0.0, phase, bias)
private const val minimumBudgetRest = .8
private const val maximumBudgetRest = 3.0
// Adjustable initial arrangements; percentages are absolute commands, never audio-scaled.
private val flickerUnits = listOf(
    listOf(on(7,2.0),gap(),on(10,3.0),on(7,2.0)),
    listOf(on(10,2.0),gap(),on(7,2.0),gap(FlickerPhase.REST_B)),
    listOf(on(7,2.0),gap(bias=1),on(15,2.0),on(7,2.0)),
    listOf(on(15,2.0),gap(bias=-1),on(10,3.0),on(7,2.0),gap()),
    listOf(on(7,2.0),on(10,3.0),on(15,2.0),on(20,2.0),on(7,2.0)),
    listOf(on(40,1.0),on(15,2.0),on(7,2.0),gap()),
    listOf(on(15,3.0),on(10,2.0),on(7,2.0),gap(),on(20,2.0)),
    listOf(on(20,2.0),on(7,2.0),on(40,1.0),on(10,3.0),gap(FlickerPhase.REST_B)))

private val finishUnits = listOf(
    listOf(on(20,3.0),on(40,2.0),on(20,3.0),on(15,2.0)),
    listOf(on(40,2.0),on(20,3.0),on(40,2.0),on(15,2.0)),
    listOf(on(20,3.0),on(40,2.0),on(15,2.0),on(40,2.0)),
    listOf(on(40,2.0),on(20,2.0),on(40,2.0),on(20,3.0)),
    listOf(on(15,2.0),on(20,3.0),on(40,2.0),on(20,3.0)),
    listOf(on(40,2.0),on(20,3.0),on(40,2.0),on(15,2.0)),
    listOf(on(20,3.0),on(40,2.0),on(20,3.0),on(40,2.0),on(15,2.0)),
    listOf(on(40,2.0),on(15,2.0),on(40,2.0),on(20,3.0),on(40,2.0),on(20,3.0)))
private fun stageBucket(progress: Double) = when {
    progress < MotionDefaults.introEnd -> 0
    progress < MotionDefaults.endlessStart -> 1
    else -> 2
}
private fun bridge(percent: Int, finish: Boolean) = on(if (finish) {
    if (percent == 15) 20 else 15
} else if (percent == 7) 10 else 7,2.0)

/** Middle broad-wave example at progress 627s; budget rests are deliberately separate. */
internal fun sampleFlicker(pattern: Int, seconds: Double): Double {
    require(pattern in flickerUnits.indices && seconds.isFinite() && seconds >= 0.0)
    val range = FavoriteCrescendo.range(627.0)
    val units = flickerUnits[pattern]
    fun duration(unit: FlickerUnit) = if (unit.phase == FlickerPhase.ON) unit.seconds else 2.0
    val cycle = units.sumOf(::duration)
    val cycleStart = floor(seconds/cycle)*cycle
    var at = cycleStart
    for (unit in units) {
        if (seconds < at+duration(unit)) {
            val motif = if (unit.phase == FlickerPhase.ON) (unit.percent-7)/33.0 else 0.0
            val gain = FavoriteCrescendo.macroGain(FavoriteCrescendo.wave(pattern,at,false))
            return ((range.flickerFloor+(range.flickerPeak-range.flickerFloor)*motif*gain)*100).roundToInt()/100.0
        }
        at += duration(unit)
    }
    return range.flickerFloor
}

/** Independent monotonic clock and PRNG. Random draws happen only at unit/pattern boundaries. */
internal class FlickerTrack(seed: Long) {
    private val random = SeededRandom(seed)
    private var pattern = -1
    private var repeats = 0
    private var patternStart = 0.0
    private var patternEnd = 0.0
    private var style = 0
    private var cursor = 0
    private var unit = on(7, 2.0)
    private var unitStart = 0.0
    private var unitEnd = 0.0
    private var lastTime = 0.0
    private var pendingAt: Double? = null
    private var pendingPatternAt: Double? = null
    private var manualPattern = -1
    private var requestedRestSettings = RestSettings()
    private var activeRestSettings = requestedRestSettings
    private var budgetStage = -1
    private var restCredit = 0.0
    private var lastResumeAt = Double.NEGATIVE_INFINITY

    private fun draw(weights: DoubleArray): Int {
        var value = random.next() * weights.sum()
        for (i in weights.indices) { value -= weights[i]; if (value < 0.0) return i }
        return weights.lastIndex
    }
    private fun choose(at: Double, progress: Double, sound: SoundFeatures) {
        patternStart = at
        patternEnd = at + MotionDefaults.holdMin + random.next() * (MotionDefaults.holdMax - MotionDefaults.holdMin)
        style = draw(ScenarioEngine.styleWeights(progress))
        val weights = MotionDefaults.audioWeights.getValue(sound.kind).mapIndexed { i, w ->
            w * MotionDefaults.recipeStyleWeights[style][i]
        }.toDoubleArray()
        if (pattern >= 0) weights[pattern] *= if (repeats >= 2) 0.0 else MotionDefaults.repeatWeight
        val next = if (manualPattern >= 0) manualPattern else draw(weights)
        repeats = if (next == pattern) repeats + 1 else 1
        pattern = next
        cursor = 0
    }
    private fun middleUnit(source: FlickerUnit, at: Double, progress: Double, sound: SoundFeatures): FlickerUnit {
        val range = FavoriteCrescendo.range(progress)
        val wave = FavoriteCrescendo.wave(pattern,at,false)
        val motif = if (source.phase == FlickerPhase.ON) (source.percent-7)/33.0 else 0.0
        val gain = FavoriteCrescendo.macroGain(wave)
        val level = if (sound.level.isFinite()) sound.level.coerceIn(0.0,1.0) else 0.0
        val audioGain = .8+.2*level
        val percent = ((range.flickerFloor+(range.flickerPeak-range.flickerFloor)*motif*gain*audioGain)*100).roundToInt()
        return on(percent,if(source.phase == FlickerPhase.ON) source.seconds else 2.0)
    }
    private fun finishUnit(source: FlickerUnit, at: Double): FlickerUnit {
        val percent = when (source.percent) {
            40 -> (80.0+20.0*FavoriteCrescendo.wave(pattern,at,false)).roundToInt()
            20 -> 40
            else -> 20
        }
        return on(percent,source.seconds)
    }
    fun setRestSettings(settings: RestSettings) { requestedRestSettings = settings }

    private fun startUnit(at: Double, progress: Double, sound: SoundFeatures,
        previous: FlickerPhase?, completedOnSeconds: Double = 0.0) {
        val stage = stageBucket(progress)
        val reset = stage != budgetStage || requestedRestSettings != activeRestSettings
        if (reset) {
            budgetStage = stage
            activeRestSettings = requestedRestSettings
            restCredit = 0.0
        }
        val percent = when (stage) { 0 -> activeRestSettings.intro; 1 -> activeRestSettings.middle; else -> activeRestSettings.finish }
        // A boundary change starts a fresh budget. The preceding ON belongs to the old profile.
        if (!reset) restCredit += completedOnSeconds * percent / (100.0-percent)
        if (percent == 0) restCredit = 0.0
        if (restCredit >= minimumBudgetRest) {
            val seconds = minOf(restCredit,maximumBudgetRest)
            restCredit -= seconds
            val phase = if (stage == 1 && pattern in listOf(1,7)) FlickerPhase.REST_B else FlickerPhase.REST_A
            unit = FlickerUnit(0,seconds,phase)
        } else {
            val quiet = sound.kind == SoundKind.QUIET
            val units = if (stage == 2) finishUnits[pattern] else flickerUnits[pattern]
            val source = units[cursor % units.size]
            val candidate = when {
                quiet -> if (stage == 2) on(if (previous == FlickerPhase.ON && unit.percent == 15) 20 else 15,3.0)
                    else on(if (previous == FlickerPhase.ON && unit.percent == 7) 10 else 7,2.0)
                stage == 0 -> on(if (source.percent == 7 || source.phase != FlickerPhase.ON) 7 else 10,2.0)
                stage == 2 -> finishUnit(source,at)
                else -> middleUnit(source,at,progress,sound)
            }
            unit = if (previous == FlickerPhase.ON && candidate.percent == unit.percent) {
                if (stage == 0 || quiet) bridge(candidate.percent,stage == 2) else {
                    // Preserve the broad wave instead of dropping to a 7% separator.
                    if (!quiet) cursor++
                    val peak = if (stage == 2) 100 else (FavoriteCrescendo.range(progress).flickerPeak*100).roundToInt()
                    candidate.copy(percent=if(candidate.percent >= peak) candidate.percent-1 else candidate.percent+1)
                }
            } else {
                if (!quiet) cursor++
                candidate
            }
        }
        unitStart = at
        unitEnd = at + unit.seconds
    }
    fun frame(actualSeconds: Double, progressSeconds: Double, sound: SoundFeatures): FlickerFrame {
        require(actualSeconds.isFinite() && actualSeconds >= lastTime)
        require(progressSeconds.isFinite() && progressSeconds >= 0.0)
        fun progress(at: Double) = (progressSeconds - actualSeconds + at).coerceAtLeast(0.0)
        if (pattern < 0) {
            choose(0.0, progress(0.0), sound)
            startUnit(0.0, progress(0.0), sound, null)
        }
        // A request during a gap takes effect at its explicit actual timestamp, without waiting for it.
        val request = listOfNotNull(pendingAt,pendingPatternAt).maxOrNull()
        if (request != null && unit.phase != FlickerPhase.ON && request < unitEnd) {
            restCredit += unitEnd-request // Return the portion of a scheduled rest not actually consumed.
            choose(request, progress(request), sound)
            startUnit(request, progress(request), sound, unit.phase)
            pendingAt = null
            pendingPatternAt = null
        }
        while (actualSeconds >= unitEnd) {
            val at = unitEnd
            val previous = unit.phase
            val completedOnSeconds = if (previous == FlickerPhase.ON) unitEnd-unitStart else 0.0
            val requested = pendingAt?.let { at >= it } == true || pendingPatternAt?.let { at >= it } == true
            if (requested || at >= patternEnd) {
                choose(at, progress(at), sound)
                if (requested) { pendingAt = null; pendingPatternAt = null }
            }
            startUnit(at, progress(at), sound, previous,completedOnSeconds)
        }
        lastTime = actualSeconds
        return FlickerFrame(unit.percent / 100.0, pattern, patternStart, patternEnd, style,
            unit.percent, unitStart, unitEnd, unit.phase, pendingAt != null,manualPattern,pendingPatternAt != null)
    }
    fun selectPattern(actual: Double, index: Int, @Suppress("UNUSED_PARAMETER") sound: SoundFeatures): Boolean {
        if (pattern < 0 || !actual.isFinite() || actual < lastTime || index !in -1..flickerPatterns.lastIndex ||
            index == manualPattern) return false
        manualPattern = index
        pendingPatternAt = actual
        lastTime = actual
        return true
    }
    fun requestStageSkip(actualSeconds: Double) {
        require(actualSeconds.isFinite() && actualSeconds >= lastTime)
        if (pattern >= 0 && pendingAt == null) pendingAt = actualSeconds
        lastTime = actualSeconds
    }
    fun resumeFromPause(actualSeconds: Double) {
        require(actualSeconds.isFinite() && actualSeconds >= lastTime)
        if (pattern < 0 || actualSeconds == lastResumeAt) return
        lastResumeAt = actualSeconds
        if (unit.phase == FlickerPhase.ON) {
            unitStart = actualSeconds
            unitEnd = actualSeconds + unit.seconds
        }
        lastTime = actualSeconds
    }
}
