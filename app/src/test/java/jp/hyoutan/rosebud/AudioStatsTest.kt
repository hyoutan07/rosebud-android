package jp.hyoutan.rosebud

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class AudioStatsTest {
    @Test fun stereoDurationAndSilenceUseSamplesRatherThanFrames() {
        val stats = AudioStats()
        repeat(10) { stats.add(ShortArray(9600), 9600) }
        assertEquals(96000L, stats.samples)
        assertEquals(1000L, stats.durationMs())
        assertEquals(1000L, stats.durationMs(stats.silentSamples))
        assertEquals(0.0, stats.rms, 0.0)
        assertEquals(-120.0, AudioStats.db(stats.rms), 0.0)
    }
    @Test fun negativeFullScaleIsNotLostToShortOverflow() {
        val stats = AudioStats()
        stats.add(shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE), 2)
        assertEquals(1.0, stats.peak, 0.0)
        assertTrue(stats.rms > 0.999)
        assertEquals(0L, stats.silentSamples)
    }
    @Test fun oppositeStereoChannelsDoNotCancel() {
        val stats = AudioStats()
        stats.add(shortArrayOf(16384, -16384), 2)
        assertEquals(0.5, stats.rms, 0.000001)
        assertEquals(-6.0206, AudioStats.db(stats.rms), 0.0001)
    }
    @Test fun totalRmsIsWeightedBySampleCountAndWindowsReset() {
        val stats = AudioStats()
        stats.add(shortArrayOf(16384, 16384), 2)
        stats.add(ShortArray(6), 6)
        assertEquals(0.25, stats.totalRms(), 0.000001)
        val window = stats.takeWindow()
        assertEquals(8L, window.samples)
        assertEquals(0.25, window.rms, 0.000001)
        assertEquals(0.5, window.peak, 0.000001)
        assertEquals(0L, stats.takeWindow().samples)
        assertEquals(8L, stats.samples)
        assertEquals(0.5, stats.maxPeak, 0.0)
    }
    @Test fun silenceRunEndsWithAudibleBlockAndRetainsLongest() {
        val stats = AudioStats()
        repeat(12) { stats.add(ShortArray(9600), 9600) }
        stats.add(ShortArray(9600) { 8192 }, 9600)
        assertEquals(0L, stats.silenceRunSamples)
        repeat(3) { stats.add(ShortArray(9600), 9600) }
        assertEquals(1200L, stats.durationMs(stats.longestSilenceSamples))
        assertEquals(1500L, stats.durationMs(stats.silentSamples))
        assertEquals(300L, stats.durationMs(stats.silenceRunSamples))
    }
    @Test fun unusedBufferTailIsExcluded() {
        val stats = AudioStats()
        stats.add(shortArrayOf(0, 16384, 32767, 32767), 2)
        assertEquals(sqrt(0.125), stats.rms, 0.000001)
        assertEquals(2L, stats.samples)
        assertEquals(0.5, stats.peak, 0.0)
    }
}
