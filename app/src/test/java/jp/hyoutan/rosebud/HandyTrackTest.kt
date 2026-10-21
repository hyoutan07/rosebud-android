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
        val mid=t.tick(20000,SoundFeatures(SoundKind.BROKEN))
        assertEquals(20000,mid.ageMs)
        val boundary=t.tick(mid.segmentEndMs,SoundFeatures(SoundKind.BROKEN))
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
    @Test fun normalizedRangeAndSpeedAreBoundedAcrossStages() {
        val settings=HandySettings(lower=.4,upper=.6,halfMs=2000)
        val t=HandyTrack(11,settings);t.start(0);var previous=.5
        for(time in 0L..4000000L step 1000) {
            val f=t.tick(time,SoundFeatures(SoundKind.REGULAR))
            f.move?.let { m -> assertTrue(m.target in settings.lower..settings.upper);assertTrue(m.durationMs in 2000..20000)
                assertTrue(kotlin.math.abs(m.target-previous)*1000/m.durationMs<=.100001);previous=m.target }
        }
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
    @Test(expected=IllegalArgumentException::class) fun excessiveRangeRejected() { HandySettings(lower=.2,upper=.8) }
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
