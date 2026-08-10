package com.curv3

import com.curv3.route.RouteStyle
import com.curv3.route.Valhalla
import com.curv3.route.Waypoint
import com.curv3.route.curvinessScore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.net.InetAddress

/**
 * Hits the real FOSSGIS Valhalla service. This is the only check that the response parsing
 * — leg stitching, shape index offsets, mile-to-metre conversion — matches what the service
 * actually sends. Skipped rather than failed when there is no network.
 */
class ValhallaLiveTest {

    private fun requireNetwork() {
        try {
            InetAddress.getByName("valhalla1.openstreetmap.de")
        } catch (e: Exception) {
            assumeNoException("no network — skipping live routing test", e)
        }
    }

    // Deals Gap, NC: the Tail of the Dragon.
    private val dragonStart = Waypoint(LatLon(35.5145, -83.9290), "Deals Gap")
    private val dragonEnd = Waypoint(LatLon(35.4870, -83.8390), "Tabcat Bridge")

    @Test
    fun `a real route parses into usable geometry and instructions`() = runBlocking {
        requireNetwork()
        val plan = Valhalla.plan(listOf(dragonStart, dragonEnd), RouteStyle.CURVY)

        assertTrue("shape should be detailed, got ${plan.shape.size}", plan.shape.size > 100)
        assertTrue("should have turn instructions", plan.maneuvers.size >= 2)
        assertTrue("length ${plan.lengthM} m looks wrong", plan.lengthM in 5_000.0..60_000.0)
        assertTrue("time ${plan.timeS}s looks wrong", plan.timeS in 300.0..7_200.0)

        // Summary length must agree with the geometry — this is what catches a unit mix-up.
        val measured = pathLengthM(plan.shape)
        assertEquals(
            "summary ${plan.lengthM} vs measured $measured",
            plan.lengthM, measured, plan.lengthM * 0.05,
        )

        // Every maneuver must index into the shape we actually built.
        plan.maneuvers.forEach {
            assertTrue("beginIdx ${it.beginIdx} out of ${plan.shape.size}", it.beginIdx in plan.shape.indices)
        }
        // Maneuvers must run in order, or navigation progress goes backwards.
        val idxs = plan.maneuvers.map { it.beginIdx }
        assertEquals(idxs.sorted(), idxs)

        // Voice guidance needs something to say.
        assertTrue(plan.maneuvers.count { it.verbalPre.isNotBlank() } >= plan.maneuvers.size - 1)

        // The Dragon is the twistiest road in the US. If this ever reads "Straight", the
        // scoring has broken.
        assertTrue("curviness=${plan.curviness}", plan.curviness > 350)
        assertEquals("Very twisty", plan.curveLabel)
    }

    @Test
    fun `a multi-waypoint route stitches legs without breaking shape indices`() = runBlocking {
        requireNetwork()
        val via = Waypoint(LatLon(35.5000, -83.8800), "Via")
        val plan = Valhalla.plan(listOf(dragonStart, via, dragonEnd), RouteStyle.CURVY)

        assertTrue(plan.shape.size > 100)
        // No duplicated vertex where the legs join.
        val dupes = (1 until plan.shape.size).count { plan.shape[it] == plan.shape[it - 1] }
        assertEquals("duplicate consecutive points at leg joins", 0, dupes)

        val idxs = plan.maneuvers.map { it.beginIdx }
        assertEquals(idxs.sorted(), idxs)
        assertTrue(plan.maneuvers.last().beginIdx < plan.shape.size)

        val measured = pathLengthM(plan.shape)
        assertEquals(plan.lengthM, measured, plan.lengthM * 0.05)
    }

    @Test
    fun `curvy never returns a straighter road than fast`() = runBlocking {
        requireNetwork()
        // A Smoky Mountains crossing where both a highway and mountain roads exist, so the
        // styles have something to actually choose between.
        val from = Waypoint(LatLon(35.4300, -83.9500), "Robbinsville")
        val to = Waypoint(LatLon(35.6800, -83.5000), "Gatlinburg")

        val fast = Valhalla.plan(listOf(from, to), RouteStyle.FAST)
        val curvy = Valhalla.plan(listOf(from, to), RouteStyle.CURVY)

        // Where only one sensible road exists both styles land on it, so a tie is correct;
        // curvy being *straighter* than fast never is.
        assertTrue(
            "curvy=${curvy.curviness} must not be straighter than fast=${fast.curviness}",
            curvy.curviness >= fast.curviness - 1.0,
        )
        assertTrue("curvy should not exceed its time budget", curvy.timeS <= fast.timeS * RouteStyle.CURVY.timeBudget)
        // And the score must be reproducible from the geometry alone.
        assertEquals(curvinessScore(curvy.shape), curvy.curviness, 0.001)
    }
}
