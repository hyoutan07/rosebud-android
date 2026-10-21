package jp.hyoutan.rosebud

import org.junit.Assert.*
import org.junit.Test

class HandyTrialTest {
    @Test fun rangeIsFiniteAndStartsAtDeclaredLowerPointWithoutAddingAnAlignmentMove() {
        val p=HandyTrialPlan.range("45","55","3","3")
        assertEquals(45.0,p.initialPercent,0.0);assertEquals(6,p.points.size);assertEquals(18000,p.totalMs)
        assertEquals(listOf(55.0,45.0,55.0,45.0,55.0,45.0),p.points.map { it.percent })
    }
    @Test fun sequencePreservesAsymmetricTimesAndRepeatJunctions() {
        val p=HandyTrialPlan.sequence("45","55,2.001\n48,4.500\n45,3", "2")
        assertEquals(listOf(2001L,4500L,3000L,2001L,4500L,3000L),p.points.map { it.durationMs })
        assertEquals(19002,p.totalMs)
    }
    @Test fun fullWidthInputAndBlankLinesAreAccepted() {
        val a=HandyTrialPlan.sequence("４５", "５５，３．００１\n\n４５，２", "１")
        assertEquals(5001,a.totalMs)
    }
    @Test fun virtualClockSeparatesIntervalsAndFinishesWithoutReplayingOldMoves() {
        val p=HandyTrialPlan.range("45","55","3","1")
        assertEquals(45.0,p.frame(-100).diagramPercent,0.0)
        assertEquals(50.0,p.frame(1500).diagramPercent,1e-9)
        assertEquals(0,p.frame(2999).segment);assertEquals(1,p.frame(3000).segment)
        assertFalse(p.frame(5999).complete);assertTrue(p.frame(6000).complete)
        assertEquals(p.frame(6000),p.frame(86400000))
    }
    @Test fun exactTimeBudgetIsAccepted() {
        assertEquals(120000,HandyTrialPlan.range("45","55","20","3").totalMs)
    }
    @Test fun twentyMovesAcceptedAndTwentyOneRejected() {
        assertEquals(20,HandyTrialPlan.sequence("45","55,2\n45,2","10").points.size)
        rejects { HandyTrialPlan.sequence("45",List(21) { "55,2" }.joinToString("\n"),"1") }
    }
    @Test fun sourceInputCannotRepresentAnInfiniteLoop() {
        for(n in listOf("0","-1","11","99999999999999999999","1.5")) rejects { HandyTrialPlan.range("45","55","3",n) }
    }
    @Test fun emptyAndMalformedRowsRejectedBeforePlayback() {
        for(rows in listOf("","55","55,3,8",",3","55,","55;3")) rejects { HandyTrialPlan.sequence("45",rows,"1") }
    }
    @Test fun nanInfinityExponentAndTooPreciseValuesAreRejected() {
        for(value in listOf("NaN","Infinity","1e2","45.1234","-45")) rejects { HandyTrialPlan.range(value,"55","3","1") }
    }
    @Test fun rangeAndDurationLimitsApplyToBothInputModes() {
        rejects { HandyTrialPlan.range("55","45","3","1") }
        rejects { HandyTrialPlan.range("101","102","3","1") }
        rejects { HandyTrialPlan.range("45","101","3","1") }
        for(seconds in listOf("0.249","20.001")) rejects { HandyTrialPlan.sequence("45","55,$seconds","1") }
    }
    @Test fun totalTimeBudgetAndInitialPositionAreChecked() {
        rejects { HandyTrialPlan.range("45","55","20","4") }
        rejects { HandyTrialPlan.sequence("","55,3","1") }
    }
    @Test fun directPlansCannotBypassValidation() {
        rejects { HandyTrialPlan("sequence",45.0,emptyList()) }
        rejects { HandyTrialPlan("sequence",45.0,listOf(HandyTrialPoint(Double.NaN,2000))) }
        rejects { HandyTrialPlan("sequence",45.0,listOf(HandyTrialPoint(55.0,0))) }
    }
    @Test fun shareContainsExactPointsUnitsNotesAndNoClaimOfPhysicalValidation() {
        val p=HandyTrialPlan.sequence("45","55,2.001\n45,3","1")
        val text=p.share("  上りは良い\n戻りをゆっくりに  ","完了")
        assertTrue(text.contains("Handyテスト案 v1"));assertTrue(text.contains("55% / 2.001秒"))
        assertTrue(text.contains("実機未確認"));assertTrue(text.contains("図は予定値・受信位置とは別"));assertTrue(text.contains("上りは良い\n戻りをゆっくりに"))
        assertFalse(text.contains("ws://"));assertFalse(text.contains("Firmware確認済み"))
    }
    @Test fun shareRejectsInventedTestStatusAndBoundsNotes() {
        val p=HandyTrialPlan.range("45","55","3","1")
        val text=p.share("a".repeat(1000)+"\u0000","物理停止確認済み")
        assertTrue(text.contains("入力プレビュー：未実施"));assertFalse(text.contains("\u0000"));assertEquals(500,text.substringAfter("感想：").length)
    }
    @Test fun fullRangeShortSchedulesAndRepeatedJunctionsAreAccepted() {
        val p=HandyTrialPlan.range("0","100","0.25","10")
        assertEquals(20,p.points.size);assertEquals(5000,p.totalMs)
        val sequence=HandyTrialPlan.sequence("0","100,0.25\n0,20","2")
        assertEquals(40500,sequence.totalMs)
    }
    @Test fun actualSharingRecordsSettingsWithoutClaimingPhysicalVerification() {
        val p=HandyTrialPlan.range("0","100","3","1")
        val text=p.share("途中で止まった","途中終了",actualStatus="途中終了",maximumMm=85.0)
        assertTrue(text.contains("85mm"));assertTrue(text.contains("実機送信テスト 途中終了"))
        assertTrue(text.contains("最低速度"));assertTrue(text.contains("物理動作・停止の確認は感想に記入"))
        assertFalse(text.contains("物理停止確認済み"))
        assertTrue(p.share("","完了","物理停止確認済み",85.0).contains("実機未確認"))
        rejects { p.share("","完了",maximumMm=Double.NaN) }
    }
    private fun rejects(block:()->Unit) {
        try { block();fail("expected invalid trial") } catch(_:IllegalArgumentException) { }
    }
}
