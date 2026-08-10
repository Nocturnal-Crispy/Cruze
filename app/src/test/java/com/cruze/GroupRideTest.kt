package com.cruze

import com.cruze.safety.FallDetector
import com.cruze.safety.FallPhase
import com.cruze.safety.SensorSample
import com.cruze.safety.accelMagnitude
import com.cruze.safety.tiltFromGravity
import com.cruze.sync.AlertKind
import com.cruze.sync.RideEvent
import com.cruze.sync.RiderPing
import com.cruze.sync.RiderRole
import com.cruze.sync.Wire
import com.cruze.sync.gapsToLeader
import com.cruze.sync.groupSpreadM
import com.cruze.sync.lowestRange
import com.cruze.sync.updateIntervalMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupRideTest {

    // --- wire format -----------------------------------------------------------------------

    private val ping = RiderPing(
        riderId = "R1", name = "Mark",
        pos = LatLon(41.40213, -80.39044),
        bearing = 137f, speedMps = 24.4f, batteryPct = 63,
        atMs = 1_700_000_000_000L, role = RiderRole.LEADER,
    )

    @Test
    fun `positions survive a round trip within GPS accuracy`() {
        val decoded = Wire.decode(Wire.encode(RideEvent.Position(ping))) as RideEvent.Position
        val p = decoded.ping
        assertEquals(ping.riderId, p.riderId)
        assertEquals(ping.name, p.name)
        assertEquals(RiderRole.LEADER, p.role)
        assertEquals(ping.batteryPct, p.batteryPct)
        assertEquals(ping.atMs, p.atMs)
        // Fixed-point coordinates must stay well inside GPS error.
        assertTrue(distanceM(ping.pos, p.pos) < 1.5)
        assertEquals(ping.speedMps, p.speedMps, 0.1f)
    }

    @Test
    fun `a position message stays small enough to send every second`() {
        val bytes = Wire.encode(RideEvent.Position(ping)).toByteArray().size
        assertTrue("position payload was $bytes bytes", bytes < 200)
    }

    @Test
    fun `alerts and presets round trip`() {
        val alert = RideEvent.Alert("R2", "Sam", AlertKind.RIDER_DOWN, LatLon(41.5, -80.2), 123L)
        val back = Wire.decode(Wire.encode(alert)) as RideEvent.Alert
        assertEquals(AlertKind.RIDER_DOWN, back.kind)
        assertEquals("Sam", back.name)
        assertNotNull(back.pos)

        val preset = RideEvent.Preset("R3", "Jo", "fuel stop", 9L)
        assertEquals(preset, Wire.decode(Wire.encode(preset)))
    }

    @Test
    fun `unknown or malformed messages are ignored rather than fatal`() {
        assertNull(Wire.decode("not json"))
        assertNull(Wire.decode("""{"t":"zzz"}"""))
        assertNull(Wire.decode("""{"t":"p","id":"x"}"""))   // no coordinates
        assertNull(Wire.decode("""{"t":"a","id":"x","k":"NOPE"}"""))
    }

    // --- group addressing ------------------------------------------------------------------

    @Test
    fun `every rider id maps to a real palette colour`() {
        // A hash with the high bit set used to truncate to a negative index and crash.
        repeat(5000) {
            val id = Wire.newRiderId()
            require(riderColorIndex(id) in 0 until 8) { "bad index for $id" }
        }
        listOf("SIMLEADER001", "SIMSWEEP0002", "SIMRIDER0003", "", "\uFFFF").forEach {
            require(riderColorIndex(it) in 0 until 8) { "bad index for '$it'" }
        }
    }

    /** Mirrors the index maths in riderColor, which cannot be called without Compose. */
    private fun riderColorIndex(riderId: String, palette: Int = 8) =
        ((riderId.hashCode().toLong() and 0xFFFFFFFFL) % palette).toInt()

    @Test
    fun `join codes avoid characters that get misheard`() {
        repeat(200) {
            val code = Wire.newJoinCode()
            assertEquals(Wire.CODE_LENGTH, code.length)
            assertTrue("ambiguous char in $code", code.none { it in "ILOU" })
        }
    }

    @Test
    fun `codes normalise the way someone would mistype them`() {
        assertEquals(Wire.normaliseCode("ab1o"), Wire.normaliseCode("AB1O"))
        // O reads as zero, I and L as one, U as V.
        assertEquals("AB10", Wire.normaliseCode("ABIO"))
        assertEquals("AB10", Wire.normaliseCode("ab-lo"))
    }

    @Test
    fun `the relay topic hides the join code and is stable`() {
        val code = "K7M2QX"
        val topic = Wire.topicFor(code)
        assertEquals(topic, Wire.topicFor("k7m2qx"))
        assertTrue(topic.startsWith("cruze"))
        assertTrue("topic leaks the code", !topic.contains(code, ignoreCase = true))
        assertTrue(topic != Wire.topicFor("K7M2QY"))
    }

    // --- group maths -----------------------------------------------------------------------

    private fun at(lat: Double, lon: Double, id: String, role: RiderRole = RiderRole.RIDER) =
        RiderPing(id, id, LatLon(lat, lon), 0f, 20f, 80, 0L, role)

    @Test
    fun `spread is the front to back length of the group`() {
        assertEquals(0.0, groupSpreadM(emptyList()), 0.0)
        assertEquals(0.0, groupSpreadM(listOf(LatLon(41.0, -80.0))), 0.0)
        // 0.01 degrees of latitude is ~1112 m.
        val spread = groupSpreadM(
            listOf(LatLon(41.0, -80.0), LatLon(41.005, -80.0), LatLon(41.01, -80.0))
        )
        assertEquals(1112.0, spread, 25.0)
    }

    @Test
    fun `riders past the threshold are flagged lost, furthest first`() {
        val roster = listOf(
            at(41.0, -80.0, "leader", RiderRole.LEADER),
            at(41.002, -80.0, "close"),
            at(41.03, -80.0, "miles-back"),
        )
        val gaps = gapsToLeader(roster, "leader", thresholdM = 1600.0)
        assertEquals(2, gaps.size)
        assertEquals("miles-back", gaps.first().ping.riderId)
        assertTrue(gaps.first().lost)
        assertTrue(!gaps.last().lost)
    }

    @Test
    fun `the leader is found by role when no id is given`() {
        val roster = listOf(at(41.0, -80.0, "a"), at(41.03, -80.0, "b", RiderRole.LEADER))
        val gaps = gapsToLeader(roster, leaderId = null)
        assertEquals(1, gaps.size)
        assertEquals("a", gaps.first().ping.riderId)
    }

    @Test
    fun `update rate backs off when it safely can and speeds up when it matters`() {
        // Parked: almost silent.
        assertEquals(30_000L, updateIntervalMs(50.0, false, null, stationary = true))
        // Approaching a junction while navigating: fastest we dare push the free relay.
        assertEquals(3_000L, updateIntervalMs(100.0, true, 200.0, stationary = false))
        // Strung out across a mile.
        assertEquals(5_000L, updateIntervalMs(1500.0, false, null, stationary = false))
        // Bunched and cruising: cheapest.
        assertEquals(12_000L, updateIntervalMs(80.0, false, null, stationary = false))
        // Nothing may ever be fast enough to drain the relay's bucket.
        listOf(true, false).forEach { nav ->
            listOf(0.0, 500.0, 5000.0).forEach { spread ->
                assertTrue(updateIntervalMs(spread, nav, 10.0, false) >= 3_000L)
            }
        }
        // Rate must never get faster as the group bunches up.
        val spreads = listOf(50.0, 200.0, 400.0, 900.0)
        val rates = spreads.map { updateIntervalMs(it, false, null, false) }
        assertEquals(rates.sortedDescending(), rates)
    }

    @Test
    fun `fuel range warns for the smallest tank and keeps a reserve`() {
        val worst = lowestRange(
            tanks = mapOf("big" to 6.0, "small" to 2.6),
            milesSinceFill = mapOf("big" to 60.0, "small" to 60.0),
            mpg = 45.0,
        )
        assertNotNull(worst)
        assertEquals("small", worst!!.riderName)
        // 2.6 gal * 0.85 usable * 45 mpg = 99.5 mi, less 60 ridden = ~39.5 left.
        assertEquals(39.5, worst.milesRemaining, 1.0)
        assertTrue(!worst.low)

        val nearlyDry = lowestRange(
            tanks = mapOf("small" to 2.6),
            milesSinceFill = mapOf("small" to 80.0),
            mpg = 45.0,
        )
        assertTrue(nearlyDry!!.low)
    }

    // --- rider-down detection ---------------------------------------------------------------

    /** Replays a scripted incident one sample at a time, as the real detector would see it. */
    private fun replay(samples: List<SensorSample>, d: FallDetector = FallDetector()): FallPhase {
        var phase = FallPhase.IDLE
        samples.forEach { phase = d.update(it).phase }
        return phase
    }

    private fun cruising(startMs: Long, count: Int, speed: Float = 25f, stepMs: Long = 1000) =
        (0 until count).map { SensorSample(startMs + it * stepMs, 9.8f, 5f, speed) }

    private fun stopped(startMs: Long, count: Int, tilt: Float, stepMs: Long = 1000) =
        (0 until count).map { SensorSample(startMs + it * stepMs, 9.8f, tilt, 0f) }

    @Test
    fun `a genuine crash is confirmed - impact then stillness then phone lying over`() {
        val samples = buildList {
            addAll(cruising(0, 20))
            add(SensorSample(20_000, 55f, 40f, 22f))   // impact
            addAll(stopped(21_000, 40, tilt = 80f))    // down and not moving for 40 s
        }
        assertEquals(FallPhase.CONFIRMED, replay(samples))
    }

    /**
     * A tank-top cradle holds the phone nearly flat, well past the old absolute 60° threshold.
     * Those riders were one hard stop away from a permanent false alarm, because the phone
     * already "looked fallen" sitting exactly where it always sits.
     */
    @Test
    fun `a nearly-flat tank mount is not a crash just because it is flat`() {
        val samples = buildList {
            // Twenty seconds of normal riding with the phone lying at 70° in its cradle.
            addAll((0 until 20).map { SensorSample(it * 1000L, 9.8f, 70f, 25f) })
            add(SensorSample(20_000, 55f, 70f, 22f))   // hard stop, phone never moves
            // Long enough to outlast the suspicion window: the tilt never agrees, because the
            // phone is exactly where it always is, so the suspicion has to expire on its own.
            addAll(stopped(21_000, 50, tilt = 70f))
        }
        assertEquals(FallPhase.IDLE, replay(samples))
    }

    /** The same mount, genuinely down: what matters is the change from where it normally sits. */
    @Test
    fun `a crash on a flat tank mount is still caught`() {
        val samples = buildList {
            addAll((0 until 20).map { SensorSample(it * 1000L, 9.8f, 70f, 25f) })
            add(SensorSample(20_000, 55f, 70f, 22f))
            addAll(stopped(21_000, 40, tilt = 110f))   // laid right over
        }
        assertEquals(FallPhase.CONFIRMED, replay(samples))
    }

    @Test
    fun `a pothole is not a crash - the rider keeps going`() {
        val samples = buildList {
            addAll(cruising(0, 20))
            add(SensorSample(20_000, 48f, 12f, 24f))   // sharp jolt
            addAll(cruising(21_000, 40, speed = 24f))  // still riding
        }
        assertEquals(FallPhase.IDLE, replay(samples))
    }

    @Test
    fun `a normal stop at a red light is not a crash - the phone stays upright`() {
        val samples = buildList {
            addAll(cruising(0, 20))
            add(SensorSample(20_000, 12f, 6f, 3f))     // braking
            addAll(stopped(21_000, 60, tilt = 8f))     // waiting, mounted upright
        }
        assertEquals(FallPhase.IDLE, replay(samples))
    }

    @Test
    fun `dropping a parked phone is not a crash`() {
        val samples = buildList {
            addAll(stopped(0, 10, tilt = 5f))
            add(SensorSample(10_000, 60f, 85f, 0f))    // dropped on the floor
            addAll(stopped(11_000, 60, tilt = 85f))
        }
        // Never moving above riding speed beforehand means this cannot be a crash.
        assertEquals(FallPhase.IDLE, replay(samples))
    }

    @Test
    fun `hard deceleration without an impact spike still counts once the bike lies still`() {
        val samples = buildList {
            addAll(cruising(0, 15, speed = 28f))
            add(SensorSample(15_000, 14f, 30f, 2f))    // 26 m/s lost in a second
            addAll(stopped(16_000, 40, tilt = 75f))
        }
        assertEquals(FallPhase.CONFIRMED, replay(samples))
    }

    @Test
    fun `a suspected fall clears itself if the rider rides on`() {
        val d = FallDetector()
        replay(cruising(0, 10) + SensorSample(10_000, 55f, 40f, 24f), d)
        assertEquals(FallPhase.SUSPECTED, d.current.phase)
        // Back up to speed: no alert.
        assertEquals(FallPhase.IDLE, replay(cruising(11_000, 5, speed = 22f), d))
    }

    @Test
    fun `a confirmed fall clears when the rider gets going again`() {
        val d = FallDetector()
        val crash = cruising(0, 20) +
            SensorSample(20_000, 55f, 40f, 22f) +
            stopped(21_000, 40, tilt = 80f)
        assertEquals(FallPhase.CONFIRMED, replay(crash, d))
        assertEquals(FallPhase.IDLE, replay(cruising(70_000, 3, speed = 20f), d))
    }

    @Test
    fun `sensor helpers convert raw readings correctly`() {
        assertEquals(9.81f, accelMagnitude(0f, 9.81f, 0f), 0.01f)
        assertEquals(5f, accelMagnitude(3f, 4f, 0f), 0.01f)
        // Gravity along y: phone upright in a mount, so near zero tilt.
        assertTrue(tiltFromGravity(0f, 9.81f, 0f) < 5f)
        // Gravity along z: phone flat on the ground.
        assertTrue(tiltFromGravity(0f, 0f, 9.81f) > 85f)
    }
}
