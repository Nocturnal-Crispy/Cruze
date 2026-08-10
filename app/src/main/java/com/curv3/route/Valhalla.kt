package com.curv3.route

import com.curv3.LatLon
import com.curv3.M_PER_MILE
import com.curv3.bearingDeg
import com.curv3.destinationPoint
import com.curv3.distanceM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Routing via the FOSSGIS-hosted Valhalla instance. Keyless and free; in exchange we keep
 * request volume low (at most two calls per plan) and identify ourselves properly.
 */
object Valhalla {
    private const val URL = "https://valhalla1.openstreetmap.de/route"
    private val JSON = "application/json".toMediaType()

    suspend fun plan(
        waypoints: List<Waypoint>,
        style: RouteStyle,
        roundTrip: Boolean = false,
    ): RoutePlan {
        require(waypoints.size >= 2) { "need at least a start and a destination" }
        if (roundTrip) return planLoop(waypoints, style)

        // One probe per highway tolerance, each returning a primary plus alternates. Two calls
        // gives ~6 candidates, which is plenty of spread to pick a genuinely curvier line from.
        val probes = if (style == RouteStyle.FAST) listOf(1.0) else listOf(style.useHighways, 0.5)

        val candidates = coroutineScope {
            probes.map { hw -> async(Dispatchers.IO) { runCatching { fetch(waypoints, style, hw) }.getOrDefault(emptyList()) } }
                .flatMap { it.await() }
        }
        if (candidates.isEmpty()) throw ServiceException("No route found. Check the points are reachable by road.")
        return pickBest(dedupe(candidates), style)
            ?: throw ServiceException("No usable route found.")
    }

    /**
     * A circular route: out to the far point one way and home another.
     *
     * Valhalla has no round-trip mode, and blocking the outbound roads to force a different
     * return fails outright wherever roads are sparse — which is exactly the terrain worth
     * riding. Instead the loop is shaped by placing via points off to each side of the
     * start-to-destination axis, which bows the two halves apart and always stays routable.
     */
    private suspend fun planLoop(waypoints: List<Waypoint>, style: RouteStyle): RoutePlan {
        val start = waypoints.first()
        val far = waypoints.last()
        val axis = bearingDeg(start.pos, far.pos)
        val span = distanceM(start.pos, far.pos)
        val mid = destinationPoint(start.pos, axis, span / 2)

        fun via(sideDeg: Double, k: Double) =
            Waypoint(destinationPoint(mid, axis + sideDeg, k * span), "")

        // Wider offsets make a rounder, longer loop. Only bow the outbound when the rider has
        // not already chosen the way there with their own waypoints.
        val bowBoth = waypoints.size == 2
        val shapes = listOf(0.35, 0.6, 0.9).map { k ->
            buildList {
                add(start)
                if (bowBoth) add(via(-90.0, k))
                addAll(waypoints.drop(1))
                add(via(+90.0, k))
                add(start)
            }
        }
        // Plain out-and-back, so a loop that cannot be routed still yields a usable ride home.
        val fallback = waypoints + start

        val candidates = coroutineScope {
            (shapes + listOf(fallback)).map { wps ->
                async(Dispatchers.IO) {
                    runCatching { fetch(wps, style, style.useHighways) }.getOrDefault(emptyList())
                }
            }.flatMap { it.await() }
        }
        if (candidates.isEmpty()) throw ServiceException("Could not build a round trip from here.")
        // Report the rider's own points, not the via points invented to shape the loop.
        return (pickBest(dedupe(candidates), style) ?: throw ServiceException("No usable round trip found."))
            .copy(waypoints = waypoints + start)
    }

    private fun fetch(waypoints: List<Waypoint>, style: RouteStyle, useHighways: Double): List<RoutePlan> {
        val body = JSONObject().apply {
            put("locations", JSONArray().apply {
                waypoints.forEachIndexed { i, w ->
                    put(JSONObject().apply {
                        put("lat", w.pos.lat)
                        put("lon", w.pos.lon)
                        put("type", if (i == 0 || i == waypoints.lastIndex) "break" else "through")
                    })
                }
            })
            put("costing", "motorcycle")
            put("costing_options", JSONObject().apply {
                put("motorcycle", JSONObject().apply {
                    put("use_highways", useHighways)
                    put("use_tolls", 0.2)
                    put("use_trails", 0.0)
                })
            })
            // Drives the spoken instructions too — "in a quarter mile, turn right".
            put("directions_options", JSONObject().put("units", "miles"))
            put("alternates", 2)
        }

        val req = Request.Builder().url(URL)
            .header("User-Agent", USER_AGENT)
            .post(body.toString().toRequestBody(JSON))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()
                throw ServiceException(msg?.takeIf { it.isNotBlank() } ?: "Routing service error ${resp.code}")
            }
            val root = JSONObject(text)
            val out = ArrayList<RoutePlan>()
            root.optJSONObject("trip")?.let { out.add(parseTrip(it, waypoints, style)) }
            val alts = root.optJSONArray("alternates")
            for (i in 0 until (alts?.length() ?: 0)) {
                alts!!.optJSONObject(i)?.optJSONObject("trip")
                    ?.let { out.add(parseTrip(it, waypoints, style)) }
            }
            return out
        }
    }

    private fun parseTrip(trip: JSONObject, waypoints: List<Waypoint>, style: RouteStyle): RoutePlan {
        val shape = ArrayList<LatLon>()
        val maneuvers = ArrayList<Maneuver>()
        val legs = trip.optJSONArray("legs") ?: JSONArray()

        for (l in 0 until legs.length()) {
            val leg = legs.getJSONObject(l)
            val legShape = decodePolyline(leg.optString("shape"))
            // Legs join at a shared point; drop the duplicate so shape indices stay continuous.
            val offset = shape.size
            val skipFirst = shape.isNotEmpty() && legShape.isNotEmpty() && shape.last() == legShape.first()
            shape.addAll(if (skipFirst) legShape.drop(1) else legShape)
            val idxShift = if (skipFirst) offset - 1 else offset

            val ms = leg.optJSONArray("maneuvers") ?: JSONArray()
            for (i in 0 until ms.length()) {
                val m = ms.getJSONObject(i)
                maneuvers.add(
                    Maneuver(
                        type = m.optInt("type"),
                        instruction = m.optString("instruction"),
                        verbalAlert = m.optString("verbal_transition_alert_instruction"),
                        verbalPre = m.optString("verbal_pre_transition_instruction")
                            .ifBlank { m.optString("instruction") },
                        verbalPost = m.optString("verbal_post_transition_instruction"),
                        streets = m.optJSONArray("street_names")?.let { a ->
                            (0 until a.length()).joinToString(", ") { a.optString(it) }
                        }.orEmpty(),
                        lengthM = m.optDouble("length", 0.0) * M_PER_MILE,
                        timeS = m.optDouble("time", 0.0),
                        beginIdx = (m.optInt("begin_shape_index") + idxShift).coerceAtLeast(0),
                        endIdx = (m.optInt("end_shape_index") + idxShift).coerceAtLeast(0),
                    )
                )
            }
        }

        val sum = trip.optJSONObject("summary") ?: JSONObject()
        return RoutePlan(
            shape = shape,
            maneuvers = maneuvers,
            lengthM = sum.optDouble("length", 0.0) * M_PER_MILE,
            timeS = sum.optDouble("time", 0.0),
            hasHighway = sum.optBoolean("has_highway", false),
            waypoints = waypoints,
            style = style,
            curviness = curvinessScore(shape),
        )
    }
}
