package com.cruze

import com.cruze.route.RouteStyle
import com.cruze.route.Valhalla
import com.cruze.route.Waypoint
import com.cruze.sync.RideEvent
import com.cruze.sync.RouteTransfer
import com.cruze.sync.Wire
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.net.InetAddress

/**
 * A route pushed to the group must arrive as the identical route — same line, same turns, same
 * spoken words. A follower riding a subtly different line, or one with no instructions, is
 * worse than no sharing at all.
 */
class RoutePushTest {

    private fun requireNetwork() {
        try {
            InetAddress.getByName("valhalla1.openstreetmap.de")
        } catch (e: Exception) {
            assumeNoException("no network", e)
        }
    }

    private fun realPlan() = runBlocking {
        Valhalla.plan(
            listOf(
                Waypoint(LatLon(41.4020, -80.3900), "Greenville"),
                Waypoint(LatLon(42.1592, -80.1120), "Presque Isle"),
            ),
            RouteStyle.CURVY,
        )
    }

    /** Pushes through the real wire format and reassembles, as two phones would. */
    private fun roundTrip(plan: com.cruze.route.RoutePlan): com.cruze.route.RoutePlan? {
        val chunks = RouteTransfer.chunk(plan, "LEADER01", "Dana")
        val assembler = RouteTransfer.Assembler()
        var received: com.cruze.route.RoutePlan? = null
        chunks.forEach { c ->
            // Every chunk goes through encode/decode exactly as it would over the relay.
            val wire = Wire.encode(c)
            assertTrue("chunk too big for the relay: ${wire.length}", wire.length < 4096)
            val back = Wire.decode(wire) as RideEvent.RouteChunk
            assembler.accept(back)?.let { received = it.first }
        }
        return received
    }

    @Test
    fun `a pushed route arrives identical - geometry, turns and spoken words`() {
        requireNetwork()
        val original = realPlan()
        val received = roundTrip(original)

        assertNotNull("route never completed", received)
        val r = received!!

        assertEquals("shape length differs", original.shape.size, r.shape.size)
        original.shape.zip(r.shape).forEach { (a, b) ->
            assertTrue("a point moved: $a vs $b", distanceM(a, b) < 0.2)
        }

        assertEquals("maneuver count differs", original.maneuvers.size, r.maneuvers.size)
        original.maneuvers.zip(r.maneuvers).forEach { (a, b) ->
            assertEquals(a.instruction, b.instruction)
            assertEquals("voice differs — followers would hear something else", a.verbalPre, b.verbalPre)
            assertEquals(a.verbalAlert, b.verbalAlert)
            assertEquals("turn anchor moved", a.beginIdx, b.beginIdx)
        }

        assertEquals(original.style, r.style)
        assertEquals(original.lengthM, r.lengthM, 1.0)
        assertEquals(original.timeS, r.timeS, 1.0)
        assertEquals(original.waypoints.size, r.waypoints.size)
        // The curviness the follower sees must match what the leader chose the road for.
        assertEquals(original.curviness, r.curviness, 0.5)
    }

    @Test
    fun `chunks may arrive out of order or duplicated`() {
        requireNetwork()
        val original = realPlan()
        val chunks = RouteTransfer.chunk(original, "L", "Dana")
        assertTrue("route should need several chunks", chunks.size > 1)

        val assembler = RouteTransfer.Assembler()
        var received: com.cruze.route.RoutePlan? = null
        // Reversed, with every chunk sent twice.
        (chunks.reversed() + chunks.reversed()).forEach { c ->
            assembler.accept(c)?.let { received = it.first }
        }
        assertNotNull("out-of-order delivery lost the route", received)
        assertEquals(original.shape.size, received!!.shape.size)
    }

    @Test
    fun `an incomplete or corrupted route is never applied`() {
        requireNetwork()
        val chunks = RouteTransfer.chunk(realPlan(), "L", "Dana")

        // Missing the last chunk: nothing should ever complete.
        val a1 = RouteTransfer.Assembler()
        chunks.dropLast(1).forEach { assertNull(a1.accept(it)) }

        // A tampered chunk must fail the hash rather than produce a wrong route.
        val a2 = RouteTransfer.Assembler()
        var out: com.cruze.route.RoutePlan? = null
        chunks.forEachIndexed { i, c ->
            val corrupted = if (i == 0) c.copy(payload = c.payload.replaceFirst("shape", "shapX")) else c
            a2.accept(corrupted)?.let { out = it.first }
        }
        assertNull("a corrupted route was accepted", out)
    }
}
