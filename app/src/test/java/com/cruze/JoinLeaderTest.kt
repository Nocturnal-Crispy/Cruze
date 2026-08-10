package com.cruze

import com.cruze.sync.GroupState
import com.cruze.sync.RideEvent
import com.cruze.sync.RideSyncRepository
import com.cruze.sync.RiderPing
import com.cruze.sync.RiderRole
import com.cruze.sync.TransportKind
import com.cruze.sync.TransportStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A ride only exists while a leader is running it, so joining one that has no leader must fail
 * rather than dropping the rider into an empty group that looks exactly like a working one.
 *
 * This is a rule that broke silently once — a stray state reset skipped the wait entirely and
 * the screen went straight to the roster — so it is pinned down here.
 */
class JoinLeaderTest {

    private class FakeRepo : RideSyncRepository {
        override val events = MutableSharedFlow<RideEvent>()
        override val status = MutableStateFlow(TransportStatus(TransportKind.CLOUD, true))
        override val roster = MutableStateFlow<List<RiderPing>>(emptyList())
        var joined: String? = null
        override suspend fun join(joinCode: String, me: RiderPing) { joined = joinCode }
        override suspend fun leave() { joined = null; roster.value = emptyList() }
        override suspend fun publish(event: RideEvent) = Unit
    }

    private val repo = FakeRepo()
    private val real = GroupState.repository

    private fun ping(role: RiderRole) = RiderPing(
        riderId = "LEADER1", name = "Dana", pos = LatLon(41.4, -80.4),
        bearing = 0f, speedMps = 20f, batteryPct = 90,
        atMs = System.currentTimeMillis(), role = role,
    )

    /** Waits for a join state, so the test never depends on how fast the poll loop ticks. */
    private suspend fun await(want: GroupState.JoinState, ms: Long) =
        withTimeout(ms) { GroupState.joinState.first { it == want } }

    @After
    fun restore() = runBlocking {
        GroupState.stop()
        GroupState.repository = real
    }

    @Test
    fun `joining stays pending until a leader is heard from`() = runBlocking {
        GroupState.repository = repo
        GroupState.start("MERCER", "Mike", RiderRole.RIDER)

        // Nothing on the relay yet: the rider must not be in the ride.
        assertEquals(GroupState.JoinState.SEARCHING, GroupState.joinState.value)

        repo.roster.value = listOf(ping(RiderRole.LEADER))
        await(GroupState.JoinState.ACTIVE, 5_000)
        assertNotNull("the session should survive a successful join", GroupState.session.value)
    }

    @Test
    fun `a code with no leader behind it fails instead of faking a ride`() = runBlocking {
        GroupState.repository = repo
        GroupState.start("NOB0DY", "Mike", RiderRole.RIDER)

        // Riders alone on a topic are not a ride — only a LEADER claim makes one.
        repo.roster.value = listOf(ping(RiderRole.RIDER))

        await(GroupState.JoinState.NOT_FOUND, 30_000)
        assertNull("a failed join must not leave a session behind", GroupState.session.value)
    }

    @Test
    fun `starting a ride as leader is active immediately`() = runBlocking {
        GroupState.repository = repo
        GroupState.start("LEADR1", "Mike", RiderRole.LEADER)
        assertEquals(GroupState.JoinState.ACTIVE, GroupState.joinState.value)
    }
}
