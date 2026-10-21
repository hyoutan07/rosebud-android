package jp.hyoutan.rosebud

import org.junit.Assert.*
import org.junit.Test

class HandySessionTest {
    @Test fun configuredUpperIsAnAbsoluteLimitNotAStrokeWidth() {
        val bounds = HandyBounds.create(5.0, 119.0, 85.0)
        assertEquals(6.0, bounds.target(0.0), 0.0)
        assertEquals(45.0, bounds.target(.5), 0.0)
        assertEquals(84.0, bounds.target(1.0), 0.0)
        assertFalse(bounds.contains(90.0))
    }
    @Test fun inverseMappingMatchesInsetTargetsAndClampsOnlyTheDesignCoordinate() {
        val bounds = HandyBounds(5.0, 85.0)
        assertEquals(0.0, bounds.normalized(6.0), 0.0)
        assertEquals(.5, bounds.normalized(45.0), 0.0)
        assertEquals(1.0, bounds.normalized(84.0), 0.0)
        assertEquals(0.0, bounds.normalized(5.333), 0.0)
        assertEquals(1.0, bounds.normalized(84.667), 0.0)
        assertThrows(IllegalArgumentException::class.java) { bounds.normalized(85.333) }
    }
    @Test fun envelopesWithoutRoomForBothMarginsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { HandyBounds(5.0, 7.0) }
        assertThrows(IllegalArgumentException::class.java) { HandyBounds(5.0, 6.5) }
        assertThrows(IllegalArgumentException::class.java) { HandyBounds.create(5.0, 7.0, 85.0) }
    }
    @Test fun deviceRangeMayFurtherReduceUserUpper() {
        assertEquals(74.0, HandyBounds.create(5.0, 75.0, 85.0).target(1.0), 0.0)
    }
    @Test fun readingAPositionOutsideUpperDoesNotAlignOrClampIt() {
        val bounds = HandyBounds.create(5.0, 119.0, 85.0)
        assertThrows(IllegalArgumentException::class.java) { bounds.requireStart(90.0, 0.0, 1000, 1000) }
        assertThrows(IllegalArgumentException::class.java) { bounds.requireStart(45.0, 32.0, 1000, 1000) }
        assertThrows(IllegalArgumentException::class.java) { bounds.requireStart(45.0, 0.0, 1000, 3100) }
        assertThrows(IllegalArgumentException::class.java) { bounds.requireStart(45.0, 0.0, 1100, 1000) }
        bounds.requireStart(45.0, 0.0, 1000, 3000)
    }
    @Test fun physicalMaximumSpeedExtendsTooFastRequestsButDoesNotShortenSlowOnes() {
        val bounds = HandyBounds(5.0, 85.0)
        assertEquals(2000L, bounds.duration(5.0, 85.0, 250, 40.0))
        assertEquals(10000L, bounds.duration(5.0, 85.0, 10000, 400.0))
        assertThrows(IllegalArgumentException::class.java) { bounds.duration(5.0, 90.0, 1000, 400.0) }
        assertThrows(IllegalArgumentException::class.java) { bounds.duration(5.0, 80.0, 1000, Double.NaN) }
    }
    @Test fun emptyOrInvalidEnvelopeCannotArmMovement() {
        assertThrows(IllegalArgumentException::class.java) { HandyBounds.create(5.0, 119.0, 5.0) }
        assertThrows(IllegalArgumentException::class.java) { HandyBounds.create(5.0, Double.NaN, 85.0) }
        assertThrows(IllegalArgumentException::class.java) { HandyBounds.create(5.0, 119.0, Double.POSITIVE_INFINITY) }
    }
}

class HandyScenarioPacingTest {
    private val bounds = HandyBounds(5.0, 85.0)

    @Test fun scenarioUsesPhysicalSpeedWithinReadDeviceLimits() {
        assertEquals(390L, bounds.scenarioDuration(6.0, 84.0, 500.0, 32.0, 200.0))
        assertEquals(2438L, bounds.scenarioDuration(6.0, 84.0, 1.0, 32.0, 400.0))
        assertEquals(300L, bounds.scenarioDuration(6.0, 84.0, 260.0, 32.0, 400.0))
    }
    @Test fun shortScenarioMovesHaveNoQuarterSecondOrMultiSecondFloor() {
        assertEquals(10L, bounds.scenarioDuration(45.0, 46.0, 100.0, 32.0, 400.0))
        assertEquals(1L, bounds.scenarioDuration(45.0, 45.0, 100.0, 32.0, 400.0))
        assertTrue(bounds.target(1.0) < 85.0)
        assertThrows(IllegalArgumentException::class.java) { bounds.scenarioDuration(45.0, 90.0, 100.0, 32.0, 400.0) }
    }
    @Test fun invalidSpeedInformationCannotArmANewLeg() {
        assertThrows(IllegalArgumentException::class.java) { bounds.scenarioDuration(6.0, 84.0, Double.NaN, 32.0, 400.0) }
        assertThrows(IllegalArgumentException::class.java) { bounds.scenarioDuration(6.0, 84.0, 100.0, 0.0, 400.0) }
        assertThrows(IllegalArgumentException::class.java) { bounds.scenarioDuration(6.0, 84.0, 100.0, 100.0, 32.0) }
    }
    @Test fun delayedFirmwareAcknowledgementDoesNotReplaceArrivalConfirmation() {
        val gate = HandyArrivalGate(84.0)
        assertFalse(gate.canContinue(84.0, 0.0, 2000))
        gate.acknowledge(3000)
        assertFalse(gate.canContinue(40.0, 0.0, 3000))
        assertFalse(gate.canContinue(84.0, 32.0, 3000))
        assertFalse(gate.canContinue(84.0, 0.0, 2999))
        assertTrue(gate.canContinue(84.0, 0.0, 3000))
    }
    @Test fun inputTestRetainsItsExplicitWaitAfterAcknowledgement() {
        val gate = HandyArrivalGate(84.0, 10000)
        gate.acknowledge(3000)
        assertFalse(gate.canContinue(84.0, 0.0, 3500))
        assertFalse(gate.canContinue(84.0, 0.0, 12999))
        assertTrue(gate.canContinue(84.0, 0.0, 13000))
    }
}
