package com.cruze.sync

import android.content.Context
import com.cruze.LatLon
import com.cruze.RideState
import com.cruze.angleDiff
import com.cruze.distanceM
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Below this, a new position tells the group nothing worth spending relay budget on. */
private const val MIN_MOVE_M = 25.0
private const val MIN_TURN_DEG = 25.0

/** A rider must be heard from this often or they age out of everyone's roster. */
private const val HEARTBEAT_MS = 45_000L

data class GroupSession(
    val joinCode: String,
    val riderId: String,
    val name: String,
    val role: RiderRole,
)

/**
 * The live group ride, shared between the UI and the foreground service.
 *
 * Like [RideState] this is a singleton because there is exactly one ride at a time, and the
 * service must be able to keep broadcasting position with the app in the background.
 */
object GroupState {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Swappable so tests and future transports can drop in without touching callers. */
    var repository: RideSyncRepository = RideSyncManager(scope, listOf(NtfyTransport(scope)))
        private set

    private val _session = MutableStateFlow<GroupSession?>(null)
    val session: StateFlow<GroupSession?> = _session.asStateFlow()

    private val _alerts = MutableStateFlow<List<RideEvent.Alert>>(emptyList())
    val alerts: StateFlow<List<RideEvent.Alert>> = _alerts.asStateFlow()

    private val _messages = MutableStateFlow<List<RideEvent.Preset>>(emptyList())
    val messages: StateFlow<List<RideEvent.Preset>> = _messages.asStateFlow()

    private val assembler = RouteTransfer.Assembler()

    /** A route pushed by the leader, once every chunk has arrived and verified. */
    private val _sharedRoute = MutableStateFlow<com.cruze.route.RoutePlan?>(null)
    val sharedRoute: StateFlow<com.cruze.route.RoutePlan?> = _sharedRoute.asStateFlow()

    private val _sharedRouteFrom = MutableStateFlow("")
    val sharedRouteFrom: StateFlow<String> = _sharedRouteFrom.asStateFlow()

    /**
     * How much of an incoming route has arrived, or null when none is in flight.
     *
     * A route takes a dozen relay messages, which is seconds on a good signal and much longer
     * on a bad one. Without this the follower just sees nothing happen and taps the button again.
     */
    private val _routeProgress = MutableStateFlow<Float?>(null)
    val routeProgress: StateFlow<Float?> = _routeProgress.asStateFlow()

    /** Breadcrumbs per rider, so a rider who dropped out still shows where they went. */
    private val _trails = MutableStateFlow<Map<String, List<LatLon>>>(emptyMap())
    val trails: StateFlow<Map<String, List<LatLon>>> = _trails.asStateFlow()

    val roster: StateFlow<List<RiderPing>> get() = repository.roster
    val status: StateFlow<TransportStatus> get() = repository.status

    val active: Boolean get() = _session.value != null

    private var lastPublishAt = 0L
    private var lastPublished: RiderPing? = null

    /**
     * When a suspected fall will be broadcast, or null if nothing is pending.
     *
     * A detector this cheap will sometimes be wrong, so it never alerts anyone silently — the
     * rider gets a countdown with one enormous button, and cancelling costs a single tap.
     */
    private val _fallDeadline = MutableStateFlow<Long?>(null)
    val fallDeadline: StateFlow<Long?> = _fallDeadline.asStateFlow()

    fun beginFallCountdown(seconds: Int = 30) {
        if (_fallDeadline.value != null) return
        _fallDeadline.value = System.currentTimeMillis() + seconds * 1000L
    }

    fun cancelFallCountdown() { _fallDeadline.value = null }

    /** Fires the alert if the countdown ran out. Returns true when an alert went out. */
    fun fireFallIfElapsed(): Boolean {
        val deadline = _fallDeadline.value ?: return false
        if (System.currentTimeMillis() < deadline) return false
        _fallDeadline.value = null
        sendAlert(AlertKind.RIDER_DOWN)
        return true
    }

    init {
        scope.launch {
            repository.events.collect { event ->
                when (event) {
                    is RideEvent.Alert -> _alerts.value = (_alerts.value + event).takeLast(20)
                    is RideEvent.Preset -> _messages.value = (_messages.value + event).takeLast(20)
                    is RideEvent.RouteChunk -> {
                        val done = assembler.accept(event)
                        if (done != null) {
                            _sharedRoute.value = done.first
                            _sharedRouteFrom.value = done.second
                            _routeProgress.value = null
                        } else {
                            _routeProgress.value = assembler.progress()
                        }
                    }
                    is RideEvent.Position -> {
                        val id = event.ping.riderId
                        val trail = (_trails.value[id].orEmpty() + event.ping.pos).takeLast(200)
                        _trails.value = _trails.value + (id to trail)
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Lets the UI start and stop the foreground service that carries a group ride. */
    var onSessionChanged: ((Boolean) -> Unit)? = null

    suspend fun start(joinCode: String, name: String, role: RiderRole) {
        val s = GroupSession(Wire.normaliseCode(joinCode), Wire.newRiderId(), name, role)
        _session.value = s
        _alerts.value = emptyList()
        _messages.value = emptyList()
        _trails.value = emptyMap()
        _sharedRoute.value = null
        _routeProgress.value = null
        assembler.clear()
        onSessionChanged?.invoke(false)
        lastPublishAt = 0L
        lastPublished = null
        repository.join(s.joinCode, me(s, batteryPct = 100))
        onSessionChanged?.invoke(true)
    }

    suspend fun stop() {
        repository.leave()
        _session.value = null
        _trails.value = emptyMap()
    }

    private fun me(s: GroupSession, batteryPct: Int): RiderPing {
        val fix = RideState.fix.value
        return RiderPing(
            riderId = s.riderId,
            name = s.name,
            pos = fix?.pos ?: LatLon(0.0, 0.0),
            bearing = fix?.bearing ?: 0f,
            speedMps = fix?.speedMps ?: 0f,
            batteryPct = batteryPct,
            atMs = System.currentTimeMillis(),
            role = s.role,
        )
    }

    /**
     * Broadcasts our position if enough time has passed. The interval adapts to how spread out
     * the group is and whether a turn is coming, so cellular data and battery go on the moments
     * that actually matter.
     */
    fun publishPositionIfDue(context: Context, navigating: Boolean, distToManeuverM: Double?) {
        val s = _session.value ?: return
        val fix = RideState.fix.value ?: return

        val spread = groupSpreadM(roster.value.map { it.pos } + fix.pos)
        val interval = updateIntervalMs(
            spreadM = spread,
            navigating = navigating,
            distanceToManeuverM = distToManeuverM,
            stationary = fix.speedMps < 1f,
        )
        val now = System.currentTimeMillis()
        if (now - lastPublishAt < interval) return

        // Even when the interval is up, a ping that says nothing new is wasted relay budget.
        // A heartbeat still goes out periodically so riders do not age out of the roster.
        val prev = lastPublished
        val heartbeatDue = now - lastPublishAt > HEARTBEAT_MS
        if (prev != null && !heartbeatDue) {
            val moved = distanceM(prev.pos, fix.pos)
            val turned = kotlin.math.abs(angleDiff(prev.bearing.toDouble(), fix.bearing.toDouble()))
            if (moved < MIN_MOVE_M && turned < MIN_TURN_DEG) return
        }

        lastPublishAt = now
        val ping = me(s, batteryPct(context))
        lastPublished = ping
        scope.launch { repository.publish(RideEvent.Position(ping)) }
    }

    fun sendAlert(kind: AlertKind) {
        val s = _session.value ?: return
        val alert = RideEvent.Alert(
            s.riderId, "${s.name} (you)", kind, RideState.fix.value?.pos, System.currentTimeMillis()
        )
        // Shown immediately: our own traffic is filtered out of the relay stream, so waiting
        // for it to come back would mean never seeing what we just said.
        _alerts.value = (_alerts.value + alert).takeLast(20)
        scope.launch { repository.publish(alert.copy(name = s.name)) }
    }

    fun sendPreset(message: String) {
        val s = _session.value ?: return
        val at = System.currentTimeMillis()
        _messages.value = (_messages.value + RideEvent.Preset(s.riderId, "${s.name} (you)", message, at))
            .takeLast(20)
        scope.launch { repository.publish(RideEvent.Preset(s.riderId, s.name, message, at)) }
    }

    /**
     * Pushes the leader's route to everyone, chunk by chunk.
     *
     * The whole plan goes, geometry and maneuvers both, so followers navigate exactly the same
     * line with exactly the same spoken instructions rather than just seeing a drawn route.
     */
    fun shareRoute(plan: com.cruze.route.RoutePlan) {
        val s = _session.value ?: return
        scope.launch {
            RouteTransfer.chunk(plan, s.riderId, s.name).forEach { repository.publish(it) }
        }
    }

    fun consumeSharedRoute() { _sharedRoute.value = null }

    fun dismissAlert(alert: RideEvent.Alert) {
        _alerts.value = _alerts.value.filterNot { it.riderId == alert.riderId && it.atMs == alert.atMs }
    }

    private fun batteryPct(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        return bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }
}

/** One-tap things worth saying at 70 mph. Never free text — nobody types while riding. */
val PRESET_MESSAGES = listOf(
    "Fuel stop",
    "Pulling over",
    "Need a break",
    "Go ahead without me",
    "Catching up",
    "Hazard ahead",
)
