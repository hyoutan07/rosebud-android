package jp.hyoutan.rosebud

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class HandyWaveTest {
    @Test fun deterministicAndIndependentOfSamplingFrequencyForTheSameParameters() {
        val dense=HandyWave(14,.5,0);val sparse=HandyWave(14,.5,0)
        for(time in 0L..30000L step 10) {
            val actual=dense.sample(time,8,130.0,80.0)
            if(time%1000==0L) assertEquals(actual,sparse.sample(time,8,130.0,80.0))
        }
        val other=HandyWave(15,.5,0)
        assertNotEquals(dense.sample(31000,8,130.0,80.0).position,other.sample(31000,8,130.0,80.0).position,1e-6)
    }
    @Test fun positionAndVelocityRemainContinuousIncludingRandomTurns() {
        val wave=HandyWave(3,.5,0)
        var previous=wave.sample(0,10,230.0,80.0)
        var turns=0
        var direction=0
        for(time in 1L..20000) {
            val point=wave.sample(time,10,230.0,80.0)
            assertTrue(point.position in 0.0..1.0);assertTrue(point.targetPosition in 0.0..1.0)
            assertTrue(point.speedMmPerSec<=230.001)
            assertTrue(abs(point.position-previous.position)<=.003)
            assertTrue(abs(point.velocityPerSec-previous.velocityPerSec)<.2)
            val measured=(point.position-previous.position)*1000
            assertEquals((point.velocityPerSec+previous.velocityPerSec)/2,measured,.001)
            val sign=when { point.velocityPerSec>1e-7 -> 1;point.velocityPerSec< -1e-7 -> -1;else -> 0 }
            if(sign!=0 && direction!=0 && sign!=direction) turns++
            if(sign!=0) direction=sign
            previous=point
        }
        assertTrue(turns>15)
    }
    @Test fun localWavesDominateBeforeFinishAndLargeWavesDominateFinish() {
        for(pattern in 0..12) {
            val wave=HandyWave(80+pattern.toLong(),.5,0)
            var previous=wave.sample(0,pattern,130.0,80.0)
            var direction=0
            var lastTurn=.5
            var halfCount=0
            var wide=0
            val turns=mutableSetOf<Int>()
            for(time in 5L..120000L step 5) {
                val point=wave.sample(time,pattern,130.0,80.0)
                val sign=when { point.velocityPerSec>1e-7 -> 1;point.velocityPerSec< -1e-7 -> -1;else -> 0 }
                if(sign!=0 && direction!=0 && sign!=direction) {
                    val turn=previous.position
                    if(abs(turn-lastTurn)>.6) wide++
                    turns+=(turn*10000).toInt()
                    halfCount++;lastTurn=turn
                }
                if(sign!=0) direction=sign
                previous=point
            }
            val share=wide.toDouble()/halfCount
            if(pattern<3) assertTrue("intro wide=$share",share<.4)
            else if(pattern<10) assertTrue("middle wide=$share",share<.65)
            else assertTrue("finish wide=$share",share>.6)
            assertTrue("pattern $pattern variable local turns",turns.size>(if(pattern<10) 5 else 1))
        }
    }
    @Test fun configChangesKeepPositionAndVelocityContinuousAtTheNextTurn() {
        val wave=HandyWave(91,.3,0)
        var before=wave.sample(0,1,45.0,80.0)
        for(time in 1L..12000) {
            val after=wave.sample(time,if(time<4000) 1 else 12,if(time<4000) 45.0 else 240.0,80.0)
            assertTrue(abs(after.position-before.position)<.004)
            assertTrue(abs(after.velocityPerSec-before.velocityPerSec)<.2)
            before=after
        }
    }
    @Test fun weakWaveStaysNearItsInitialAreaAndHasNoPlannedRest() {
        val wave=HandyWave(51,.23,0)
        for(time in 0L..30000L step 10) {
            val p=wave.sample(time,12,32.0,80.0,weak=true)
            assertFalse(p.plannedRest);assertTrue(p.position in .08.. .38)
            assertTrue(p.speedMmPerSec<=32.001)
        }
    }
    @Test(timeout=2000) fun longGapRebasesFromFreshPositionWithoutAnUnboundedHistory() {
        val wave=HandyWave(8,.2,0)
        wave.sample(0,10,260.0,80.0)
        val after=wave.sample(7L*86400000,10,260.0,80.0,currentNormalized=.34)
        assertEquals(.34,after.position,0.0)
        assertEquals(0.0,after.velocityPerSec,0.0)
        assertTrue(after.targetPosition in 0.0..1.0)
        val next=wave.sample(7L*86400000+100,10,260.0,80.0,currentNormalized=.34)
        assertTrue(abs(next.position-after.position)<=260.0/80.0*.1+.001)
    }
    @Test fun streamHorizonIsForwardedAndBounded() {
        val track=HandyTrack(4);track.start(0)
        assertTrue(track.sampleTrajectory(0,SoundFeatures(),.5,80.0,450).move!!.durationMs in 50..450)
        try { track.sampleTrajectory(100,SoundFeatures(),.5,80.0,501);fail() } catch(_:IllegalArgumentException) { }
    }
    @Test fun trajectoryTrackUsesTheSameClockAndCanStopWithoutRevival() {
        val track=HandyTrack(4);track.start(0)
        val first=track.sampleTrajectory(0,SoundFeatures(),.2,80.0)
        assertNotNull(first.move);assertEquals(200,first.move!!.durationMs)
        track.sampleTrajectory(1000,SoundFeatures(),.4,80.0)
        assertEquals(1000,track.frame.elapsedMs)
        track.weaken(true,1000);track.alignPosition(.4)
        for(time in 1000L..10000L step 100) {
            val f=track.sampleTrajectory(time,SoundFeatures(),.4,80.0)
            assertEquals(1000,f.elapsedMs);assertFalse(f.plannedRest)
        }
        track.stop();assertFalse(track.sampleTrajectory(12000,SoundFeatures(),.4,80.0).running)
        assertNull(track.frame.move)
    }
    @Test fun fastLookaheadKeepsTheFullTurnInsteadOfAveragingAcrossIt() {
        val wave=HandyWave(5,.5,0)
        val first=wave.sample(0,10,400.0,80.0,horizonMs=500,fullStrokeChance=1.0)
        assertEquals(0.0,first.targetPosition,0.0)
        assertTrue(first.suggestedHorizonMs in 50..200)
    }
    @Test fun exactFullSpanTurnsBecomeMoreCommonTowardFinish() {
        fun share(pattern: Int): Double {
            val wave=HandyWave(94,.5,0)
            var direction=0;var previous=.5;var turns=0;var exact=0
            for(time in 0L..240000L step 5) {
                val p=wave.sample(time,pattern,150.0,80.0)
                val sign=if(p.velocityPerSec>1e-8) 1 else if(p.velocityPerSec< -1e-8) -1 else 0
                if(sign!=0 && direction!=0 && sign!=direction) {
                    turns++;if(previous<.001 || previous>.999) exact++
                }
                if(sign!=0) direction=sign
                previous=p.position
            }
            return exact.toDouble()/turns
        }
        val intro=share(1);val middle=share(5);val finish=share(11)
        assertTrue("intro=$intro middle=$middle finish=$finish",middle>intro+.10 && finish>middle+.10)
        assertTrue(finish>.85)
    }
    @Test fun shortRestsAreScatteredMoreOftenDuringIntro() {
        for(pattern in listOf(1,5,11)) {
            val wave=HandyWave(6,.5,0)
            var restAt:Long?=null;var lastEnd=0L;var rests=0
            for(time in 0L..180000L step 10) {
                val p=wave.sample(time,pattern,180.0,80.0)
                if(p.plannedRest && restAt==null) { assertTrue(time-lastEnd>=if(pattern<3) 4000 else if(pattern<10) 8000 else 16000);restAt=time }
                if(!p.plannedRest && restAt!=null) {
                    assertTrue(time-restAt in if(pattern<3) 990..2010 else if(pattern<10) 690..1510 else 390..1010);rests++;lastEnd=time;restAt=null
                }
            }
            assertTrue(rests>=2)
        }
    }
    @Test fun finishTrajectoryHasClearlyHigherSustainedPace() {
        fun average(stage:String):Double {
            val track=HandyTrack(31);track.start(0);track.selectStage(stage,0)
            var sum=0.0;var n=0
            for(time in 5000L..45000L step 20) { sum+=track.sampleTrajectory(time,SoundFeatures(),.5,80.0).speedMmPerSec;n++ }
            return sum/n
        }
        assertTrue(average("フィニッシュ")>average("はじめ")*3)
    }

    @Test fun introUsesLowSpeedEvenWhenReportedPositionLagsBehindTheTrajectory() {
        val track=HandyTrack(31);track.start(0)
        val bounds=HandyBounds(5.0,87.0)
        var moves=0
        for(time in 0L..45000L step 100) {
            val frame=track.sampleTrajectory(time,SoundFeatures(),.5,80.0)
            assertTrue(frame.speedLimitMmPerSec==32.0)
            frame.move?.let { move ->
                val command=bounds.streamCommand(6.0,move.target,move.durationMs,frame.speedLimitMmPerSec)
                val distance=abs(command.targetMm-6.0)
                assertTrue(distance/command.durationMs*1000<=32.00001)
                moves++
            }
        }
        assertTrue(moves>100)
    }

    @Test fun broadTravelHasItsOwnSlowLimitBeforeFinishIncludingLaggedCommands() {
        for((pattern,limit) in listOf(1 to 32.0,5 to 40.0,11 to 400.0)) {
            val wave=HandyWave(9,.5,0)
            val bounds=HandyBounds(5.0,87.0)
            for(time in 0L..15000L step 100) {
                val p=wave.sample(time,pattern,400.0,80.0,fullStrokeChance=1.0)
                if(!p.plannedRest) {
                    assertEquals(limit,p.speedLimitMmPerSec,0.0)
                    assertTrue(p.speedMmPerSec<=limit+.0001)
                    val command=bounds.streamCommand(6.0,p.targetPosition,p.suggestedHorizonMs,p.speedLimitMmPerSec)
                    assertTrue(abs(command.targetMm-6.0)/command.durationMs*1000<=limit+.0001)
                }
            }
        }
    }
    @Test fun restFrequencyAndRestTimeAreHigherInIntroThanInLaterStages() {
        fun rests(pattern:Int):Pair<Int,Int> {
            val wave=HandyWave(61,.5,0);var count=0;var restSamples=0;var wasRest=false
            for(time in 0L..240000L step 10) {
                val p=wave.sample(time,pattern,130.0,80.0)
                if(p.plannedRest) { restSamples++;if(!wasRest) count++ }
                wasRest=p.plannedRest
            }
            return Pair(count,restSamples)
        }
        val intro=rests(1);val middle=rests(5);val finish=rests(11)
        assertTrue("$intro $middle $finish",intro.first>middle.first && middle.first>finish.first)
        assertTrue(intro.second>middle.second && middle.second>finish.second)
    }
    @Test fun middleSlowsDownWhileFinishRetainsFastEnvelope() {
        for(stage in listOf("中間","フィニッシュ")) {
            val track=HandyTrack(31);track.start(0);track.selectStage(stage,0)
            var moving=0
            for(time in 5000L..45000L step 100) {
                val frame=track.sampleTrajectory(time,SoundFeatures(),.5,80.0)
                if(frame.move!=null) {
                    if(stage=="中間") assertTrue(frame.speedLimitMmPerSec<=130)
                    else assertTrue(frame.speedLimitMmPerSec>=300)
                    moving++
                }
            }
            assertTrue(moving>100)
        }
    }

}
