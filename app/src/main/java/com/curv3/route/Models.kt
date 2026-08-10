package com.curv3.route

import com.curv3.LatLon

/**
 * @param timeBudget how much longer than the quickest option a route may take.
 * @param minGainPerExtraTime curviness (°/mi) the detour must buy per unit of extra time.
 *   Without this, a long crawl through flat farmland "wins" over the interstate purely by
 *   being marginally less straight. Measured on real routes — see Curvature.kt.
 */
enum class RouteStyle(
    val label: String,
    val useHighways: Double,
    val timeBudget: Double,
    val minGainPerExtraTime: Double,
) {
    /** Get there. No detour tolerated. */
    FAST("Fast", 1.0, 1.0, 0.0),

    /** A nicer road if it costs little. */
    BALANCED("Balanced", 0.3, 1.25, 120.0),

    /** The point of the app: take the twisty way. */
    CURVY("Curvy", 0.0, 1.75, 50.0);
}

data class Waypoint(val pos: LatLon, val name: String = "")

data class Maneuver(
    val type: Int,
    val instruction: String,
    val verbalAlert: String,
    val verbalPre: String,
    val verbalPost: String,
    val streets: String,
    val lengthM: Double,
    val timeS: Double,
    /** Index into [RoutePlan.shape] where this maneuver begins. */
    val beginIdx: Int,
    val endIdx: Int,
)

data class RoutePlan(
    val shape: List<LatLon>,
    val maneuvers: List<Maneuver>,
    val lengthM: Double,
    val timeS: Double,
    val hasHighway: Boolean,
    val waypoints: List<Waypoint>,
    val style: RouteStyle,
    /** Degrees of useful direction change per mile — see [curvinessScore]. */
    val curviness: Double = 0.0,
) {
    val curveLabel: String
        get() = when {
            curviness >= 350 -> "Very twisty"
            curviness >= 180 -> "Twisty"
            curviness >= 60 -> "Flowing"
            else -> "Straight"
        }
}
