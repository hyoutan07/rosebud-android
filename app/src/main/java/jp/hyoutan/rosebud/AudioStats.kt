package jp.hyoutan.rosebud

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** Counts interleaved PCM16 samples; silence means a whole read block below -60 dBFS. */
class AudioStats(val sampleRate: Int = 48_000, val channels: Int = 2) {
    var samples = 0L; private set
    var silentSamples = 0L; private set
    var silenceRunSamples = 0L; private set
    var longestSilenceSamples = 0L; private set
    var peak = 0.0; private set
    var rms = 0.0; private set
    var maxPeak = 0.0; private set
    private var sumSquares = 0.0
    private var windowSquares = 0.0
    private var windowSamples = 0L
    private var windowPeak = 0.0

    fun add(buffer: ShortArray, count: Int) {
        require(count in 1..buffer.size)
        var squares = 0.0
        var blockPeak = 0.0
        for (i in 0 until count) {
            val value = buffer[i] / 32768.0
            squares += value * value
            blockPeak = maxOf(blockPeak, abs(value))
        }
        rms = sqrt(squares / count)
        peak = blockPeak
        maxPeak = maxOf(maxPeak, peak)
        samples += count
        sumSquares += squares
        windowSquares += squares
        windowSamples += count
        windowPeak = maxOf(windowPeak, peak)
        if (rms < SILENCE_THRESHOLD) {
            silentSamples += count
            silenceRunSamples += count
            longestSilenceSamples = maxOf(longestSilenceSamples, silenceRunSamples)
        } else {
            silenceRunSamples = 0
        }
    }

    fun durationMs(count: Long = samples): Long = count * 1000 / (sampleRate * channels)
    fun totalRms(): Double = if (samples == 0L) 0.0 else sqrt(sumSquares / samples)
    fun takeWindow(): Window {
        val window = Window(windowSamples, if (windowSamples == 0L) 0.0 else sqrt(windowSquares / windowSamples), windowPeak)
        windowSquares = 0.0; windowSamples = 0; windowPeak = 0.0
        return window
    }

    data class Window(val samples: Long, val rms: Double, val peak: Double)
    companion object {
        const val SILENCE_THRESHOLD = 0.001
        fun db(value: Double): Double = if (value <= 0.000001) -120.0 else 20 * log10(value)
    }
}
