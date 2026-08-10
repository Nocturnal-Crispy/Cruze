package com.cruze.service

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
import com.cruze.Fix
import com.cruze.LatLon
import com.cruze.MainActivity
import com.cruze.R
import com.cruze.RideState
import com.cruze.distanceM
import com.cruze.fmtDist
import com.cruze.fmtTurnDist
import com.cruze.nav.Cue
import com.cruze.nav.CuePlanner
import com.cruze.nav.NavEngine
import com.cruze.nav.Speaker
import com.cruze.safety.FallDetector
import com.cruze.safety.FallPhase
import com.cruze.safety.SensorSample
import com.cruze.safety.accelMagnitude
import com.cruze.safety.tiltFromGravity
import com.cruze.sync.GroupState
import com.cruze.route.RoutePlan
import com.cruze.route.Valhalla
import com.cruze.route.Waypoint
import com.cruze.trackDistanceM
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns GPS for the whole app. Navigation guidance and ride recording both run here so that
 * pocketing the phone mid-ride does not stop either of them.
 */
class RideService : Service(), LocationListener, android.hardware.SensorEventListener {

    companion object {
        const val ACTION_START_NAV = "com.cruze.START_NAV"
        const val ACTION_STOP_NAV = "com.cruze.STOP_NAV"
        const val ACTION_START_RECORD = "com.cruze.START_RECORD"
        const val ACTION_STOP_RECORD = "com.cruze.STOP_RECORD"
        const val ACTION_STOP_ALL = "com.cruze.STOP_ALL"

        /**
         * A group ride needs this service even with no navigation or recording running: it is
         * what broadcasts our position and speaks what the group says. Without it a rider joins
         * a group and silently never appears on anyone else's map.
         */
        const val ACTION_START_GROUP = "com.cruze.START_GROUP"
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

    private val fallDetector = FallDetector()
    private var sensors: android.hardware.SensorManager? = null
    private var lastTiltDeg = 0f
    private var lastSpokenMessageAt = 0L
    private var lastSpokenAlertAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Everything the group says is spoken. A rider must never have to read at speed.
        scope.launch {
            GroupState.messages.collect { list ->
                val latest = list.lastOrNull() ?: return@collect
                if (latest.atMs > lastSpokenMessageAt) {
                    lastSpokenMessageAt = latest.atMs
                    speaker?.say("${latest.name} says ${latest.message}")
                }
            }
        }
        scope.launch {
            GroupState.alerts.collect { list ->
                val latest = list.lastOrNull() ?: return@collect
                if (latest.atMs > lastSpokenAlertAt) {
                    lastSpokenAlertAt = latest.atMs
                    val what = latest.kind.name.replace('_', ' ').lowercase()
                    speaker?.say("Alert. ${latest.name}: $what.")
                }
            }
        }
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensors = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
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
            ACTION_START_GROUP -> speaker = speaker ?: Speaker(this)
            ACTION_STOP_ALL -> { stopEverything(); return START_NOT_STICKY }
        }

        if (!RideState.navigating.value && !RideState.recording.value && !GroupState.active) {
            stopEverything(); return START_NOT_STICKY
        }

        startLocation()
        startSensors()
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

    /** Accelerometer only while a ride is actually running — it is not free. */
    private fun startSensors() {
        val sm = sensors ?: return
        sm.unregisterListener(this)
        sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onSensorChanged(event: android.hardware.SensorEvent) {
        if (event.sensor.type != android.hardware.Sensor.TYPE_ACCELEROMETER) return
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        lastTiltDeg = tiltFromGravity(x, y, z)
        val sample = SensorSample(
            atMs = System.currentTimeMillis(),
            accelMag = accelMagnitude(x, y, z),
            tiltDeg = lastTiltDeg,
            speedMps = RideState.fix.value?.speedMps ?: 0f,
        )
        if (com.cruze.Settings.fallDetection &&
            fallDetector.update(sample).phase == FallPhase.CONFIRMED && GroupState.active
        ) {
            GroupState.beginFallCountdown(com.cruze.Settings.fallCountdownSec)
        }
        // The countdown has to run even with no GPS updates coming in.
        if (GroupState.fireFallIfElapsed()) {
            speaker?.say("Alerting your group.")
        }
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}

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

        if (GroupState.active) {
            GroupState.publishPositionIfDue(
                context = this,
                navigating = RideState.navigating.value,
                distToManeuverM = RideState.progress.value?.distToManeuverM,
            )
        }

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

        val nextIdx = progress.nextManeuverIdx
        val next = eng.maneuverAt(nextIdx)
        if (next != null) {
            val followUp = eng.maneuverAt(progress.followUpManeuverIdx)
            val thenPart = followUp?.let { ", then ${it.instruction.trimEnd('.')}" }.orEmpty()
            notifText = "${fmtTurnDist(progress.distToManeuverM)} · ${next.instruction}$thenPart"
            val cue = cues.next(
                nextIdx, progress.distToManeuverM, fix.speedMps.toDouble(),
                progress.followUpManeuverIdx,
            )
            when (cue) {
                Cue.ALERT -> speaker?.say(next.verbalAlert.ifBlank { next.verbalPre } + thenPart)
                Cue.PRE -> speaker?.say(next.verbalPre + thenPart)
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
        if (!RideState.recording.value && !GroupState.active) stopEverything() else refreshNotification()
    }

    private fun stopEverything() {
        runCatching { lm.removeUpdates(this) }
        runCatching { sensors?.unregisterListener(this) }
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
        runCatching { sensors?.unregisterListener(this) }
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
            RideState.recording.value -> "Recording ride · ${fmtDist(trackDistanceM(RideState.track.value))}"
            else -> "Group ride"
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
