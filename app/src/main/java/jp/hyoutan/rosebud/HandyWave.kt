package jp.hyoutan.rosebud

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.ceil

/** Continuous normalized trajectory. Velocity is signed; targetPosition is a bounded lookahead. */
internal data class HandyWaveFrame(val position: Double, val velocityPerSec: Double,
    val speedMmPerSec: Double, val targetPosition: Double, val suggestedHorizonMs: Long,
    val plannedRest: Boolean)

/** Randomness is consumed only when planning a half-wave, never once per control tick.
 * Quintic easing has zero velocity/acceleration at a turn, with no dwell between half-waves.
 * A configuration change is taken at the next turn, preserving position and velocity continuity. */
internal class HandyWave(seed: Long, initialPosition: Double, private val beganAt: Long) {
    private val random = SeededRandom(seed xor 0x57415645L)
    private data class Segment(val from: Double, val to: Double, val began: Long, val duration: Long, val rest: Boolean) {
        val end get() = began+duration
        fun at(time: Long): Pair<Double,Double> {
            val u=((time-began).toDouble()/duration).coerceIn(0.0,1.0)
            val ease=u*u*u*(10+u*(-15+6*u))
            val derivative=30*u*u*(1-u)*(1-u)
            return Pair(from+(to-from)*ease,(to-from)*derivative*1000/duration)
        }
    }
    private val segments=java.util.ArrayDeque<Segment>()
    private val initial=initialPosition.also { require(it.isFinite() && it in 0.0..1.0) }
    private var anchorPosition=initial
    private var anchorAt=beganAt
    private var forward=initial<.5
    private var count=0L

    fun sample(time: Long, pattern: Int, peakSpeedMmPerSec: Double, physicalSpanMm: Double,
        lower: Double=0.0, upper: Double=1.0, weak: Boolean=false, horizonMs: Long=200, currentNormalized: Double?=null): HandyWaveFrame {
        require(time>=beganAt && pattern in 0..12)
        require(peakSpeedMmPerSec.isFinite() && peakSpeedMmPerSec>0)
        require(physicalSpanMm.isFinite() && physicalSpanMm>0)
        require(lower.isFinite() && upper.isFinite() && lower>=0 && upper<=1 && lower<upper)
        require(horizonMs in 80..500)
        require(currentNormalized==null || currentNormalized.isFinite() && currentNormalized in 0.0..1.0)
        fun append() {
            val previous=segments.peekLast()
            val from=previous?.to ?: anchorPosition
            val began=previous?.end ?: anchorAt
            val chance=when { weak || pattern in setOf(0,4,10) -> 0.0; pattern<3 -> .04; pattern<10 -> .025; else -> .012 }
            val rest=previous!=null && !previous.rest && random.next()<chance
            if(rest) {
                segments.addLast(Segment(from,from,began,150+(random.next()*200).toLong(),true))
                return
            }
            // Eight of ten half-waves span almost the full range. Brief local passages add variety.
            val whole=weak.not() && (count%10<8 || pattern in setOf(0,4,10))
            val width=upper-lower
            val low: Double
            val high: Double
            if(weak) {
                val center=((from-lower)/width).coerceIn(.09,.91)
                low=(center-.07).coerceAtLeast(.01);high=(center+.07).coerceAtMost(.99)
            } else if(whole) {
                low=.02+.11*random.next();high=.87+.11*random.next()
            } else {
                val center=when(pattern) {
                    1,3,11 -> .70+.12*random.next()
                    2,5 -> .18+.12*random.next()
                    7,8 -> if(count%20<10) .22 else .78
                    else -> .25+.5*random.next()
                }
                val localWidth=.16+.12*random.next()
                low=(center-localWidth/2).coerceIn(.02,.82)
                high=(center+localWidth/2).coerceIn(.18,.98)
            }
            var target=lower+width*(if(forward) high else low)
            // Local passages must still advance, rather than repeatedly issuing an identical endpoint.
            if(abs(target-from)<width*.025) target=lower+width*(if(forward) low else high)
            val peak=if(weak) 32.0 else peakSpeedMmPerSec*(.85+.15*random.next())
            // max(d/dt quintic)=1.875; respect the requested physical peak without artificial dwell.
            val duration=ceil(max(180.0,abs(target-from)*physicalSpanMm*1.875/peak*1000)).toLong()
            segments.addLast(Segment(from,target,began,duration,false))
            forward=!forward;count++
        }
        var caughtUp=0
        while(segments.isEmpty() || segments.peekLast().end<=time+horizonMs) {
            if(caughtUp++>=256) {
                // A stalled handler needs one fresh trajectory, never an unbounded history backlog.
                anchorPosition=(currentNormalized ?: segments.peekLast().to).coerceIn(lower,upper)
                anchorAt=time;segments.clear();forward=anchorPosition<(lower+upper)/2
                caughtUp=0
            }
            append()
        }
        while(segments.size>1 && segments.elementAt(1).began<=time) segments.removeFirst()
        val current=segments.first { time<it.end }
        val future=segments.first { time+horizonMs<it.end }
        val (position,velocity)=current.at(time)
        val target=future.at(time+horizonMs).first
        return HandyWaveFrame(position,velocity,abs(velocity)*physicalSpanMm,target,horizonMs,current.rest)
    }
}
