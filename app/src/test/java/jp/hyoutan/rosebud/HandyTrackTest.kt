package jp.hyoutan.rosebud

import org.junit.Assert.*
import org.junit.Test

class HandyTrackTest {
    @Test fun doesNotStartOnConstruction() { assertFalse(HandyTrack(1).tick(100000,SoundFeatures()).running) }
    @Test fun defaultPhaseBoundariesAndEndlessFinish() {
        val t=HandyTrack(1); t.start(1000)
        assertEquals("はじめ",t.tick(480999,SoundFeatures()).stage)
        assertEquals("中間",t.tick(481000,SoundFeatures()).stage)
        assertEquals("フィニッシュ",t.tick(901000,SoundFeatures()).stage)
        assertEquals(2,t.tick(1201000,SoundFeatures()).cycle)
        assertEquals(286,t.tick(86401000,SoundFeatures()).cycle)
        assertTrue(t.frame.running)
    }
    @Test fun adjustedPhaseLengths() {
        val t=HandyTrack(3,HandySettings(60000,120000,60000));t.start(0)
        assertEquals("中間",t.tick(60000,SoundFeatures()).stage)
        assertEquals(2,t.tick(240000,SoundFeatures()).cycle)
    }
    @Test fun soundCannotReplacePatternBeforeTwentySecondsOrMidSegment() {
        val t=HandyTrack(15);t.start(0); val first=t.tick(0,SoundFeatures(SoundKind.SMOOTH))
        val before=t.tick(19999,SoundFeatures(SoundKind.REGULAR))
        assertEquals(first.pattern,before.pattern); assertEquals(19999,before.ageMs)
        val mid=t.clockFrame(20000)
        assertEquals(20000,mid.ageMs)
        val boundary=t.tick(maxOf(20000,mid.segmentEndMs),SoundFeatures(SoundKind.BROKEN))
        assertEquals(0,boundary.ageMs)
    }
    @Test fun eightySecondsUpdatesAtNextBoundary() {
        val t=HandyTrack(1);t.start(0);t.tick(0,SoundFeatures())
        var time=0L
        while(time<80000) { time=t.frame.segmentEndMs;t.tick(time,SoundFeatures()) }
        assertEquals(0,t.frame.ageMs)
    }
    @Test fun weakHoldsOnlyHandyClockUntilManualRelease() {
        val t=HandyTrack(3);t.start(0);t.tick(10000,SoundFeatures());t.weaken(true,10000)
        assertTrue(t.tick(200000,SoundFeatures()).weak);assertEquals(10000,t.frame.elapsedMs)
        assertNotNull(t.frame.move)
        t.weaken(false,200000);assertEquals(11000,t.tick(201000,SoundFeatures()).elapsedMs)
    }
    @Test fun finishStartsFirstFinishCycleAndWeakCannotSkip() {
        val t=HandyTrack(2);t.start(0);t.weaken(true,0);t.finish(1000)
        assertEquals("はじめ",t.tick(1000,SoundFeatures()).stage)
        t.weaken(false,1000);t.finish(1000);assertEquals(1,t.tick(1000,SoundFeatures()).cycle)
    }
    @Test fun stopCannotReviveWithLateTick() {
        val t=HandyTrack(1);t.start(0);t.tick(0,SoundFeatures());t.stop()
        assertFalse(t.tick(10000000,SoundFeatures()).running);assertNull(t.frame.move)
    }
    @Test fun longDelayedTickCreatesOneFreshInterval() {
        val t=HandyTrack(1);t.start(0);t.tick(0,SoundFeatures());val f=t.tick(1000000,SoundFeatures())
        assertTrue(f.segmentEndMs>1000000);assertTrue(f.segmentEndMs<=1020000)
    }
    @Test fun deterministicSeparateSeedsAndLegacyRandomUnaffected() {
        val a=HandyTrack(3); val b=HandyTrack(3);a.start(0);b.start(0)
        val reference=ScenarioEngine(4);val existing=ScenarioEngine(4)
        for(time in 0L..2000000L step 1000) {
            val sound=SoundFeatures(SoundKind.REGULAR,.7,.6,.8)
            assertEquals(a.tick(time,sound),b.tick(time,sound))
            assertEquals(reference.frame(time/1000.0,sound),existing.frame(time/1000.0,sound))
        }
    }
    @Test fun normalizedRangeAndScheduledDurationAreBoundedAcrossStages() {
        val settings=HandySettings(lower=.4,upper=.6,halfMs=2000)
        val t=HandyTrack(11,settings);t.start(0);var observedWide=false
        for(time in 0L..4000000L step 1000) {
            val f=t.tick(time,SoundFeatures(SoundKind.REGULAR))
            f.move?.let { m -> assertTrue(m.target in settings.lower..settings.upper);assertTrue(m.durationMs in 40..20000)
                if(m.target>.55 || m.target<.45) observedWide=true }
        }
        assertTrue(observedWide)
    }
    @Test fun plannedRestIsNotWeakOrFault() {
        val t=HandyTrack(20,HandySettings(weights=listOf(10,10,200)));t.start(0)
        var time=0L;var rest=false
        repeat(100) { val f=t.tick(time,SoundFeatures(SoundKind.BROKEN));if(f.plannedRest) {rest=true;assertNull(f.move);assertFalse(f.weak);assertTrue(f.running)};time=f.segmentEndMs }
        assertTrue(rest)
    }
    @Test fun weightChangesSelectionWithoutClaimingPercentages() {
        fun count(weights: List<Int>): Int {
            val t=HandyTrack(30,HandySettings(weights=weights));t.start(0);var time=0L;var n=0
            repeat(300) { val f=t.tick(time,SoundFeatures(SoundKind.UNCLEAR));if(f.pattern==0)n++;time+=81000 };return n
        }
        assertTrue(count(listOf(200,10,10))>count(listOf(10,200,200)))
    }
    @Test(expected=IllegalArgumentException::class) fun invalidPositionRejected() { HandySettings(lower=Double.NaN) }
    @Test(expected=IllegalArgumentException::class) fun reversedRangeRejected() { HandySettings(lower=.8,upper=.2) }
    @Test fun repertoireIsStageSpecificAndAllCandidatesCanBeSelected() {
        assertEquals(3,HandyTrack.candidates("はじめ").count())
        assertEquals(7,HandyTrack.candidates("中間").count())
        assertEquals(3,HandyTrack.candidates("フィニッシュ").count())
        val seen=mutableSetOf<Int>()
        for(seed in 1L..30L) {
            val track=HandyTrack(seed);track.start(0)
            for(time in 0L..2400000L step 81000) {
                val f=track.tick(time,SoundFeatures(SoundKind.UNCLEAR))
                assertTrue(f.pattern in HandyTrack.candidates(f.stage));seen+=f.pattern
            }
        }
        assertEquals(HandyTrack.names.indices.toSet(),seen)
    }
    @Test fun oldWeightsMigrateAndNewWeightsStayExact() {
        val legacy=HandySettings(weights=listOf(10,100,200)).normalizedWeights
        assertEquals(13,legacy.size);assertEquals(listOf(10,100,200),legacy.take(3))
        val full=List(13) { 10+it }
        assertEquals(full,HandySettings(weights=full).normalizedWeights)
    }
    @Test fun fullRangeAndShortLegSchedulesAreAccepted() {
        val settings=HandySettings(lower=0.0,upper=1.0,halfMs=250)
        val track=HandyTrack(7,settings);track.start(0);track.finish(0)
        var time=0L
        repeat(1000) {
            val f=track.tick(time,SoundFeatures(SoundKind.REGULAR))
            f.move?.let { assertTrue(it.target in 0.0..1.0);assertTrue(it.durationMs in 40..20000) }
            time=f.segmentEndMs
        }
    }
    @Test fun weakeningInterruptsOldIntervalAndResumeStartsWithoutTargetJump() {
        val track=HandyTrack(8);track.start(0);track.finish(0)
        val before=track.tick(0,SoundFeatures())
        track.weaken(true,100);val weak=track.tick(100,SoundFeatures())
        assertTrue(weak.weak);assertTrue(weak.segmentEndMs<before.segmentEndMs+20000)
        val paused=weak.elapsedMs
        track.tick(100000,SoundFeatures());assertEquals(paused,track.frame.elapsedMs)
        track.weaken(false,100000);val resumed=track.tick(100000,SoundFeatures())
        assertNotNull(resumed.move)
        assertTrue(resumed.speedMmPerSec<=33.0)
        assertEquals("フィニッシュ",resumed.stage)
        assertEquals(paused+1000,track.tick(101000,SoundFeatures()).elapsedMs)
    }
    @Test fun physicalPositionAlignmentKeepsWeakMovesNearTheStoppedPosition() {
        val track=HandyTrack(8);track.start(0);track.finish(0);track.tick(0,SoundFeatures())
        track.weaken(true,100);track.alignPosition(.1);track.interruptSegment(100)
        val weak=track.tick(100,SoundFeatures())
        assertTrue(kotlin.math.abs(weak.move!!.target-.1)<=.070001)
        track.weaken(false,1000);track.alignPosition(.11);track.interruptSegment(1000)
        val first=track.tick(1000,SoundFeatures())
        assertNotNull(first.move);assertTrue(first.move!!.target in 0.0..1.0)
        assertTrue(first.speedMmPerSec<=33.0)
        assertEquals("フィニッシュ",first.stage)
    }
    @Test fun cancelledSegmentCannotKeepReturningAnOldMove() {
        val track=HandyTrack(1);track.start(0);val before=track.tick(0,SoundFeatures())
        track.interruptSegment(100);assertNull(track.frame.move)
        val after=track.tick(100,SoundFeatures())
        assertTrue(after.segmentEndMs>100);assertNotEquals(before.move,after.move)
    }
    @Test fun clockRefreshDoesNotGenerateMovementOrConsumeRandomSequence() {
        val a=HandyTrack(41);val b=HandyTrack(41);a.start(0);b.start(0)
        assertEquals(a.tick(0,SoundFeatures()),b.tick(0,SoundFeatures()))
        val first=a.frame
        for(time in 1L..10000L step 73) {
            val clock=a.clockFrame(time)
            assertEquals(first.move,clock.move);assertEquals(first.segmentEndMs,clock.segmentEndMs)
            assertEquals(first.pattern,clock.pattern);assertEquals(first.speedMmPerSec,clock.speedMmPerSec,0.0)
        }
        a.completeSegment(10000);b.completeSegment(10000)
        assertEquals(b.tick(10000,SoundFeatures()),a.tick(10000,SoundFeatures()))
    }
    @Test fun arrivalImmediatelyStartsNextLegWithoutOriginalScheduledWait() {
        val t=HandyTrack(1);t.start(0);val initial=t.tick(0,SoundFeatures())
        assertTrue(initial.segmentEndMs>100)
        t.completeSegment(100);val next=t.tick(100,SoundFeatures())
        assertNotEquals(initial.move!!.target,next.move!!.target,0.0)
        assertTrue(next.segmentEndMs>100)
    }
    @Test fun selectingEachStageResetsItsClockButWeakRejectsStageChanges() {
        val t=HandyTrack(41);t.start(0)
        assertTrue(t.selectStage("中間",100));assertEquals(480000,t.frame.elapsedMs)
        assertTrue(t.selectStage("フィニッシュ",200));assertEquals(900000,t.frame.elapsedMs)
        assertTrue(t.selectStage("はじめ",300));assertEquals(0,t.frame.elapsedMs)
        t.weaken(true,400);val held=t.clockFrame(400)
        assertFalse(t.selectStage("中間",9000));assertFalse(t.selectStage("unknown",9000))
        assertEquals(held.elapsedMs,t.clockFrame(9000).elapsedMs)
    }
    private fun sampledStage(stage: String, seed: Long = 3): List<HandyFrame> {
        val t=HandyTrack(seed,HandySettings(introMs=3600000,middleMs=3600000,finishMs=3600000))
        t.start(0);t.selectStage(stage,0)
        return (0L..600000L step 250).map { time ->
            t.completeSegment(time);t.tick(time,SoundFeatures(SoundKind.REGULAR,.6,.6,.7))
        }
    }
    @Test fun finishIsFasterAndEveryStageHasSpeedVariation() {
        val ranges=listOf("はじめ" to (32.0..60.0),"中間" to (60.0..150.0),"フィニッシュ" to (160.0..260.0))
        val averages=mutableListOf<Double>()
        ranges.forEach { (stage,range) ->
            val speeds=sampledStage(stage).filter { it.ageMs>=5000 && it.move!=null }.map { it.speedMmPerSec }
            assertTrue("$stage speed range",speeds.all { it in range })
            assertTrue("$stage speed variation",speeds.max()-speeds.min()>10)
            averages+=speeds.average()
        }
        assertTrue(averages[2]>averages[1]+60);assertTrue(averages[1]>averages[0]+25)
    }
    @Test fun middleBuildsItsLongTermSpeedWithoutLosingShortValleys() {
        fun sample(offset: Long): List<Double> {
            val t=HandyTrack(11,HandySettings(introMs=60000,middleMs=420000,finishMs=60000))
            t.start(0);t.selectStage("中間",0);t.clockFrame(offset)
            return (0L..40000L step 250).map { relative ->
                val time=offset+relative;t.completeSegment(time)
                t.tick(time,SoundFeatures(SoundKind.REGULAR,.6,.6,.7))
            }.filter { it.ageMs>=5000 && it.move!=null }.map { it.speedMmPerSec }
        }
        val early=sample(1000);val late=sample(330000)
        assertTrue(late.average()>early.average()+8)
        assertTrue(late.max()-late.min()>15)
    }
    @Test fun windowsIncludeUpperLowerWholeAndSmallWithIndependentSpeeds() {
        val frames=(1L..5L).flatMap { sampledStage("中間",it) }.filter { it.move!=null }
        assertTrue(frames.any { it.pattern==3 && it.move!!.target>=.60 })
        assertTrue(frames.any { it.pattern==5 && it.move!!.target<=.40 })
        assertTrue(frames.any { it.pattern==4 && it.move!!.target==.05 })
        assertTrue(frames.any { it.pattern==4 && it.move!!.target==.95 })
        val upper=frames.filter { it.pattern==3 && it.ageMs>=5000 }.map { it.speedMmPerSec }
        assertTrue(upper.max()-upper.min()>35) // The same upper-only window has slow and fast passes.
        val sharedSpeed=frames.filter { it.speedMmPerSec in 95.0..105.0 }
        assertTrue(sharedSpeed.any { it.move!!.target<.4 });assertTrue(sharedSpeed.any { it.move!!.target>.6 })
        val small=frames.filter { it.pattern==6 }
        assertTrue(small.any { it.move!!.target<.35 });assertTrue(small.any { it.move!!.target>.65 })
    }
    @Test fun restsAreOccasionalShortAndWeakIsContinuous() {
        val limits=listOf("はじめ" to (.025.. .065),"中間" to (.015.. .045),"フィニッシュ" to (.005.. .025))
        limits.forEach { (stage,range) ->
            val fs=(1L..12L).flatMap { sampledStage(stage,it) }
            val rate=fs.count { it.plannedRest }.toDouble()/fs.size
            assertTrue("$stage rest fraction $rate",rate in range)
            assertTrue(fs.filter { it.plannedRest }.all { it.move==null })
            assertTrue(fs.filter { it.pattern in setOf(0,4,10) }.none { it.plannedRest })
        }
        val t=HandyTrack(2);t.start(0);t.weaken(true,0)
        repeat(200) { i -> t.completeSegment(i*100L);val f=t.tick(i*100L,SoundFeatures())
            assertFalse(f.plannedRest);assertEquals(32.0,f.speedMmPerSec,0.0);assertEquals(0,f.elapsedMs) }
    }
    @Test fun linearBodyUsesDeviceAndFeatureIndicesAndMilliseconds() {
        val body=LinearMove(.52,2500).body(8,2);val v=body.getJSONArray("Vectors").getJSONObject(0)
        assertEquals(8,body.getInt("DeviceIndex"));assertEquals(2,v.getInt("Index"));assertEquals(2500,v.getInt("Duration"));assertEquals(.52,v.getDouble("Position"),0.0)
    }
    @Test fun linearAckDoesNotCompleteIntervalAndExpiryDoesNotReplacePending() {
        val lane=LinearLane(50);lane.arm(.5);var ack:()->Unit={}
        assertTrue(lane.offer(LinearMove(.55,2000),0) { _,a->ack=a })
        assertFalse(lane.offer(LinearMove(.45,2000),2000) { _,_->fail() })
        ack();assertFalse(lane.offer(LinearMove(.45,2000),1999) { _,_->fail() })
        assertTrue(lane.offer(LinearMove(.45,2000),2000) { _,_-> })
    }
    @Test fun independentLanesAndTimeoutDeadline() {
        val slow=LinearLane(50);val fast=LinearLane(50);slow.arm(.5);fast.arm(.5)
        slow.offer(LinearMove(.55,2000),0) { _,_-> };fast.offer(LinearMove(.55,2000),0) { _,ack->ack() }
        assertFalse(slow.timedOut(1500));assertTrue(slow.timedOut(1501))
        assertFalse(slow.offer(LinearMove(.45,2000),5000) { _,_->fail("timeout revival") })
        assertTrue(fast.offer(LinearMove(.45,2000),2000) { _,_-> })
    }
    @Test fun stopInvalidatesLateAckAndReconnectionNeedsKnownPosition() {
        val lane=LinearLane(0);var old:()->Unit={};var fresh:()->Unit={}
        lane.arm(.5);lane.offer(LinearMove(.55,2000),0) { _,ack->old=ack };lane.invalidate();old()
        assertFalse(lane.offer(LinearMove(.45,2000),3000) { _,_->fail() })
        lane.arm(.5);lane.offer(LinearMove(.55,2000),4000) { _,ack->fresh=ack };old()
        assertFalse(lane.offer(LinearMove(.45,2000),6000) { _,_->fail() });fresh()
        assertTrue(lane.offer(LinearMove(.45,2000),6000) { _,_-> })
    }
    @Test fun stopPolicyCannotBeEnabledByPreferences() { assertFalse(HandyStopPolicy.continuousOutputEnabled) }
}
