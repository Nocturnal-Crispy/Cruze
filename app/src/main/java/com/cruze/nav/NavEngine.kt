package com.cruze.nav

import com.cruze.LatLon
import com.cruze.Snap
import com.cruze.cumulative
import com.cruze.distanceM
import com.cruze.route.Maneuver
import com.cruze.route.RoutePlan
import com.cruze.snapToPath

/** Beyond this from the route line we assume the rider has left it. */
const val OFF_ROUTE_M = 60.0

/** Consecutive off-route fixes before rerouting — one bad GPS fix must not trigger it. */
const val OFF_ROUTE_STRIKES = 3

data class NavProgress(
    val snapped: LatLon,
    val shapeIdx: Int,
    val distFromRouteM: Double,
    val maneuverIdx: Int,
    val distToManeuverM: Double,
    val remainingM: Double,
    val remainingS: Double,
    val offRoute: Boolean,
    val arrived: Boolean,
)

/**
 * Tracks a rider's position against a [RoutePlan]. Pure state machine — it holds no Android
 * dependencies so the whole thing is testable on the JVM.
 */
class NavEngine(val plan: RoutePlan) {
    private val cum = cumulative(plan.shape)
    private val totalM = cum.lastOrNull() ?: 0.0
    private var lastIdx = 0
    private var strikes = 0

    val maneuvers: List<Maneuver> get() = plan.maneuvers

    fun maneuverAt(i: Int): Maneuver? = plan.maneuvers.getOrNull(i)

    fun update(pos: LatLon): NavProgress {
        // Search forward from the last match, with a small backward allowance for GPS jitter.
        // An unbounded search would snap to a later crossing of the same road.
        val from = (lastIdx - 8).coerceAtLeast(0)
        val local = snapToPath(pos, plan.shape, from, 400)
        // If we look lost locally, re-check the whole route before declaring off-route: the
        // rider may have legitimately skipped ahead (or we resumed mid-ride).
        val snap: Snap = if (local.distFromRoute() > OFF_ROUTE_M) {
            val global = snapToPath(pos, plan.shape)
            if (global.distM < local.distM) global else local
        } else local

        lastIdx = snap.index

        val off = snap.distM > OFF_ROUTE_M
        strikes = if (off) strikes + 1 else 0

        val travelled = cum.getOrElse(snap.index) { 0.0 } +
            distanceM(plan.shape[snap.index], snap.point)
        val remainingM = (totalM - travelled).coerceAtLeast(0.0)
        val remainingS = if (totalM > 0) plan.timeS * (remainingM / totalM) else 0.0

        val mIdx = currentManeuverIdx(snap.index)
        val next = plan.maneuvers.getOrNull(mIdx + 1)
        val distToManeuver = if (next != null) {
            (cum.getOrElse(next.beginIdx) { totalM } - travelled).coerceAtLeast(0.0)
        } else remainingM

        return NavProgress(
            snapped = snap.point,
            shapeIdx = snap.index,
            distFromRouteM = snap.distM,
            maneuverIdx = mIdx,
            distToManeuverM = distToManeuver,
            remainingM = remainingM,
            remainingS = remainingS,
            offRoute = strikes >= OFF_ROUTE_STRIKES,
            arrived = remainingM < 40.0,
        )
    }

    private fun Snap.distFromRoute() = distM

    /** Index of the maneuver currently being executed. */
    private fun currentManeuverIdx(shapeIdx: Int): Int {
        var idx = 0
        for (i in plan.maneuvers.indices) {
            if (plan.maneuvers[i].beginIdx <= shapeIdx) idx = i else break
        }
        return idx
    }
}

enum class Cue { ALERT, PRE, NONE }

/**
 * Decides when a maneuver should be spoken. Cue distances scale with speed so a warning at
 * 80 mph does not arrive after the exit.
 */
class CuePlanner {
    private var spokenManeuver = -1
    private var spokenCue = Cue.NONE

    fun alertDistance(speedMps: Double) = (speedMps * 22.0).coerceIn(250.0, 1500.0)
    fun preDistance(speedMps: Double) = (speedMps * 5.0).coerceIn(60.0, 300.0)

    /** Returns the cue to speak now, or null if nothing new is due. */
    fun next(maneuverIdx: Int, distM: Double, speedMps: Double): Cue? {
        if (maneuverIdx != spokenManeuver) {
            spokenManeuver = maneuverIdx
            spokenCue = Cue.NONE
        }
        val due = when {
            distM <= preDistance(speedMps) -> Cue.PRE
            distM <= alertDistance(speedMps) -> Cue.ALERT
            else -> Cue.NONE
        }
        // Cues only ever progress ALERT -> PRE for a given maneuver, and never repeat.
        val progressed = when {
            due == Cue.NONE -> false
            spokenCue == Cue.NONE -> true
            spokenCue == Cue.ALERT && due == Cue.PRE -> true
            else -> false
        }
        if (!progressed) return null
        spokenCue = due
        return due
    }

    fun reset() {
        spokenManeuver = -1
        spokenCue = Cue.NONE
    }
}
