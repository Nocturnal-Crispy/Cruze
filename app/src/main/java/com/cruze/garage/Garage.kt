package com.cruze.garage

import com.cruze.metresToMiles

/**
 * The rider's bikes and their upkeep. Everything here is local to the device — no account,
 * no sync, no server.
 */
data class Bike(
    val id: String,
    val name: String,
    val make: String = "",
    val model: String = "",
    val year: Int = 0,
    /** Odometer in miles. Grows automatically as rides are recorded. */
    val odometerMi: Double = 0.0,
    val active: Boolean = true,
) {
    val title: String get() = name.ifBlank { listOf(year.takeIf { it > 0 }?.toString(), make, model).filterNotNull().joinToString(" ").trim() }
}

/**
 * A recurring service task. Either interval may be zero, meaning "don't track that dimension" —
 * a chain needs mileage, an oil change realistically needs whichever comes first.
 */
data class ServiceItem(
    val id: String,
    val bikeId: String,
    val name: String,
    val intervalMi: Double = 0.0,
    val intervalDays: Int = 0,
    val lastDoneMi: Double = 0.0,
    val lastDoneAt: Long = 0L,
    val notes: String = "",
)

data class FuelEntry(
    val id: String,
    val bikeId: String,
    val atMs: Long,
    val odometerMi: Double,
    val gallons: Double,
    val cost: Double = 0.0,
    /** Only tank-to-tank full fills give a trustworthy economy figure. */
    val full: Boolean = true,
    val station: String = "",
)

enum class DueState { OK, SOON, DUE, OVERDUE }

data class ServiceStatus(
    val item: ServiceItem,
    val milesRemaining: Double?,
    val daysRemaining: Int?,
    val state: DueState,
) {
    /** Short human summary, whichever dimension is closest to falling due. */
    val summary: String
        get() {
            val parts = buildList {
                milesRemaining?.let {
                    add(if (it < 0) "${(-it).toInt()} mi overdue" else "in ${it.toInt()} mi")
                }
                daysRemaining?.let {
                    add(if (it < 0) "${-it} days overdue" else "in $it days")
                }
            }
            return if (parts.isEmpty()) "No interval set" else parts.joinToString(" · ")
        }
}

/** Warn this far ahead so a service can be booked before it is actually due. */
private const val SOON_MILES = 300.0
private const val SOON_DAYS = 14

fun statusOf(item: ServiceItem, bike: Bike, nowMs: Long): ServiceStatus {
    val milesRemaining = if (item.intervalMi > 0) {
        item.lastDoneMi + item.intervalMi - bike.odometerMi
    } else null

    val daysRemaining = if (item.intervalDays > 0 && item.lastDoneAt > 0) {
        val elapsedDays = ((nowMs - item.lastDoneAt) / 86_400_000L).toInt()
        item.intervalDays - elapsedDays
    } else null

    // Whichever dimension is worst decides the state — that's what "or sooner" means.
    val state = listOfNotNull(
        milesRemaining?.let {
            when {
                it < 0 -> DueState.OVERDUE
                it <= SOON_MILES -> DueState.SOON
                else -> DueState.OK
            }
        },
        daysRemaining?.let {
            when {
                it < 0 -> DueState.OVERDUE
                it <= SOON_DAYS -> DueState.SOON
                else -> DueState.OK
            }
        },
    ).maxByOrNull { it.ordinal } ?: DueState.OK

    return ServiceStatus(item, milesRemaining, daysRemaining, state)
}

/**
 * Miles per gallon between consecutive full fill-ups.
 *
 * Only full-to-full pairs are usable: a partial fill tells you nothing about how much fuel was
 * actually burned since the last one, so those entries are skipped rather than guessed at.
 */
fun economyMpg(entries: List<FuelEntry>): List<Pair<FuelEntry, Double>> {
    val full = entries.filter { it.full }.sortedBy { it.odometerMi }
    return full.zipWithNext().mapNotNull { (prev, next) ->
        val miles = next.odometerMi - prev.odometerMi
        if (miles <= 0 || next.gallons <= 0) null else next to (miles / next.gallons)
    }
}

fun averageMpg(entries: List<FuelEntry>): Double? {
    val pairs = economyMpg(entries)
    return if (pairs.isEmpty()) null else pairs.sumOf { it.second } / pairs.size
}

fun totalFuelSpend(entries: List<FuelEntry>): Double = entries.sumOf { it.cost }

/** Cost per mile across the tracked fill-ups, or null when there isn't enough data. */
fun costPerMile(entries: List<FuelEntry>): Double? {
    val sorted = entries.sortedBy { it.odometerMi }
    if (sorted.size < 2) return null
    val miles = sorted.last().odometerMi - sorted.first().odometerMi
    // The first fill paid for fuel used before tracking started, so it is excluded.
    val spend = sorted.drop(1).sumOf { it.cost }
    return if (miles > 0 && spend > 0) spend / miles else null
}

/** Adds a recorded ride's distance to the bike's odometer. */
fun Bike.plusRide(metres: Double) = copy(odometerMi = odometerMi + metresToMiles(metres))
