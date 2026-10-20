package jp.hyoutan.rosebud

import org.junit.Assert.*
import org.junit.Test

class FlickerTrackTest {
    private val sound = SoundFeatures(kind=SoundKind.SMOOTH, level=1.0, density=.5)
    private fun motifFrames(pattern: Int, count: Int): List<FlickerFrame> {
        val track = FlickerTrack(42)
        track.setRestSettings(RestSettings(0,0,0))
        val initial = track.frame(0.0,627.0,sound)
        track.selectPattern(0.0,pattern,sound)
        var frame = track.frame(initial.unitEnd,627.0+initial.unitEnd,sound)
        return List(count) {
            val result = frame
            frame = track.frame(frame.unitEnd,627.0+frame.unitEnd,sound)
            result
        }
    }
    @Test fun actualMiddleFramesKeepShortPeakTailStairsAndTwoSeparatePeaks() {
        val short = motifFrames(5,3)
        assertEquals(listOf(1.0,2.0,2.0),short.map { it.unitEnd-it.unitStart })
        assertTrue(short[0].percent > short[1].percent+15)
        assertTrue(short[1].percent > short[2].percent+3)
        val stairs = motifFrames(4,5).map { it.percent }
        assertTrue(stairs[0] < stairs[1] && stairs[1] < stairs[2] && stairs[2] < stairs[3])
        assertTrue(stairs[4] < stairs[1])
        val double = motifFrames(3,4).map { it.percent }
        assertTrue(double[0] > double[1] && double[2] > double[1] && double[2] > double[3])
        assertTrue(double[0] > double[2])
        val weakPeak = motifFrames(0,30).maxOf { it.percent }
        val strongPeak = motifFrames(5,30).maxOf { it.percent }
        assertTrue("weak motifs must not be independently expanded to full range",strongPeak > weakPeak+25)
    }
    @Test fun middleAudioLevelChangesNextOnWithoutRewritingTheCurrentOn() {
        val loud = FlickerTrack(42); val soft = FlickerTrack(42)
        val low = sound.copy(level=0.0)
        for (track in listOf(loud,soft)) track.setRestSettings(RestSettings(0,0,0))
        val first = loud.frame(0.0,627.0,sound)
        soft.frame(0.0,627.0,low)
        loud.selectPattern(0.0,5,sound); soft.selectPattern(0.0,5,low)
        val a = loud.frame(first.unitEnd,627.0+first.unitEnd,sound)
        val b = soft.frame(first.unitEnd,627.0+first.unitEnd,low)
        assertTrue(a.percent > b.percent)
        val held = loud.frame(a.unitStart+.5,627.0+a.unitStart+.5,low)
        assertEquals(a.percent,held.percent)
        assertEquals(a.unitEnd,held.unitEnd,0.0)
        val next = loud.frame(a.unitEnd,627.0+a.unitEnd,low)
        val nextSoft = soft.frame(b.unitEnd,627.0+b.unitEnd,low)
        assertEquals(next.percent,nextSoft.percent)
    }

    @Test fun activeUnitSurvivesSoundChangesAndKeepsAbsolutePercent() {
        val track = FlickerTrack(42)
        val first = track.frame(0.0, 0.0, sound)
        assertEquals(FlickerPhase.ON, first.phase)
        assertTrue(first.percent in listOf(7,10,15))
        assertTrue(first.unitEnd in 2.0..3.0)
        for (i in 1..19) {
            val at = i * .1
            val frame = track.frame(at, at, SoundFeatures(kind=SoundKind.QUIET, level=0.0))
            assertEquals(first, frame)
            assertEquals(first.percent / 100.0, frame.output, 0.0)
        }
        assertEquals(first, track.frame(19*.1, 19*.1, sound.copy(level=.01)))
    }
    @Test fun everyPatternStageAndRatioKeepsItsTimeBudgetWithoutHiddenZeros() {
        for (progress in listOf(0.0,300.0,900.0)) for (ratio in listOf(0,1,8,33,50))
            for (pattern in 0..7) for (audio in listOf(sound,sound.copy(kind=SoundKind.QUIET))) {
                val track = FlickerTrack(pattern.toLong())
                track.setRestSettings(RestSettings(ratio,ratio,ratio))
                var frame = track.frame(0.0,progress,audio)
                track.selectPattern(0.0,pattern,audio)
                var rest = 0.0
                var total = 0.0
                while (total < 1800.0) {
                    val duration = frame.unitEnd-frame.unitStart
                    assertTrue(duration > 0.0)
                    if (frame.phase != FlickerPhase.ON) {
                        assertEquals(0.0,frame.output,0.0)
                        assertTrue(duration >= .8-1e-9 && duration <= 3.0+1e-9)
                        rest += duration
                    } else {
                        assertTrue(frame.output > 0.0)
                        if (progress >= 900.0) {
                            assertTrue(frame.percent in 15..100)
                            if (frame.percent >= 80) assertEquals(2.0,duration,1e-9)
                        }
                    }
                    total += duration
                    val next = track.frame(frame.unitEnd,progress,audio)
                    if (frame.phase == FlickerPhase.ON && next.phase == FlickerPhase.ON)
                        assertNotEquals(frame.percent,next.percent)
                    frame = next
                }
                assertEquals("stage=$progress ratio=$ratio pattern=$pattern sound=${audio.kind}",ratio/100.0,rest/total,.002)
                if (ratio == 0) assertEquals(0.0,rest,0.0)
            }
    }
    @Test fun fixedSeedSelectionsAndBoundariesDoNotDependOnFrameCadence() {
        val fine = FlickerTrack(9831)
        val coarse = FlickerTrack(9831)
        for (tick in 0..36000) {
            val at = tick * .1
            val frame = fine.frame(at, at, sound)
            if (tick % 10 == 0) assertEquals(frame, coarse.frame(at, at, sound))
            assertEquals(frame, fine.frame(at, at, sound.copy(level=.01)))
        }
    }
    @Test fun holdExpiryWaitsForOnCompletionAndNeverCreatesTripleRepeats() {
        val seen = mutableSetOf<Int>()
        val phases = mutableSetOf<FlickerPhase>()
        repeat(8) { seed ->
            val track = FlickerTrack(seed.toLong())
            var frame = track.frame(0.0, 0.0, sound)
            var previousPattern = -1
            var repeats = 0
            var previousStart = -1.0
            var previousDeadline = 0.0
            while (frame.unitStart < 3000.0) {
                seen.add(frame.pattern); phases.add(frame.phase)
                assertTrue(frame.output in 0.0..1.0)
                assertTrue(frame.patternEnd-frame.patternStart in 30.0..60.0)
                assertTrue(frame.unitEnd > frame.unitStart)
                if (frame.patternStart != previousStart) {
                    if (previousStart >= 0.0) {
                        assertTrue(frame.patternStart >= previousDeadline)
                        assertTrue(frame.patternStart <= previousDeadline + 3.0 + 1e-9)
                    }
                    repeats = if (frame.pattern == previousPattern) repeats+1 else 1
                    assertTrue(repeats <= 2)
                    previousPattern = frame.pattern
                    previousStart = frame.patternStart
                    previousDeadline = frame.patternEnd
                }
                if (frame.phase == FlickerPhase.ON && frame.unitEnd > frame.patternEnd) {
                    val atDeadline = track.frame(frame.patternEnd, frame.patternEnd, sound)
                    assertEquals(frame, atDeadline)
                }
                val at = frame.unitEnd
                frame = track.frame(at, at, sound)
            }
        }
        assertEquals((0..7).toSet(), seen)
        assertEquals(FlickerPhase.entries.toSet(), phases)
    }
    @Test fun stageSkipWaitsForCurrentOnAndDoesNotConsumeUnplayedTime() {
        val track = FlickerTrack(51)
        val initial = track.frame(0.0, 0.0, sound)
        track.frame(.5, .5, sound)
        track.requestStageSkip(.5)
        val pending = track.frame(.5, 900.0, sound)
        assertTrue(pending.pendingStage)
        assertEquals(initial.percent, pending.percent)
        assertEquals(initial.unitEnd, pending.unitEnd, 0.0)
        assertEquals(initial.patternEnd, pending.patternEnd, 0.0)
        val at = pending.unitEnd
        val next = track.frame(at, at+899.5, sound)
        assertFalse(next.pendingStage)
        assertEquals(at, next.patternStart, 0.0)
        assertTrue(next.patternEnd-at in 30.0..60.0)
        assertTrue(next.style in 1..3)
    }
    @Test fun skipInRestChoosesImmediatelyAndRepeatedCallsAreIdempotent() {
        val track = FlickerTrack(2)
        var frame = track.frame(0.0, 0.0, sound)
        while (frame.phase == FlickerPhase.ON) frame = track.frame(frame.unitEnd, frame.unitEnd, sound)
        val at = frame.unitStart+.05
        track.frame(at, at, sound)
        track.requestStageSkip(at)
        track.requestStageSkip(at)
        val changed = track.frame(at, 900.0, sound)
        assertEquals(at, changed.patternStart, 0.0)
        assertFalse(changed.pendingStage)
        assertEquals(changed, track.frame(at,900.0,sound))
    }
    @Test fun resumeRestartsTheSameOnForItsFullDurationAndKeepsPattern() {
        val track = FlickerTrack(39)
        val initial = track.frame(0.0,0.0,sound)
        track.frame(.7,.7,sound)
        track.resumeFromPause(.7)
        val resumed = track.frame(.7,.7,sound.copy(kind=SoundKind.QUIET))
        assertEquals(initial.pattern, resumed.pattern)
        assertEquals(initial.patternStart, resumed.patternStart, 0.0)
        assertEquals(initial.patternEnd, resumed.patternEnd, 0.0)
        assertEquals(initial.percent, resumed.percent)
        assertEquals(.7, resumed.unitStart, 0.0)
        assertEquals(initial.unitEnd-initial.unitStart, resumed.unitEnd-resumed.unitStart, 1e-9)
        track.resumeFromPause(.7)
        assertEquals(resumed, track.frame(.7,.7,sound))
    }
    @Test fun middleKeepsMotifAndEnvelopeFrozenForOriginalOneTwoOrThreeSecondOn() {
        for (pattern in 0..7) {
            val track = FlickerTrack(pattern.toLong())
            track.setRestSettings(RestSettings(0,0,0))
            track.frame(0.0,627.0,sound)
            track.selectPattern(0.0,pattern,sound)
            var frame = track.frame(3.0,630.0,sound)
            val values = mutableListOf<Int>()
            while (frame.unitStart < 70.0) {
                assertTrue(frame.percent in 10..75)
                assertTrue(frame.unitEnd-frame.unitStart in 1.0..3.0)
                values.add(frame.percent)
                val inside = (frame.unitStart+.2).coerceAtLeast(3.0)
                if (inside < frame.unitEnd) {
                    val held = track.frame(inside,627.0+inside,sound.copy(level=.01,density=1.0))
                    assertEquals(frame.percent,held.percent)
                    assertEquals(frame.unitEnd,held.unitEnd,0.0)
                }
                frame = track.frame(frame.unitEnd,627.0+frame.unitEnd,sound)
            }
            assertTrue(values.max()-values.min() >= if(pattern in listOf(5,7)) 40 else 2)
        }
    }
    @Test fun samplesShowEightIndependentUnscaledBroadStepExamples() {
        assertEquals(8,flickerPatterns.size)
        assertEquals(.10,sampleFlicker(0,0.0),0.0)
        assertTrue(sampleFlicker(0,4.0) in .11.. .16)
        assertEquals(.10,sampleFlicker(0,8.0),0.0)
        assertTrue(sampleFlicker(5,0.0) > sampleFlicker(5,1.0))
        assertTrue(sampleFlicker(5,1.0) > sampleFlicker(5,3.0))
        assertEquals(sampleFlicker(0,4.0),sampleFlicker(0,5.9),0.0)
        val track = FlickerTrack(17)
        val before = track.frame(0.0,0.0,sound)
        for (pattern in 0..7) repeat(100) { assertTrue(sampleFlicker(pattern,it*.1) in .10.. .70) }
        assertEquals(before,track.frame(0.0,0.0,sound))
    }
    @Test fun equalAdjacentCommandsUseMovingValleysAndFortyPercentNeverJoinsIntoLongerOn() {
        var repeatedGaps = 0
        repeat(10) { seed ->
            val track = FlickerTrack(seed.toLong())
            var frame = track.frame(0.0,2700.0,sound)
            while (frame.unitStart < 2000.0) {
                val at = frame.unitEnd
                val next = track.frame(at,2700.0+at,sound)
                if (frame.phase == FlickerPhase.ON && next.phase == FlickerPhase.ON)
                    assertNotEquals(frame.percent, next.percent)
                if (frame.percent >= 80) assertEquals(2.0,frame.unitEnd-frame.unitStart,1e-9)
                if (frame.phase == FlickerPhase.ON && next.phase == FlickerPhase.REST_A &&
                    next.unitEnd-next.unitStart <= 1.2+1e-9) repeatedGaps++
                frame = next
            }
        }
        assertTrue(repeatedGaps > 0)
        // Middle examples also retain the broad moving valley.
        assertEquals(.10,sampleFlicker(0,8.0),0.0)
    }
    @Test fun invalidClocksAreRejectedWithoutChangingTrack() {
        val track = FlickerTrack(5)
        val frame = track.frame(1.0,1.0,sound)
        for (bad in listOf(Double.NaN,Double.POSITIVE_INFINITY,-1.0,.5)) {
            try { track.frame(bad,1.0,sound); fail("invalid actual") } catch (_: IllegalArgumentException) { }
            try { track.requestStageSkip(bad); fail("invalid skip") } catch (_: IllegalArgumentException) { }
            try { track.resumeFromPause(bad); fail("invalid resume") } catch (_: IllegalArgumentException) { }
        }
        for (bad in listOf(Double.NaN,Double.NEGATIVE_INFINITY,-1.0)) {
            try { track.frame(1.0,bad,sound); fail("invalid progress") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(frame,track.frame(1.0,1.0,sound))
    }
    @Test fun allIntroPatternsUseTwoSecondWeakOnAndExactlyOneSecondAfterEveryOn() {
        for (pattern in 0..7) for (audio in listOf(sound,sound.copy(kind=SoundKind.QUIET))) {
            val track = FlickerTrack(pattern.toLong())
            var frame = track.frame(0.0,0.0,audio)
            assertTrue(track.selectPattern(0.0,pattern,audio))
            while (frame.unitStart < 180.0) {
                assertEquals(2.0,if(frame.phase==FlickerPhase.ON) frame.unitEnd-frame.unitStart else 2.0,1e-9)
                if (frame.phase==FlickerPhase.ON) assertTrue(frame.percent in listOf(7,10))
                else {
                    assertEquals(FlickerPhase.REST_A,frame.phase)
                    assertEquals(2.0*33/67,frame.unitEnd-frame.unitStart,1e-9)
                }
                val at = frame.unitEnd
                val next = track.frame(at,at,audio)
                assertNotEquals(frame.phase,next.phase)
                assertEquals(pattern,next.pattern)
                frame = next
            }
        }
    }
    @Test fun changingProfileOrStageStartsFreshAtOnBoundaryAndNeverCutsCurrentOn() {
        val track = FlickerTrack(42)
        track.setRestSettings(RestSettings(0,0,0))
        val first = track.frame(0.0,0.0,sound)
        track.frame(.5,.5,sound)
        track.setRestSettings(RestSettings(50,50,50))
        assertEquals(first,track.frame(.5,.5,sound))
        val second = track.frame(first.unitEnd,first.unitEnd,sound)
        assertEquals(FlickerPhase.ON,second.phase) // preceding ON belonged to the previous profile
        val rest = track.frame(second.unitEnd,second.unitEnd,sound)
        assertEquals(FlickerPhase.REST_A,rest.phase)
        assertEquals(2.0,rest.unitEnd-rest.unitStart,1e-9)
        val on = track.frame(rest.unitEnd,rest.unitEnd,sound)
        track.requestStageSkip(on.unitStart)
        val pending = track.frame(on.unitStart,900.0,sound)
        assertTrue(pending.pendingStage)
        assertEquals(on.unitEnd,pending.unitEnd,0.0)
        val finish = track.frame(on.unitEnd,900.0+on.unitEnd-on.unitStart,sound)
        assertEquals(FlickerPhase.ON,finish.phase)
        assertTrue(finish.percent >= 15)
        track.setRestSettings(RestSettings(0,0,0))
        assertEquals(finish,track.frame(on.unitEnd,900.0+on.unitEnd-on.unitStart,sound))
        var next = track.frame(finish.unitEnd,910.0,sound)
        repeat(100) {
            assertEquals(FlickerPhase.ON,next.phase)
            next = track.frame(next.unitEnd,910.0,sound)
        }
    }
    @Test fun restDefaultsAndRangesAreValidated() {
        assertEquals(RestSettings(33,8,1),RestSettings())
        for (bad in listOf(-1,51)) {
            try { RestSettings(bad,8,1); fail("intro") } catch (_: IllegalArgumentException) { }
            try { RestSettings(33,bad,1); fail("middle") } catch (_: IllegalArgumentException) { }
            try { RestSettings(33,8,bad); fail("finish") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun repeatedManualAndAudioChangesCarryLowRatioCreditAcrossPatternHolds() {
        val track = FlickerTrack(9)
        track.setRestSettings(RestSettings(1,1,1))
        var frame = track.frame(0.0,0.0,sound)
        var onSeconds = 0.0
        var changes = 0
        while (frame.phase == FlickerPhase.ON) {
            onSeconds += frame.unitEnd-frame.unitStart
            assertTrue(track.selectPattern(frame.unitStart,changes%8,sound))
            val at = frame.unitEnd
            changes++
            val audio = if (changes%2==0) sound else sound.copy(kind=SoundKind.QUIET)
            frame = track.frame(at,0.0,audio)
            assertTrue("credit must not reset with each manual/audio change",changes<=40)
        }
        assertEquals(80.0,onSeconds,1e-9)
        assertEquals(onSeconds/99,frame.unitEnd-frame.unitStart,1e-9)
    }
    @Test fun patternSelectionWaitsForOnCompletesInRestAndPinnedStageUsesNewStyle() {
        val track = FlickerTrack(44)
        assertFalse(track.selectPattern(0.0,2,sound))
        val initial = track.frame(0.0,0.0,sound)
        track.frame(.5,.5,sound)
        assertTrue(track.selectPattern(.5,5,sound))
        val pending = track.frame(.5,.5,sound)
        assertTrue(pending.pendingPattern)
        assertEquals(initial.unitEnd,pending.unitEnd,0.0)
        assertEquals(initial.percent,pending.percent)
        val at = pending.unitEnd
        val rest = track.frame(at,at,sound)
        assertEquals(5,rest.pattern)
        assertEquals(FlickerPhase.REST_A,rest.phase)
        assertFalse(rest.pendingPattern)
        assertTrue(track.selectPattern(at,7,sound))
        track.requestStageSkip(at)
        val middle = track.frame(at,300.0,sound)
        assertEquals(7,middle.pattern)
        assertEquals(7,middle.manualPattern)
        assertTrue(middle.style in 1..2)
        assertFalse(middle.pendingPattern)
        assertFalse(middle.pendingStage)
        assertFalse(track.selectPattern(at,7,sound))
        assertFalse(track.selectPattern(at,8,sound))
        assertTrue(track.selectPattern(at,-1,sound))
        val auto = track.frame(at,300.0,sound)
        assertEquals(-1,auto.manualPattern)
    }
}
