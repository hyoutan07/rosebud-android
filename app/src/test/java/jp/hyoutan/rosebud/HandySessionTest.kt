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

class HandyContinuousStreamTest {
    private val bounds = HandyBounds(5.0, 85.0)

    @Test fun futureTargetsRemainInsideSavedLimitWithBothMargins() {
        assertEquals(6.0, bounds.streamCommand(45.0, 0.0, 200, 400.0).targetMm, 0.0)
        assertEquals(84.0, bounds.streamCommand(45.0, 1.0, 200, 400.0).targetMm, 0.0)
        assertThrows(IllegalArgumentException::class.java) { bounds.streamCommand(45.0, 1.01, 200, 400.0) }
        assertThrows(IllegalArgumentException::class.java) { bounds.streamCommand(90.0, .5, 200, 400.0) }
    }
    @Test fun stateLagExtendsTravelDurationWithoutAnArrivalFailureOrAnEndpointWait() {
        val command = bounds.streamCommand(6.0, 1.0, 200, 50.0)
        assertEquals(84.0, command.targetMm, 0.0)
        assertEquals(1560L, command.durationMs)
        val quantized = bounds.streamCommand(42.0, bounds.normalized(43.189343), 200, 400.0)
        assertEquals(43.189343, quantized.targetMm, .000001)
        assertEquals(200L, quantized.durationMs)
    }
    @Test fun invalidStreamInformationCannotArmANewCommand() {
        assertThrows(IllegalArgumentException::class.java) { bounds.streamCommand(45.0, .5, 200, Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { bounds.streamCommand(45.0, .5, 200, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { bounds.streamCommand(45.0, .5, 0, 400.0) }
    }
    @Test fun delayedAcknowledgementSchedulesOnlyTheNextCurrentSample() {
        val cadence = HandyStreamCadence(0)
        cadence.commandSent(0)
        assertEquals(75L, cadence.nextDelay(25))
        assertEquals(0L, cadence.nextDelay(1500))
        assertTrue(cadence.stateDue(1500))
        cadence.readCompleted(1500)
        cadence.commandSent(1500)
        assertFalse(cadence.stateDue(1749))
        assertTrue(cadence.stateDue(1750))
        assertEquals(100L, cadence.nextDelay(1500))
    }
    @Test fun lookaheadAdaptsToSerialRoundTripsAndRemainsBounded() {
        val cadence=HandyStreamCadence(0)
        val before=cadence.horizonMs()
        repeat(5) { i ->
            cadence.commandSent(i*1000L)
            cadence.commandCompleted(i*1000L+250)
            cadence.readCompleted(i*1000L+450,200)
        }
        assertTrue(cadence.horizonMs()>before)
        assertTrue(cadence.horizonMs() in 200..500)
        assertEquals(500,cadence.horizonMs())
        repeat(20) { i -> cadence.commandSent(i*100L);cadence.commandCompleted(i*100L+5);cadence.readCompleted(i*100L+10,5) }
        assertEquals(200,cadence.horizonMs())
    }
    @Test fun freshFiniteUpdatesContinueButMissingUpdatesHaveAFiniteDeadline() {
        val cadence = HandyStreamCadence(1000)
        assertTrue(cadence.stateFresh(1000, 2000))
        assertTrue(cadence.stateFresh(1000, 5000))
        assertFalse(cadence.stateFresh(1000, 5001))
        assertFalse(cadence.stateFresh(1000, 999))
        cadence.readCompleted(5000)
        assertFalse(cadence.stateDue(5249))
    }
    @Test fun inputTestStillRequiresAcknowledgementPositionAndStoppedSpeed() {
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

class HandyLimitDefaultsTest {
    @org.junit.Test fun default87MigrationChangesOnlyThePreviousDefault() {
        org.junit.Assert.assertEquals(87f,HandyLimits.migratedUpper(null),0f)
        org.junit.Assert.assertEquals(87f,HandyLimits.migratedUpper(85f),0f)
        org.junit.Assert.assertEquals(72f,HandyLimits.migratedUpper(72f),0f)
        org.junit.Assert.assertEquals(87f,HandyLimits.migratedUpper(87f),0f)
        org.junit.Assert.assertEquals(86.0,HandyBounds.create(5.0,119.0,87.0).target(1.0),0.0)
    }
}

class HandyTelemetryRecoveryTest {
    @org.junit.Test fun encoderRoundingAtBothEndsDoesNotRejectAnOtherwiseValidRead() {
        val high=67.6961669921875
        org.junit.Assert.assertEquals(high,HandyTelemetry.position(68.0,5.0,high)!!,0.0)
        org.junit.Assert.assertEquals(5.0,HandyTelemetry.position(4.6666665,5.0,high)!!,0.0)
        org.junit.Assert.assertEquals(30.0,HandyTelemetry.position(30.0,5.0,high)!!,0.0)
    }
    @org.junit.Test fun corruptOrLargeOutOfRangeSamplesCannotRefreshTrustedPosition() {
        for(p in listOf(Double.NaN,Double.POSITIVE_INFINITY,0.0,90.0)) {
            org.junit.Assert.assertNull(HandyTelemetry.position(p,5.0,87.0))
        }
        org.junit.Assert.assertNull(HandyTelemetry.position(5.0,87.0,5.0))
    }
    @org.junit.Test fun repeatedUnusableReadsDoNotRefreshTrustButARecoveredReadDoes() {
        val cadence=HandyStreamCadence(1000)
        var trustedSampleAt=1000L
        for(time in 1250L..5500L step 250) {
            cadence.readCompleted(time)
            org.junit.Assert.assertNull(HandyTelemetry.position(0.0,5.0,87.0))
        }
        org.junit.Assert.assertFalse(cadence.stateFresh(trustedSampleAt,5500))
        org.junit.Assert.assertNotNull(HandyTelemetry.position(42.0,5.0,87.0))
        trustedSampleAt=5750
        cadence.readCompleted(trustedSampleAt)
        org.junit.Assert.assertTrue(cadence.stateFresh(trustedSampleAt,5750))
        val bounds=HandyBounds(5.0,87.0)
        for(target in listOf(0.0,.5,1.0)) {
            val command=bounds.streamCommand(42.0,target,200,400.0)
            org.junit.Assert.assertTrue(command.targetMm in 6.0..86.0)
        }
    }
}

class HandyStartReadinessTest {
    private val bounds=HandyBounds(5.0,67.6961669921875)
    @org.junit.Test fun stopAcknowledgementDoesNotRequireTheFirstReadToBeStationary() {
        fun state(speed:Double,at:Long)=HandyDeviceState(5.0,67.6961669921875,26.666667938232422,speed,at)
        org.junit.Assert.assertNull(HandyStartReadiness.position(bounds,state(32.0,1000),1000))
        org.junit.Assert.assertNull(HandyStartReadiness.position(bounds,state(.2,1250),1250))
        org.junit.Assert.assertEquals(26.666667938232422,HandyStartReadiness.position(bounds,state(0.0,1500),1500)!!,0.0)
    }
    @org.junit.Test fun badReadWaitsAndTheNextValidReadCanStartWithoutChangingTheBounds() {
        val normal=HandyDeviceState(5.0,67.6961669921875,68.0,0.0,1000)
        org.junit.Assert.assertNull(HandyStartReadiness.position(bounds,normal.copy(speedMmPerSec=Double.NaN),1000))
        org.junit.Assert.assertNull(HandyStartReadiness.position(bounds,normal.copy(positionMm=100.0),1000))
        org.junit.Assert.assertNull(HandyStartReadiness.position(bounds,normal,3001))
        org.junit.Assert.assertEquals(bounds.upperMm,HandyStartReadiness.position(bounds,normal.copy(sampledAt=3250),3250)!!,0.0)
        org.junit.Assert.assertEquals(66.6961669921875,bounds.target(1.0),0.0)
    }
}

class HandyMaximumRangeTest {
    @org.junit.Test fun maximumModeUsesReportedLimitInsteadOfOld87Millimetres() {
        val bounds=HandyBounds.create(5.0,119.0,87.0,true)
        org.junit.Assert.assertEquals(119.0,bounds.upperMm,0.0)
        org.junit.Assert.assertEquals(6.0,bounds.target(0.0),0.0)
        org.junit.Assert.assertEquals(118.0,bounds.target(1.0),0.0)
    }
    @org.junit.Test fun manualCapRemainsEditableAndNeverOverridesTheReportedLimit() {
        org.junit.Assert.assertEquals(87.0,HandyBounds.create(5.0,119.0,87.0,false).upperMm,0.0)
        org.junit.Assert.assertEquals(100.0,HandyBounds.create(5.0,100.0,120.0,false).upperMm,0.0)
        org.junit.Assert.assertEquals(100.0,HandyBounds.create(5.0,100.0,87.0,true).upperMm,0.0)
    }
    @org.junit.Test fun widerStrokeExtendsTravelTimeInsteadOfIncreasingItsSpeed() {
        val old=HandyBounds.create(5.0,119.0,87.0)
        val wide=HandyBounds.create(5.0,119.0,87.0,true)
        val oldCommand=old.streamCommand(6.0,1.0,200,32.0)
        val wideCommand=wide.streamCommand(6.0,1.0,200,32.0)
        org.junit.Assert.assertEquals(2500L,oldCommand.durationMs)
        org.junit.Assert.assertEquals(3500L,wideCommand.durationMs)
        org.junit.Assert.assertEquals(118.0,wideCommand.targetMm,0.0)
    }
}
