package com.curv3.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.curv3.Fix
import com.curv3.LatLon
import com.curv3.MainActivity
import com.curv3.R
import com.curv3.RideState
import com.curv3.distanceM
import com.curv3.fmtDist
import com.curv3.fmtTurnDist
import com.curv3.nav.Cue
import com.curv3.nav.CuePlanner
import com.curv3.nav.NavEngine
import com.curv3.nav.Speaker
import com.curv3.route.RoutePlan
import com.curv3.route.Valhalla
import com.curv3.route.Waypoint
import com.curv3.trackDistanceM
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns GPS for the whole app. Navigation guidance and ride recording both run here so that
 * pocketing the phone mid-ride does not stop either of them.
 */
class RideService : Service(), LocationListener {

    companion object {
        const val ACTION_START_NAV = "com.curv3.START_NAV"
        const val ACTION_STOP_NAV = "com.curv3.STOP_NAV"
        const val ACTION_START_RECORD = "com.curv3.START_RECORD"
        const val ACTION_STOP_RECORD = "com.curv3.STOP_RECORD"
        const val ACTION_STOP_ALL = "com.curv3.STOP_ALL"
        private const val CHANNEL = "ride"
        private const val NOTIF_ID = 1

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, RideService::class.java).setAction(action)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var lm: LocationManager
    private var speaker: Speaker? = null
    private var engine: NavEngine? = null
    private val cues = CuePlanner()
    private var rerouting = false
    private var lastRerouteAt = 0L
    private var notifText = "Waiting for GPS…"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        when (intent?.action) {
            ACTION_START_NAV -> {
                val plan = RideState.plan.value
                if (plan == null || plan.shape.size < 2) {
                    stopEverything(); return START_NOT_STICKY
                }
                engine = NavEngine(plan)
                cues.reset()
                speaker = speaker ?: Speaker(this)
                RideState.setNavigating(true)
                speaker?.say("Starting navigation. ${fmtDist(plan.lengthM)}.")
            }
            ACTION_STOP_NAV -> {
                engine = null
                RideState.setNavigating(false)
                RideState.setProgress(null)
            }
            ACTION_START_RECORD -> {
                RideState.clearTrack()
                RideState.setRecording(true)
            }
            ACTION_STOP_RECORD -> RideState.setRecording(false)
            ACTION_STOP_ALL -> { stopEverything(); return START_NOT_STICKY }
        }

        if (!RideState.navigating.value && !RideState.recording.value) {
            stopEverything(); return START_NOT_STICKY
        }

        startLocation()
        refreshNotification()
        return START_STICKY
    }

    private fun startLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            RideState.setStatus("Location permission is required.")
            stopEverything()
            return
        }
        // Already listening — requesting again would just reset the provider.
        runCatching { lm.removeUpdates(this) }
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                RideState.setStatus("Location is turned off on this device.")
                return
            }
        }
        runCatching { lm.requestLocationUpdates(provider, 1000L, 0f, this, Looper.getMainLooper()) }
            .onFailure { RideState.setStatus("Could not start GPS: ${it.message}") }
    }

    override fun onLocationChanged(loc: Location) {
        val fix = Fix(
            pos = LatLon(loc.latitude, loc.longitude),
            bearing = loc.bearing,
            speedMps = loc.speed,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy else 0f,
            altitudeM = if (loc.hasAltitude()) loc.altitude else 0.0,
            timeMs = System.currentTimeMillis(),
        )
        RideState.setFix(fix)

        if (RideState.recording.value) recordPoint(fix)
        if (RideState.navigating.value) guide(fix)

        refreshNotification()
    }

    /** Drops noise so a parked bike does not accumulate a track of jitter. */
    private fun recordPoint(fix: Fix) {
        val last = RideState.track.value.lastOrNull()
        val moved = last == null || distanceM(last.pos, fix.pos) >= 5.0
        val accurate = fix.accuracyM == 0f || fix.accuracyM < 50f
        if (moved && accurate) RideState.appendTrack(fix)
    }

    private fun guide(fix: Fix) {
        val eng = engine ?: return
        val progress = eng.update(fix.pos)
        RideState.setProgress(progress)

        if (progress.arrived) {
            speaker?.say("You have arrived.")
            stopNavOnly()
            return
        }

        if (progress.offRoute) {
            reroute(fix)
            notifText = "Off route — recalculating"
            return
        }

        val nextIdx = progress.maneuverIdx + 1
        val next = eng.maneuverAt(nextIdx)
        if (next != null) {
            notifText = "${fmtTurnDist(progress.distToManeuverM)} · ${next.instruction}"
            when (cues.next(nextIdx, progress.distToManeuverM, fix.speedMps.toDouble())) {
                Cue.ALERT -> speaker?.say(next.verbalAlert.ifBlank { next.verbalPre })
                Cue.PRE -> speaker?.say(next.verbalPre)
                else -> {}
            }
        } else {
            notifText = "${fmtDist(progress.remainingM)} to destination"
        }
    }

    private fun reroute(fix: Fix) {
        val now = System.currentTimeMillis()
        if (rerouting || now - lastRerouteAt < 15_000) return
        val plan = RideState.plan.value ?: return
        rerouting = true
        lastRerouteAt = now
        speaker?.say("Recalculating.")

        scope.launch {
            // Re-plan from here to whatever destinations are still ahead of us.
            val remaining = remainingWaypoints(plan, fix)
            val result = runCatching {
                Valhalla.plan(listOf(Waypoint(fix.pos, "Current position")) + remaining, plan.style)
            }
            result.onSuccess { fresh ->
                RideState.setPlan(fresh)
                engine = NavEngine(fresh)
                cues.reset()
            }.onFailure {
                RideState.setStatus("Reroute failed: ${it.message}")
            }
            rerouting = false
        }
    }

    /**
     * Waypoints still ahead. Anything already passed is dropped, otherwise a reroute would
     * send the rider back to a point they have been through.
     */
    private fun remainingWaypoints(plan: RoutePlan, fix: Fix): List<Waypoint> {
        val progress = RideState.progress.value
        val passedIdx = progress?.shapeIdx ?: 0
        val ahead = plan.waypoints.drop(1).filter { w ->
            val nearest = plan.shape.indices.minByOrNull { distanceM(plan.shape[it], w.pos) } ?: 0
            nearest > passedIdx
        }
        return ahead.ifEmpty { listOf(plan.waypoints.last()) }
    }

    private fun stopNavOnly() {
        engine = null
        RideState.setNavigating(false)
        RideState.setProgress(null)
        if (!RideState.recording.value) stopEverything() else refreshNotification()
    }

    private fun stopEverything() {
        runCatching { lm.removeUpdates(this) }
        RideState.setNavigating(false)
        RideState.setRecording(false)
        RideState.setProgress(null)
        speaker?.release()
        speaker = null
        engine = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { lm.removeUpdates(this) }
        speaker?.release()
        scope.cancel()
        super.onDestroy()
    }

    // --- notification ----------------------------------------------------------------------

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Ride", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            description = "Navigation and ride recording"
        }
        (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RideService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = when {
            RideState.navigating.value && RideState.recording.value -> "Navigating · recording"
            RideState.navigating.value -> "Navigating"
            else -> "Recording ride · ${fmtDist(trackDistanceM(RideState.track.value))}"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle(title)
            .setContentText(notifText)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun refreshNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        (getSystemService(NotificationManager::class.java)).notify(NOTIF_ID, buildNotification())
    }

    @Deprecated("Required by LocationListener on older API levels")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        RideState.setStatus("Location was turned off.")
    }
}
