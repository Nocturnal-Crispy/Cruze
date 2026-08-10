package com.cruze.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cruze.Fix
import com.cruze.LatLon
import com.cruze.RideState
import com.cruze.data.Gpx
import com.cruze.data.SavedTrack
import com.cruze.data.Store
import com.cruze.route.Nominatim
import com.cruze.route.Place
import com.cruze.route.RoutePlan
import com.cruze.route.RouteStyle
import com.cruze.route.Valhalla
import com.cruze.route.Waypoint
import com.cruze.service.RideService
import com.cruze.weather.Weather
import com.cruze.metresToMiles
import com.cruze.trackDistanceM
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)

    var waypoints by mutableStateOf<List<Waypoint>>(emptyList())
        private set
    var style by mutableStateOf(
        runCatching { RouteStyle.valueOf(com.cruze.Settings.defaultStyle) }.getOrDefault(RouteStyle.CURVY)
    )
        private set
    var roundTrip by mutableStateOf(false)
        private set
    var mapLayer by mutableStateOf(
        runCatching { MapLayer.valueOf(com.cruze.Settings.defaultLayer) }.getOrDefault(MapLayer.DARK)
    )
        private set

    var radarOn by mutableStateOf(false)
        private set
    var radarFrame by mutableStateOf<String?>(null)
        private set
    var alerts by mutableStateOf<List<Weather.Alert>>(emptyList())
        private set
    var plan by mutableStateOf<RoutePlan?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var searchResults by mutableStateOf<List<Place>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set

    var tracks by mutableStateOf<List<SavedTrack>>(emptyList())
        private set

    private var searchJob: Job? = null

    init {
        refreshLibrary()
        // A route pushed by the leader becomes this rider's route, verbatim.
        viewModelScope.launch {
            com.cruze.sync.GroupState.sharedRoute.collect { shared ->
                if (shared != null) {
                    plan = shared
                    waypoints = shared.waypoints
                    style = shared.style
                    RideState.setPlan(shared)
                    message = "Route received from ${com.cruze.sync.GroupState.sharedRouteFrom.value}."
                }
            }
        }
    }

    // --- planning --------------------------------------------------------------------------

    fun addWaypoint(p: LatLon, name: String = "") {
        waypoints = waypoints + Waypoint(p, name)
        plan = null
        if (name.isBlank()) nameLast(p)
    }

    /**
     * Adds a place the rider picked. A route almost always starts from where they are standing,
     * so the current position becomes the start automatically rather than making them add it.
     */
    fun addDestination(p: LatLon, name: String = "") {
        if (waypoints.isEmpty()) {
            RideState.fix.value?.let { waypoints = listOf(Waypoint(it.pos, MY_LOCATION)) }
        }
        addWaypoint(p, name)
    }

    /** True when the first waypoint is the rider's live position rather than a chosen place. */
    val startsFromMyLocation: Boolean
        get() = waypoints.firstOrNull()?.name == MY_LOCATION

    fun useMyLocationAsStart() {
        val here = RideState.fix.value?.pos ?: run {
            message = "No GPS fix yet — waiting for a position."
            return
        }
        waypoints = if (startsFromMyLocation) {
            listOf(Waypoint(here, MY_LOCATION)) + waypoints.drop(1)
        } else {
            listOf(Waypoint(here, MY_LOCATION)) + waypoints
        }
        plan = null
    }

    fun toggleRoundTrip() {
        roundTrip = !roundTrip
        plan = null
        if (waypoints.size >= 2) route()
    }

    fun setLayer(l: MapLayer) { mapLayer = l }

    /** Toggles the rain radar, refetching frame paths since RainViewer rotates them often. */
    fun toggleRadar() {
        radarOn = !radarOn
        if (!radarOn) {
            radarFrame = null
            return
        }
        viewModelScope.launch {
            runCatching { Weather.radarFrames() }
                .onSuccess { frames ->
                    // The newest frame at or before now is the current picture of the sky.
                    val now = System.currentTimeMillis()
                    radarFrame = (frames.lastOrNull { it.timeMs <= now } ?: frames.lastOrNull())?.path
                    if (radarFrame == null) message = "No radar data available right now."
                }
                .onFailure {
                    radarOn = false
                    message = "Could not load rain radar."
                }
        }
    }

    /** Weather warnings along the planned route. Silent on failure — it is advisory only. */
    private fun refreshAlerts(shape: List<LatLon>) = viewModelScope.launch {
        alerts = runCatching { Weather.alertsAlong(shape) }.getOrDefault(emptyList())
    }

    /** Labels a map-tapped point in the background; a failed lookup is not worth an error. */
    private fun nameLast(p: LatLon) = viewModelScope.launch {
        val label = runCatching { Nominatim.reverse(p) }.getOrNull() ?: return@launch
        val short = label.split(",").take(2).joinToString(",").trim()
        waypoints = waypoints.map { if (it.pos == p && it.name.isBlank()) it.copy(name = short) else it }
    }

    fun removeWaypoint(index: Int) {
        waypoints = waypoints.filterIndexed { i, _ -> i != index }
        plan = null
    }

    fun moveWaypoint(from: Int, to: Int) {
        if (from !in waypoints.indices || to !in waypoints.indices) return
        val list = waypoints.toMutableList()
        list.add(to, list.removeAt(from))
        waypoints = list
        plan = null
    }

    fun clearPlan() {
        waypoints = emptyList()
        plan = null
        searchResults = emptyList()
        alerts = emptyList()
    }

    fun chooseStyle(s: RouteStyle) {
        if (s == style) return
        style = s
        if (waypoints.size >= 2) route() else plan = null
    }

    fun route() {
        if (waypoints.size < 2) {
            message = "Add a start and a destination first."
            return
        }
        busy = true
        viewModelScope.launch {
            // Re-read the live position so a route started minutes ago still begins from here.
            val wps = if (startsFromMyLocation) {
                val here = RideState.fix.value?.pos
                if (here != null) listOf(Waypoint(here, MY_LOCATION)) + waypoints.drop(1) else waypoints
            } else waypoints
            runCatching { Valhalla.plan(wps, style, roundTrip) }
                .onSuccess {
                    plan = it
                    RideState.setPlan(it)
                    refreshAlerts(it.shape)
                }
                .onFailure { message = it.message ?: "Could not plan a route." }
            busy = false
        }
    }

    fun search(query: String, near: LatLon?) {
        searchJob?.cancel()
        if (query.length < 3) {
            searchResults = emptyList()
            return
        }
        searching = true
        searchJob = viewModelScope.launch {
            runCatching { Nominatim.search(query, near) }
                .onSuccess { searchResults = it }
                .onFailure { message = it.message ?: "Search failed." }
            searching = false
        }
    }

    fun clearSearch() { searchResults = emptyList() }

    // --- ride control ----------------------------------------------------------------------

    fun startNavigation(ctx: Context) {
        val p = plan ?: run { message = "Plan a route first."; return }
        RideState.setPlan(p)
        RideService.send(ctx, RideService.ACTION_START_NAV)
    }

    fun stopNavigation(ctx: Context) = RideService.send(ctx, RideService.ACTION_STOP_NAV)

    fun startRecording(ctx: Context) = RideService.send(ctx, RideService.ACTION_START_RECORD)

    fun stopRecordingAndSave(ctx: Context, onDistanceRidden: (Double) -> Unit = {}) {
        val points = RideState.track.value
        RideService.send(ctx, RideService.ACTION_STOP_RECORD)
        if (points.size < 2) {
            message = "Ride was too short to save."
            return
        }
        saveTrack(points)
        onDistanceRidden(com.cruze.trackDistanceM(points))
    }

    // --- library ---------------------------------------------------------------------------

    fun refreshLibrary() {
        tracks = runCatching { store.listTracks() }.getOrDefault(emptyList())
    }

    private fun saveTrack(points: List<Fix>) {
        val name = "Ride ${stamp(points.first().timeMs)} · ${
            "%.1f mi".format(Locale.getDefault(), metresToMiles(trackDistanceM(points)))
        }"
        store.saveTrack(SavedTrack(UUID.randomUUID().toString(), name, points.first().timeMs, points))
        refreshLibrary()
        message = "Ride saved."
    }

    fun deleteTrack(t: SavedTrack) { store.deleteTrack(t.id); refreshLibrary() }

    // --- GPX -------------------------------------------------------------------------------

    fun exportCurrentPlan(ctx: Context, target: Uri) {
        val p = plan ?: return
        write(ctx, target, Gpx.writeRoute(p, "Cruze route"), "Route exported.")
    }

    fun exportTrack(ctx: Context, t: SavedTrack, target: Uri) =
        write(ctx, target, Gpx.writeTrack(t.points, t.name), "Ride exported.")

    private fun write(ctx: Context, target: Uri, text: String, ok: String) {
        runCatching {
            ctx.contentResolver.openOutputStream(target)?.use { it.write(text.toByteArray()) }
                ?: error("Could not open the destination file.")
        }.onSuccess { message = ok }
            .onFailure { message = "Export failed: ${it.message}" }
    }

    fun importGpx(ctx: Context, source: Uri) {
        runCatching {
            ctx.contentResolver.openInputStream(source)?.use { Gpx.read(it) }
                ?: error("Could not open the file.")
        }.onSuccess { imported ->
            if (imported.waypoints.size < 2) {
                message = "That file has no usable route points."
                return@onSuccess
            }
            waypoints = imported.waypoints
            plan = null
            message = "Imported ${imported.waypoints.size} points from “${imported.name}”."
            route()
        }.onFailure { message = "Import failed: ${it.message}" }
    }

    companion object {
        /** Marks the waypoint that tracks the rider's live position. */
        const val MY_LOCATION = "My location"

        fun stamp(ms: Long): String =
            SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(ms))
    }
}
