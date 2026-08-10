package com.curv3.route

import com.curv3.LatLon
import com.curv3.angleDiff
import com.curv3.bearingDeg
import com.curv3.metresToMiles
import com.curv3.pathLengthM
import com.curv3.resample
import kotlin.math.abs

/** Spacing used when measuring curvature. At 30 m a bend's radius maps cleanly onto its angle. */
const val CURVE_STEP_M = 30.0

/**
 * How much a single [CURVE_STEP_M] step of direction change is worth to a rider.
 *
 * Raw "total degrees turned" is a bad proxy for a good road: a town full of right-angle
 * junctions scores higher than an alpine pass. So credit is capped at the angle of a
 * pleasant sweeper and decays past it, where the bend is really a junction or a hairpin.
 *
 * At a 30 m step: 5° ~= 340 m radius, 25° ~= 70 m radius, 60° ~= 30 m radius (hairpin/junction).
 *
 * ponytail: hand-tuned constants, not a fitted model. Ride it and adjust GOOD_DEG/DECAY if
 * the routes feel wrong — that is the intended tuning knob.
 */
private const val NOISE_DEG = 3.0
private const val GOOD_DEG = 25.0
private const val DECAY = 0.6

fun stepWeight(deg: Double): Double {
    val a = abs(deg)
    if (a < NOISE_DEG) return 0.0
    if (a <= GOOD_DEG) return a
    return (GOOD_DEG - (a - GOOD_DEG) * DECAY).coerceAtLeast(0.0)
}

/**
 * Curviness of a path in weighted degrees of direction change per mile.
 * Measured on real roads: interstate ~7, open plains ~20, Blue Ridge Parkway ~335,
 * Stelvio Pass ~384, Tail of the Dragon ~479.
 */
fun curvinessScore(path: List<LatLon>): Double {
    if (path.size < 3) return 0.0
    val rs = resample(path, CURVE_STEP_M)
    if (rs.size < 3) return 0.0
    var turn = 0.0
    for (i in 1 until rs.size - 1) {
        val b1 = bearingDeg(rs[i - 1], rs[i])
        val b2 = bearingDeg(rs[i], rs[i + 1])
        turn += stepWeight(angleDiff(b1, b2))
    }
    val miles = metresToMiles(pathLengthM(rs))
    return if (miles < 0.03) 0.0 else turn / miles
}

/**
 * Choose among routing candidates. FAST just takes the quickest. The other styles look for the
 * twistiest option inside a time budget, but only actually spend that budget when the extra
 * time buys a worthwhile improvement — otherwise a flat, marginally-wigglier back road wins on
 * a route where no good road exists, and the rider loses an hour for nothing.
 */
fun pickBest(candidates: List<RoutePlan>, style: RouteStyle): RoutePlan? {
    val alive = candidates.filter { it.shape.size >= 2 }
    if (alive.isEmpty()) return null
    val fastest = alive.minByOrNull { it.timeS } ?: return null
    if (style == RouteStyle.FAST) return fastest

    val budget = fastest.timeS * style.timeBudget
    val affordable = alive.filter { it.timeS <= budget }
    // Small nudge away from motorways: they are never the reason anyone installed this app.
    val best = affordable.maxByOrNull { it.curviness - if (it.hasHighway) 13.0 else 0.0 }
        ?: return fastest
    if (best === fastest) return fastest

    val extraTime = (best.timeS / fastest.timeS) - 1.0
    val gain = best.curviness - fastest.curviness
    return if (gain >= extraTime * style.minGainPerExtraTime) best else fastest
}

/** Drops candidates whose geometry is a near-duplicate of one already kept. */
fun dedupe(candidates: List<RoutePlan>): List<RoutePlan> {
    val kept = ArrayList<RoutePlan>()
    for (c in candidates) {
        val dup = kept.any {
            abs(it.lengthM - c.lengthM) < c.lengthM * 0.01 && abs(it.timeS - c.timeS) < 30
        }
        if (!dup) kept.add(c)
    }
    return kept
}
