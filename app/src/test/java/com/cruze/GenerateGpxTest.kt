package com.cruze

import com.cruze.data.Gpx
import com.cruze.route.RouteStyle
import com.cruze.route.Valhalla
import com.cruze.route.Waypoint
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.io.File
import java.net.InetAddress

/**
 * Produces the Presque Isle loop as a GPX file using the app's own writer, so what lands on the
 * phone is byte-for-byte what the app itself would export.
 */
class GenerateGpxTest {

    @Test
    fun `write the Presque Isle loop to a gpx file`() = runBlocking {
        try {
            InetAddress.getByName("valhalla1.openstreetmap.de")
        } catch (e: Exception) {
            assumeNoException("no network", e); return@runBlocking
        }

        val stops = listOf(
            Waypoint(LatLon(41.2339, -80.4931), "Sharon PA"),
            Waypoint(LatLon(41.8742, -80.1315), "Edinboro PA"),
            Waypoint(LatLon(42.1592, -80.1120), "Presque Isle"),
            Waypoint(LatLon(41.2270, -80.2384), "Mercer PA"),
            Waypoint(LatLon(41.2339, -80.4931), "Sharon PA"),
        )
        val plan = Valhalla.plan(stops, RouteStyle.CURVY)
        val gpx = Gpx.writeRoute(plan, "Presque Isle loop via Edinboro and Mercer")

        val out = File(System.getProperty("cruze.gpx.out") ?: "build/presque-isle-loop.gpx")
        out.parentFile?.mkdirs()
        out.writeText(gpx)
        println("GPX_WRITTEN ${out.absolutePath} bytes=${gpx.length} " +
            "points=${plan.shape.size} miles=${"%.1f".format(metresToMiles(plan.lengthM))}")
    }
}
