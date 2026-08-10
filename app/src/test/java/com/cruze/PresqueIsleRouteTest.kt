package com.cruze

import com.cruze.nav.CuePlanner
import com.cruze.nav.NavEngine
import com.cruze.route.Nominatim
import com.cruze.route.RouteStyle
import com.cruze.route.Valhalla
import com.cruze.route.Waypoint
import com.cruze.route.curvinessScore
import com.cruze.sync.RouteTransfer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.net.InetAddress

/**
 * The real ride the app exists for: out to Presque Isle by way of Edinboro, home by way of
 * Mercer. Every leg uses live services and the app's own code, end to end.
 */
class PresqueIsleRouteTest {

    private fun requireNetwork() {
        try {
            InetAddress.getByName("valhalla1.openstreetmap.de")
        } catch (e: Exception) {
            assumeNoException("no network", e)
        }
    }

    private val home = Waypoint(LatLon(41.2339, -80.4931), "Sharon PA")
    private val edinboro = Waypoint(LatLon(41.8742, -80.1315), "Edinboro PA")
    private val presqueIsle = Waypoint(LatLon(42.1592, -80.1120), "Presque Isle")
    private val mercer = Waypoint(LatLon(41.2270, -80.2384), "Mercer PA")

    @Test
    fun `the whole loop plans, guides and shares correctly`() = runBlocking {
        requireNetwork()

        val stops = listOf(home, edinboro, presqueIsle, mercer, home)
        val plan = Valhalla.plan(stops, RouteStyle.CURVY)

        println("=== Sharon -> Edinboro -> Presque Isle -> Mercer -> Sharon ===")
        println("distance   : ${fmtDist(plan.lengthM)}")
        println("riding time: ${fmtDur(plan.timeS)}")
        println("curviness  : ${"%.0f".format(plan.curviness)} deg/mi (${plan.curveLabel})")
        println("shape pts  : ${plan.shape.size}   maneuvers: ${plan.maneuvers.size}")
        println("uses highway: ${plan.hasHighway}")

        // Geometry has to be real and self-consistent.
        assertEquals("summary disagrees with the drawn line",
            plan.lengthM, pathLengthM(plan.shape), plan.lengthM * 0.05)
        assertTrue("route is implausibly short", metresToMiles(plan.lengthM) > 100)
        assertTrue("route is implausibly long", metresToMiles(plan.lengthM) < 400)
        assertEquals(curvinessScore(plan.shape), plan.curviness, 0.001)

        // It must actually pass near every stop the rider asked for, in order.
        var searchFrom = 0
        listOf(edinboro, presqueIsle, mercer).forEach { stop ->
            val idx = (searchFrom until plan.shape.size)
                .minByOrNull { distanceM(plan.shape[it], stop.pos) }
            requireNotNull(idx) { "ran off the end looking for ${stop.name}" }
            val miss = distanceM(plan.shape[idx], stop.pos)
            println("passes ${stop.name} at point $idx, ${fmtDist(miss)} away")
            assertTrue("${stop.name} is not on the route (${fmtDist(miss)} away)", miss < 3000)
            assertTrue("${stop.name} is visited out of order", idx > searchFrom)
            searchFrom = idx
        }
        // And it must come home.
        assertTrue("does not return home",
            distanceM(plan.shape.last(), home.pos) < 3000)

        // Turn-by-turn must survive the whole loop.
        val engine = NavEngine(plan)
        val cues = CuePlanner()
        val cum = cumulative(plan.shape)
        var travelled = 0.0
        var announcements = 0
        var offRoute = 0
        var arrived = false
        while (travelled < cum.last()) {
            val i = cum.indexOfFirst { it >= travelled }.coerceAtLeast(1)
            val segStart = cum[i - 1]
            val segLen = (cum[i] - segStart).coerceAtLeast(1e-6)
            val pos = interpolate(
                plan.shape[i - 1], plan.shape[i],
                ((travelled - segStart) / segLen).coerceIn(0.0, 1.0),
            )
            val p = engine.update(pos)
            if (p.offRoute) offRoute++
            if (p.arrived) { arrived = true; break }
            if (p.nextManeuverIdx >= 0) {
                cues.next(p.nextManeuverIdx, p.distToManeuverM, 20.0, p.followUpManeuverIdx)
                    ?.let { announcements++ }
            }
            travelled += 20.0
        }
        println("announcements: $announcements   spurious reroutes: $offRoute")
        assertTrue("never arrived home", arrived)
        assertEquals("riding the route exactly still triggered a reroute", 0, offRoute)
        assertTrue("almost nothing was announced", announcements >= plan.maneuvers.size / 2)

        // Pushing this to the group must deliver it unchanged.
        val chunks = RouteTransfer.chunk(plan, "LEADER", "Dana")
        val assembler = RouteTransfer.Assembler()
        var received: com.cruze.route.RoutePlan? = null
        chunks.forEach { assembler.accept(it)?.let { r -> received = r.first } }
        println("shared as ${chunks.size} chunks")
        requireNotNull(received) { "the group never received the route" }
        assertEquals(plan.shape.size, received!!.shape.size)
        assertEquals(plan.maneuvers.size, received!!.maneuvers.size)
        plan.shape.zip(received!!.shape).forEach { (a, b) ->
            assertTrue(distanceM(a, b) < 0.2)
        }
        plan.maneuvers.zip(received!!.maneuvers).forEach { (a, b) ->
            assertEquals(a.verbalPre, b.verbalPre)
        }
    }

    @Test
    fun `the stops resolve by name through real search`() = runBlocking {
        requireNetwork()
        listOf("Edinboro, Pennsylvania", "Presque Isle State Park, Erie PA", "Mercer, Pennsylvania")
            .zip(listOf(edinboro, presqueIsle, mercer))
            .forEach { (query, expected) ->
                val hits = Nominatim.search(query, near = expected.pos)
                assertTrue("search found nothing for $query", hits.isNotEmpty())
                val best = hits.minByOrNull { distanceM(it.pos, expected.pos) }!!
                val off = distanceM(best.pos, expected.pos)
                println("$query -> ${best.name.take(60)} (${fmtDist(off)} from expected)")
                assertTrue("$query resolved ${fmtDist(off)} away", off < 15000)
            }
    }
}
