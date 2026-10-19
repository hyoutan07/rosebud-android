package jp.hyoutan.rosebud

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.GZIPInputStream

class BoilingFrogTest {
    @Test fun japaneseKeyboardUrlIsNormalizedWithoutRewritingTheHost() {
        assertEquals("ws://192.168.11.10:12345", normalizeServerUrl(" ｗｓ：／／１９２。１６８。１１。１０：１２３４５ "))
        assertEquals("ws://192.168.11.10:12345", normalizeServerUrl("１９２。１６８。１１。１０"))
        assertEquals("ws://192.168.11.10:9999", normalizeServerUrl("192.168.11.10:9999"))
        assertEquals("wss://example.local:12345", normalizeServerUrl("wss://example.local:12345"))
    }
    @Test fun matchesActualWebFormulasAndProcessScenarioAt19231Times() {
        val fixture = GZIPInputStream(javaClass.getResourceAsStream("/web-boiling-frog.json.gz")!!).reader().use { JSONObject(it.readText()) }
        val scenario = BoilingFrog(SeededRandom(fixture.getLong("seed"))::next)
        val rows = fixture.getJSONArray("rows")
        assertEquals(19231, rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.getJSONArray(i)
            val frame = scenario.frame(row.getDouble(0))
            val label = "time=${row.getDouble(0)}"
            assertEquals(label, row.getInt(1), frame.stage)
            assertEquals(label, row.getDouble(2), frame.output.vibrate, 1e-12)
            assertEquals(label, row.getDouble(3), frame.output.flicker, 1e-12)
        }
    }
    @Test fun boundaryAndLoopDoNotResetToFirstStage() {
        val s = BoilingFrog { 0.5 }
        assertEquals(0, s.frame(359.999).stage)
        assertEquals(1, s.frame(360.0).stage)
        assertEquals(1, s.frame(1140.0).stage)
        assertEquals(1, s.frame(1920.0).stage)
        assertEquals(1, s.frame(2700.0).stage)
        assertEquals(0.0, s.frame(2700.0).localSeconds, 0.0)
    }
    @Test fun silenceKeepsPositiveVibrationAndOriginalFlickerRests() {
        val e = AudioEnvelope()
        repeat(100) { e.add(0.0, it * 100L) }
        assertEquals(0.55, e.multiplier, 0.0)
        val scenario = BoilingFrog { 0.5 }
        for (tick in 0..19200) {
            val f = scenario.frame(tick / 10.0)
            assertTrue(quantize(f.output.vibrate * e.multiplier, 0.05, 255) > 0)
            val flicker = quantize(f.output.flicker * e.multiplier, 0.05, 255)
            if (f.plannedFlickerRest) assertEquals(0.0, flicker, 0.0) else assertTrue(flicker > 0)
        }
    }
    @Test fun envelopeIsSmoothedBoundedAndReleasesAfterPlaybackPause() {
        val e = AudioEnvelope()
        e.add(1.0, 0)
        assertTrue(e.level in 0.0..0.9)
        repeat(20) { e.add(1.0, 100L + it * 100) }
        assertTrue(e.multiplier > 0.99)
        val before = e.multiplier
        e.add(0.0, 2200)
        assertTrue(e.multiplier > 0.55 && e.multiplier < before)
        repeat(100) { e.add(0.0, 2300L + it * 100) }
        assertEquals(0.55, e.multiplier, 0.00001)
    }
    @Test fun offComparisonQuantizationMatchesWebMasterIntensityAndNeverExceedsCap() {
        for (steps in listOf(5, 20, 255)) for (cap in listOf(0.05, 0.3, 1.0)) for (i in 0..1000) {
            val v = i / 1000.0
            val q = quantize(v, cap, steps)
            assertTrue(q >= 0 && q <= cap)
            if (steps == 255 && v >= 0.1) assertEquals(kotlin.math.floor(v * cap * 255) / 255, q, 0.0)
        }
    }
}
