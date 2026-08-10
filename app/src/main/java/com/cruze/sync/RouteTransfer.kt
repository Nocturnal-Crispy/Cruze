package com.cruze.sync

import com.cruze.LatLon
import com.cruze.route.Maneuver
import com.cruze.route.RoutePlan
import com.cruze.route.RouteStyle
import com.cruze.route.Waypoint
import com.cruze.route.decodePolyline
import com.cruze.route.encodePolyline
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Sending the leader's route to the group.
 *
 * A route is far too big for one relay message — a modest 70-mile route encodes to about 26 kB
 * against a 4 kB message limit — so it is split into numbered chunks and rebuilt on arrival.
 * The whole payload is hashed, and a route is only applied once every chunk is present and the
 * hash matches, so a follower never navigates a half-received or corrupted line.
 *
 * The complete plan travels, not just the geometry: without the maneuvers a follower would see
 * the right line on the map and get no turn instructions and no voice at all.
 */
object RouteTransfer {

    /** Comfortably inside the relay's per-message limit, leaving room for the envelope. */
    const val CHUNK_SIZE = 2500

    fun planToJson(p: RoutePlan): String = JSONObject().apply {
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
                    put("t", it.type)
                    put("i", it.instruction)
                    put("a", it.verbalAlert)
                    put("p", it.verbalPre)
                    put("o", it.verbalPost)
                    put("s", it.streets)
                    put("l", it.lengthM)
                    put("d", it.timeS)
                    put("b", it.beginIdx)
                    put("e", it.endIdx)
                })
            }
        })
    }.toString()

    fun planFromJson(text: String): RoutePlan {
        val o = JSONObject(text)
        val wps = o.optJSONArray("waypoints") ?: JSONArray()
        val ms = o.optJSONArray("maneuvers") ?: JSONArray()
        return RoutePlan(
            shape = decodePolyline(o.optString("shape")),
            maneuvers = (0 until ms.length()).map { i ->
                val m = ms.getJSONObject(i)
                Maneuver(
                    type = m.optInt("t"),
                    instruction = m.optString("i"),
                    verbalAlert = m.optString("a"),
                    verbalPre = m.optString("p"),
                    verbalPost = m.optString("o"),
                    streets = m.optString("s"),
                    lengthM = m.optDouble("l", 0.0),
                    timeS = m.optDouble("d", 0.0),
                    beginIdx = m.optInt("b"),
                    endIdx = m.optInt("e"),
                )
            },
            lengthM = o.optDouble("lengthM", 0.0),
            timeS = o.optDouble("timeS", 0.0),
            hasHighway = o.optBoolean("hasHighway", false),
            waypoints = (0 until wps.length()).map { i ->
                val w = wps.getJSONObject(i)
                Waypoint(LatLon(w.optDouble("lat"), w.optDouble("lon")), w.optString("name"))
            },
            style = runCatching { RouteStyle.valueOf(o.optString("style")) }
                .getOrDefault(RouteStyle.CURVY),
            curviness = o.optDouble("curviness", 0.0),
        )
    }

    fun hashOf(payload: String): String =
        MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    /** Splits a plan into relay-sized chunks. */
    fun chunk(plan: RoutePlan, riderId: String, name: String): List<RideEvent.RouteChunk> {
        val payload = planToJson(plan)
        val hash = hashOf(payload)
        val parts = payload.chunked(CHUNK_SIZE)
        return parts.mapIndexed { i, part ->
            RideEvent.RouteChunk(riderId, hash, i, parts.size, part, name)
        }
    }

    /**
     * Collects chunks until a route is complete.
     *
     * Chunks can arrive out of order, duplicated, or interleaved with a newer push, so parts
     * are keyed by route hash and a stale in-flight transfer is simply never completed.
     */
    class Assembler {
        private val partsByRoute = HashMap<String, HashMap<Int, String>>()
        private val expected = HashMap<String, Int>()
        private val names = HashMap<String, String>()
        private val startedAt = HashMap<String, Long>()

        /** A transfer missing a chunk this long is never completing; it is abandoned. */
        private val staleAfterMs = 120_000L

        /** Returns the finished plan once the last missing chunk lands, else null. */
        fun accept(chunk: RideEvent.RouteChunk): Pair<RoutePlan, String>? {
            if (chunk.count <= 0 || chunk.index !in 0 until chunk.count) return null
            dropStale()
            startedAt.getOrPut(chunk.routeId) { System.currentTimeMillis() }
            val parts = partsByRoute.getOrPut(chunk.routeId) { HashMap() }
            parts[chunk.index] = chunk.payload
            expected[chunk.routeId] = chunk.count
            names[chunk.routeId] = chunk.name
            if (parts.size < chunk.count) return null

            val payload = (0 until chunk.count).joinToString("") { parts[it].orEmpty() }
            // Only trust a route whose bytes hash to what the sender said they would.
            if (hashOf(payload) != chunk.routeId) {
                partsByRoute.remove(chunk.routeId)
                startedAt.remove(chunk.routeId)
                return null
            }
            val plan = runCatching { planFromJson(payload) }.getOrNull() ?: return null
            partsByRoute.remove(chunk.routeId)
            expected.remove(chunk.routeId)
            startedAt.remove(chunk.routeId)
            return plan to names.remove(chunk.routeId).orEmpty()
        }

        /**
         * Fraction received of the transfer currently in flight, for a progress readout.
         *
         * Returns 1f — meaning "nothing in flight" — once a stalled transfer has been given up
         * on. Without that, a single chunk lost to a dead zone left the receiving rider staring
         * at a loading card stuck at two thirds for the rest of the ride.
         */
        fun progress(): Float {
            dropStale()
            val id = partsByRoute.keys.firstOrNull() ?: return 1f
            val total = expected[id] ?: return 1f
            return (partsByRoute[id]?.size ?: 0).toFloat() / total
        }

        private fun dropStale() {
            val cutoff = System.currentTimeMillis() - staleAfterMs
            startedAt.filterValues { it < cutoff }.keys.forEach { id ->
                partsByRoute.remove(id); expected.remove(id); names.remove(id); startedAt.remove(id)
            }
        }

        fun clear() {
            partsByRoute.clear(); expected.clear(); names.clear(); startedAt.clear()
        }
    }
}
