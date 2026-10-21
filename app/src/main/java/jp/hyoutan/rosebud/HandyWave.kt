package jp.hyoutan.rosebud

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.ceil

/** Continuous normalized trajectory. Velocity is signed; targetPosition is a bounded lookahead. */
internal data class HandyWaveFrame(val position: Double, val velocityPerSec: Double,
    val speedMmPerSec: Double, val targetPosition: Double, val suggestedHorizonMs: Long,
    val plannedRest: Boolean, val speedLimitMmPerSec: Double)

/** Randomness is consumed only when planning a half-wave, never once per control tick.
 * Rounded ramps have zero velocity/acceleration at a turn, with a longer fast middle and no dwell.
 * A configuration change is taken at the next turn, preserving position and velocity continuity. */
internal class HandyWave(seed: Long, initialPosition: Double, private val beganAt: Long) {
    private val random = SeededRandom(seed xor 0x57415645L)
    private data class Segment(val from: Double, val to: Double, val began: Long, val duration: Long, val rest: Boolean, val speedLimit: Double) {
        val end get() = began+duration
        fun at(time: Long): Pair<Double,Double> {
            val u=((time-began).toDouble()/duration).coerceIn(0.0,1.0)
            val ramp=.18
            fun rising(x: Double): Pair<Double,Double> {
                val z=x/ramp
                return Pair(ramp*(z*z*z-.5*z*z*z*z)/(1-ramp),z*z*(3-2*z)/(1-ramp))
            }
            val (ease,derivative)=when {
                u<ramp -> rising(u)
                u>1-ramp -> rising(1-u).let { Pair(1-it.first,it.second) }
                else -> Pair((u-ramp/2)/(1-ramp),1/(1-ramp))
            }
            return Pair(from+(to-from)*ease,(to-from)*derivative*1000/duration)
        }
    }
    private val segments=java.util.ArrayDeque<Segment>()
    private val initial=initialPosition.also { require(it.isFinite() && it in 0.0..1.0) }
    private var anchorPosition=initial
    private var anchorAt=beganAt
    private var forward=initial<.5
    private var count=0L
    private var fullPair=false
    private var nextRestAt: Long? = null
    private var restFamily=-1
    private var wasWeak=false

    fun sample(time: Long, pattern: Int, peakSpeedMmPerSec: Double, physicalSpanMm: Double,
        lower: Double=0.0, upper: Double=1.0, weak: Boolean=false, horizonMs: Long=200, currentNormalized: Double?=null, fullStrokeChance: Double = if(pattern<3) .12 else if(pattern<10) .40 else .96): HandyWaveFrame {
        require(time>=beganAt && pattern in 0..12)
        require(peakSpeedMmPerSec.isFinite() && peakSpeedMmPerSec>0)
        require(physicalSpanMm.isFinite() && physicalSpanMm>0)
        require(lower.isFinite() && upper.isFinite() && lower>=0 && upper<=1 && lower<upper)
        require(horizonMs in 80..500)
        require(currentNormalized==null || currentNormalized.isFinite() && currentNormalized in 0.0..1.0)
        require(fullStrokeChance.isFinite() && fullStrokeChance in 0.0..1.0)
        val family=if(pattern<3) 0 else if(pattern<10) 1 else 2
        fun activeBlockMs(): Long = when(family) {
            0 -> 4000L+(random.next()*4000).toLong()
            1 -> 10000L+(random.next()*8000).toLong()
            else -> 16000L+(random.next()*12000).toLong()
        }
        if(nextRestAt==null || restFamily!=family || wasWeak && !weak) {
            nextRestAt=time+activeBlockMs();restFamily=family
        }
        wasWeak=weak
        fun append() {
            val previous=segments.peekLast()
            val from=previous?.to ?: anchorPosition
            val began=previous?.end ?: anchorAt
            val rest=family!=2 && previous!=null && !previous.rest && !weak && began>=checkNotNull(nextRestAt)
            if(rest) {
                val pause=when(family) {
                    0 -> 1000L+(random.next()*1000).toLong()
                    1 -> 1500L+(random.next()*1000).toLong()
                    else -> 400L+(random.next()*600).toLong()
                }
                segments.addLast(Segment(from,from,began,pause,true,32.0))
                nextRestAt=began+pause+activeBlockMs()
                return
            }
            // Full-span mode is chosen for a pair of legs, so both ends occur in one round trip.
            if(count%2==0L) fullPair=!weak && random.next()<fullStrokeChance
            val whole=!weak && (fullPair || family==2 && random.next()<.80)
            val width=upper-lower
            val low: Double
            val high: Double
            if(weak) {
                val center=((from-lower)/width).coerceIn(.09,.91)
                low=(center-.07).coerceAtLeast(.01);high=(center+.07).coerceAtMost(.99)
            } else if(fullPair) {
                low=0.0;high=1.0
            } else if(whole) {
                low=.005+.025*random.next();high=.97+.025*random.next()
            } else {
                val center=when(pattern) {
                    1,3,11 -> .70+.12*random.next()
                    2,5 -> .18+.12*random.next()
                    7,8 -> if(count%20<10) .22 else .78
                    else -> .25+.5*random.next()
                }
                val localWidth=when(family) { 0 -> .28+.24*random.next(); 1 -> .38+.27*random.next(); else -> .50+.22*random.next() }
                low=(center-localWidth/2).coerceIn(.02,.82)
                high=(center+localWidth/2).coerceIn(.18,.98)
            }
            var target=lower+width*(if(forward) high else low)
            // Local passages must still advance, rather than repeatedly issuing an identical endpoint.
            if(abs(target-from)<width*.025) target=lower+width*(if(forward) low else high)
            // Sensitive broad travel stays slow before finish, including transitions between local areas.
            val broad=abs(target-from)>=width*.75
            val broadLimit=if(family==0) 32.0 else if(family==1) 40.0 else peakSpeedMmPerSec
            val limit=if(!weak && (whole || broad)) minOf(peakSpeedMmPerSec,broadLimit) else peakSpeedMmPerSec
            val peak=if(weak) 32.0 else limit*(.92+.08*random.next())
            // Rounded ramp maximum derivative=1/(1-.18). Keep a long fast middle within the physical peak.
            val duration=ceil(max(120.0,abs(target-from)*physicalSpanMm/(1-.18)/peak*1000)).toLong()
            segments.addLast(Segment(from,target,began,duration,false,if(weak) 32.0 else limit))
            forward=!forward;count++
        }
        var caughtUp=0
        while(segments.isEmpty() || checkNotNull(segments.peekLast()).end<=time+horizonMs) {
            if(caughtUp++>=256) {
                // A stalled handler needs one fresh trajectory, never an unbounded history backlog.
                anchorPosition=(currentNormalized ?: checkNotNull(segments.peekLast()).to).coerceIn(lower,upper)
                anchorAt=time;segments.clear();forward=anchorPosition<(lower+upper)/2
                caughtUp=0
            }
            append()
        }
        while(segments.size>1 && segments.elementAt(1).began<=time) segments.removeFirst()
        val current=segments.first { time<it.end }
        val (position,velocity)=current.at(time)
        // Do not look past a turn: doing so averaged away the requested top/bottom excursion.
        val lookahead=minOf(time+horizonMs,current.end)
        val target=current.at(lookahead).first
        val travelHorizon=(lookahead-time).coerceAtLeast(50)
        return HandyWaveFrame(position,velocity,abs(velocity)*physicalSpanMm,target,travelHorizon,current.rest,current.speedLimit)
    }
}
