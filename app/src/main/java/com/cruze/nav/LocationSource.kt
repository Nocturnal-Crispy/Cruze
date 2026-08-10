package com.cruze.nav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.cruze.Fix
import com.cruze.LatLon
import com.cruze.RideState
import com.cruze.sync.GroupState

/**
 * Foreground location for the map screen.
 *
 * RideService owns GPS during a ride, but it only runs while navigating or recording — without
 * this the map would have no idea where the rider is when simply opening the app to plan
 * something. Defers to the service whenever that is running so GPS is never requested twice.
 */
class LocationSource(context: Context) : LocationListener {

    private val appCtx = context.applicationContext
    private val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var active = false

    private fun hasPermission() = ContextCompat.checkSelfPermission(
        appCtx, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * A group ride runs the service too, and it was missing from this check — so for the whole
     * of every group ride the app held a second set of location updates the service was already
     * receiving, at 2 s intervals, for nothing.
     */
    private fun serviceOwnsGps() =
        RideState.navigating.value || RideState.recording.value || GroupState.active

    @SuppressLint("MissingPermission")
    fun start() {
        if (active || !hasPermission()) return
        if (serviceOwnsGps()) return

        // Seed immediately from the last known fix so the map can centre without waiting the
        // 10-30 s a cold GPS lock can take.
        seedFromLastKnown()

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            RideState.setStatus("Location is turned off on this device.")
            return
        }
        providers.forEach { p ->
            runCatching { lm.requestLocationUpdates(p, 2000L, 5f, this, Looper.getMainLooper()) }
        }
        active = true
    }

    @SuppressLint("MissingPermission")
    private fun seedFromLastKnown() {
        if (RideState.fix.value != null) return
        val best = lm.allProviders
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        best?.let { RideState.setFix(it.toFix()) }
    }

    fun stop() {
        if (!active) return
        runCatching { lm.removeUpdates(this) }
        active = false
    }

    override fun onLocationChanged(loc: Location) {
        // The service is authoritative once a ride starts.
        if (serviceOwnsGps()) {
            stop()
            return
        }
        RideState.setFix(loc.toFix())
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    @Deprecated("Required by LocationListener on older API levels")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
}

fun Location.toFix() = Fix(
    pos = LatLon(latitude, longitude),
    bearing = bearing,
    speedMps = speed,
    accuracyM = if (hasAccuracy()) accuracy else 0f,
    altitudeM = if (hasAltitude()) altitude else 0.0,
    timeMs = System.currentTimeMillis(),
)
