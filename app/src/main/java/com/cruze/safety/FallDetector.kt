package com.cruze.safety

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One fused sample. Kept as plain data with an explicit timestamp so a whole incident can be
 * replayed from a fixture file in a unit test — a crash detector you cannot replay is a crash
 * detector you cannot trust.
 */
data class SensorSample(
    val atMs: Long,
    /** Total accelerometer magnitude including gravity, m/s². */
    val accelMag: Float,
    /** Angle of the device's up-axis away from vertical, degrees. */
    val tiltDeg: Float,
    /** Ground speed from GPS, m/s. */
    val speedMps: Float,
)

enum class FallPhase {
    /** Nothing unusual. */
    IDLE,

    /** An impact or violent deceleration was seen; watching to see if the bike stops. */
    SUSPECTED,

    /** Impact plus stillness plus a lying-over orientation. Time to warn the group. */
    CONFIRMED,
}

data class FallState(
    val phase: FallPhase,
    val since: Long = 0L,
    val peakAccel: Float = 0f,
    val reason: String = "",
)

/**
 * Detects a rider going down from accelerometer, orientation and GPS.
 *
 * The rule is deliberately conservative and needs three independent things to agree:
 *  1. a violent event — a hard impact spike, or hard braking from real speed,
 *  2. the bike then stops and stays stopped,
 *  3. the phone ends up lying over rather than upright in its mount.
 *
 * Any one alone is an everyday event: a pothole spikes the accelerometer, a red light stops
 * you, and knocking the phone tilts it. Requiring all three is what keeps this from crying wolf
 * on every ride, and the caller still shows a countdown so a false positive costs one tap.
 */
class FallDetector(
    /** Impact spike, m/s². ~4g; a pothole rarely sustains this. */
    private val impactThreshold: Float = 39f,
    /** Speed above which a sudden stop is meaningful, m/s (~13 mph). */
    private val ridingSpeed: Float = 6f,
    /** Deceleration over the impact window that counts as violent, m/s². */
    private val hardDecel: Float = 6.5f,
    /** Below this the bike counts as stopped, m/s. */
    private val stoppedSpeed: Float = 1.0f,
    /** How long the bike must stay still after the event, ms. */
    private val stillnessMs: Long = 30_000L,
    /**
     * Beyond this the phone is lying over rather than mounted upright, degrees.
     * Only used until the mount's own resting angle has been learned — see [mountTiltDeg].
     */
    private val fallenTiltDeg: Float = 60f,
    /**
     * How far the phone must tilt away from however it normally sits in its mount, degrees.
     *
     * An absolute threshold assumes every rider mounts their phone bolt upright. A tank-top
     * cradle or a reclined bar mount already reads past 60°, so those riders were one hard stop
     * away from a permanent false "fallen" reading. What actually indicates a fall is the phone
     * moving away from where it has been sitting all ride.
     */
    private val tiltChangeDeg: Float = 35f,
    /** An unconfirmed suspicion expires after this, ms. */
    private val suspicionWindowMs: Long = 45_000L,
) {

    private var state = FallState(FallPhase.IDLE)
    private var lastSample: SensorSample? = null
    private var speedBeforeEvent = 0f
    private var movedSince = 0L

    /**
     * The resting tilt of this phone in this mount, learned while actually riding.
     *
     * Every mount sits at its own angle and no two riders' are the same, so this is measured
     * rather than assumed. Null until enough riding has been seen to trust it, in which case
     * the absolute threshold stands in.
     */
    private var mountTiltDeg: Float? = null

    /** Whether the phone has moved far enough from its usual angle to look like a fall. */
    private fun looksFallen(tiltDeg: Float): Boolean {
        val baseline = mountTiltDeg ?: return tiltDeg >= fallenTiltDeg
        return tiltDeg - baseline >= tiltChangeDeg
    }

    val current: FallState get() = state

    fun reset() {
        state = FallState(FallPhase.IDLE)
        lastSample = null
        speedBeforeEvent = 0f
        movedSince = 0L
        // The baseline is deliberately kept: the phone is still in the same mount.
    }

    fun update(s: SensorSample): FallState {
        val prev = lastSample
        lastSample = s

        if (s.speedMps > stoppedSpeed) movedSince = s.atMs

        // Learn the mount angle only while genuinely riding: stationary readings include the
        // phone being taken out at a petrol stop, which is exactly the angle not to learn.
        if (s.speedMps > ridingSpeed && state.phase == FallPhase.IDLE) {
            val prev = mountTiltDeg
            // Slow average — a single corner leaning the bike over must not move it much.
            mountTiltDeg = if (prev == null) s.tiltDeg else prev * 0.98f + s.tiltDeg * 0.02f
        }

        when (state.phase) {
            FallPhase.IDLE -> {
                val impact = s.accelMag >= impactThreshold
                val decel = prev?.let {
                    val dt = (s.atMs - it.atMs) / 1000f
                    if (dt <= 0f) 0f else (it.speedMps - s.speedMps) / dt
                } ?: 0f
                val violentStop = prev != null && prev.speedMps >= ridingSpeed && decel >= hardDecel

                if (impact || violentStop) {
                    speedBeforeEvent = prev?.speedMps ?: s.speedMps
                    // A spike while already parked is someone handling the phone, not a crash.
                    if (speedBeforeEvent >= ridingSpeed) {
                        state = FallState(
                            FallPhase.SUSPECTED,
                            since = s.atMs,
                            peakAccel = s.accelMag,
                            reason = if (impact) "impact" else "hard deceleration",
                        )
                    }
                }
            }

            FallPhase.SUSPECTED -> {
                val elapsed = s.atMs - state.since
                val movedRecently = s.atMs - movedSince
                when {
                    // Rider carried on — whatever it was, it was not a crash.
                    s.speedMps > ridingSpeed -> state = FallState(FallPhase.IDLE)

                    movedRecently >= stillnessMs && looksFallen(s.tiltDeg) ->
                        state = state.copy(
                            phase = FallPhase.CONFIRMED,
                            reason = "${state.reason}, stopped ${stillnessMs / 1000}s, phone tilted ${s.tiltDeg.toInt()}°",
                        )

                    // Long enough without the orientation ever agreeing: this was a hard stop,
                    // not a fall. Sitting at a red light must not leave a suspicion latched on
                    // forever waiting for a tilt that is never coming.
                    elapsed > suspicionWindowMs -> state = FallState(FallPhase.IDLE)

                    else -> state = state.copy(peakAccel = maxOf(state.peakAccel, s.accelMag))
                }
            }

            FallPhase.CONFIRMED -> {
                // Moving again means the rider is up; clear it without any further ceremony.
                if (s.speedMps > ridingSpeed) state = FallState(FallPhase.IDLE)
            }
        }
        return state
    }
}

/** Magnitude of a raw accelerometer reading. */
fun accelMagnitude(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

/**
 * How far the device is tilted from upright, in degrees, from a gravity vector.
 *
 * A phone in a bar mount sits near vertical; one that has hit the road lies flat, which shows
 * up as gravity moving onto the z axis.
 */
fun tiltFromGravity(x: Float, y: Float, z: Float): Float {
    val mag = accelMagnitude(x, y, z)
    if (mag < 1e-3f) return 0f
    val cos = (abs(z) / mag).coerceIn(0f, 1f)
    return Math.toDegrees(kotlin.math.acos(cos.toDouble())).toFloat().let { 90f - it }
}
