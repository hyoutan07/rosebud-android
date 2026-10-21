package jp.hyoutan.rosebud

import kotlin.math.abs
import kotlin.math.ceil

/** A session freezes its physical envelope. Changing preferences never changes a leg in flight. */
internal data class HandyBounds(val minimumMm: Double, val upperMm: Double) {
    init { require(minimumMm.isFinite() && upperMm.isFinite() && upperMm - minimumMm > 2.0) { "機器の下限から上限まで2mmより広く設定してください（両端に1mmの余白を取ります）" } }
    val targetMinimumMm get() = minimumMm + 1.0
    val targetMaximumMm get() = upperMm - 1.0
    fun target(percent: Double): Double {
        require(percent.isFinite() && percent in 0.0..1.0)
        return targetMinimumMm + (targetMaximumMm - targetMinimumMm) * percent
    }
    fun normalized(position: Double): Double {
        require(contains(position))
        return ((position - targetMinimumMm) / (targetMaximumMm - targetMinimumMm)).coerceIn(0.0, 1.0)
    }
    fun contains(position: Double) = position.isFinite() && position >= minimumMm - .05 && position <= upperMm + .05
    fun requireStart(position: Double, speed: Double, sampledAt: Long, now: Long) {
        require(now - sampledAt in 0..2000 && speed.isFinite() && abs(speed) <= .1) { "停止中の現在位置を取得できませんでした。再接続してください" }
        require(contains(position)) { "現在位置が設定範囲の外です。本体で上限内へ戻してから開始してください" }
    }
    fun duration(from: Double, target: Double, plannedMs: Long, maximumSpeed: Double): Long {
        require(contains(from) && contains(target) && plannedMs in 250..20000)
        require(maximumSpeed.isFinite() && maximumSpeed > 0)
        return maxOf(plannedMs, ceil(abs(target - from) / maximumSpeed * 1000).toLong(), 250)
    }
    /** Scenario pace is a physical speed request; no input-test wait is added at the endpoint. */
    fun scenarioDuration(from: Double, target: Double, requestedSpeed: Double, minimumSpeed: Double, maximumSpeed: Double): Long {
        require(contains(from) && contains(target))
        require(requestedSpeed.isFinite() && requestedSpeed > 0)
        require(minimumSpeed.isFinite() && maximumSpeed.isFinite() && minimumSpeed > 0 && maximumSpeed >= minimumSpeed)
        val speed = requestedSpeed.coerceIn(minimumSpeed, maximumSpeed)
        val duration = ceil(abs(target - from) / speed * 1000).toLong().coerceAtLeast(1)
        require(duration <= 120000) { "指定速度での移動時間が長すぎます" }
        return duration
    }
    companion object {
        fun create(deviceMinimum: Double, deviceMaximum: Double, userUpper: Double): HandyBounds {
            require(deviceMinimum.isFinite() && deviceMaximum.isFinite() && userUpper.isFinite())
            require(userUpper in 1.0..200.0) { "上限は1〜200mmで入力してください" }
            return HandyBounds(deviceMinimum, minOf(deviceMaximum, userUpper))
        }
    }
}
internal data class HandySnapshot(
    val connected: Boolean = false, val connecting: Boolean = false,
    val running: Boolean = false, val trialActive: Boolean = false, val stopping: Boolean = false,
    val status: String = "未接続", val error: String? = null, val frame: HandyFrame = HandyFrame(),
    val positionMm: Double? = null, val upperMm: Double = 85.0,
    val minimumMm: Double? = null, val maximumMm: Double? = null,
    val trialStatus: String = "未実施",
)

/** A firmware acknowledgement does not mean the carriage has reached the requested position. */
internal class HandyArrivalGate(private val targetMm: Double, private val minimumWaitMs: Long = 0) {
    private var acceptedAt: Long? = null
    init { require(targetMm.isFinite() && minimumWaitMs >= 0) }
    fun acknowledge(time: Long) { if (acceptedAt == null) acceptedAt = time }
    fun canContinue(positionMm: Double, speedMmPerSec: Double, time: Long): Boolean {
        val accepted = acceptedAt ?: return false
        return time >= accepted && time - accepted >= minimumWaitMs && positionMm.isFinite() && speedMmPerSec.isFinite() &&
            abs(positionMm - targetMm) <= 1.0 && abs(speedMmPerSec) <= .1
    }
}
