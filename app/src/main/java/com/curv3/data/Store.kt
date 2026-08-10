package com.curv3.data

import android.content.Context
import com.curv3.Fix
import com.curv3.LatLon
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedTrack(
    val id: String,
    val name: String,
    val startedAt: Long,
    val points: List<Fix>,
)

/**
 * Recorded-ride storage: one JSON file per track under the app's private files dir.
 *
 * ponytail: no database. These are tens of records read as a whole list; a Room dependency,
 * schema and migration story would all be pure overhead. Move to Room if a user ever
 * accumulates enough rides that listing them gets slow.
 */
class Store(context: Context) {
    private val tracksDir = File(context.filesDir, "tracks").apply { mkdirs() }

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
