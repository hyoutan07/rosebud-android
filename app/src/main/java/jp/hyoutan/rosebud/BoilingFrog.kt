package jp.hyoutan.rosebud

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

internal data class Output(val vibrate: Double = 0.0, val flicker: Double = 0.0)
internal data class ScenarioFrame(val stage: Int, val name: String, val localSeconds: Double, val output: Output) {
    val plannedFlickerRest get() = output.flicker == 0.0
}

/** Same unsigned 32-bit LCG as the Web comparison fixture, independent of Kotlin Random versions. */
internal class SeededRandom(seed: Long) {
    private var state = seed and 0xffffffffL
    fun next(): Double {
        state = (state * 1664525L + 1013904223L) and 0xffffffffL
        return state.toDouble() / 4294967296.0
    }
}

/** Port of scenarios.ts at 89397ee. Random call order and all five formulas are preserved. */
internal class BoilingFrog(private val random: () -> Double) {
    fun frame(elapsedSeconds: Double): ScenarioFrame {
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0)
        var t = if (elapsedSeconds >= 1140) 360 + (elapsedSeconds - 1140) % 780 else elapsedSeconds
        for (i in durations.indices) {
            if (t < durations[i]) return ScenarioFrame(i, names[i], t, when (i) {
                0 -> micro(t, false)
                1 -> micro(t, true)
                2 -> Output(0.4 + random() * 0.1, 0.4 + (sin(t) + sin(t * 1.3) + sin(t * 0.7)) / 3 * 0.3)
                3 -> Output(0.5, if (t % 0.8 < 0.5) 0.4 + random() * 0.3 else 0.05)
                4 -> chaos()
                else -> if (floor(t / 10).toInt() % 3 == 0) chaos() else micro(t, true)
            })
            t -= durations[i]
        }
        error("unreachable scenario time")
    }
    private fun micro(t: Double, level2: Boolean): Output = if (t % 2.6 < 2.0) {
        if (level2) Output(0.3, 0.3 + random() * 0.1) else Output(0.2, 0.1 + random() * 0.05)
    } else if (level2) Output(0.15, 0.0) else Output(0.1, 0.0)
    private fun chaos(): Output {
        val flicker = if (random() > 0.2) 0.8 + random() * 0.2 else 0.2
        return Output(0.8 + random() * 0.2, flicker)
    }
    companion object {
        val durations = listOf(360, 180, 180, 180, 120, 120)
        val names = listOf("01. 微かな触れ合い", "02. 続く焦燥", "03. 揺らぎ", "04. 急な昂ぶり", "05. 崩壊", "06. 残響")
    }
}

/** Fixed calibration for the first experiment: -60..-18 dBFS -> 0..1, attack 150 / release 650 ms. */
internal class AudioEnvelope {
    var level = 0.0; private set
    var lastAt = -1L; private set
    var rmsDb = -120.0; private set
    fun add(rms: Double, now: Long) {
        rmsDb = AudioStats.db(rms)
        val target = ((rmsDb + 60.0) / 42.0).coerceIn(0.0, 1.0)
        val dt = if (lastAt < 0) 100.0 else (now - lastAt).coerceIn(0, 2000).toDouble()
        val tau = if (target > level) 150.0 else 650.0
        level += (target - level) * (1 - exp(-dt / tau))
        lastAt = now
    }
    val multiplier get() = 0.55 + 0.45 * level
    fun reset() { level = 0.0; lastAt = -1; rmsDb = -120.0 }
}

/** Deliberate zero is kept; positive values stay at least one step if the configured cap permits it. */
internal fun quantize(value: Double, scale: Double, steps: Int): Double {
    require(value.isFinite() && scale.isFinite() && steps > 0)
    val maxStep = floor(scale.coerceIn(0.0, 1.0) * steps).toInt()
    if (value <= 0 || maxStep == 0) return 0.0
    return floor(value.coerceIn(0.0, 1.0) * scale * steps).toInt().coerceIn(1, maxStep).toDouble() / steps
}

/** Japanese keyboards may produce full-width URI characters and an ideographic full stop. */
internal fun normalizeServerUrl(text: String): String {
    val ascii = java.text.Normalizer.normalize(text.trim(), java.text.Normalizer.Form.NFKC).replace('。', '.')
    // The normal case is just a LAN IP: no slash or Latin keyboard switching is needed.
    if (Regex("""\d{1,3}(?:\.\d{1,3}){3}(?::\d{1,5})?""").matches(ascii)) {
        return "ws://" + ascii + if (':' in ascii) "" else ":12345"
    }
    return ascii
}
