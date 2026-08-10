package com.cruze.sync

import com.cruze.LatLon
import com.cruze.distanceM
import com.cruze.pathLengthM

/**
 * Pure group maths — spread, lost riders, and how hard to hammer the network. Kept free of
 * Android so all of it is unit-testable.
 */

/** Distance from the leader beyond which a rider counts as lost, by default. */
const val DEFAULT_LOST_THRESHOLD_M = 1600.0

/**
 * How often to broadcast position, in milliseconds.
 *
 * Cellular data and battery are the scarce resources on a long ride, so the rate follows how
 * much the group actually needs to know: fast when riders are strung out or a turn is imminent,
 * slow when everyone is bunched up and cruising in a straight line.
 */
fun updateIntervalMs(
    spreadM: Double,
    navigating: Boolean,
    distanceToManeuverM: Double?,
    stationary: Boolean,
): Long = when {
    // Parked at a petrol stop: nothing is changing, so say almost nothing.
    stationary -> 30_000L
    // A junction is where groups actually get split up. Measured against the public relay,
    // anything faster than ~3 s sustained drains its rate-limit bucket and starts 429ing,
    // so this is the floor rather than the 1 s the map would ideally like.
    navigating && (distanceToManeuverM ?: Double.MAX_VALUE) < 400 -> 3_000L
    // Strung out — the whole point is watching the gap.
    spreadM > 800 -> 5_000L
    spreadM > 300 -> 8_000L
    else -> 12_000L
}

/** Front-to-back length of the group along the ground, in metres. */
fun groupSpreadM(positions: List<LatLon>): Double {
    if (positions.size < 2) return 0.0
    var worst = 0.0
    for (i in positions.indices) {
        for (j in i + 1 until positions.size) {
            val d = distanceM(positions[i], positions[j])
            if (d > worst) worst = d
        }
    }
    return worst
}

data class RiderGap(val ping: RiderPing, val metresBehindLeader: Double, val lost: Boolean)

/**
 * Each rider's gap to the leader.
 *
 * Straight-line distance is used rather than distance along the route: a rider who has taken a
 * wrong turn is exactly the one you care about, and they are by definition no longer on it.
 */
fun gapsToLeader(
    roster: List<RiderPing>,
    leaderId: String?,
    thresholdM: Double = DEFAULT_LOST_THRESHOLD_M,
): List<RiderGap> {
    val leader = roster.firstOrNull { it.riderId == leaderId }
        ?: roster.firstOrNull { it.role == RiderRole.LEADER }
        ?: return roster.map { RiderGap(it, 0.0, false) }

    return roster.filterNot { it.riderId == leader.riderId }.map {
        val d = distanceM(leader.pos, it.pos)
        RiderGap(it, d, d > thresholdM)
    }.sortedByDescending { it.metresBehindLeader }
}

/**
 * Fuel range left for the rider who will run dry first.
 *
 * Range is deliberately conservative: reserve is treated as unusable, because a group that
 * plans to the last drop ends up pushing a bike.
 */
data class FuelRange(val riderName: String, val milesRemaining: Double, val low: Boolean)

fun lowestRange(
    tanks: Map<String, Double>,
    milesSinceFill: Map<String, Double>,
    mpg: Double,
    reserveFraction: Double = 0.15,
    lowThresholdMi: Double = 30.0,
): FuelRange? {
    if (tanks.isEmpty() || mpg <= 0) return null
    return tanks.mapNotNull { (name, gallons) ->
        val usable = gallons * (1 - reserveFraction)
        val used = milesSinceFill[name] ?: 0.0
        val remaining = usable * mpg - used
        FuelRange(name, remaining, remaining <= lowThresholdMi)
    }.minByOrNull { it.milesRemaining }
}

/** Length of a breadcrumb trail, for showing where a lost rider actually went. */
fun trailLengthM(trail: List<LatLon>): Double = pathLengthM(trail)
