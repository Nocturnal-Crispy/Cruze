package com.curv3.data

import android.content.Context
import com.curv3.Fix
import com.curv3.LatLon
import com.curv3.route.Maneuver
import com.curv3.route.RoutePlan
import com.curv3.route.RouteStyle
import com.curv3.route.Waypoint
import com.curv3.route.decodePolyline
import com.curv3.route.encodePolyline
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedRoute(val id: String, val name: String, val createdAt: Long, val plan: RoutePlan)
data class SavedTrack(
    val id: String,
    val name: String,
    val startedAt: Long,
    val points: List<Fix>,
)

/**
 * Route and track storage as one JSON file per item under the app's private files dir.
 *
 * ponytail: no database. These are tens of records read as a whole list; a Room dependency,
 * schema and migration story would all be pure overhead. Move to Room if a user ever
 * accumulates enough rides that listing them gets slow.
 */
class Store(context: Context) {
    private val routesDir = File(context.filesDir, "routes").apply { mkdirs() }
    private val tracksDir = File(context.filesDir, "tracks").apply { mkdirs() }

    fun listRoutes(): List<SavedRoute> = routesDir.listFiles().orEmpty()
        .filter { it.extension == "json" }
        .mapNotNull { f -> runCatching { routeFromJson(JSONObject(f.readText())) }.getOrNull() }
        .sortedByDescending { it.createdAt }

    fun saveRoute(r: SavedRoute) {
        File(routesDir, "${r.id}.json").writeText(routeToJson(r).toString())
    }

    fun deleteRoute(id: String) { File(routesDir, "$id.json").delete() }

    fun listTracks(): List<SavedTrack> = tracksDir.listFiles().orEmpty()
        .filter { it.extension == "json" }
        .mapNotNull { f -> runCatching { trackFromJson(JSONObject(f.readText())) }.getOrNull() }
        .sortedByDescending { it.startedAt }

    fun saveTrack(t: SavedTrack) {
        File(tracksDir, "${t.id}.json").writeText(trackToJson(t).toString())
    }

    fun deleteTrack(id: String) { File(tracksDir, "$id.json").delete() }
}

// --- serialisation -----------------------------------------------------------------------

private fun routeToJson(r: SavedRoute) = JSONObject().apply {
    put("id", r.id)
    put("name", r.name)
    put("createdAt", r.createdAt)
    put("plan", planToJson(r.plan))
}

private fun routeFromJson(o: JSONObject) = SavedRoute(
    id = o.getString("id"),
    name = o.optString("name"),
    createdAt = o.optLong("createdAt"),
    plan = planFromJson(o.getJSONObject("plan")),
)

fun planToJson(p: RoutePlan): JSONObject = JSONObject().apply {
    // The shape is the bulk of the payload; keep it encoded rather than as 10k JSON numbers.
    put("shape", encodePolyline(p.shape))
    put("lengthM", p.lengthM)
    put("timeS", p.timeS)
    put("hasHighway", p.hasHighway)
    put("style", p.style.name)
    put("curviness", p.curviness)
    put("waypoints", JSONArray().apply {
        p.waypoints.forEach {
            put(JSONObject().apply {
                put("lat", it.pos.lat); put("lon", it.pos.lon); put("name", it.name)
            })
        }
    })
    put("maneuvers", JSONArray().apply {
        p.maneuvers.forEach {
            put(JSONObject().apply {
                put("type", it.type)
                put("instruction", it.instruction)
                put("alert", it.verbalAlert)
                put("pre", it.verbalPre)
                put("post", it.verbalPost)
                put("streets", it.streets)
                put("lengthM", it.lengthM)
                put("timeS", it.timeS)
                put("begin", it.beginIdx)
                put("end", it.endIdx)
            })
        }
    })
}

fun planFromJson(o: JSONObject): RoutePlan {
    val wps = o.optJSONArray("waypoints") ?: JSONArray()
    val ms = o.optJSONArray("maneuvers") ?: JSONArray()
    return RoutePlan(
        shape = decodePolyline(o.optString("shape")),
        maneuvers = (0 until ms.length()).map { i ->
            val m = ms.getJSONObject(i)
            Maneuver(
                type = m.optInt("type"),
                instruction = m.optString("instruction"),
                verbalAlert = m.optString("alert"),
                verbalPre = m.optString("pre"),
                verbalPost = m.optString("post"),
                streets = m.optString("streets"),
                lengthM = m.optDouble("lengthM", 0.0),
                timeS = m.optDouble("timeS", 0.0),
                beginIdx = m.optInt("begin"),
                endIdx = m.optInt("end"),
            )
        },
        lengthM = o.optDouble("lengthM", 0.0),
        timeS = o.optDouble("timeS", 0.0),
        hasHighway = o.optBoolean("hasHighway", false),
        waypoints = (0 until wps.length()).map { i ->
            val w = wps.getJSONObject(i)
            Waypoint(LatLon(w.optDouble("lat"), w.optDouble("lon")), w.optString("name"))
        },
        style = runCatching { RouteStyle.valueOf(o.optString("style")) }.getOrDefault(RouteStyle.CURVY),
        curviness = o.optDouble("curviness", 0.0),
    )
}

private fun trackToJson(t: SavedTrack) = JSONObject().apply {
    put("id", t.id)
    put("name", t.name)
    put("startedAt", t.startedAt)
    put("points", JSONArray().apply {
        t.points.forEach {
            put(JSONArray().apply {
                put(it.pos.lat); put(it.pos.lon); put(it.timeMs)
                put(it.speedMps.toDouble()); put(it.altitudeM); put(it.bearing.toDouble())
            })
        }
    })
}

private fun trackFromJson(o: JSONObject): SavedTrack {
    val arr = o.optJSONArray("points") ?: JSONArray()
    return SavedTrack(
        id = o.getString("id"),
        name = o.optString("name"),
        startedAt = o.optLong("startedAt"),
        points = (0 until arr.length()).map { i ->
            val a = arr.getJSONArray(i)
            Fix(
                pos = LatLon(a.getDouble(0), a.getDouble(1)),
                timeMs = a.getLong(2),
                speedMps = a.optDouble(3, 0.0).toFloat(),
                altitudeM = a.optDouble(4, 0.0),
                bearing = a.optDouble(5, 0.0).toFloat(),
                accuracyM = 0f,
            )
        },
    )
}
