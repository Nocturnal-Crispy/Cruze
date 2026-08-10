package com.cruze

import com.cruze.nav.CuePlanner
import com.cruze.nav.Cue
import com.cruze.nav.NavEngine
import com.cruze.route.Maneuver
import com.cruze.route.RoutePlan
import com.cruze.route.RouteStyle
import com.cruze.route.Waypoint
import com.cruze.route.curvinessScore
import com.cruze.route.decodePolyline
import com.cruze.route.encodePolyline
import com.cruze.route.pickBest
import com.cruze.route.stepWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Covers the logic that decides which road you actually ride and when you get told to turn.
 * All pure — no Android, no network.
 */
class RoutingTest {

    private val base = LatLon(47.5, 11.0)

    /** Builds a path heading east, [n] points spaced [stepM] apart. */
    private fun straight(n: Int, stepM: Double = 30.0): List<LatLon> {
        val dLon = stepM / (EARTH_R * Math.toRadians(1.0) * cos(Math.toRadians(base.lat)))
        return (0 until n).map { LatLon(base.lat, base.lon + it * dLon) }
    }

    /** A circular arc of the given radius — constant curvature, easy to reason about. */
    private fun arc(radiusM: Double, sweepDeg: Double, points: Int = 200): List<LatLon> {
        val mLat = EARTH_R * Math.toRadians(1.0)
        val mLon = mLat * cos(Math.toRadians(base.lat))
        return (0 until points).map { i ->
            val a = Math.toRadians(sweepDeg) * i / (points - 1)
            LatLon(base.lat + (radiusM * sin(a)) / mLat, base.lon + (radiusM * (1 - cos(a))) / mLon)
        }
    }

    @Test
    fun `distance and bearing are sane`() {
        val a = LatLon(47.0, 11.0)
        val b = LatLon(48.0, 11.0)
        // One degree of latitude is ~111.2 km everywhere.
        assertEquals(111_195.0, distanceM(a, b), 500.0)
        assertEquals(0.0, bearingDeg(a, b), 0.5)
        assertEquals(180.0, bearingDeg(b, a), 0.5)
        assertEquals(-90.0, angleDiff(90.0, 0.0), 1e-9)
        assertEquals(20.0, angleDiff(350.0, 10.0), 1e-9)
    }

    @Test
    fun `polyline round trips at valhalla precision`() {
        val path = arc(500.0, 90.0, 50)
        val decoded = decodePolyline(encodePolyline(path))
        assertEquals(path.size, decoded.size)
        path.zip(decoded).forEach { (a, b) ->
            assertTrue(abs(a.lat - b.lat) < 1e-6 && abs(a.lon - b.lon) < 1e-6)
        }
    }

    @Test
    fun `resampling gives even spacing regardless of input density`() {
        // Same geometry, wildly different vertex counts — the whole reason resampling exists.
        val sparse = arc(300.0, 180.0, 12)
        val dense = arc(300.0, 180.0, 900)
        val a = curvinessScore(sparse)
        val b = curvinessScore(dense)
        assertTrue("sparse=$a dense=$b should agree within 20%", abs(a - b) < 0.2 * maxOf(a, b))
    }

    @Test
    fun `a straight road scores zero and a twisty one scores high`() {
        assertEquals(0.0, curvinessScore(straight(50)), 1e-6)
        val sweeper = curvinessScore(arc(400.0, 180.0))
        val tight = curvinessScore(arc(120.0, 180.0))
        // Thresholds calibrated against real roads (see curvinessScore docs).
        assertTrue("sweeper=$sweeper should register as curvy", sweeper > 180)
        assertTrue("tight=$tight should out-score sweeper=$sweeper", tight > sweeper)
    }

    @Test
    fun `junction-angle turns are not rewarded like real curves`() {
        // A 90-degree corner taken in one 30 m step is a junction, not a bend worth riding.
        assertTrue(stepWeight(90.0) < stepWeight(20.0))
        assertEquals(0.0, stepWeight(1.0), 1e-9)
        assertEquals(20.0, stepWeight(20.0), 1e-9)
    }

    private fun plan(len: Double, time: Double, curviness: Double, highway: Boolean = false) =
        RoutePlan(
            shape = straight(3), maneuvers = emptyList(), lengthM = len, timeS = time,
            hasHighway = highway, waypoints = emptyList(), style = RouteStyle.CURVY,
            curviness = curviness,
        )

    @Test
    fun `fast style takes the quickest and curvy takes the twistiest it can afford`() {
        val quick = plan(50_000.0, 1800.0, 10.0, highway = true)
        val nice = plan(70_000.0, 2700.0, 90.0)
        val absurd = plan(200_000.0, 9000.0, 200.0)
        val all = listOf(quick, nice, absurd)

        assertEquals(quick, pickBest(all, RouteStyle.FAST))
        // CURVY allows 1.75x the quickest time: 3150 s. The 9000 s option is out of budget.
        assertEquals(nice, pickBest(all, RouteStyle.CURVY))
        // BALANCED allows 2250 s, so only the fast one qualifies.
        assertEquals(quick, pickBest(all, RouteStyle.BALANCED))
    }

    @Test
    fun `over-budget candidates still yield a route rather than nothing`() {
        val only = plan(400_000.0, 20_000.0, 5.0)
        assertNotNull(pickBest(listOf(only), RouteStyle.CURVY))
    }

    @Test
    fun `a detour that buys almost no extra curviness is refused`() {
        // The flat-interstate case measured on Dallas to Waco: the back road is barely less
        // straight than the freeway, and costs two thirds again as long. Not worth it.
        val freeway = plan(152_000.0, 5_530.0, 7.2, highway = true)
        val backRoad = plan(254_000.0, 9_270.0, 20.4, highway = true)
        assertEquals(freeway, pickBest(listOf(freeway, backRoad), RouteStyle.CURVY))

        // Whereas a genuinely twisty alternative for a few minutes more is taken.
        val dull = plan(112_000.0, 8_400.0, 288.7)
        val great = plan(116_000.0, 8_920.0, 328.1)
        assertEquals(great, pickBest(listOf(dull, great), RouteStyle.CURVY))
    }

    @Test
    fun `snapping finds the nearest point on the line`() {
        val path = straight(20, 50.0)
        val offset = LatLon(path[5].lat + 0.0002, path[5].lon) // ~22 m north of the line
        val snap = snapToPath(offset, path)
        assertTrue("dist=${snap.distM}", snap.distM in 15.0..30.0)
        assertTrue(snap.index in 4..5)
    }

    private fun navPlan(): RoutePlan {
        val shape = straight(100, 50.0) // 4950 m total
        return RoutePlan(
            shape = shape,
            maneuvers = listOf(
                Maneuver(1, "Start", "", "Head east", "", "", 2500.0, 100.0, 0, 50),
                Maneuver(15, "Turn right", "Turn right ahead", "Turn right onto Pass Road", "", "", 2450.0, 100.0, 50, 99),
                Maneuver(4, "Arrive", "", "You have arrived", "", "", 0.0, 0.0, 99, 99),
            ),
            lengthM = pathLengthM(shape), timeS = 200.0, hasHighway = false,
            waypoints = listOf(Waypoint(shape.first()), Waypoint(shape.last())),
            style = RouteStyle.CURVY,
        )
    }

    @Test
    fun `nav progress tracks the right maneuver and remaining distance`() {
        val p = navPlan()
        val eng = NavEngine(p)

        val start = eng.update(p.shape[0])
        assertEquals(0, start.maneuverIdx)
        assertEquals(p.lengthM, start.remainingM, 5.0)
        // The right turn is at shape index 50 = 2500 m in.
        assertEquals(2500.0, start.distToManeuverM, 30.0)

        val nearTurn = eng.update(p.shape[48])
        assertEquals(0, nearTurn.maneuverIdx)
        assertTrue("dist=${nearTurn.distToManeuverM}", nearTurn.distToManeuverM < 120.0)

        val afterTurn = eng.update(p.shape[60])
        assertEquals(1, afterTurn.maneuverIdx)

        assertTrue(eng.update(p.shape.last()).arrived)
    }

    @Test
    fun `off route needs repeated evidence, not one bad fix`() {
        val p = navPlan()
        val eng = NavEngine(p)
        eng.update(p.shape[10])

        // 500 m off the line.
        val lost = LatLon(p.shape[10].lat + 0.0045, p.shape[10].lon)
        assertTrue(!eng.update(lost).offRoute)      // strike 1
        assertTrue(!eng.update(lost).offRoute)      // strike 2
        assertTrue(eng.update(lost).offRoute)       // strike 3 -> reroute

        // Back on the road clears it immediately.
        assertTrue(!eng.update(p.shape[11]).offRoute)
    }

    @Test
    fun `each maneuver is announced at most once per stage and scales with speed`() {
        val cues = CuePlanner()
        val fast = 36.0 // 130 km/h
        val slow = 8.0  // 29 km/h
        assertTrue(cues.alertDistance(fast) > cues.alertDistance(slow))

        assertEquals(null, cues.next(1, 5000.0, fast))
        assertEquals(Cue.ALERT, cues.next(1, 700.0, fast))
        assertEquals(null, cues.next(1, 600.0, fast))   // still in the ALERT band, stay quiet
        assertEquals(Cue.PRE, cues.next(1, 150.0, fast))
        assertEquals(null, cues.next(1, 60.0, fast))    // already spoken
        // A new maneuver starts the cycle over: alert first, then the pre-turn call.
        assertEquals(Cue.ALERT, cues.next(2, 100.0, slow))
        assertEquals(Cue.PRE, cues.next(2, 50.0, slow))
        assertEquals(null, cues.next(2, 20.0, slow))
    }

    @Test
    fun `gpx thinning keeps both ends and the requested count`() {
        val path = straight(1000, 10.0)
        val thin = com.cruze.data.Gpx.thin(path, 12)
        assertEquals(12, thin.size)
        assertEquals(path.first(), thin.first())
        assertEquals(path.last(), thin.last())
    }

    @Test
    fun `track stats ignore stationary time`() {
        val t0 = 1_700_000_000_000L
        val pts = listOf(
            Fix(base, 0f, 0f, 5f, 0.0, t0),                       // parked
            Fix(LatLon(base.lat + 0.001, base.lon), 0f, 20f, 5f, 0.0, t0 + 10_000),
            Fix(LatLon(base.lat + 0.002, base.lon), 0f, 20f, 5f, 0.0, t0 + 20_000),
            Fix(LatLon(base.lat + 0.002, base.lon), 0f, 0f, 5f, 0.0, t0 + 300_000), // stopped for coffee
        )
        assertEquals(222.0, trackDistanceM(pts), 15.0)
        assertEquals(20.0, trackMovingS(pts), 0.1)
    }

    @Test
    fun `distances and speeds read in imperial`() {
        assertEquals("400 ft", fmtDist(123.0))          // 0.08 mi -> feet
        assertEquals("0.8 mi", fmtDist(1234.0))
        assertEquals("7.7 mi", fmtDist(12_345.0))
        assertEquals("62 mi", fmtDist(100_000.0))
        assertEquals("1h 1m", fmtDur(3700.0))

        assertEquals("now", fmtTurnDist(10.0))
        assertEquals("450 ft", fmtTurnDist(150.0))
        assertEquals("0.6 mi", fmtTurnDist(1000.0))

        assertEquals("0", fmtSpeed(0f))
        assertEquals("44", fmtSpeed(20f))               // 20 m/s = 44.7 mph
        assertTrue(PI > 3)
    }
}
