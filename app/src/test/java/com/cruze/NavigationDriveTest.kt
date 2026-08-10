package com.cruze

import com.cruze.nav.Cue
import com.cruze.nav.CuePlanner
import com.cruze.nav.NavEngine
import com.cruze.route.RouteStyle
import com.cruze.route.Valhalla
import com.cruze.route.Waypoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.net.InetAddress

/**
 * Drives a virtual rider along a real route, end to end, and checks that guidance behaves.
 *
 * This is the closest thing to actually riding it: the route comes from the live routing
 * service, and every position the engine sees is one a rider would genuinely pass through.
 * It catches the failures that matter — an instruction that never fires, fires twice, fires in
 * the wrong order, or arrives after the junction.
 */
class NavigationDriveTest {

    private fun requireNetwork() {
        try {
            InetAddress.getByName("valhalla1.openstreetmap.de")
        } catch (e: Exception) {
            assumeNoException("no network — skipping live navigation drive", e)
        }
    }

    private data class Spoken(
        val maneuverIdx: Int,
        val cue: Cue,
        val atMetres: Double,
        /** A turn folded into this announcement as ", then ...". */
        val followUpIdx: Int = -1,
    )

    /**
     * Replays a ride along [shape] at [speedMps], sampling once a second like real GPS, and
     * returns every announcement that would have been made.
     */
    private fun drive(
        engine: NavEngine,
        shape: List<LatLon>,
        speedMps: Double,
        jitterM: Double = 0.0,
    ): Pair<List<Spoken>, Boolean> {
        val cues = CuePlanner()
        val spoken = mutableListOf<Spoken>()
        val cum = cumulative(shape)
        val total = cum.last()
        var travelled = 0.0
        var arrived = false
        var seed = 12345L

        while (travelled < total) {
            // Position at this distance along the route.
            val i = cum.indexOfFirst { it >= travelled }.coerceAtLeast(1)
            val segStart = cum[i - 1]
            val segLen = (cum[i] - segStart).coerceAtLeast(1e-6)
            var pos = interpolate(shape[i - 1], shape[i], ((travelled - segStart) / segLen).coerceIn(0.0, 1.0))

            if (jitterM > 0) {
                // Cheap deterministic wobble, so the test is reproducible.
                seed = seed * 6364136223846793005L + 1442695040888963407L
                val dx = ((seed ushr 33) % 1000) / 1000.0 - 0.5
                seed = seed * 6364136223846793005L + 1442695040888963407L
                val dy = ((seed ushr 33) % 1000) / 1000.0 - 0.5
                pos = LatLon(
                    pos.lat + dy * jitterM / (EARTH_R * Math.toRadians(1.0)),
                    pos.lon + dx * jitterM / (EARTH_R * Math.toRadians(1.0) * Math.cos(Math.toRadians(pos.lat))),
                )
            }

            val p = engine.update(pos)
            if (p.arrived) { arrived = true; break }

            val nextIdx = p.nextManeuverIdx
            if (nextIdx < 0) { travelled += speedMps; continue }
            cues.next(nextIdx, p.distToManeuverM, speedMps, p.followUpManeuverIdx)?.let {
                spoken.add(Spoken(nextIdx, it, travelled, p.followUpManeuverIdx))
            }
            travelled += speedMps
        }
        return spoken to arrived
    }

    @Test
    fun `a real route is guided turn by turn, in order, once each, and arrives`() = runBlocking {
        requireNetwork()
        // A twisty road with plenty of junctions to get wrong.
        val plan = Valhalla.plan(
            listOf(
                Waypoint(LatLon(35.4300, -83.9500), "Robbinsville"),
                Waypoint(LatLon(35.5145, -83.9290), "Deals Gap"),
            ),
            RouteStyle.CURVY,
        )
        assertTrue("need a route with real turns", plan.maneuvers.size >= 4)

        val (spoken, arrived) = drive(NavEngine(plan), plan.shape, speedMps = 18.0)

        assertTrue("the rider never arrived", arrived)
        assertTrue("nothing was announced", spoken.isNotEmpty())

        // Every turn must be announced at least once. The first maneuver is "start out", and
        // the last is Valhalla's destination marker, which the service speaks on arrival rather
        // than through the cue planner — so both sit outside this check.
        // A turn counts as announced whether it was spoken on its own or folded into the
        // previous instruction as ", then ...". Back-to-back turns metres apart are one
        // spoken instruction to a rider, and Valhalla emits plenty of them on roads like this.
        val announced = (spoken.map { it.maneuverIdx } + spoken.map { it.followUpIdx }).toSet()
        val expected = (1 until plan.maneuvers.lastIndex).toSet()
        val missed = expected - announced
        assertTrue(
            "turns never announced: $missed\n" +
                "spoken: " + spoken.joinToString { "${it.maneuverIdx}${if (it.followUpIdx >= 0) "+${it.followUpIdx}" else ""}/${it.cue}" },
            missed.isEmpty(),
        )

        // And a folded turn must never also be announced separately, or it is said twice.
        val folded = spoken.mapNotNull { it.followUpIdx.takeIf { i -> i >= 0 } }.toSet()
        val separately = spoken.map { it.maneuverIdx }.toSet()
        assertTrue("turns announced twice: ${folded intersect separately}",
            (folded intersect separately).isEmpty())

        // Announcements must progress forward — guidance that jumps back is disorienting.
        val order = spoken.map { it.maneuverIdx }
        assertEquals("announcements out of order", order.sorted(), order)

        // Each maneuver gets at most one ALERT and one PRE, never a repeat.
        spoken.groupBy { it.maneuverIdx }.forEach { (idx, cues) ->
            val kinds = cues.map { it.cue }
            assertEquals("maneuver $idx repeated a cue", kinds.distinct().size, kinds.size)
            if (kinds.size == 2) assertEquals(listOf(Cue.ALERT, Cue.PRE), kinds)
        }
    }

    @Test
    fun `guidance survives noisy GPS without spurious reroutes`() = runBlocking {
        requireNetwork()
        val plan = Valhalla.plan(
            listOf(
                Waypoint(LatLon(35.4300, -83.9500), "Robbinsville"),
                Waypoint(LatLon(35.5145, -83.9290), "Deals Gap"),
            ),
            RouteStyle.CURVY,
        )

        // +/- 20 m of wobble, which is worse than a phone in a mount usually sees.
        val engine = NavEngine(plan)
        val cum = cumulative(plan.shape)
        var travelled = 0.0
        var offRouteCount = 0
        var seed = 99L
        while (travelled < cum.last()) {
            val i = cum.indexOfFirst { it >= travelled }.coerceAtLeast(1)
            val segStart = cum[i - 1]
            val segLen = (cum[i] - segStart).coerceAtLeast(1e-6)
            val base = interpolate(plan.shape[i - 1], plan.shape[i], ((travelled - segStart) / segLen).coerceIn(0.0, 1.0))
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val wobble = (((seed ushr 33) % 1000) / 1000.0 - 0.5) * 40.0
            val pos = LatLon(base.lat + wobble / (EARTH_R * Math.toRadians(1.0)), base.lon)

            val p = engine.update(pos)
            if (p.offRoute) offRouteCount++
            if (p.arrived) break
            travelled += 18.0
        }
        assertEquals("noise alone triggered a reroute", 0, offRouteCount)
    }

    @Test
    fun `leaving the route actually triggers a reroute`() = runBlocking {
        requireNetwork()
        val plan = Valhalla.plan(
            listOf(
                Waypoint(LatLon(35.4300, -83.9500), "Robbinsville"),
                Waypoint(LatLon(35.5145, -83.9290), "Deals Gap"),
            ),
            RouteStyle.CURVY,
        )
        val engine = NavEngine(plan)
        // Ride a little way in, then head off at a tangent.
        val start = plan.shape[plan.shape.size / 4]
        engine.update(start)

        var last = engine.update(start)
        for (step in 1..6) {
            // ~200 m per step due north of the route.
            val off = LatLon(start.lat + step * 0.0018, start.lon)
            last = engine.update(off)
        }
        assertTrue("a genuine departure did not trigger a reroute", last.offRoute)
    }
}
