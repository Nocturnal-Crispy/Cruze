package com.cruze

import com.cruze.route.RouteStyle
import com.cruze.route.Surface
import com.cruze.route.Valhalla
import com.cruze.route.Waypoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress

/** Routes handed to a rider must be paved. */
class SurfaceTest {

    private fun requireNetwork() {
        try {
            InetAddress.getByName("valhalla1.openstreetmap.de")
        } catch (e: Exception) {
            assumeNoException("no network", e)
        }
    }

    @Test
    fun `planned routes come back paved`() = runBlocking {
        requireNetwork()
        // Rural north-west Pennsylvania, where unpaved back roads genuinely exist.
        val legs = listOf(
            LatLon(41.6200, -80.3000) to LatLon(41.7200, -80.1500),
            LatLon(41.4020, -80.3900) to LatLon(41.8742, -80.1315),
            LatLon(41.2339, -80.4931) to LatLon(41.2270, -80.2384),
        )
        var checked = 0
        legs.forEach { (from, to) ->
            val plan = Valhalla.plan(
                listOf(Waypoint(from, "from"), Waypoint(to, "to")),
                RouteStyle.CURVY,
            )
            val report = Surface.check(plan.shape)
            println(
                "route ${fmtDist(plan.lengthM)}: unpaved ${"%.2f".format(report.unpavedFraction * 100)}%" +
                    " (${fmtDist(report.unpavedMetres)}) checked=${report.checked}"
            )
            // An unchecked leg used to pass silently, so the one test guarding "no dirt roads"
            // asserted nothing whenever the surface endpoint was unavailable — which looks
            // identical to the rule working.
            checked += if (report.checked) 1 else 0
            if (report.checked) {
                assertTrue(
                    "route is ${"%.1f".format(report.unpavedFraction * 100)}% unpaved",
                    report.unpavedFraction <= 0.05,
                )
            }
        }
        assumeTrue(
            "surface data was unavailable for every leg — this proved nothing",
            checked > 0,
        )
    }
}
