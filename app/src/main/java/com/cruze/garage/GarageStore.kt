package com.cruze.garage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Garage persistence. One JSON file holds the whole garage — bikes, service schedules and fuel
 * entries are read and written together and number in the dozens, so splitting them across files
 * or a database would buy nothing.
 */
class GarageStore(context: Context) {

    private val file = File(context.filesDir, "garage.json")

    data class Garage(
        val bikes: List<Bike> = emptyList(),
        val service: List<ServiceItem> = emptyList(),
        val fuel: List<FuelEntry> = emptyList(),
        val activeBikeId: String? = null,
    ) {
        val activeBike: Bike?
            get() = bikes.firstOrNull { it.id == activeBikeId } ?: bikes.firstOrNull()

        fun serviceFor(bikeId: String) = service.filter { it.bikeId == bikeId }
        fun fuelFor(bikeId: String) = fuel.filter { it.bikeId == bikeId }.sortedByDescending { it.atMs }
    }

    fun load(): Garage = runCatching {
        if (!file.exists()) return@runCatching Garage()
        val o = JSONObject(file.readText())
        Garage(
            bikes = o.optJSONArray("bikes").map { b ->
                Bike(
                    id = b.getString("id"),
                    name = b.optString("name"),
                    make = b.optString("make"),
                    model = b.optString("model"),
                    year = b.optInt("year"),
                    odometerMi = b.optDouble("odometerMi", 0.0),
                    active = b.optBoolean("active", true),
                )
            },
            service = o.optJSONArray("service").map { s ->
                ServiceItem(
                    id = s.getString("id"),
                    bikeId = s.getString("bikeId"),
                    name = s.optString("name"),
                    intervalMi = s.optDouble("intervalMi", 0.0),
                    intervalDays = s.optInt("intervalDays"),
                    lastDoneMi = s.optDouble("lastDoneMi", 0.0),
                    lastDoneAt = s.optLong("lastDoneAt"),
                    notes = s.optString("notes"),
                )
            },
            fuel = o.optJSONArray("fuel").map { f ->
                FuelEntry(
                    id = f.getString("id"),
                    bikeId = f.getString("bikeId"),
                    atMs = f.optLong("atMs"),
                    odometerMi = f.optDouble("odometerMi", 0.0),
                    gallons = f.optDouble("gallons", 0.0),
                    cost = f.optDouble("cost", 0.0),
                    full = f.optBoolean("full", true),
                    station = f.optString("station"),
                )
            },
            activeBikeId = o.optString("activeBikeId").takeIf { it.isNotBlank() },
        )
    }.getOrDefault(Garage())

    fun save(g: Garage) {
        val o = JSONObject().apply {
            put("bikes", JSONArray().apply {
                g.bikes.forEach {
                    put(JSONObject().apply {
                        put("id", it.id); put("name", it.name); put("make", it.make)
                        put("model", it.model); put("year", it.year)
                        put("odometerMi", it.odometerMi); put("active", it.active)
                    })
                }
            })
            put("service", JSONArray().apply {
                g.service.forEach {
                    put(JSONObject().apply {
                        put("id", it.id); put("bikeId", it.bikeId); put("name", it.name)
                        put("intervalMi", it.intervalMi); put("intervalDays", it.intervalDays)
                        put("lastDoneMi", it.lastDoneMi); put("lastDoneAt", it.lastDoneAt)
                        put("notes", it.notes)
                    })
                }
            })
            put("fuel", JSONArray().apply {
                g.fuel.forEach {
                    put(JSONObject().apply {
                        put("id", it.id); put("bikeId", it.bikeId); put("atMs", it.atMs)
                        put("odometerMi", it.odometerMi); put("gallons", it.gallons)
                        put("cost", it.cost); put("full", it.full); put("station", it.station)
                    })
                }
            })
            g.activeBikeId?.let { put("activeBikeId", it) }
        }
        file.writeText(o.toString())
    }

    /**
     * The whole garage as portable JSON.
     *
     * Cloud backup only restores when the rider has Google backup switched on, and never
     * during a developer reinstall, so an explicit file the rider owns is the only guarantee
     * their service history survives.
     */
    fun exportJson(): String = if (file.exists()) file.readText() else "{}"

    fun importJson(text: String): Garage {
        val restored = runCatching {
            JSONObject(text)
            file.writeText(text)
            load()
        }.getOrElse { throw IllegalArgumentException("That file is not a Cruze garage backup.") }
        return restored
    }

    /** The schedule most riders actually run, so a new bike is useful immediately. */
    fun defaultSchedule(bikeId: String, odometerMi: Double, nowMs: Long) = listOf(
        service(bikeId, "Engine oil & filter", 4000.0, 365, odometerMi, nowMs),
        service(bikeId, "Chain clean & lube", 500.0, 0, odometerMi, nowMs),
        service(bikeId, "Tyres — check wear & pressure", 1000.0, 30, odometerMi, nowMs),
        service(bikeId, "Brake pads & fluid", 8000.0, 730, odometerMi, nowMs),
        service(bikeId, "Air filter", 12000.0, 0, odometerMi, nowMs),
        service(bikeId, "Coolant", 24000.0, 1095, odometerMi, nowMs),
        service(bikeId, "Valve clearance", 16000.0, 0, odometerMi, nowMs),
    )

    private fun service(
        bikeId: String, name: String, mi: Double, days: Int, odo: Double, now: Long,
    ) = ServiceItem(UUID.randomUUID().toString(), bikeId, name, mi, days, odo, now)
}

/** Maps a JSON array of objects, tolerating a missing array. */
private inline fun <T> JSONArray?.map(transform: (JSONObject) -> T): List<T> {
    val arr = this ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        arr.optJSONObject(i)?.let { runCatching { transform(it) }.getOrNull() }
    }
}
