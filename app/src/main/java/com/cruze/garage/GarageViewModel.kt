package com.cruze.garage

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.util.UUID

class GarageViewModel(app: Application) : AndroidViewModel(app) {

    private val store = GarageStore(app)

    var garage by mutableStateOf(GarageStore.Garage())
        private set
    var message by mutableStateOf<String?>(null)

    init { garage = store.load() }

    private fun commit(g: GarageStore.Garage) {
        garage = g
        store.save(g)
    }

    val bike: Bike? get() = garage.activeBike

    fun selectBike(id: String) = commit(garage.copy(activeBikeId = id))

    fun addBike(name: String, make: String, model: String, year: Int, odometerMi: Double) {
        val b = Bike(UUID.randomUUID().toString(), name, make, model, year, odometerMi)
        // A new bike arrives with the usual schedule already set up, dated from today.
        val schedule = store.defaultSchedule(b.id, odometerMi, System.currentTimeMillis())
        commit(
            garage.copy(
                bikes = garage.bikes + b,
                service = garage.service + schedule,
                activeBikeId = b.id,
            )
        )
        message = "Added ${b.title}."
    }

    fun deleteBike(id: String) = commit(
        garage.copy(
            bikes = garage.bikes.filterNot { it.id == id },
            service = garage.service.filterNot { it.bikeId == id },
            fuel = garage.fuel.filterNot { it.bikeId == id },
            activeBikeId = garage.activeBikeId.takeIf { it != id },
        )
    )

    fun setOdometer(miles: Double) {
        val b = bike ?: return
        commit(garage.copy(bikes = garage.bikes.map { if (it.id == b.id) it.copy(odometerMi = miles) else it }))
    }

    /** Rolls a recorded ride's distance onto the active bike's odometer. */
    fun addRideDistance(metres: Double) {
        val b = bike ?: return
        commit(garage.copy(bikes = garage.bikes.map { if (it.id == b.id) it.plusRide(metres) else it }))
    }

    fun statuses(): List<ServiceStatus> {
        val b = bike ?: return emptyList()
        val now = System.currentTimeMillis()
        return garage.serviceFor(b.id)
            .map { statusOf(it, b, now) }
            // Most urgent first — that is the only order that matters on this screen.
            .sortedWith(compareByDescending<ServiceStatus> { it.state.ordinal }
                .thenBy { it.milesRemaining ?: Double.MAX_VALUE })
    }

    fun markDone(item: ServiceItem) {
        val b = bike ?: return
        commit(
            garage.copy(
                service = garage.service.map {
                    if (it.id == item.id) {
                        it.copy(lastDoneMi = b.odometerMi, lastDoneAt = System.currentTimeMillis())
                    } else it
                }
            )
        )
        message = "${item.name} marked done."
    }

    fun addService(name: String, intervalMi: Double, intervalDays: Int) {
        val b = bike ?: run { message = "Add a bike first."; return }
        commit(
            garage.copy(
                service = garage.service + ServiceItem(
                    UUID.randomUUID().toString(), b.id, name, intervalMi, intervalDays,
                    b.odometerMi, System.currentTimeMillis(),
                )
            )
        )
    }

    fun deleteService(id: String) = commit(garage.copy(service = garage.service.filterNot { it.id == id }))

    fun addFuel(odometerMi: Double, gallons: Double, cost: Double, full: Boolean) {
        val b = bike ?: run { message = "Add a bike first."; return }
        commit(
            garage.copy(
                fuel = garage.fuel + FuelEntry(
                    UUID.randomUUID().toString(), b.id, System.currentTimeMillis(),
                    odometerMi, gallons, cost, full,
                ),
                // A fill-up is a fresh odometer reading; trust it if it moved forward.
                bikes = garage.bikes.map {
                    if (it.id == b.id && odometerMi > it.odometerMi) it.copy(odometerMi = odometerMi) else it
                },
            )
        )
    }

    fun deleteFuel(id: String) = commit(garage.copy(fuel = garage.fuel.filterNot { it.id == id }))

    fun fuelEntries(): List<FuelEntry> = bike?.let { garage.fuelFor(it.id) } ?: emptyList()

    fun backupJson(): String = store.exportJson()

    fun restoreFrom(text: String) {
        runCatching { garage = store.importJson(text) }
            .onSuccess { message = "Garage restored — ${garage.bikes.size} bike(s)." }
            .onFailure { message = it.message ?: "Restore failed." }
    }

    /** CSV of the fuel log, for the riders who keep spreadsheets. */
    fun fuelCsv(): String = buildString {
        appendLine("date,odometer_mi,gallons,cost,full,mpg")
        val entries = fuelEntries().sortedBy { it.odometerMi }
        val mpg = economyMpg(entries).toMap()
        entries.forEach { e ->
            appendLine(
                listOf(
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        .format(java.util.Date(e.atMs)),
                    // Locale.ROOT throughout: a comma-decimal phone wrote "12,5" into a
                    // comma-separated file, which every spreadsheet then read as two columns.
                    "%.1f".format(java.util.Locale.ROOT, e.odometerMi),
                    "%.3f".format(java.util.Locale.ROOT, e.gallons),
                    "%.2f".format(java.util.Locale.ROOT, e.cost),
                    e.full,
                    mpg[e]?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "",
                ).joinToString(",")
            )
        }
    }
}
