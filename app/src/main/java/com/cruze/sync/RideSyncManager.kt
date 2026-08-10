package com.cruze.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A rider is dropped from the roster after this long without a ping. */
const val ROSTER_STALE_MS = 150_000L

/** How many offline breadcrumbs to hold before thinning the oldest away. */
const val TRAIL_BUFFER = 600

/** How often the roster is aged and the unsent queue retried, independent of incoming traffic. */
const val HOUSEKEEPING_MS = 15_000L

/**
 * Routes group traffic over whichever transport is alive, newest-preferred, and holds on to
 * anything that could not be sent yet.
 *
 * Transports are tried in the order given, so a peer-to-peer link can be listed ahead of the
 * cloud relay for a canyon or a petrol station where there is no signal but the group is
 * physically together.
 */
class RideSyncManager(
    private val scope: CoroutineScope,
    private val transports: List<RideTransport>,
) : RideSyncRepository {

    private val _events = MutableSharedFlow<RideEvent>(extraBufferCapacity = 256)
    override val events = _events.asSharedFlow()

    private val _status = MutableStateFlow(TransportStatus(TransportKind.OFFLINE, connected = false))
    override val status = _status.asStateFlow()

    private val _roster = MutableStateFlow<List<RiderPing>>(emptyList())
    override val roster = _roster.asStateFlow()

    private val lock = Mutex()

    /** Alerts and messages must survive a dead zone; they are never thinned away. */
    private val criticalQueue = ArrayDeque<RideEvent>()

    /** Position breadcrumbs, thinned oldest-first once the buffer fills. */
    private val trailQueue = ArrayDeque<RideEvent.Position>()

    private var meId: String? = null
    private var jobs = mutableListOf<Job>()

    override suspend fun join(joinCode: String, me: RiderPing) {
        leave()
        meId = me.riderId
        val topic = Wire.topicFor(joinCode)

        transports.forEach { t ->
            jobs += scope.launch {
                t.events.collect { event ->
                    // Our own traffic comes back off the relay; ignore it rather than
                    // double-counting ourselves in the roster.
                    if (event.riderId() == meId) return@collect
                    ingest(event)
                    _events.emit(event)
                }
            }
            jobs += scope.launch {
                t.status.collect { s ->
                    recomputeStatus()
                    if (s.connected) flush()
                }
            }
            t.connect(topic)
        }

        // A heartbeat, because two things used to happen only as a side effect of traffic
        // arriving: the roster aged out stale riders solely inside ingest(), so a rider who
        // rode out of signal stayed pinned to their last position forever if nobody else was
        // moving; and the queue was drained only on a transport *transition*, so an alert or
        // route chunk rejected by a rate limit sat unsent until the connection happened to
        // flap. Neither can be left to chance on a ride.
        jobs += scope.launch {
            while (true) {
                delay(HOUSEKEEPING_MS)
                ageRoster()
                if (_status.value.connected) flush()
            }
        }
        recomputeStatus()
    }

    /** Drops riders nobody has heard from in a while, whether or not anything else arrived. */
    private fun ageRoster() {
        val now = System.currentTimeMillis()
        val fresh = _roster.value.filter { now - it.atMs < ROSTER_STALE_MS }
        if (fresh.size != _roster.value.size) _roster.value = fresh
    }

    override suspend fun leave() {
        meId?.let { id -> runCatching { sendNow(RideEvent.Left(id)) } }
        jobs.forEach { it.cancel() }
        jobs.clear()
        transports.forEach { runCatching { it.disconnect() } }
        lock.withLock {
            criticalQueue.clear()
            trailQueue.clear()
        }
        _roster.value = emptyList()
        meId = null
        _status.value = TransportStatus(TransportKind.OFFLINE, connected = false)
    }

    override suspend fun publish(event: RideEvent) {
        if (sendNow(event)) return
        // Nothing carried it. Hold it for when signal returns.
        lock.withLock {
            if (event is RideEvent.Position) {
                trailQueue.addLast(event)
                thinTrailLocked()
            } else {
                criticalQueue.addLast(event)
            }
        }
        recomputeStatus()
    }

    private suspend fun sendNow(event: RideEvent): Boolean {
        for (t in transports) {
            if (t.send(event)) return true
        }
        return false
    }

    /**
     * Drops every other breadcrumb once the buffer is full rather than discarding the oldest
     * outright — the group cares about the shape of where a lost rider went, not its resolution.
     */
    private fun thinTrailLocked() {
        if (trailQueue.size <= TRAIL_BUFFER) return
        val kept = trailQueue.filterIndexed { i, _ -> i % 2 == 0 }
        trailQueue.clear()
        trailQueue.addAll(kept)
    }

    /** Pushes everything buffered, alerts first. */
    private suspend fun flush() {
        val critical: List<RideEvent>
        val trail: List<RideEvent.Position>
        lock.withLock {
            critical = criticalQueue.toList()
            trail = trailQueue.toList()
            criticalQueue.clear()
            trailQueue.clear()
        }
        val unsentCritical = critical.filterNot { sendNow(it) }
        val unsentTrail = trail.filterNot { sendNow(it) }
        if (unsentCritical.isNotEmpty() || unsentTrail.isNotEmpty()) {
            lock.withLock {
                criticalQueue.addAll(unsentCritical)
                trailQueue.addAll(unsentTrail)
                thinTrailLocked()
            }
        }
        recomputeStatus()
    }

    private fun ingest(event: RideEvent) {
        when (event) {
            is RideEvent.Position -> {
                val now = System.currentTimeMillis()
                val known = _roster.value.firstOrNull { it.riderId == event.ping.riderId }
                // A rider coming out of a dead zone flushes their whole backlog at once, so
                // pings arrive out of order. Taking whichever landed last dragged their marker
                // backwards along the road and could age them straight off the roster.
                if (known != null && known.atMs > event.ping.atMs) return
                val others = _roster.value.filterNot { it.riderId == event.ping.riderId }
                _roster.value = (others + event.ping)
                    .filter { now - it.atMs < ROSTER_STALE_MS }
                    .sortedBy { it.name }
            }
            is RideEvent.Left -> {
                _roster.value = _roster.value.filterNot { it.riderId == event.riderId }
            }
            else -> Unit
        }
    }

    private fun recomputeStatus() {
        val live = transports.firstOrNull { it.status.value.connected }
        val queued = criticalQueue.size + trailQueue.size
        _status.value = if (live != null) {
            TransportStatus(live.kind, connected = true, queued = queued)
        } else {
            TransportStatus(TransportKind.OFFLINE, connected = false, queued = queued)
        }
    }
}

fun RideEvent.riderId(): String = when (this) {
    is RideEvent.Position -> ping.riderId
    is RideEvent.RouteChunk -> riderId
    is RideEvent.Alert -> riderId
    is RideEvent.Preset -> riderId
    is RideEvent.Left -> riderId
    is RideEvent.Handover -> riderId
    is RideEvent.RideEnded -> riderId
}
