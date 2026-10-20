package jp.hyoutan.rosebud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** Green = vibration, rose = movement. History is generated commands, never sensor measurements. */
class MotionWaveView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val points = ArrayDeque<Pair<Double,Double>>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sample = -1
    private val path = Path()
    internal fun add(output: Output) {
        points.addLast(output.vibrate to output.flicker)
        if(points.size > 100) points.removeFirst()
        invalidate()
    }
    internal fun sample(recipe: Int) { sample = recipe; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.strokeWidth=resources.displayMetrics.density
        paint.color=Color.rgb(37,48,65)
        for(i in 1..2) canvas.drawLine(0f,height*i/3f,width.toFloat(),height*i/3f,paint)
        val data = if(sample >= 0) (0..100).map {
            val y=ScenarioEngine.shape(recipes[sample].shape,it/30.0)
            (.03+y*.22) to sampleFlicker(sample,it*.2)
        } else points.toList()
        if(data.size<2) return
        for(channel in 0..1) {
            paint.color=Color.parseColor(if(channel==0) "#34D399" else "#F472B6")
            paint.strokeWidth=2.2f*resources.displayMetrics.density; paint.style=Paint.Style.STROKE
            path.reset()
            data.forEachIndexed { i,p ->
                val x=i*width.toFloat()/(data.size-1)
                val y=(height-8-(if(channel==0) p.first else p.second)*(height-16)).toFloat()
                if(i==0) path.moveTo(x,y) else {
                    if(channel==1) {
                        val previousY=(height-8-data[i-1].second*(height-16)).toFloat()
                        path.lineTo(x,previousY)
                    }
                    path.lineTo(x,y)
                }
            }
            canvas.drawPath(path,paint); paint.style=Paint.Style.FILL
        }
    }
}
