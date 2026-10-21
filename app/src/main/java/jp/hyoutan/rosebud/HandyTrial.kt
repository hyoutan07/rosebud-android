package jp.hyoutan.rosebud

import java.math.BigDecimal

/** Design points only: none are a measured physical position or an authorization to send. */
internal data class HandyTrialPoint(val percent: Double, val durationMs: Long)
internal data class HandyTrialPlan(val mode: String, val initialPercent: Double, val points: List<HandyTrialPoint>) {
    val totalMs get() = points.sumOf { it.durationMs }
    init {
        require(mode in listOf("range","sequence"))
        require(initialPercent.isFinite() && initialPercent in 0.0..100.0) { "図の開始点は0〜100%で入力してください" }
        require(points.size in 1..20) { "区間数は1〜20にしてください" }
        points.forEach {
            require(it.percent.isFinite() && it.percent in 0.0..100.0) { "位置は0〜100%で入力してください" }
            require(it.durationMs in 250..20000) { "1区間の時間は0.25〜20秒で入力してください" }
        }
        require(totalMs <= 120000) { "1回のテストは120秒以内にしてください" }
    }
    /** Virtual interval progress, deliberately not named reached/current device position. */
    fun frame(elapsedMs: Long): HandyTrialFrame {
        val elapsed = elapsedMs.coerceIn(0,totalMs)
        var began = 0L; var from = initialPercent
        points.forEachIndexed { i,p ->
            val end = began+p.durationMs
            if(elapsed < end || i==points.lastIndex) {
                val fraction = ((elapsed-began).toDouble()/p.durationMs).coerceIn(0.0,1.0)
                return HandyTrialFrame(elapsed,i,from+(p.percent-from)*fraction,elapsed==totalMs)
            }
            from=p.percent; began=end
        }
        error("empty trial")
    }
    fun share(notes: String, previewStatus: String, actualStatus: String? = null, maximumMm: Double? = null): String {
        require(maximumMm == null || maximumMm.isFinite() && maximumMm > 0)
        val actual = actualStatus?.takeIf { it in listOf("未実施","途中終了","完了","エラー") } ?: "未実施"
        val status = previewStatus.takeIf { it in listOf("未実施","途中終了","完了") } ?: "未実施"
        return buildString {
            appendLine("Handyテスト案 v1")
            appendLine(if(actual == "未実施") "確認：入力プレビューのみ／実機未確認" else "確認：実機送信テスト $actual（物理動作・停止の確認は感想に記入）")
            if(maximumMm != null) appendLine("設定した位置上限：${number(maximumMm)}mm（機器の可動範囲とも照合・両端1mm余白）")
            appendLine("秒数は指令の予定時間です。最低速度によって移動後に間が入る場合があります。")
            appendLine("入力方式：${if(mode=="range") "かんたん往復" else "位置列"}")
            appendLine("指定開始位置：${number(initialPercent)}%（図は予定値・受信位置とは別）")
            points.forEachIndexed { i,p -> appendLine("${i+1}. ${number(p.percent)}% / ${number(p.durationMs/1000.0)}秒") }
            appendLine("合計：${number(totalMs/1000.0)}秒 / ${points.size}区間")
            appendLine("入力プレビュー：$status")
            append("感想：${notes.filter { it=='\n' || it=='\t' || !it.isISOControl() }.trim().take(500)}")
        }
    }
    companion object {
        fun number(value: Double) = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        private fun normal(value: String) = value.trim().map { c -> if(c in '０'..'９') '0'+(c-'０') else when(c) { '．' -> '.'; '，' -> ','; '　' -> ' '; else -> c } }.joinToString("")
        private fun decimal(value: String, title: String): BigDecimal {
            val v=normal(value)
            require(v.length<=12 && v.matches(Regex("[0-9]+(?:\\.[0-9]{1,3})?"))) { "${title}を半角または全角の数値で入力してください（小数3桁まで）" }
            return v.toBigDecimal()
        }
        private fun position(value: String) = decimal(value,"位置").toDouble().also {
            require(it in 0.0..100.0) { "位置は0〜100%で入力してください" }
        }
        private fun duration(value: String) = decimal(value,"秒数").also {
            require(it >= BigDecimal("0.25") && it <= BigDecimal("20")) { "1区間の時間は0.25〜20秒で入力してください" }
        }.multiply(BigDecimal(1000)).longValueExact()
        private fun count(value: String) = normal(value).toIntOrNull()?.also { require(it in 1..10) { "繰り返しは1〜10回で入力してください" } }
            ?: throw IllegalArgumentException("繰り返しは1〜10回で入力してください")
        fun range(lower: String, upper: String, seconds: String, cycles: String): HandyTrialPlan {
            val l=position(lower);val u=position(upper);val t=duration(seconds);val n=count(cycles)
            require(l<u) { "下限は上限より小さくしてください" }
            return HandyTrialPlan("range",l,List(n*2) { HandyTrialPoint(if(it%2==0) u else l,t) })
        }
        fun sequence(initial: String, rows: String, repeats: String): HandyTrialPlan {
            require(rows.length<=2000) { "位置列は2000文字以内にしてください" }
            val start=position(initial);val n=count(repeats)
            val lines=normal(rows).lines().filter { it.isNotBlank() }
            require(lines.size in 1..20 && lines.size*n<=20) { "繰り返しを含めて1〜20区間にしてください" }
            val moves=lines.mapIndexed { index,line ->
                val cells=line.split(',')
                require(cells.size==2) { "${index+1}行目は「目標位置, 秒数」で入力してください" }
                HandyTrialPoint(position(cells[0]),duration(cells[1]))
            }
            return HandyTrialPlan("sequence",start,List(moves.size*n) { moves[it%moves.size] })
        }
    }
}
internal data class HandyTrialFrame(val elapsedMs: Long, val segment: Int, val diagramPercent: Double, val complete: Boolean)
