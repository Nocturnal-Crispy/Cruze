package com.cruze

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

const val EARTH_R = 6371008.8

data class LatLon(val lat: Double, val lon: Double)

fun distanceM(a: LatLon, b: LatLon): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val la1 = Math.toRadians(a.lat)
    val la2 = Math.toRadians(b.lat)
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(la1) * cos(la2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_R * asin(min(1.0, sqrt(h)))
}

fun bearingDeg(a: LatLon, b: LatLon): Double {
    val la1 = Math.toRadians(a.lat)
    val la2 = Math.toRadians(b.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val y = sin(dLon) * cos(la2)
    val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/** Signed smallest difference between two bearings, in (-180, 180]. */
fun angleDiff(from: Double, to: Double): Double = (to - from + 540.0) % 360.0 - 180.0

fun pathLengthM(path: List<LatLon>): Double {
    var s = 0.0
    for (i in 1 until path.size) s += distanceM(path[i - 1], path[i])
    return s
}

/** Running distance from the path start to each vertex. Same size as [path]. */
fun cumulative(path: List<LatLon>): DoubleArray {
    val c = DoubleArray(path.size)
    for (i in 1 until path.size) c[i] = c[i - 1] + distanceM(path[i - 1], path[i])
    return c
}

fun interpolate(a: LatLon, b: LatLon, t: Double) =
    LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)

/** The point [distM] metres from [from] along [bearing]. Used to place round-trip via points. */
fun destinationPoint(from: LatLon, bearing: Double, distM: Double): LatLon {
    val br = Math.toRadians(bearing)
    val la = Math.toRadians(from.lat)
    val lo = Math.toRadians(from.lon)
    val ad = distM / EARTH_R
    val la2 = kotlin.math.asin(sin(la) * cos(ad) + cos(la) * sin(ad) * cos(br))
    val lo2 = lo + atan2(sin(br) * sin(ad) * cos(la), cos(ad) - sin(la) * sin(la2))
    return LatLon(Math.toDegrees(la2), ((Math.toDegrees(lo2) + 540) % 360) - 180)
}

/**
 * Distance in metres from [p] to segment [a]-[b], plus how far along the segment the
 * closest point lies (0..1). Uses a local flat projection — exact enough at segment scale.
 */
fun distToSegment(p: LatLon, a: LatLon, b: LatLon): Pair<Double, Double> {
    val mLat = EARTH_R * Math.toRadians(1.0)
    val mLon = mLat * cos(Math.toRadians(a.lat))
    val bx = (b.lon - a.lon) * mLon
    val by = (b.lat - a.lat) * mLat
    val px = (p.lon - a.lon) * mLon
    val py = (p.lat - a.lat) * mLat
    val len2 = bx * bx + by * by
    val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
    return Pair(hypot(px - t * bx, py - t * by), t)
}

data class Snap(val index: Int, val t: Double, val distM: Double, val point: LatLon)

/**
 * Nearest point on [path] to [p]. [fromIdx]/[window] limit the search to a slice, which keeps
 * navigation updates cheap and stops a route that loops back on itself from snapping backwards.
 */
fun snapToPath(p: LatLon, path: List<LatLon>, fromIdx: Int = 0, window: Int = Int.MAX_VALUE): Snap {
    if (path.size < 2) return Snap(0, 0.0, Double.MAX_VALUE, path.firstOrNull() ?: p)
    val start = fromIdx.coerceIn(0, path.size - 2)
    val end = if (window == Int.MAX_VALUE) path.size - 2
    else min(path.size - 2, start + window)
    var best = Snap(start, 0.0, Double.MAX_VALUE, path[start])
    for (i in start..end) {
        val (d, t) = distToSegment(p, path[i], path[i + 1])
        if (d < best.distM) best = Snap(i, t, d, interpolate(path[i], path[i + 1], t))
    }
    return best
}

/**
 * Re-space a path to roughly [stepM] between points. OSM geometry has wildly uneven vertex
 * density, so curvature has to be measured on an even sampling or it just measures mapping style.
 */
fun resample(path: List<LatLon>, stepM: Double): List<LatLon> {
    if (path.size < 2) return path
    val out = ArrayList<LatLon>(((pathLengthM(path) / stepM).toInt() + 2).coerceAtLeast(2))
    out.add(path[0])
    var carry = 0.0
    for (i in 1 until path.size) {
        val a = path[i - 1]
        val b = path[i]
        val seg = distanceM(a, b)
        if (seg <= 0.0) continue
        var pos = stepM - carry
        while (pos <= seg) {
            out.add(interpolate(a, b, pos / seg))
            pos += stepM
        }
        carry = (carry + seg) % stepM
    }
    if (out.last() != path.last()) out.add(path.last())
    return out
}

// Geometry is metric throughout — GPS, GPX and the routing shapes all are. Only what the
// rider reads is imperial, converted here at the edge.
const val M_PER_MILE = 1609.344
const val M_PER_FOOT = 0.3048
const val MPS_TO_MPH = 2.2369363

fun metresToMiles(m: Double) = m / M_PER_MILE

fun fmtDist(m: Double): String {
    val miles = metresToMiles(m)
    return when {
        miles < 0.1 -> "${((m / M_PER_FOOT) / 10).toInt() * 10} ft"
        miles < 10 -> String.format("%.1f mi", miles)
        else -> "${miles.toInt()} mi"
    }
}

fun fmtDur(sec: Double): String {
    val s = sec.toInt()
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

fun fmtTurnDist(m: Double): String {
    val miles = metresToMiles(m)
    return when {
        m < 30 -> "now"
        miles < 0.19 -> "${((m / M_PER_FOOT) / 50).toInt() * 50} ft"
        else -> String.format("%.1f mi", miles)
    }
}

fun fmtSpeed(mps: Float): String = "${(mps * MPS_TO_MPH).toInt()}"

internal fun sq(x: Double) = x * x

@Suppress("unused")
internal fun absDeg(x: Double) = abs(x)
