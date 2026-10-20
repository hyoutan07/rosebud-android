package jp.hyoutan.rosebud

import org.junit.Assert.*
import org.junit.Test

class FavoriteCrescendoTest {
    private val sound = SoundFeatures(kind=SoundKind.SMOOTH,level=1.0,density=.5)
    @Test fun favoriteKeyframesInterpolateContinuouslyAndExpandMonotonically() {
        val expected = listOf(
            300.0 to FavoriteRange(.15,.30,.10,.40),464.0 to FavoriteRange(.20,.50,.10,.70),
            627.0 to FavoriteRange(.25,.50,.10,.70),791.0 to FavoriteRange(.30,.80,.20,.80),
            900.0 to FavoriteRange(.40,1.0,.20,1.0))
        for ((at,range) in expected) assertEquals(range,FavoriteCrescendo.range(at))
        assertEquals(FavoriteRange(.175,.40,.10,.55),FavoriteCrescendo.range(382.0))
        var previous = FavoriteCrescendo.range(300.0)
        for (tick in 3001..9000) {
            val next = FavoriteCrescendo.range(tick*.1)
            assertTrue(next.vibrationFloor >= previous.vibrationFloor && next.vibrationPeak >= previous.vibrationPeak)
            assertTrue(next.flickerFloor >= previous.flickerFloor && next.flickerPeak >= previous.flickerPeak)
            assertTrue(next.vibrationPeak-previous.vibrationPeak < .001)
            assertTrue(next.flickerPeak-previous.flickerPeak < .001)
            previous = next
        }
        for (pattern in 0..7) for (vibration in listOf(false,true)) {
            val period = FavoriteCrescendo.period(pattern,vibration)
            assertTrue(period in 12.0..20.0)
            assertEquals(FavoriteCrescendo.wave(pattern,1.0,vibration),FavoriteCrescendo.wave(pattern,1.0+period,vibration),1e-12)
        }
    }
    @Test fun everyPinnedPatternHasBroadActualMiddleWavesAndLargerLatePeaks() {
        for (pattern in 0..7) {
            val engine = ScenarioEngine(pattern.toLong())
            engine.setRestSettings(RestSettings(0,0,0))
            engine.frame(0.0,sound)
            engine.selectVibrationPattern(0.0,pattern,sound)
            engine.selectFlickerPattern(0.0,pattern,sound)
            val earlyV = mutableListOf<Double>(); val earlyF = mutableListOf<Double>()
            val lateV = mutableListOf<Double>(); val lateF = mutableListOf<Double>()
            val labels = mutableSetOf<Strength>()
            engine.frame(299.9,sound)
            for (tick in 3000..8999) {
                val at = tick*.1
                val frame = engine.frame(at,sound)
                labels.add(frame.strength)
                val range = FavoriteCrescendo.range(at)
                if (frame.strength==Strength.STRONG)
                    assertTrue(frame.output.vibrate >= range.vibrationFloor+.65*(range.vibrationPeak-range.vibrationFloor)-1e-9)
                if (frame.strength==Strength.ULTRA)
                    assertTrue(frame.output.vibrate < range.vibrationFloor+.20*(range.vibrationPeak-range.vibrationFloor)+1e-9)
                if (at in 305.0..365.0) { earlyV.add(frame.output.vibrate); earlyF.add(frame.output.flicker) }
                if (at in 825.0..885.0) { lateV.add(frame.output.vibrate); lateF.add(frame.output.flicker) }
                assertTrue(frame.output.vibrate in 0.0..1.0 && frame.output.flicker in 0.0..1.0)
            }
            fun width(values: List<Double>) = values.max()-values.min()
            println("pattern=$pattern early V=${earlyV.min()}..${earlyV.max()} F=${earlyF.min()}..${earlyF.max()} late V=${lateV.min()}..${lateV.max()} F=${lateF.min()}..${lateF.max()}")
            assertTrue(width(earlyV) > .12)
            assertTrue(width(earlyF) > .23)
            assertTrue(width(lateV) > .35)
            assertTrue(width(lateF) > .50)
            assertTrue(lateV.max() > earlyV.max()+.30)
            assertTrue(lateF.max() > earlyF.max()+.30)
            assertEquals(setOf(Strength.ULTRA,Strength.WEAK,Strength.STRONG),labels)
        }
    }
    @Test fun lateDensityChangesPreserveWavePhaseAndOnsetStillHasModestEffect() {
        val baseline = ScenarioEngine(23)
        val changed = ScenarioEngine(23)
        val low = sound.copy(density=0.0)
        for (engine in listOf(baseline,changed)) {
            engine.setRestSettings(RestSettings(0,0,0))
            engine.frame(0.0,low)
            engine.selectVibrationPattern(0.0,0,low)
            engine.skipTo(0.0,900.0,low)
        }
        for (tick in 1..36000) {
            baseline.frame(tick*.1,low)
            changed.frame(tick*.1,low)
        }
        val a = baseline.frame(3600.1,low)
        val b = changed.frame(3600.1,low.copy(density=1.0))
        assertEquals(a.output.vibrate,b.output.vibrate,.00005)
        var maximumStep = 0.0
        var previous = b.output.vibrate
        for (tick in 36002..36100) {
            val frame = changed.frame(tick*.1,low.copy(density=1.0))
            maximumStep = maxOf(maximumStep,kotlin.math.abs(frame.output.vibrate-previous))
            previous = frame.output.vibrate
        }
        assertTrue(maximumStep < .035)
        val plain = ScenarioEngine(9); val onset = ScenarioEngine(9)
        for (engine in listOf(plain,onset)) {
            engine.setRestSettings(RestSettings(0,0,0)); engine.frame(0.0,sound); engine.skipTo(0.0,900.0,sound)
            engine.frame(5.0,sound)
        }
        val without = plain.frame(5.1,sound).output.vibrate
        val with = onset.frame(5.1,sound.copy(onsetAt=5100)).output.vibrate
        assertTrue(kotlin.math.abs(with-without) in .000001.. .02)
    }
}
