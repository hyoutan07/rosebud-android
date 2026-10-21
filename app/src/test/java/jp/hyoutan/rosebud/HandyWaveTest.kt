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
            assertTrue(abs(point.velocityPerSec-previous.velocityPerSec)<.1)
            val measured=(point.position-previous.position)*1000
            assertEquals((point.velocityPerSec+previous.velocityPerSec)/2,measured,.001)
            val sign=when { point.velocityPerSec>1e-7 -> 1;point.velocityPerSec< -1e-7 -> -1;else -> 0 }
            if(sign!=0 && direction!=0 && sign!=direction) turns++
            if(sign!=0) direction=sign
            previous=point
        }
        assertTrue(turns>15)
    }
    @Test fun everyCandidateIsDominatedByLargeFullRangeWavesWithVaryingEndpoints() {
        for(pattern in 0..12) {
            val wave=HandyWave(80+pattern.toLong(),.5,0)
            var previous=wave.sample(0,pattern,130.0,80.0)
            var direction=0
            var lastTurn=.5
            var halfCount=0
            var wide=0
            val lows=mutableSetOf<Int>();val highs=mutableSetOf<Int>()
            for(time in 5L..120000L step 5) {
                val point=wave.sample(time,pattern,130.0,80.0)
                val sign=when { point.velocityPerSec>1e-7 -> 1;point.velocityPerSec< -1e-7 -> -1;else -> 0 }
                if(sign!=0 && direction!=0 && sign!=direction) {
                    val turn=previous.position
                    if(abs(turn-lastTurn)>.6) wide++
                    if(turn<.15) lows+=(turn*10000).toInt()
                    if(turn>.85) highs+=(turn*10000).toInt()
                    halfCount++;lastTurn=turn
                }
                if(sign!=0) direction=sign
                previous=point
            }
            assertTrue("pattern $pattern wide=$wide/$halfCount",wide.toDouble()/halfCount>.60)
            assertTrue("pattern $pattern variable lows",lows.size>10)
            assertTrue("pattern $pattern variable highs",highs.size>10)
        }
    }
    @Test fun configChangesKeepPositionAndVelocityContinuousAtTheNextTurn() {
        val wave=HandyWave(91,.3,0)
        var before=wave.sample(0,1,45.0,80.0)
        for(time in 1L..12000) {
            val after=wave.sample(time,if(time<4000) 1 else 12,if(time<4000) 45.0 else 240.0,80.0)
            assertTrue(abs(after.position-before.position)<.004)
            assertTrue(abs(after.velocityPerSec-before.velocityPerSec)<.12)
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
        assertTrue(abs(next.position-after.position)<.1)
    }
    @Test fun streamHorizonIsForwardedAndBounded() {
        val track=HandyTrack(4);track.start(0)
        assertEquals(450,track.sampleTrajectory(0,SoundFeatures(),.5,80.0,450).move!!.durationMs)
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
}
