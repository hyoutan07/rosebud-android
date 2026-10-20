package jp.hyoutan.rosebud

import org.junit.Assert.*
import org.junit.Test

class ScenarioEngineTest {
    @Test fun soundAndStageChangesDoNotReplaceHeldRecipeOrExtendDeadline() {
        val engine=ScenarioEngine(42)
        val first=engine.frame(0.0,SoundFeatures())
        assertTrue(first.recipeEnd in 30.0..60.0)
        for(i in 1..299) {
            val f=engine.frame(i/10.0,SoundFeatures(kind=SoundKind.entries[i%5]))
            assertEquals(first.recipe,f.recipe); assertEquals(first.recipeEnd,f.recipeEnd,0.0)
        }
        val boundary=engine.frame(first.recipeEnd,SoundFeatures(kind=SoundKind.REGULAR))
        assertEquals(first.recipeEnd,boundary.recipeStart,0.0)
        assertTrue(boundary.recipeEnd-boundary.recipeStart in 30.0..60.0)
        // All stage boundaries preserve an already running recipe.
        for(t in listOf(300.0,720.0,900.0,1800.0,2700.0)) {
            val before=engine.frame(t-.01,SoundFeatures())
            val after=engine.frame(t,SoundFeatures(kind=SoundKind.BROKEN))
            assertEquals(before.recipeEnd,after.recipeEnd,0.0)
        }
    }
    @Test fun multipleSeedsHaveEightRecipesNoTripleRepeatsAndBoundedEndlessOutput() {
        val seen=mutableSetOf<Int>(); val states=mutableSetOf<Strength>()
        var strongLate=0; var samplesLate=0
        val styles=IntArray(4)
        val periodStrong=IntArray(2);val periodTotal=IntArray(2)
        repeat(12) { seed ->
            val engine=ScenarioEngine(seed.toLong())
            var previous=-1; var repeats=0; var end=-1.0
            for(tick in 0..24000) {
                val t=tick*.2
                val f=engine.frame(t,SoundFeatures(kind=SoundKind.entries[seed%5]))
                seen.add(f.recipe); states.add(f.strength)
                if(f.recipeEnd!=end) {
                    repeats=if(f.recipe==previous) repeats+1 else 1
                    assertTrue("same recipe 3 times",repeats<=2)
                    assertTrue(f.recipeEnd-f.recipeStart in 30.0..60.0)
                    previous=f.recipe;end=f.recipeEnd
                }
                if(t>=900) assertEquals("エンドレス",f.phase)
                assertTrue(f.output.vibrate.isFinite() && f.output.vibrate in 0.0..1.0)
                assertTrue(f.output.flicker.isFinite() && f.output.flicker in 0.0..1.0)
                if(f.strength==Strength.REST_B) assertEquals(Output(),f.output)
                else if(f.strength==Strength.REST_A) { assertEquals(0.0,f.output.flicker,0.0); assertTrue(f.output.vibrate>0) }
                else assertTrue(quantize(f.output.flicker,.05,255)>0)
                if(t>=900 && t<2700) { val bin=if(t<1800) 0 else 1;periodTotal[bin]++;if(f.strength==Strength.STRONG)periodStrong[bin]++ }
                if(t>=2700) { samplesLate++;styles[f.style]++;if(f.strength==Strength.STRONG) strongLate++ }
            }
        }
        assertEquals((0..7).toSet(),seen); assertEquals(Strength.entries.toSet(),states)
        val fraction=strongLate.toDouble()/samplesLate
        println("45〜80分、12seed・0.2秒刻み：強め状態=$fraction / style=${styles.toList()}")
        println("15〜30分・30〜45分の強め実時間配分="+periodStrong.indices.map { periodStrong[it].toDouble()/periodTotal[it] })
        assertEquals(.734,periodStrong[0].toDouble()/periodTotal[0],.07)
        assertEquals(.781,periodStrong[1].toDouble()/periodTotal[1],.07)
        assertEquals(.805,fraction,.045)
        assertEquals(.75,styles[3].toDouble()/samplesLate,.06)
    }
    @Test fun quietingActsWithinHeldRecipeAndDoesNotStopItsClock() {
        val loud=ScenarioEngine(23);val quiet=ScenarioEngine(23)
        var lower=0
        for(tick in 0..250) {
            val a=loud.frame(tick*.1,SoundFeatures(kind=SoundKind.SMOOTH,level=1.0))
            val b=quiet.frame(tick*.1,SoundFeatures(kind=if(tick<100) SoundKind.SMOOTH else SoundKind.QUIET,level=if(tick<100) 1.0 else 0.0))
            assertEquals(a.recipe,b.recipe);assertEquals(a.recipeEnd,b.recipeEnd,0.0)
            if(b.strength !in listOf(Strength.REST_A,Strength.REST_B)) {
                assertTrue(b.output.flicker>0)
                if(tick>110 && b.output.flicker<a.output.flicker) lower++
            }
        }
        assertTrue(lower>50)
    }
    @Test fun cadenceDoesNotChangeRandomSelectionsAndWeightsFollowSound() {
        val fine=ScenarioEngine(25);val coarse=ScenarioEngine(25)
        for(i in 0..3600) {
            val a=fine.frame(i*.25,SoundFeatures())
            if(i%4==0) { val b=coarse.frame(i*.25,SoundFeatures());assertEquals(a.recipe,b.recipe);assertEquals(a.recipeEnd,b.recipeEnd,0.0);assertEquals(a.style,b.style) }
        }
        val smooth=IntArray(8);val regular=IntArray(8)
        repeat(1000) { seed ->
            smooth[ScenarioEngine(seed.toLong()*9871).frame(0.0,SoundFeatures(kind=SoundKind.SMOOTH)).recipe]++
            regular[ScenarioEngine(seed.toLong()*9871).frame(0.0,SoundFeatures(kind=SoundKind.REGULAR)).recipe]++
        }
        assertTrue(smooth[0]+smooth[6]>regular[0]+regular[6])
        assertTrue(regular[2]+regular[3]>smooth[2]+smooth[3])
        assertTrue(smooth.count { it>0 }>=6 && regular.count { it>0 }>=6)
        for(t in listOf(0.0,300.0,720.0,900.0,1800.0,2700.0,1e6)) assertEquals(1.0,ScenarioEngine.styleWeights(t).sum(),1e-9)
        assertArrayEquals(MotionDefaults.mix15,ScenarioEngine.styleWeights(900.0),1e-9)
        assertArrayEquals(doubleArrayOf(0.0,.075,.30,.625),ScenarioEngine.styleWeights(1800.0),1e-9)
        assertArrayEquals(MotionDefaults.mix45,ScenarioEngine.styleWeights(2700.0),1e-9)
    }
    @Test fun lightweightFeaturesFindRegularOnsetsAndQuietImmediatelyWithoutRetainingPcm() {
        val features=AudioFeatures()
        var now=0L
        repeat(100) { i -> features.add(if(i%5==0) .1 else .01,now);now+=100 }
        assertEquals(SoundKind.REGULAR,features.snapshot.kind)
        assertTrue(features.snapshot.regularity>.9)
        assertTrue(features.snapshot.density>0)
        features.add(0.0,now)
        assertEquals(SoundKind.QUIET,features.snapshot.kind)
        repeat(100) { features.add(.05,now+100+it*100L) }
        assertEquals(SoundKind.SMOOTH,features.snapshot.kind)
        assertEquals(0.0,features.snapshot.density,0.0)
    }
    @Test(expected=IllegalArgumentException::class) fun nonFiniteTimeIsRejected() { ScenarioEngine(0).frame(Double.NaN,SoundFeatures()) }
}
