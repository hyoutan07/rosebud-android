package jp.hyoutan.rosebud

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal firmware 4.3 RPC schema confirmed by the standalone BLE probe. The only settings write is an explicit zero end-buffer command. */
internal object HandyRpc {
    data class Reply(val id: Long, val error: Boolean, val result: Int?, val transport: Int, val values: Map<String, Double>, val errorCode: Int? = null, val errorMessage: String? = null)
    internal data class Field(val tag: Int, val wire: Int, val number: Long = 0, val bytes: ByteArray = byteArrayOf())
    fun request(id: Long, method: Int, targetMm: Double? = null, durationMs: Long? = null): ByteArray {
        require(id in 0x40000000L..0xffffffffL && method in setOf(715, 743, 840, 841, 842, 2003, 2004))
        val params = if (method == 743) {
            require(targetMm != null && targetMm.isFinite() && targetMm.toFloat().isFinite())
            require(durationMs != null && durationMs in 1..120000L)
            float(1, targetMm.toFloat()) + uint(2, durationMs) + uint(3, 1)
        } else if(method==841) {
            require(targetMm==null && durationMs==null)
            float(1,0f)+float(2,1f) // Only the user-requested complete normalized stroke, never calibration/settings.
        } else if(method==2004) {
            require(targetMm==null && durationMs==null)
            uint(3,0)+uint(19,1) // xEndBuffer=0; hasXEndBuffer=true. No other settings flags.
        } else { require(targetMm == null && durationMs == null); byteArrayOf() }
        return uint(1, 1) + bytes(2, uint(2, id) + bytes(method, params))
    }
    fun reply(data: ByteArray): Reply? {
        val envelope = fields(data)
        if (number(envelope, 1, true) != 3L) return null
        require(envelope.count { it.tag in 2..5 } == 1) { "invalid_envelope" }
        val inner = fields(one(envelope, 4, 2, true)!!.bytes)
        val id = number(inner, 1, true)
        val errorFields = one(inner, 2, 2)?.let { fields(it.bytes) }
        val error = errorFields!=null
        val errorCode = errorFields?.let { one(it,1,0)?.number?.toInt() ?: 0 }
        val errorMessage = errorFields?.let { one(it,2,2)?.bytes?.toString(Charsets.UTF_8) }
        val transport = number(inner, 3).also { require(it in 0..5) }.toInt()
        val result = inner.filter { it.tag !in 1..3 }.also { require(it.size <= 1) }.firstOrNull()
        val values = mutableMapOf<String, Double>()
        result?.let {
            require(it.wire == 2)
            val fs = fields(it.bytes)
            val names = when (it.tag) {
                840, 841 -> listOf("min", "max", "minimumMm", "maximumMm")
                842 -> listOf("position", "positionMm", "motorTempC", "speedMmPerSec")
                2003 -> listOf("xLimitStart", "xLimitStop")
                else -> emptyList()
            }
            names.forEachIndexed { i, name -> values[name] = floatValue(fs, i + 1) }
            if (it.tag == 2003) {
                for(tag in 3..12) values["setting$tag"]=number(fs,tag).toDouble()
                for(tag in 14..16) values["setting$tag"]=floatValue(fs,tag)
                for(tag in 17..21) values["setting$tag"]=number(fs,tag).toDouble()
            }
        }
        return Reply(id, error, result?.tag, transport, values,errorCode,errorMessage)
    }
    /** An unrelated (late) reply is ignored; a matching error is always a failure, even code zero. */
    fun validate(reply: Reply, id: Long, method: Int): Boolean {
        if (reply.id != id) return false
        require(!reply.error) { "firmware_error:${reply.errorCode}:${reply.errorMessage.orEmpty()}" }
        require(reply.result == if (method in setOf(840, 841, 842, 2003)) method else null) { "unexpected_result" }
        return true
    }
    internal fun fields(data: ByteArray): List<Field> {
        require(data.size <= 4096)
        var offset = 0
        fun read(): Long {
            var n = 0L
            for (i in 0..9) {
                require(offset < data.size) { "truncated_varint" }
                val b = data[offset++].toInt() and 255
                require(i != 9 || b <= 1) { "varint_overflow" }
                n = n or ((b and 127).toLong() shl (i * 7))
                if (b and 128 == 0) return n
            }
            error("varint_overflow")
        }
        val result = mutableListOf<Field>()
        while (offset < data.size) {
            require(result.size < 128)
            val key = read()
            val tag = key ushr 3
            val wire = (key and 7).toInt()
            require(tag in 1..0x1fffffff)
            if (wire == 0) result += Field(tag.toInt(), wire, number = read())
            else {
                require(wire in setOf(1, 2, 5))
                val size = if (wire == 2) read() else if (wire == 1) 8L else 4L
                require(size in 0..(data.size - offset).toLong())
                result += Field(tag.toInt(), wire, bytes = data.copyOfRange(offset, offset + size.toInt()))
                offset += size.toInt()
            }
        }
        return result
    }
    private fun one(fs: List<Field>, tag: Int, wire: Int, required: Boolean = false): Field? {
        val matches = fs.filter { it.tag == tag }
        require(matches.size <= 1 && (!required || matches.size == 1))
        return matches.firstOrNull()?.also { require(it.wire == wire) }
    }
    private fun number(fs: List<Field>, tag: Int, required: Boolean = false): Long =
        (one(fs, tag, 0, required)?.number ?: 0).also { require(it in 0..0xffffffffL) }
    private fun floatValue(fs: List<Field>, tag: Int): Double {
        val bytes = one(fs, tag, 5)?.bytes ?: return 0.0 // proto3 default zero
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).float.toDouble().also { require(it.isFinite()) }
    }
    internal fun uint(tag: Int, n: Long): ByteArray = varint(tag.toLong() * 8) + varint(n)
    internal fun bytes(tag: Int, value: ByteArray): ByteArray = varint(tag.toLong() * 8 + 2) + varint(value.size.toLong()) + value
    internal fun float(tag: Int, value: Float): ByteArray = varint(tag.toLong() * 8 + 5) + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()
    private fun varint(n: Long): ByteArray {
        require(n >= 0)
        var value = n
        val out = ByteArrayOutputStream()
        do { val b = (value and 127).toInt(); value = value ushr 7; out.write(b or if (value != 0L) 128 else 0) } while (value != 0L)
        return out.toByteArray()
    }
}

internal data class HandyDeviceState(
    val minimumMm: Double, val maximumMm: Double, val positionMm: Double,
    val speedMmPerSec: Double, val sampledAt: Long, val minimumSpeedMmPerSec: Double = 32.0, val maximumSpeedMmPerSec: Double = 400.0,
)
