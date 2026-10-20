package jp.hyoutan.rosebud

/** Monotonic deadlines, independent of audio and the 100 ms V/F generation loop.
 * Missed commands are replaced by the current value; never replay a historical pulse. */
internal class SuctionTrack {
    var intervalMs = 9000L; private set
    var durationMs = 300L; private set
    var target = 5; private set
    var automatic = false; private set
    var level = 0; private set
    var revision = 0L; private set
    var engaged = false; private set
    private var endAt: Long? = null
    private var nextAt = 0L
    private var repeatAt: Long? = null
    private var safetyAt = 0L
    val zeroRepeatPending get() = repeatAt != null
    val busy get() = automatic || level > 0
    fun configure(interval: Long, duration: Long, targetLevel: Int) {
        require(interval >= 1000 && interval % 1000 == 0L)
        require(duration >= 50 && duration % 50 == 0L)
        require(targetLevel in 0..5)
        intervalMs = interval; durationMs = duration; target = targetLevel
        // Current pulse duration is captured at its start. Interval is read at its end.
    }
    private fun output(value: Int) { level = value; revision++; engaged = true }
    fun manual(value: Int) { require(value in 0..5); if (!automatic) repeatAt = null; output(value) }
    fun toggle(enabled: Boolean, now: Long) {
        automatic = enabled; endAt = null; repeatAt = null
        if (enabled) { nextAt = now; tick(now) }
        else { output(0); repeatAt = now + 150 }
    }
    fun tick(now: Long) {
        val end = endAt
        if (automatic && end != null && now >= end) {
            output(0); endAt = null; nextAt = now + intervalMs
            repeatAt = now + 150; safetyAt = now + 300
        }
        if (automatic && endAt == null && now >= nextAt) {
            repeatAt = null; output(target); endAt = now + durationMs
        }
        if (repeatAt?.let { now >= it } == true) { repeatAt = null; output(0) }
        if (automatic && endAt == null && now >= safetyAt) { output(0); safetyAt = now + 300 }
    }
    fun reset() {
        automatic = false; level = 0; engaged = false; endAt = null; repeatAt = null; revision++
    }
}
