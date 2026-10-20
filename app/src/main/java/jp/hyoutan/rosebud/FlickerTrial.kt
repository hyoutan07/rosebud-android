package jp.hyoutan.rosebud

import java.math.BigDecimal
import java.text.Normalizer
import kotlin.math.floor

/** Bounds for a short manual experiment, not measured device motion parameters. No defaults are selected. */
internal object FlickerTrialLimits {
    const val minMs = 100L
    const val maxMs = 10000L
    fun parseDuration(text: String): Long? = runCatching {
        val normalized = Normalizer.normalize(text.trim(), Normalizer.Form.NFKC).replace('。', '.')
        BigDecimal(normalized).multiply(BigDecimal(1000)).longValueExact().takeIf { it in minMs..maxMs }
    }.getOrNull()
    fun output(percent: Int, cap: Int, steps: Int): Double? {
        if(percent !in 1..cap.coerceAtMost(100) || steps<=0) return null
        val step = floor(percent/100.0*steps).toInt()
        // Never round a small requested trial upward to a larger physical step.
        return if(step<=0) null else step.toDouble()/steps
    }
}
