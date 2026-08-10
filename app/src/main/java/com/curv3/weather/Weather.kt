package com.curv3.weather

import com.curv3.LatLon
import com.curv3.route.ServiceException
import com.curv3.route.USER_AGENT
import com.curv3.route.http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Weather from two free keyless sources: RainViewer for the radar tiles, and the US National
 * Weather Service for warnings. Neither needs an account.
 */
object Weather {

    private const val RAINVIEWER = "https://api.rainviewer.com/public/weather-maps.json"
    private const val NWS = "https://api.weather.gov"

    data class RadarFrame(val path: String, val timeMs: Long)

    /**
     * Radar frame paths, oldest first. The tile host rotates these every ten minutes, so the
     * paths must be refetched rather than cached for long.
     */
    suspend fun radarFrames(): List<RadarFrame> = withContext(Dispatchers.IO) {
        val root = JSONObject(get(RAINVIEWER))
        val host = root.optString("host")
        val radar = root.optJSONObject("radar") ?: return@withContext emptyList()
        val frames = ArrayList<RadarFrame>()
        listOf("past", "nowcast").forEach { key ->
            val arr = radar.optJSONArray(key) ?: JSONArray()
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                frames.add(RadarFrame(host + f.optString("path"), f.optLong("time") * 1000L))
            }
        }
        frames.sortedBy { it.timeMs }
    }

    data class Alert(
        val event: String,
        val severity: String,
        val headline: String,
        val area: String,
    ) {
        /** Whether this is worth interrupting a rider for. */
        val urgent: Boolean get() = severity in setOf("Extreme", "Severe")
    }

    /**
     * Active weather warnings along a route.
     *
     * NWS is queried per point, so the route is sampled rather than sent whole — a handful of
     * points spread along it catches any storm cell big enough to matter, without hammering a
     * free service on every replan.
     */
    suspend fun alertsAlong(shape: List<LatLon>, samples: Int = 6): List<Alert> =
        withContext(Dispatchers.IO) {
            if (shape.size < 2) return@withContext emptyList()
            val step = (shape.size - 1).toDouble() / (samples - 1).coerceAtLeast(1)
            val points = (0 until samples).map {
                shape[Math.round(it * step).toInt().coerceIn(0, shape.lastIndex)]
            }
            val seen = LinkedHashMap<String, Alert>()
            points.forEach { p ->
                runCatching {
                    val url = "$NWS/alerts/active?point=${"%.4f".format(p.lat)},${"%.4f".format(p.lon)}"
                    val features = JSONObject(get(url)).optJSONArray("features") ?: JSONArray()
                    for (i in 0 until features.length()) {
                        val props = features.getJSONObject(i).optJSONObject("properties") ?: continue
                        val id = props.optString("id").ifBlank { props.optString("headline") }
                        if (id.isBlank() || seen.containsKey(id)) continue
                        seen[id] = Alert(
                            event = props.optString("event"),
                            severity = props.optString("severity"),
                            headline = props.optString("headline"),
                            area = props.optString("areaDesc"),
                        )
                    }
                }
            }
            // Worst first, so a tornado warning never sits below a frost advisory.
            val rank = listOf("Extreme", "Severe", "Moderate", "Minor", "Unknown")
            seen.values.sortedBy { rank.indexOf(it.severity).takeIf { i -> i >= 0 } ?: 99 }
        }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ServiceException("Weather service error ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }
}
