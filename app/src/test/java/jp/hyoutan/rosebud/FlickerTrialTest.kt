package jp.hyoutan.rosebud

import org.junit.Assert.*
import org.junit.Test

class FlickerTrialTest {
    @Test fun unsetAndInvalidDurationNeverBecomeASelectedDefault() {
        for(text in listOf("", "0", "0.099", "2.001", "NaN", "Infinity", "1e100", "-1")) assertNull(text,FlickerTrialLimits.parseDuration(text))
        assertEquals(100L,FlickerTrialLimits.parseDuration("0.1"))
        assertEquals(500L,FlickerTrialLimits.parseDuration("０。５"))
        assertEquals(2000L,FlickerTrialLimits.parseDuration("2.0"))
    }
    @Test fun trialNeverRoundsUpOrExceedsTheChosenValueOrCap() {
        assertNull(FlickerTrialLimits.output(0,34,255))
        assertNull(FlickerTrialLimits.output(35,34,255))
        assertNull(FlickerTrialLimits.output(1,34,5))
        for(steps in listOf(5,255,65535)) for(percent in 1..34) {
            val output=FlickerTrialLimits.output(percent,34,steps)
            if(output!=null) { assertTrue(output>0);assertTrue(output<=percent/100.0);assertTrue(output<=.34) }
        }
    }
}
