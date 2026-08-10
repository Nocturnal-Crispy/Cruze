package com.cruze.route

import com.cruze.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Measures how much of a route is actually unpaved.
 *
 * Asking the router to avoid unpaved roads is not the same as it having done so: costing
 * options are hints, and an unknown one is silently ignored. This checks the road surface the
 * chosen line really runs on, so "avoid dirt roads" can be verified rather than assumed.
 */
object Surface {

    private const val URL = "https://valhalla1.openstreetmap.de/trace_attributes"
    private val JSON = "application/json".toMediaType()

    /** Surfaces a road bike has no business on. */
    private val UNPAVED = setOf(
        "compacted", "dirt", "gravel", "path", "impassable", "unpaved",
    )

    data class Report(val unpavedFraction: Double, val unpavedMetres: Double, val checked: Boolean)

    /**
     * Fraction of [shape] running on unpaved surfaces, or an unchecked report when the service
     * cannot answer — an unavailable check must never block a rider from riding.
     */
    suspend fun check(shape: List<LatLon>): Report = withContext(Dispatchers.IO) {
        if (shape.size < 2) return@withContext Report(0.0, 0.0, checked = false)
        runCatching {
            val body = JSONObject().apply {
                put("encoded_polyline", encodePolyline(shape))
                put("costing", "motorcycle")
                put("shape_match", "walk_or_snap")
                put("filters", JSONObject().apply {
                    put("attributes", org.json.JSONArray(listOf("edge.surface", "edge.length")))
                    put("action", "include")
                })
            }
            val req = Request.Builder().url(URL)
                .header("User-Agent", USER_AGENT)
                .post(body.toString().toRequestBody(JSON))
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Report(0.0, 0.0, checked = false)
                val edges = JSONObject(resp.body?.string().orEmpty()).optJSONArray("edges")
                    ?: return@withContext Report(0.0, 0.0, checked = false)

                var total = 0.0
                var rough = 0.0
                for (i in 0 until edges.length()) {
                    val e = edges.getJSONObject(i)
                    // trace_attributes reports edge length in kilometres.
                    val len = e.optDouble("length", 0.0) * 1000.0
                    total += len
                    if (e.optString("surface") in UNPAVED) rough += len
                }
                if (total <= 0) Report(0.0, 0.0, checked = false)
                else Report(rough / total, rough, checked = true)
            }
        }.getOrElse { Report(0.0, 0.0, checked = false) }
    }
}
