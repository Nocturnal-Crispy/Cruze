package com.curv3.route

import com.curv3.LatLon

/** Valhalla encodes shapes as Google polylines with 6 decimal places of precision. */
fun decodePolyline(encoded: String, precision: Int = 6): List<LatLon> {
    val factor = Math.pow(10.0, precision.toDouble())
    val out = ArrayList<LatLon>(encoded.length / 4)
    var i = 0
    var lat = 0
    var lon = 0
    while (i < encoded.length) {
        var shift = 0
        var result = 0
        var b: Int
        do {
            b = encoded[i++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20 && i < encoded.length)
        lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        shift = 0
        result = 0
        do {
            b = encoded[i++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20 && i < encoded.length)
        lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        out.add(LatLon(lat / factor, lon / factor))
    }
    return out
}

fun encodePolyline(path: List<LatLon>, precision: Int = 6): String {
    val factor = Math.pow(10.0, precision.toDouble())
    val sb = StringBuilder()
    var prevLat = 0L
    var prevLon = 0L
    for (p in path) {
        val lat = Math.round(p.lat * factor)
        val lon = Math.round(p.lon * factor)
        encodeValue((lat - prevLat).toInt(), sb)
        encodeValue((lon - prevLon).toInt(), sb)
        prevLat = lat
        prevLon = lon
    }
    return sb.toString()
}

private fun encodeValue(v: Int, sb: StringBuilder) {
    var value = if (v < 0) (v shl 1).inv() else v shl 1
    while (value >= 0x20) {
        sb.append(((0x20 or (value and 0x1f)) + 63).toChar())
        value = value shr 5
    }
    sb.append((value + 63).toChar())
}
