package com.cruze.sync

import com.cruze.LatLon
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything one rider tells the group. Deliberately small — this goes out every few seconds
 * over cellular, so each field has to earn its bytes.
 */
data class RiderPing(
    val riderId: String,
    val name: String,
    val pos: LatLon,
    val bearing: Float,
    val speedMps: Float,
    val batteryPct: Int,
    val atMs: Long,
    val role: RiderRole = RiderRole.RIDER,
)

enum class RiderRole { LEADER, SWEEP, RIDER }

enum class AlertKind {
    /** Sensor fusion thinks this rider went down. */
    RIDER_DOWN,

    /** Rider pressed SOS themselves. */
    SOS,

    /** Rider has fallen behind the group's threshold. */
    LOST_RIDER,

    /** Low fuel range. */
    LOW_FUEL,
}

sealed interface RideEvent {
    data class Position(val ping: RiderPing) : RideEvent

    /**
     * One slice of the leader's route. A route cannot fit in a single relay message, so it is
     * sent as [count] numbered chunks identified by [routeId], which is a hash of the whole
     * payload and doubles as the integrity check.
     */
    data class RouteChunk(
        val riderId: String,
        val routeId: String,
        val index: Int,
        val count: Int,
        val payload: String,
        val name: String,
    ) : RideEvent

    data class Alert(
        val riderId: String,
        val name: String,
        val kind: AlertKind,
        val pos: LatLon?,
        val atMs: Long,
    ) : RideEvent

    /** One of the preset one-tap messages. Never free text — nobody types while riding. */
    data class Preset(val riderId: String, val name: String, val message: String, val atMs: Long) : RideEvent

    data class Left(val riderId: String) : RideEvent

    /** The leader passing the role to someone else. */
    data class Handover(val riderId: String, val newLeaderId: String, val name: String) : RideEvent

    /** The leader ending the ride. Without a leader there is no ride, so everyone drops out. */
    data class RideEnded(val riderId: String, val name: String) : RideEvent
}

/** Which pipe the group is actually talking over right now. */
enum class TransportKind(val label: String) {
    CLOUD("Cloud"),
    PEER("Peer"),
    OFFLINE("Offline"),
}

data class TransportStatus(
    val kind: TransportKind,
    val connected: Boolean,
    /** Pings held back because nothing could carry them yet. */
    val queued: Int = 0,
    val detail: String = "",
)

/**
 * One transport for group traffic — a cloud relay, a peer-to-peer mesh, whatever comes next.
 *
 * Implementations must be safe to start and stop repeatedly, and must never throw out of
 * [send]: a transport that is down is an expected state on a bike, not an error.
 */
interface RideTransport {
    val kind: TransportKind

    /** Current, not just eventual — the router has to pick a live transport synchronously. */
    val status: StateFlow<TransportStatus>
    val events: Flow<RideEvent>

    suspend fun connect(groupTopic: String)
    suspend fun disconnect()

    /** Returns true when the event actually went out. False means "buffer it and retry". */
    suspend fun send(event: RideEvent): Boolean
}

/**
 * The single interface the UI talks to. It must never learn which transport is live — that is
 * the whole point of the abstraction, and it is what lets peer-to-peer take over in a dead zone
 * without a single screen knowing.
 */
interface RideSyncRepository {
    val events: Flow<RideEvent>
    val status: StateFlow<TransportStatus>
    val roster: StateFlow<List<RiderPing>>

    suspend fun join(joinCode: String, me: RiderPing)
    suspend fun leave()

    suspend fun publish(event: RideEvent)
}
