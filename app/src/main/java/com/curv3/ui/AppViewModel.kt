package com.curv3.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.curv3.Fix
import com.curv3.LatLon
import com.curv3.RideState
import com.curv3.data.Gpx
import com.curv3.data.SavedRoute
import com.curv3.data.SavedTrack
import com.curv3.data.Store
import com.curv3.route.Nominatim
import com.curv3.route.Place
import com.curv3.route.RoutePlan
import com.curv3.route.RouteStyle
import com.curv3.route.Valhalla
import com.curv3.route.Waypoint
import com.curv3.service.RideService
import com.curv3.metresToMiles
import com.curv3.trackDistanceM
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
    var style by mutableStateOf(RouteStyle.CURVY)
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

    var routes by mutableStateOf<List<SavedRoute>>(emptyList())
        private set
    var tracks by mutableStateOf<List<SavedTrack>>(emptyList())
        private set

    private var searchJob: Job? = null

    init { refreshLibrary() }

    // --- planning --------------------------------------------------------------------------

    fun addWaypoint(p: LatLon, name: String = "") {
        waypoints = waypoints + Waypoint(p, name)
        plan = null
        if (name.isBlank()) nameLast(p)
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
            runCatching { Valhalla.plan(waypoints, style) }
                .onSuccess {
                    plan = it
                    RideState.setPlan(it)
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

    fun stopRecordingAndSave(ctx: Context) {
        val points = RideState.track.value
        RideService.send(ctx, RideService.ACTION_STOP_RECORD)
        if (points.size < 2) {
            message = "Ride was too short to save."
            return
        }
        saveTrack(points)
    }

    // --- library ---------------------------------------------------------------------------

    fun refreshLibrary() {
        routes = runCatching { store.listRoutes() }.getOrDefault(emptyList())
        tracks = runCatching { store.listTracks() }.getOrDefault(emptyList())
    }

    fun saveCurrentRoute() {
        val p = plan ?: run { message = "Plan a route first."; return }
        val name = p.waypoints.let { w ->
            val from = w.first().name.substringBefore(",").ifBlank { "Start" }
            val to = w.last().name.substringBefore(",").ifBlank { "Finish" }
            "$from → $to"
        }
        store.saveRoute(SavedRoute(UUID.randomUUID().toString(), name, System.currentTimeMillis(), p))
        refreshLibrary()
        message = "Saved “$name”."
    }

    private fun saveTrack(points: List<Fix>) {
        val name = "Ride ${stamp(points.first().timeMs)} · ${
            "%.1f mi".format(Locale.getDefault(), metresToMiles(trackDistanceM(points)))
        }"
        store.saveTrack(SavedTrack(UUID.randomUUID().toString(), name, points.first().timeMs, points))
        refreshLibrary()
        message = "Ride saved."
    }

    fun openRoute(r: SavedRoute) {
        plan = r.plan
        waypoints = r.plan.waypoints
        style = r.plan.style
        RideState.setPlan(r.plan)
    }

    fun deleteRoute(r: SavedRoute) { store.deleteRoute(r.id); refreshLibrary() }
    fun deleteTrack(t: SavedTrack) { store.deleteTrack(t.id); refreshLibrary() }

    // --- GPX -------------------------------------------------------------------------------

    fun exportRoute(ctx: Context, r: SavedRoute, target: Uri) =
        write(ctx, target, Gpx.writeRoute(r.plan, r.name), "Route exported.")

    fun exportCurrentPlan(ctx: Context, target: Uri) {
        val p = plan ?: return
        write(ctx, target, Gpx.writeRoute(p, "Curv3 route"), "Route exported.")
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
        fun stamp(ms: Long): String =
            SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(ms))
    }
}
