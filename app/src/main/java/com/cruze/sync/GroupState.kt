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
        internal set

    private val _session = MutableStateFlow<GroupSession?>(null)
    val session: StateFlow<GroupSession?> = _session.asStateFlow()

    /**
     * Joining is not instant: a code only means something if a leader is actually out there
     * running that ride. Until one is heard from, the join is pending — otherwise a typo puts
     * a rider alone in an empty group that looks exactly like a working one.
     */
    enum class JoinState { IDLE, SEARCHING, ACTIVE, NOT_FOUND }

    private val _joinState = MutableStateFlow(JoinState.IDLE)
    val joinState: StateFlow<JoinState> = _joinState.asStateFlow()

    /** How long to listen for a leader before deciding the ride does not exist. */
    private const val JOIN_TIMEOUT_MS = 20_000L

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

    /** Set when the leader ends the ride, so followers can be told why it stopped. */
    private val _endedBy = MutableStateFlow<String?>(null)
    val endedBy: StateFlow<String?> = _endedBy.asStateFlow()

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

    /**
     * Set by the service so that dismissing a false alarm also stands the detector down.
     * Without it "I'M OK" cleared only the deadline, the detector stayed CONFIRMED, and the
     * very next accelerometer sample — about 200 ms later — started the countdown again.
     */
    var onFallDismissed: (() -> Unit)? = null

    fun cancelFallCountdown() {
        _fallDeadline.value = null
        onFallDismissed?.invoke()
    }

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
                            _sharedRouteFrom.value = done.second
                            // Null first: a StateFlow swallows a value equal to the one it
                            // already holds, so re-pushing the identical route — exactly what a
                            // leader does when someone joins late — reached nobody.
                            _sharedRoute.value = null
                            _sharedRoute.value = done.first
                            _routeProgress.value = null
                        } else {
                            _routeProgress.value = assembler.progress()
                        }
                    }
                    is RideEvent.Handover -> {
                        val s = _session.value
                        if (s != null && event.newLeaderId == s.riderId) {
                            // We have been handed the ride.
                            _session.value = s.copy(role = RiderRole.LEADER)
                            lastPublished = null   // announce the new role immediately
                            lastPublishAt = 0L
                        }
                    }

                    is RideEvent.RideEnded -> {
                        // No leader, no ride. Drop out rather than leaving riders following
                        // a group that no longer exists.
                        _endedBy.value = event.name
                        scope.launch { stop() }
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

    /**
     * A join link scanned from a leader's QR, waiting for the group screen to act on it.
     * Held here rather than passed through the activity because the scan can arrive while the
     * app is already open on any tab.
     */
    private val _pendingJoin = MutableStateFlow<Wire.JoinLink?>(null)
    val pendingJoin: StateFlow<Wire.JoinLink?> = _pendingJoin.asStateFlow()

    fun offerJoinLink(link: Wire.JoinLink) { _pendingJoin.value = link }

    fun consumeJoinLink() { _pendingJoin.value = null }

    suspend fun start(joinCode: String, name: String, role: RiderRole) {
        val s = GroupSession(Wire.normaliseCode(joinCode), Wire.newRiderId(), name, role)
        _session.value = s
        _joinState.value = if (role == RiderRole.LEADER) JoinState.ACTIVE else JoinState.SEARCHING
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

        if (role != RiderRole.LEADER) waitForLeader()
    }

    /** Gives up on a join when no leader is heard from, so a wrong code fails loudly. */
    private fun waitForLeader() = scope.launch {
        val deadline = System.currentTimeMillis() + JOIN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (_session.value == null) return@launch
            if (roster.value.any { it.role == RiderRole.LEADER }) {
                _joinState.value = JoinState.ACTIVE
                return@launch
            }
            kotlinx.coroutines.delay(500)
        }
        if (_joinState.value == JoinState.SEARCHING) {
            // stop() tears the session down; the state survives it so the UI can say why.
            stop()
            _joinState.value = JoinState.NOT_FOUND
        }
    }

    suspend fun stop() {
        repository.leave()
        _session.value = null
        _trails.value = emptyMap()
        assembler.clear()
        _routeProgress.value = null
        // Without this the foreground service keeps GPS, the accelerometer and its notification
        // running for the rest of the day: start() turns it on and nothing else turned it off.
        onSessionChanged?.invoke(false)
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

    fun consumeEnded() { _endedBy.value = null }

    /** Hands the ride to another rider and steps back to being an ordinary rider. */
    fun handOverTo(newLeaderId: String) {
        val s = _session.value ?: return
        if (s.role != RiderRole.LEADER) return
        _session.value = s.copy(role = RiderRole.RIDER)
        lastPublished = null
        lastPublishAt = 0L
        scope.launch { repository.publish(RideEvent.Handover(s.riderId, newLeaderId, s.name)) }
    }

    /** Ends the ride for the whole group. Only the leader can do this. */
    fun endRide() {
        val s = _session.value ?: return
        scope.launch {
            repository.publish(RideEvent.RideEnded(s.riderId, s.name))
            stop()
        }
    }

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
    "Police ahead",
)
