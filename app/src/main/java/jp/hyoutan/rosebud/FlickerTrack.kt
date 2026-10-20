package jp.hyoutan.rosebud

internal enum class FlickerPhase { ON, REST_A, REST_B }
internal data class FlickerFrame(val output: Double, val pattern: Int, val patternStart: Double,
    val patternEnd: Double, val style: Int, val percent: Int, val unitStart: Double,
    val unitEnd: Double, val phase: FlickerPhase, val pendingStage: Boolean)

internal val flickerPatterns = listOf(
    Recipe("ゆっくり往復", "小さな一動作から、ゆっくり往復。", "7%・2秒 → 間A → 10%・3秒 → 7%・2秒", Shape.WAVE),
    Recipe("触れて、間を置く", "動かしてから、間を大切に。", "10%・2秒 → 間A → 7%・2秒 → 間B", Shape.TOUCH),
    Recipe("まばらな小波", "長めの間に、小さな山を置く。", "7%・2秒 → 長めの間A → 15%・2秒 → 7%・2秒", Shape.SPARSE),
    Recipe("ずらした二連", "違う長さの二つの動き。", "15%・2秒 → 短めの間A → 10%・3秒 → 7%・2秒 → 間A", Shape.DOUBLE),
    Recipe("階段と戻り", "一動作ずつ上げ、小さく戻る。", "7%・2秒 → 10%・3秒 → 15%・2秒 → 20%・2秒 → 7%・2秒", Shape.RISE),
    Recipe("短い山と余韻", "短い山のあとに、弱い余韻。", "40%・1秒 → 15%・2秒 → 7%・2秒 → 間A", Shape.HOLD),
    Recipe("ほどける波", "徐々にほどけ、間から戻る。", "15%・3秒 → 10%・2秒 → 7%・2秒 → 間A → 20%・2秒", Shape.UNRAVEL),
    Recipe("変わるまとまり", "長短の一動作を混ぜる。", "20%・2秒 → 7%・2秒 → 40%・1秒 → 10%・3秒 → 間B", Shape.CLUSTER))

private data class FlickerUnit(val percent: Int, val seconds: Double, val phase: FlickerPhase = FlickerPhase.ON,
    val gapBias: Int = 0)
private fun on(percent: Int, seconds: Double) = FlickerUnit(percent, seconds)
private fun gap(phase: FlickerPhase = FlickerPhase.REST_A, bias: Int = 0) = FlickerUnit(0, 0.0, phase, bias)
private const val repeatedGapMax = 1.2
private fun repeatedGapSeconds(position: Double) = MotionDefaults.restAMin +
    (minOf(MotionDefaults.restAMax, repeatedGapMax) - MotionDefaults.restAMin) * position
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

private fun gapSeconds(unit: FlickerUnit, position: Double): Double {
    val low = if (unit.phase == FlickerPhase.REST_B) MotionDefaults.restBMin else MotionDefaults.restAMin
    val high = if (unit.phase == FlickerPhase.REST_B) MotionDefaults.restBMax else MotionDefaults.restAMax
    val fraction = when (unit.gapBias) { 1 -> .5 + position * .5; -1 -> position * .5; else -> position }
    return low + (high - low) * fraction.coerceIn(0.0, 1.0)
}

/** Static style-2 example only. Calling this cannot alter a running track or send commands. */
internal fun sampleFlicker(pattern: Int, seconds: Double): Double {
    require(pattern in flickerUnits.indices && seconds.isFinite() && seconds >= 0.0)
    val source = flickerUnits[pattern]
    val units = source.flatMapIndexed { i, unit ->
        val next = source[(i+1) % source.size]
        if (unit.phase == FlickerPhase.ON && next.phase == FlickerPhase.ON && unit.percent == next.percent)
            listOf(unit, FlickerUnit(0,repeatedGapSeconds(.5),FlickerPhase.REST_A)) else listOf(unit)
    }
    fun duration(unit: FlickerUnit) = if (unit.seconds > 0.0) unit.seconds else gapSeconds(unit, .5)
    var at = seconds % units.sumOf(::duration)
    for (unit in units) {
        if (at < duration(unit)) return unit.percent / 100.0
        at -= duration(unit)
    }
    return units.first().percent / 100.0
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
        val next = draw(weights)
        repeats = if (next == pattern) repeats + 1 else 1
        pattern = next
        cursor = 0
    }
    private fun transformed(source: FlickerUnit): FlickerUnit {
        if (source.phase != FlickerPhase.ON) return source
        return when (style) {
            0 -> when (source.percent) { 20 -> on(10,3.0); 40 -> on(15,2.0); else -> source }
            1 -> if (source.percent == 40) on(20,2.0) else source
            3 -> when (source.percent) { 7,15 -> on(15,2.0); 10 -> on(20,2.0); else -> on(40,1.0) }
            else -> source
        }
    }
    private fun startUnit(at: Double, sound: SoundFeatures, previous: FlickerPhase?) {
        var repeatedGap = false
        val source = if (sound.kind == SoundKind.QUIET) {
            if (previous == FlickerPhase.ON) gap(bias=1) else on(7,2.0)
        } else {
            val units = flickerUnits[pattern]
            val next = transformed(units[cursor % units.size])
            if (previous == FlickerPhase.ON && next.phase == FlickerPhase.ON && next.percent == unit.percent) {
                repeatedGap = true
                gap()
            } else {
                cursor++
                next
            }
        }
        unit = source
        unitStart = at
        val density = if (sound.density.isFinite()) sound.density.coerceIn(0.0,1.0) else 0.0
        val duration = if (unit.phase == FlickerPhase.ON) unit.seconds else if (repeatedGap) {
            repeatedGapSeconds(random.next())
        } else {
            val position = (random.next() * .5 + (1.0 - density) * .5).coerceIn(0.0,1.0)
            gapSeconds(unit, position)
        }
        unitEnd = at + duration
        // Complete every unit, including gaps: no clipped sub-100ms intervals at a hold boundary.
    }
    fun frame(actualSeconds: Double, progressSeconds: Double, sound: SoundFeatures): FlickerFrame {
        require(actualSeconds.isFinite() && actualSeconds >= lastTime)
        require(progressSeconds.isFinite() && progressSeconds >= 0.0)
        fun progress(at: Double) = (progressSeconds - actualSeconds + at).coerceAtLeast(0.0)
        if (pattern < 0) {
            choose(0.0, progress(0.0), sound)
            startUnit(0.0, sound, null)
        }
        // A request during a gap takes effect at its explicit actual timestamp, without waiting for it.
        val request = pendingAt
        if (request != null && unit.phase != FlickerPhase.ON && request < unitEnd) {
            choose(request, progress(request), sound)
            startUnit(request, sound, unit.phase)
            pendingAt = null
        }
        while (actualSeconds >= unitEnd) {
            val at = unitEnd
            val previous = unit.phase
            val requested = pendingAt?.let { at >= it } == true
            if (requested || at >= patternEnd) {
                choose(at, progress(at), sound)
                if (requested) pendingAt = null
            }
            startUnit(at, sound, previous)
        }
        lastTime = actualSeconds
        return FlickerFrame(unit.percent / 100.0, pattern, patternStart, patternEnd, style,
            unit.percent, unitStart, unitEnd, unit.phase, pendingAt != null)
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
