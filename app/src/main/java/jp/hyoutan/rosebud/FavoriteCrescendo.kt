package jp.hyoutan.rosebud

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow

internal data class FavoriteRange(val vibrationFloor: Double, val vibrationPeak: Double,
    val flickerFloor: Double, val flickerPeak: Double)

/** Initial command ranges based on boiling_frog; the new 2–3 second physical feel is unverified. */
internal object FavoriteCrescendo {
    const val minimumMacroGain = .45
    private val times = doubleArrayOf(300.0,464.0,627.0,791.0,900.0)
    private val ranges = listOf(
        FavoriteRange(.15,.30,.10,.40), FavoriteRange(.20,.50,.10,.70),
        FavoriteRange(.25,.50,.10,.70), FavoriteRange(.30,.80,.20,.80),
        FavoriteRange(.40,1.0,.20,1.0))
    private val periods = doubleArrayOf(16.0,18.0,20.0,14.0,19.0,12.0,17.0,15.0)
    private val offsets = doubleArrayOf(.0,.12,.24,.08,.30,.18,.36,.42)
    private val roundness = doubleArrayOf(1.0,.85,1.2,.95,1.3,.8,1.15,.9)

    fun range(progress: Double): FavoriteRange {
        require(progress.isFinite())
        if (progress <= times.first()) return ranges.first()
        if (progress >= times.last()) return ranges.last()
        val high = times.indexOfFirst { it > progress }
        val low = high-1
        val p = (progress-times[low])/(times[high]-times[low])
        fun mix(a: Double,b: Double) = a+(b-a)*p
        val a = ranges[low]; val b = ranges[high]
        return FavoriteRange(mix(a.vibrationFloor,b.vibrationFloor),mix(a.vibrationPeak,b.vibrationPeak),
            mix(a.flickerFloor,b.flickerFloor),mix(a.flickerPeak,b.flickerPeak))
    }
    fun period(pattern: Int, vibration: Boolean, density: Double = .5): Double {
        require(pattern in periods.indices)
        val audioDensity = if (density.isFinite()) density.coerceIn(0.0,1.0) else .5
        return (periods[pattern]+if(vibration) 1.5-3.0*audioDensity else 0.0).coerceIn(12.0,20.0)
    }
    fun wave(pattern: Int, seconds: Double, vibration: Boolean, density: Double = .5): Double {
        require(seconds.isFinite() && seconds >= 0.0)
        return waveFromPhase(pattern,seconds/period(pattern,vibration,density),vibration)
    }
    fun waveFromPhase(pattern: Int, cycles: Double, vibration: Boolean): Double {
        require(pattern in periods.indices && cycles.isFinite() && cycles >= 0.0)
        val phase = cycles+offsets[pattern]+if(vibration) .17 else 0.0
        val u = phase-floor(phase)
        return (.5-.5*cos(2*PI*u)).pow(roundness[pattern]).coerceIn(0.0,1.0)
    }
    fun macroGain(wave: Double) = minimumMacroGain+(1-minimumMacroGain)*wave.coerceIn(0.0,1.0)
}
