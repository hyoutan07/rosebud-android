package jp.hyoutan.rosebud

import org.junit.Assert.*
import org.junit.Test

class FlickerTrackTest {
    private val sound = SoundFeatures(kind=SoundKind.SMOOTH, level=1.0, density=.5)

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
    @Test fun middleQuietAlternatesCompleteSevenPercentUnitsAndLongRestsEvenAcrossPatternChanges() {
        val track = FlickerTrack(12)
        val quiet = SoundFeatures(kind=SoundKind.QUIET, level=0.0)
        var at = 0.0
        var previous: FlickerPhase? = null
        var changes = 0
        var patternStart = -1.0
        while (at < 300.0) {
            val frame = track.frame(at, 300.0+at, quiet)
            if (frame.patternStart != patternStart) { changes++; patternStart = frame.patternStart }
            if (previous == FlickerPhase.ON) assertEquals(FlickerPhase.REST_A, frame.phase)
            else assertEquals(FlickerPhase.ON, frame.phase)
            if (frame.phase == FlickerPhase.ON) {
                assertEquals(7, frame.percent)
                assertEquals(.07, frame.output, 0.0)
                assertEquals(2.0, frame.unitEnd-frame.unitStart, 1e-9)
            } else {
                assertEquals(0.0, frame.output, 0.0)
                assertTrue(frame.unitEnd-frame.unitStart >= (MotionDefaults.restAMin+MotionDefaults.restAMax)/2-1e-9)
                assertTrue(frame.unitEnd-frame.unitStart <= MotionDefaults.restAMax+1e-9)
            }
            previous = frame.phase
            at = frame.unitEnd
        }
        assertTrue(changes > 4)
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
                assertTrue(frame.output in 0.0.. .4)
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
    @Test fun middleKeepsShortPeakAndStrongStyleKeepsFifteenPercentReturn() {
        var middlePeakReturn = false
        var strongPeakReturn = false
        repeat(1000) { seed ->
            for (progress in listOf(720.0,2700.0)) {
                val track = FlickerTrack(seed.toLong())
                var frame = track.frame(0.0, progress, sound)
                if (frame.pattern == 5 && frame.style in listOf(2,3)) {
                    assertEquals(40, frame.percent)
                    assertEquals(1.0, frame.unitEnd-frame.unitStart, 0.0)
                    val at = frame.unitEnd
                    frame = track.frame(at,progress+at,sound)
                    assertEquals(15, frame.percent)
                    assertEquals(2.0, frame.unitEnd-frame.unitStart, 0.0)
                    if (frame.style == 2) middlePeakReturn = true else strongPeakReturn = true
                }
            }
        }
        assertTrue(middlePeakReturn && strongPeakReturn)
    }
    @Test fun samplesShowEightIndependentUnscaledStepExamples() {
        assertEquals(8, flickerPatterns.size)
        assertEquals(.07,sampleFlicker(0,0.0),0.0)
        assertEquals(0.0,sampleFlicker(0,2.1),0.0)
        assertEquals(.10,sampleFlicker(0,4.0),0.0)
        assertEquals(.4,sampleFlicker(5,0.0),0.0)
        assertEquals(.15,sampleFlicker(5,1.0),0.0)
        val track = FlickerTrack(17)
        val before = track.frame(0.0,0.0,sound)
        for (pattern in 0..7) repeat(100) { sampleFlicker(pattern,it*.1) }
        assertEquals(before,track.frame(0.0,0.0,sound))
    }
    @Test fun equalAdjacentCommandsHaveAGapAndFortyPercentNeverJoinsIntoLongerOn() {
        var repeatedGaps = 0
        repeat(10) { seed ->
            val track = FlickerTrack(seed.toLong())
            var frame = track.frame(0.0,2700.0,sound)
            while (frame.unitStart < 2000.0) {
                val at = frame.unitEnd
                val next = track.frame(at,2700.0+at,sound)
                if (frame.phase == FlickerPhase.ON && next.phase == FlickerPhase.ON)
                    assertNotEquals(frame.percent, next.percent)
                if (frame.percent == 40) assertEquals(1.0,frame.unitEnd-frame.unitStart,1e-9)
                if (frame.phase == FlickerPhase.ON && next.phase == FlickerPhase.REST_A &&
                    next.unitEnd-next.unitStart <= 1.2+1e-9) repeatedGaps++
                frame = next
            }
        }
        assertTrue(repeatedGaps > 0)
        // Pattern 0 ends and starts at 7%; its cyclic example must include the same separating gap.
        assertEquals(.07,sampleFlicker(0,8.0),0.0)
        assertEquals(0.0,sampleFlicker(0,8.8),0.0)
        assertEquals(.07,sampleFlicker(0,9.8),0.0)
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
                    assertEquals(1.0,frame.unitEnd-frame.unitStart,1e-9)
                }
                val at = frame.unitEnd
                val next = track.frame(at,at,audio)
                assertNotEquals(frame.phase,next.phase)
                assertEquals(pattern,next.pattern)
                frame = next
            }
        }
    }
    @Test fun finishRemainsContinuousExceptOneSecondEveryThirdPatternUpdateEvenWhenPinned() {
        for (pattern in 0..7) for (audio in listOf(sound,sound.copy(kind=SoundKind.QUIET))) {
            val track = FlickerTrack(pattern.toLong())
            var frame = track.frame(0.0,900.0,audio)
            track.selectPattern(0.0,pattern,audio)
            var restSeconds = 0.0
            var updates = 1
            var rests = 0
            while (frame.unitStart < 1800.0) {
                if (frame.phase != FlickerPhase.ON) {
                    assertEquals(FlickerPhase.REST_A,frame.phase)
                    assertEquals(1.0,frame.unitEnd-frame.unitStart,1e-9)
                    restSeconds += frame.unitEnd-frame.unitStart
                    rests++
                } else if (audio.kind==SoundKind.QUIET) {
                    assertTrue(frame.percent in listOf(7,10))
                    assertEquals(2.0,frame.unitEnd-frame.unitStart,1e-9)
                }
                val at = frame.unitEnd
                val next = track.frame(at,900.0+at,audio)
                if (frame.patternStart != next.patternStart) updates++
                if (frame.phase==FlickerPhase.ON && next.phase==FlickerPhase.ON)
                    assertNotEquals(frame.percent,next.percent)
                frame = next
            }
            assertEquals(updates/3,rests)
            assertTrue(restSeconds/1800.0 < .015)
            assertTrue(rests>5)
        }
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
