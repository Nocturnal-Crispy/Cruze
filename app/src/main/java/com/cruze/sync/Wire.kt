package com.cruze.sync

import com.cruze.LatLon
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Wire format and group addressing.
 *
 * Positions are sent as fixed-point integers rather than doubles: five decimal places is about
 * a metre, which is far below GPS error, and it roughly halves the size of the hottest message
 * in the app.
 */
object Wire {

    private const val COORD_SCALE = 1e5

    // Crockford-style alphabet: no I, L, O, U, so a code read aloud at a petrol station
    // over engine noise cannot be misheard into a different group.
    private const val CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val CODE_LENGTH = 6

    fun newJoinCode(): String {
        val rnd = SecureRandom()
        return (1..CODE_LENGTH)
            .map { CODE_ALPHABET[rnd.nextInt(CODE_ALPHABET.length)] }
            .joinToString("")
    }

    fun normaliseCode(raw: String): String = raw.trim().uppercase()
        .replace('I', '1').replace('L', '1').replace('O', '0').replace('U', 'V')
        .filter { it in CODE_ALPHABET }

    /**
     * The relay topic for a group.
     *
     * The join code is hashed rather than used directly so the topic cannot be recognised or
     * guessed from a code glimpsed on someone's screen. This is obscurity, not authentication:
     * anyone who learns the code can watch the group, which is why codes are per-ride and the
     * app stops publishing the moment the ride ends.
     */
    fun topicFor(joinCode: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("cruze-group-v1:${normaliseCode(joinCode)}".toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "cruze${hex.take(24)}"
    }

    fun newRiderId(): String {
        val rnd = SecureRandom()
        return (1..12).map { CODE_ALPHABET[rnd.nextInt(CODE_ALPHABET.length)] }.joinToString("")
    }

    // --- encoding --------------------------------------------------------------------------

    fun encode(event: RideEvent): String = when (event) {
        is RideEvent.Position -> JSONObject().apply {
            val p = event.ping
            put("t", "p")
            put("id", p.riderId)
            put("n", p.name)
            put("la", (p.pos.lat * COORD_SCALE).toInt())
            put("lo", (p.pos.lon * COORD_SCALE).toInt())
            put("b", p.bearing.toInt())
            put("s", (p.speedMps * 10).toInt())
            put("bat", p.batteryPct)
            put("ts", p.atMs)
            put("r", p.role.name.first().toString())
        }

        is RideEvent.Route -> JSONObject().apply {
            put("t", "r")
            put("id", event.riderId)
            put("n", event.name)
            put("sh", event.encodedShape)
        }

        is RideEvent.Alert -> JSONObject().apply {
            put("t", "a")
            put("id", event.riderId)
            put("n", event.name)
            put("k", event.kind.name)
            event.pos?.let {
                put("la", (it.lat * COORD_SCALE).toInt())
                put("lo", (it.lon * COORD_SCALE).toInt())
            }
            put("ts", event.atMs)
        }

        is RideEvent.Preset -> JSONObject().apply {
            put("t", "m")
            put("id", event.riderId)
            put("n", event.name)
            put("msg", event.message)
            put("ts", event.atMs)
        }

        is RideEvent.Left -> JSONObject().apply {
            put("t", "x")
            put("id", event.riderId)
        }
    }.toString()

    /** Returns null for anything unrecognised — a future version's messages must not crash us. */
    fun decode(json: String): RideEvent? = runCatching {
        val o = JSONObject(json)
        val id = o.optString("id")
        val name = o.optString("n")
        fun pos(): LatLon? =
            if (o.has("la") && o.has("lo")) {
                LatLon(o.getInt("la") / COORD_SCALE, o.getInt("lo") / COORD_SCALE)
            } else null

        when (o.optString("t")) {
            "p" -> RideEvent.Position(
                RiderPing(
                    riderId = id,
                    name = name,
                    pos = pos() ?: return@runCatching null,
                    bearing = o.optInt("b").toFloat(),
                    speedMps = o.optInt("s") / 10f,
                    batteryPct = o.optInt("bat", -1),
                    atMs = o.optLong("ts"),
                    role = when (o.optString("r")) {
                        "L" -> RiderRole.LEADER
                        "S" -> RiderRole.SWEEP
                        else -> RiderRole.RIDER
                    },
                )
            )

            "r" -> RideEvent.Route(id, o.optString("sh"), name)

            "a" -> RideEvent.Alert(
                riderId = id,
                name = name,
                kind = runCatching { AlertKind.valueOf(o.optString("k")) }.getOrNull()
                    ?: return@runCatching null,
                pos = pos(),
                atMs = o.optLong("ts"),
            )

            "m" -> RideEvent.Preset(id, name, o.optString("msg"), o.optLong("ts"))
            "x" -> RideEvent.Left(id)
            else -> null
        }
    }.getOrNull()
}
