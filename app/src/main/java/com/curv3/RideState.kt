package com.curv3

import com.curv3.nav.NavProgress
import com.curv3.route.RoutePlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Fix(
    val pos: LatLon,
    val bearing: Float,
    val speedMps: Float,
    val accuracyM: Float,
    val altitudeM: Double,
    val timeMs: Long,
)

/**
 * Shared live ride state. The foreground service writes it, the UI reads it, so navigation
 * and recording survive the activity going away.
 *
 * ponytail: a singleton, not a DI graph. There is exactly one ride in progress, ever.
 */
object RideState {
    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix.asStateFlow()

    private val _plan = MutableStateFlow<RoutePlan?>(null)
    val plan: StateFlow<RoutePlan?> = _plan.asStateFlow()

    private val _progress = MutableStateFlow<NavProgress?>(null)
    val progress: StateFlow<NavProgress?> = _progress.asStateFlow()

    private val _navigating = MutableStateFlow(false)
    val navigating: StateFlow<Boolean> = _navigating.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _track = MutableStateFlow<List<Fix>>(emptyList())
    val track: StateFlow<List<Fix>> = _track.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    fun setFix(f: Fix) { _fix.value = f }
    fun setPlan(p: RoutePlan?) { _plan.value = p }
    fun setProgress(p: NavProgress?) { _progress.value = p }
    fun setNavigating(v: Boolean) { _navigating.value = v }
    fun setStatus(s: String) { _status.value = s }

    fun setRecording(v: Boolean) { _recording.value = v }
    fun appendTrack(f: Fix) { _track.value = _track.value + f }
    fun clearTrack() { _track.value = emptyList() }
}

/** Distance covered by a recorded track, in metres. */
fun trackDistanceM(points: List<Fix>): Double = pathLengthM(points.map { it.pos })

/** Moving time in seconds, ignoring stops (speed under ~1 m/s). */
fun trackMovingS(points: List<Fix>): Double {
    var s = 0.0
    for (i in 1 until points.size) {
        val dt = (points[i].timeMs - points[i - 1].timeMs) / 1000.0
        if (dt in 0.0..30.0 && points[i].speedMps > 1.0) s += dt
    }
    return s
}
