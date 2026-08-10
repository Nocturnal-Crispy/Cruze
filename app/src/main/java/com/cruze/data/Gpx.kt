package com.cruze.data

import android.util.Xml
import com.cruze.Fix
import com.cruze.LatLon
import com.cruze.route.RoutePlan
import com.cruze.route.Waypoint
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * GPX 1.1 read/write. Hand-rolled against the stdlib pull parser — the format is a handful of
 * elements and a dependency would be more code than this file.
 */
object Gpx {

    private fun iso(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun header(name: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="Cruze" xmlns="http://www.topografix.com/GPX/1/1">
          <metadata><name>${esc(name)}</name></metadata>
    """.trimIndent() + "\n"

    /** A planned route: waypoints as <wpt>, the routed line as a <trk> so any app can draw it. */
    fun writeRoute(plan: RoutePlan, name: String): String = buildString {
        append(header(name))
        plan.waypoints.forEachIndexed { i, w ->
            append("  <wpt lat=\"${w.pos.lat}\" lon=\"${w.pos.lon}\">")
            append("<name>${esc(w.name.ifBlank { "Point ${i + 1}" })}</name></wpt>\n")
        }
        append("  <trk><name>${esc(name)}</name><trkseg>\n")
        plan.shape.forEach { append("    <trkpt lat=\"${it.lat}\" lon=\"${it.lon}\"/>\n") }
        append("  </trkseg></trk>\n</gpx>\n")
    }

    /** A recorded ride: timestamps and elevation included so other tools can analyse it. */
    fun writeTrack(points: List<Fix>, name: String): String = buildString {
        val fmt = iso()
        append(header(name))
        append("  <trk><name>${esc(name)}</name><trkseg>\n")
        points.forEach { p ->
            append("    <trkpt lat=\"${p.pos.lat}\" lon=\"${p.pos.lon}\">")
            append("<ele>${"%.1f".format(Locale.US, p.altitudeM)}</ele>")
            append("<time>${fmt.format(java.util.Date(p.timeMs))}</time>")
            append("</trkpt>\n")
        }
        append("  </trkseg></trk>\n</gpx>\n")
    }

    data class Imported(val name: String, val waypoints: List<Waypoint>, val track: List<LatLon>)

    /**
     * Reads whatever geometry a GPX file happens to carry. Explicit <wpt>/<rtept> become
     * waypoints; otherwise a track is thinned down to a usable set of routing points, because
     * a recorded ride can hold thousands of points and Valhalla takes a handful.
     */
    fun read(input: InputStream, maxWaypoints: Int = 12): Imported {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var name = ""
        val wpts = ArrayList<Waypoint>()
        val rtepts = ArrayList<Waypoint>()
        val trk = ArrayList<LatLon>()
        var pendingName: StringBuilder? = null
        var lastTag = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    lastTag = parser.name.lowercase()
                    val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                    val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                    if (lat != null && lon != null) {
                        when (lastTag) {
                            "wpt" -> wpts.add(Waypoint(LatLon(lat, lon)))
                            "rtept" -> rtepts.add(Waypoint(LatLon(lat, lon)))
                            "trkpt" -> trk.add(LatLon(lat, lon))
                        }
                    }
                    if (lastTag == "name" && name.isEmpty()) pendingName = StringBuilder()
                }
                XmlPullParser.TEXT -> pendingName?.append(parser.text)
                XmlPullParser.END_TAG -> {
                    if (parser.name.lowercase() == "name" && pendingName != null) {
                        if (name.isEmpty()) name = pendingName.toString().trim()
                        pendingName = null
                    }
                }
            }
            event = parser.next()
        }

        val chosen = when {
            rtepts.size >= 2 -> rtepts
            wpts.size >= 2 -> wpts
            trk.size >= 2 -> thin(trk, maxWaypoints).map { Waypoint(it) }
            else -> emptyList()
        }
        return Imported(name.ifBlank { "Imported route" }, chosen, trk)
    }

    /** Evenly spaced sample of [path], always keeping both ends. */
    internal fun thin(path: List<LatLon>, count: Int): List<LatLon> {
        if (path.size <= count) return path
        val n = count.coerceAtLeast(2)
        val step = (path.size - 1).toDouble() / (n - 1)
        return (0 until n).map { path[Math.round(it * step).toInt().coerceIn(0, path.size - 1)] }
    }
}
