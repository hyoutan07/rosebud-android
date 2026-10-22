package jp.hyoutan.rosebud

import org.junit.Assert.*
import org.junit.Test

class HandyRpcTest {
    private val id = 0x41234567L
    private fun response(method: Int? = null, payload: ByteArray = byteArrayOf(), extra: ByteArray = byteArrayOf()): ByteArray =
        HandyRpc.uint(1, 3) + HandyRpc.bytes(4, HandyRpc.uint(1, id) + HandyRpc.uint(3, 1) + extra +
            (method?.let { HandyRpc.bytes(it, payload) } ?: byteArrayOf()))
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected malformed input to fail") } catch (_: IllegalArgumentException) { }
    }
    @Test fun movementUsesAbsoluteFloatMillisecondsAndStopOnTarget() {
        val envelope = HandyRpc.fields(HandyRpc.request(id, 743, 85.0, 2500))
        assertEquals(1L, envelope.first().number)
        val inner = HandyRpc.fields(envelope.single { it.tag == 2 }.bytes)
        assertEquals(id, inner.single { it.tag == 2 }.number)
        val movement = HandyRpc.fields(inner.single { it.tag == 743 }.bytes)
        assertEquals(5, movement.single { it.tag == 1 }.wire)
        assertEquals(2500L, movement.single { it.tag == 2 }.number)
        assertEquals(1L, movement.single { it.tag == 3 }.number)
    }
    @Test fun firmwareTransportMetadataIsNotAResult() {
        val reply = HandyRpc.reply(response())!!
        assertEquals(1, reply.transport)
        assertTrue(HandyRpc.validate(reply, id, 715))
        assertFalse(HandyRpc.validate(reply, id + 1, 715))
    }
    @Test fun finiteAbsoluteValuesAndConfiguredSpeedsDecode() {
        val stroke = HandyRpc.reply(response(840, HandyRpc.float(3, 5f) + HandyRpc.float(4, 119f)))!!
        assertTrue(HandyRpc.validate(stroke, id, 840))
        assertEquals(119.0, stroke.values["maximumMm"]!!, 0.0)
        val settings = HandyRpc.reply(response(2003, HandyRpc.uint(6, 32) + HandyRpc.uint(7, 400)))!!
        assertEquals(32.0, settings.values["setting6"]!!, 0.0)
        assertEquals(400.0, settings.values["setting7"]!!, 0.0)
    }
    @Test fun firmwareErrorReasonIsAvailableForSettingDiagnosis() {
        val payload=HandyRpc.uint(1,5)+HandyRpc.bytes(2,"setting blocked".toByteArray())
        val reply=HandyRpc.reply(response(extra=HandyRpc.bytes(2,payload)))!!
        assertEquals(5,reply.errorCode);assertEquals("setting blocked",reply.errorMessage)
    }
    @Test fun errorPresenceFailsEvenWhenFirmwareCodeIsZero() {
        val reply = HandyRpc.reply(response(extra = HandyRpc.bytes(2, HandyRpc.uint(1, 0))))!!
        rejected { HandyRpc.validate(reply, id, 715) }
    }
    @Test fun unexpectedOrMissingResultFails() {
        rejected { HandyRpc.validate(HandyRpc.reply(response())!!, id, 842) }
        rejected { HandyRpc.validate(HandyRpc.reply(response(842))!!, id, 715) }
    }
    @Test fun nonfiniteTelemetryAndTargetsAreRefused() {
        rejected { HandyRpc.reply(response(842, HandyRpc.float(2, Float.NaN))) }
        rejected { HandyRpc.reply(response(840, HandyRpc.float(4, Float.POSITIVE_INFINITY))) }
        for (target in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE)) {
            rejected { HandyRpc.request(id, 743, target, 1000) }
        }
    }
    @Test fun malformedFieldsCannotBecomeAcceptedStop() {
        rejected { HandyRpc.reply(response(extra = HandyRpc.uint(1, id))) }
        rejected { HandyRpc.reply(response(extra = HandyRpc.uint(3, 1))) }
        rejected { HandyRpc.reply(response(extra = HandyRpc.bytes(3, byteArrayOf()))) }
        rejected { HandyRpc.reply(response(842, extra = HandyRpc.bytes(840, byteArrayOf()))) }
        rejected { HandyRpc.reply(response(extra = HandyRpc.uint(3, 6))) }
        rejected { HandyRpc.reply(byteArrayOf(8, 3, 34, 100, 8)) }
        rejected { HandyRpc.fields(byteArrayOf(0)) }
        rejected { HandyRpc.fields(ByteArray(10) { 0xff.toByte() }) }
        rejected { HandyRpc.fields(ByteArray(4097)) }
    }
    @Test fun onlyWhitelistedReadAndMotionMethodsCanBeEncoded() {
        rejected { HandyRpc.request(id, 2005) }
        rejected { HandyRpc.request(id, 715, 5.0, 1000) }
        rejected { HandyRpc.request(1, 715) }
        rejected { HandyRpc.request(id, 743, 5.0, 0) }
        rejected { HandyRpc.request(id, 743, 5.0, 120001) }
    }
}

class HandyFullStrokeRpcTest {
    @Test fun fullStrokeCommandIsOnlyZeroToOneAndIsNotAMoveOrCalibration() {
        val envelope=HandyRpc.fields(HandyRpc.request(0x41234567L,841))
        val request=HandyRpc.fields(envelope.single { it.tag==2 }.bytes)
        val params=HandyRpc.fields(request.single { it.tag==841 }.bytes)
        assertEquals(setOf(1,2),params.map { it.tag }.toSet())
        fun value(tag:Int)=java.nio.ByteBuffer.wrap(params.single { it.tag==tag }.bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
        assertEquals(0f,value(1),0f);assertEquals(1f,value(2),0f)
        assertFalse(request.any { it.tag in listOf(743,843,2004) })
    }
    @Test fun acknowledgementAndReadbackMustContainCompleteNormalizedStroke() {
        val id=0x41234567L
        fun reply(method:Int,low:Float,high:Float):HandyRpc.Reply {
            return HandyRpc.reply(HandyRpc.uint(1,3)+HandyRpc.bytes(4,HandyRpc.uint(1,id)+HandyRpc.bytes(method,
                HandyRpc.float(1,low)+HandyRpc.float(2,high)+HandyRpc.float(3,5f)+HandyRpc.float(4,119f))))!!
        }
        val full=reply(841,0f,1f)
        assertTrue(HandyRpc.validate(full,id,841));assertTrue(HandyStrokeRange.isFull(full))
        assertEquals(119.0,full.values["maximumMm"]!!,0.0)
        assertFalse(HandyStrokeRange.isFull(reply(840,0f,.73f)))
        assertFalse(HandyStrokeRange.isFull(reply(840,.2f,1f)))
    }
}

class HandyEndBufferRpcTest {
    @Test fun writesOnlyEndBufferZeroWithItsPresenceFlag() {
        val outer=HandyRpc.fields(HandyRpc.request(0x41234567L,2004))
        val request=HandyRpc.fields(outer.single { it.tag==2 }.bytes)
        val fields=HandyRpc.fields(request.single { it.tag==2004 }.bytes)
        assertEquals(setOf(3,19),fields.map { it.tag }.toSet())
        assertEquals(0L,fields.single { it.tag==3 }.number)
        assertEquals(1L,fields.single { it.tag==19 }.number)
        val ack=HandyRpc.Reply(0x41234567L,false,null,1,emptyMap())
        assertTrue(HandyRpc.validate(ack,0x41234567L,2004))
    }
    @Test fun readbackRejectsAnyOtherChangedSetting() {
        val values=mapOf("xLimitStart" to 0.0,"xLimitStop" to 123.993,"setting3" to 5.0,"setting6" to 32.0,"setting7" to 400.0)
        val before=HandyRpc.Reply(1,false,2003,1,values)
        val after=before.copy(values=values+("setting3" to 0.0))
        assertTrue(HandyEndBufferChange.verified(before,after))
        assertFalse(HandyEndBufferChange.verified(before,after.copy(values=after.values+("setting7" to 500.0))))
        assertFalse(HandyEndBufferChange.verified(before,before))
    }
}
