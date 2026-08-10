package com.cruze.route

import com.cruze.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray

data class Place(val name: String, val pos: LatLon)

/**
 * Geocoding via public Nominatim. Its usage policy caps us at one request per second and
 * requires an identifying User-Agent, so every call goes through the same throttle.
 */
object Nominatim {
    private const val BASE = "https://nominatim.openstreetmap.org"
    private val gate = Mutex()
    private var lastCallAt = 0L

    private suspend fun <T> throttled(block: () -> T): T = gate.withLock {
        val since = System.currentTimeMillis() - lastCallAt
        if (since < 1100) delay(1100 - since)
        try {
            block()
        } finally {
            lastCallAt = System.currentTimeMillis()
        }
    }

    suspend fun search(query: String, near: LatLon?): List<Place> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val params = buildList {
            add("q" to query)
            add("format" to "jsonv2")
            add("limit" to "8")
            // Bias results toward the map view without hard-filtering them out.
            near?.let {
                add("viewbox" to "${it.lon - 1.5},${it.lat + 1.0},${it.lon + 1.5},${it.lat - 1.0}")
            }
        }
        val url = "$BASE/search?" + query(params)

        throttled { get(url) }.let { text ->
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
                val lon = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
                Place(o.optString("display_name").ifBlank { o.optString("name") }, LatLon(lat, lon))
            }
        }
    }

    suspend fun reverse(pos: LatLon): String = withContext(Dispatchers.IO) {
        val url = "$BASE/reverse?" + query(
            listOf(
                "lat" to pos.lat.toString(),
                "lon" to pos.lon.toString(),
                "format" to "jsonv2",
                "zoom" to "16",
            )
        )
        runCatching {
            org.json.JSONObject(throttled { get(url) }).optString("display_name")
        }.getOrDefault("").ifBlank { "%.4f, %.4f".format(pos.lat, pos.lon) }
    }

    /**
     * Builds the query string directly rather than through android.net.Uri, so geocoding can
     * be exercised by plain JVM tests instead of only on a device.
     */
    private fun query(params: List<Pair<String, String>>): String =
        params.joinToString("&") { (k, v) ->
            "$k=" + java.net.URLEncoder.encode(v, "UTF-8")
        }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ServiceException("Search failed (${resp.code})")
            return resp.body?.string().orEmpty()
        }
    }
}
