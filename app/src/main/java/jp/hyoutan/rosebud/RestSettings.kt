package jp.hyoutan.rosebud

/** Planned Flicker zero-output time as a percentage of active session time. */
internal object RestDefaults {
    const val intro = 33
    const val middle = 8
    const val finish = 1
    const val maxPercent = 50
}
internal data class RestSettings(val intro: Int = RestDefaults.intro, val middle: Int = RestDefaults.middle,
    val finish: Int = RestDefaults.finish) {
    init { require(intro in 0..RestDefaults.maxPercent && middle in 0..RestDefaults.maxPercent && finish in 0..RestDefaults.maxPercent) }
}
