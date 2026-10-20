package jp.hyoutan.rosebud

import org.junit.Assert.*
import org.junit.Test

class SuctionTrackTest {
    @Test fun defaultsAreDisarmedAndTickNeverStartsThem() {
        val s=SuctionTrack(); s.tick(100000)
        assertEquals(9000L,s.intervalMs); assertEquals(300L,s.durationMs); assertEquals(5,s.target)
        assertFalse(s.automatic); assertFalse(s.engaged); assertEquals(0,s.level)
    }
    @Test fun fiftyMillisecondsIsNotRoundedToHundredAndIntervalBeginsAtEnd() {
        val s=SuctionTrack(); s.configure(1000,50,4); s.toggle(true,100)
        assertEquals(4,s.level); s.tick(149); assertEquals(4,s.level)
        s.tick(150); assertEquals(0,s.level)
        s.tick(1149); assertEquals(0,s.level); s.tick(1150); assertEquals(4,s.level)
    }
    @Test fun zeroAtEndPlus150AndEvery300DuringWaiting() {
        val s=SuctionTrack(); s.toggle(true,0); val start=s.revision
        s.tick(300); assertEquals(start+1,s.revision); assertEquals(0,s.level)
        s.tick(449); assertEquals(start+1,s.revision)
        s.tick(450); assertEquals(start+2,s.revision)
        s.tick(600); assertEquals(start+3,s.revision)
        s.tick(900); assertEquals(start+4,s.revision)
    }
    @Test fun offAndReonInvalidateEveryPreviousDeadline() {
        val s=SuctionTrack(); s.toggle(true,0); s.toggle(false,100)
        s.configure(1000,1000,3); s.toggle(true,200)
        s.tick(300); assertEquals(3,s.level); s.tick(450); assertEquals(3,s.level)
        s.tick(1200); assertEquals(0,s.level)
        s.reset(); val revision=s.revision; s.tick(99999)
        assertEquals(revision,s.revision); assertFalse(s.engaged)
    }
    @Test fun manualKeepsAutomaticAndCurrentPulseDeadlineWhileSettingsAffectNextPulse() {
        val s=SuctionTrack(); s.toggle(true,0); s.manual(2); s.configure(1000,50,4)
        assertTrue(s.automatic); s.tick(299); assertEquals(2,s.level)
        s.tick(300); assertEquals(0,s.level); s.tick(1300); assertEquals(4,s.level)
        s.tick(1350); assertEquals(0,s.level)
    }
    @Test fun offRepeatsZeroOnceAndDoesNotRestartAndManualSupersedesThatRetry() {
        val s=SuctionTrack(); s.toggle(false,0); val rev=s.revision
        s.tick(150); assertEquals(rev+1,s.revision); s.tick(10000); assertEquals(rev+1,s.revision)
        s.toggle(false,10000); s.manual(3); s.tick(10150); assertEquals(3,s.level)
    }
    @Test fun missedTimeDoesNotReplayHistoricalPulses() {
        val s=SuctionTrack(); s.toggle(true,0); s.tick(100000)
        assertEquals(0,s.level); s.tick(108999); assertEquals(0,s.level)
        s.tick(109000); assertEquals(5,s.level)
    }
    @Test fun settingsRejectUnsupportedSteps() {
        val s=SuctionTrack()
        for(action in listOf<()->Unit>({s.configure(999,50,5)},{s.configure(1000,51,5)},{s.configure(1000,50,6)})) {
            try { action(); fail("must reject") } catch (_:IllegalArgumentException) { }
        }
    }
}
