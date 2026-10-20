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
    @Test fun everyPinnedMotifKeepsItsOwnWidthAndGainsLargerLatePeaks() {
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
            assertTrue(width(earlyV) > .03)
            assertTrue(width(earlyF) > .02)
            assertTrue(width(lateV) > .12)
            assertTrue(width(lateF) > .02)
            if(pattern in listOf(5,7)) assertTrue(width(lateF) > .40)
            assertTrue(lateV.max() > earlyV.max()+.15)
            assertTrue(lateF.max() > earlyF.max()+.09)
            assertTrue(Strength.ULTRA in labels && Strength.WEAK in labels)
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
        assertEquals(a.output.vibrate,b.output.vibrate,.002)
        var maximumStep = 0.0
        var previous = b.output.vibrate
        for (tick in 36002..36100) {
            val frame = changed.frame(tick*.1,low.copy(density=1.0))
            maximumStep = maxOf(maximumStep,kotlin.math.abs(frame.output.vibrate-previous))
            previous = frame.output.vibrate
        }
        assertTrue(maximumStep < .10)
        val plain = ScenarioEngine(9); val onset = ScenarioEngine(9)
        for (engine in listOf(plain,onset)) {
            engine.setRestSettings(RestSettings(0,0,0)); engine.frame(0.0,sound); engine.skipTo(0.0,900.0,sound)
            engine.frame(5.0,sound)
        }
        val without = plain.frame(5.1,sound).output.vibrate
        val with = onset.frame(5.1,sound.copy(onsetAt=5100)).output.vibrate
        assertTrue(kotlin.math.abs(with-without) in .000001.. .08)
    }
    @Test fun actualVibrationDoubleShapeRetainsPairedPeaksBeyondTheMacroEnvelope() {
        fun peaks(pattern: Int): List<Double> {
            val engine = ScenarioEngine(42)
            val slow = sound.copy(density=0.0)
            engine.setRestSettings(RestSettings(0,0,0))
            engine.frame(0.0,slow)
            engine.skipTo(0.0,300.0,slow)
            engine.selectVibrationPattern(0.0,pattern,slow)
            val values = (1..2400).map { engine.frame(it*.05,slow).output.vibrate }
            return (402 until values.size-6).filter { i ->
                values[i]>values[i-1] && values[i]>values[i+1] &&
                    values[i]-minOf(values[i-6],values[i+6]) > .001
            }.map { (it+1)*.05 }
        }
        val double = peaks(3)
        val ramp = peaks(4)
        fun pairs(values: List<Double>) = values.zipWithNext().count { (a,b) -> b-a in .65..1.9 }
        println("actual V paired peaks: double=${pairs(double)} / ramp=${pairs(ramp)}")
        assertTrue(pairs(double) >= 8)
        assertTrue(pairs(double) >= pairs(ramp)+4)
    }
    @Test fun nonQuietAudioLevelStillScalesActualVibrationWithoutChangingWeightedSelections() {
        val loud = ScenarioEngine(42); val soft = ScenarioEngine(42)
        val low = sound.copy(level=0.0)
        for (engine in listOf(loud,soft)) engine.setRestSettings(RestSettings(0,0,0))
        loud.frame(0.0,sound); soft.frame(0.0,low)
        loud.skipTo(0.0,300.0,sound); soft.skipTo(0.0,300.0,low)
        for (tick in 1..1000) {
            val a = loud.frame(tick*.1,sound)
            val b = soft.frame(tick*.1,low)
            assertEquals(a.recipe,b.recipe)
            assertEquals(a.recipeEnd,b.recipeEnd,0.0)
            if(tick>100) assertEquals(a.output.vibrate*.5,b.output.vibrate,1e-9)
        }
    }
}
