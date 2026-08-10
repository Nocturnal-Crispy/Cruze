package com.cruze.ui

import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay

/**
 * Precipitation radar drawn over the basemap.
 *
 * RainViewer serves each timestamped frame under its own path, so the source has to be rebuilt
 * whenever the frame changes rather than configured once.
 */
private fun radarSource(framePath: String) = object : OnlineTileSourceBase(
    // Zoom 7 is measured, not guessed: above it RainViewer returns one byte-identical
    // "Zoom Level Not Supported" placeholder for every tile on Earth. The old value of 12 meant
    // the map papered itself in that placeholder from z8 up, which is every zoom a rider
    // actually uses — the reported "doesn't work under a certain zoom distance". Declaring the
    // real maximum stops us fetching them, and osmdroid's approximater upscales the z7 tile to
    // cover closer zooms: blurry, but rain in the right place beats a wall of grey labels.
    // 256 px tiles, not RainViewer's 512. Both stop at z7; osmdroid upscales the last real
    // tile to cover closer zooms, and the 256 grid keeps rain on screen a zoom level or two
    // further in before it gives up. Detail per tile matters less than the layer still being
    // there at the zoom someone rides at.
    "RainViewer-$framePath", 0, 7, 256, "",
    arrayOf(framePath),
    "RainViewer.com",
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        "$baseUrl/256/${MapTileIndex.getZoom(pMapTileIndex)}/" +
            "${MapTileIndex.getX(pMapTileIndex)}/${MapTileIndex.getY(pMapTileIndex)}/2/1_1.png"
}

/** Removes any radar layer currently on the map, and lets go of its tile provider. */
fun MapView.clearRadar() {
    overlays.filterIsInstance<TilesOverlay>().forEach { it.onDetach(this) }
    overlays.removeAll { it is TilesOverlay }
    setTag(RADAR_TAG, null)
}

/** Which radar frame is on the map, so it is only rebuilt when the frame actually changes. */
private val RADAR_TAG = "cruze_radar_frame".hashCode()

/**
 * How solid the rain is drawn. Tuned on a real screen in daylight: enough to read the intensity
 * at a glance, light enough that the road and the route line stay visible underneath.
 */
private const val RADAR_ALPHA = 0.5f

/**
 * Shows [framePath]'s radar imagery. Rain is drawn semi-transparent and beneath the route so
 * the line the rider is following is never obscured by weather.
 *
 * Rebuilt only when the frame actually changes: this is called from the map's draw block, which
 * runs on every recomposition, and tearing down the tile provider each time threw away the
 * in-flight downloads and started again from nothing.
 */
fun MapView.showRadar(framePath: String) {
    if (getTag(RADAR_TAG) == framePath) return
    clearRadar()
    setTag(RADAR_TAG, framePath)
    val provider = MapTileProviderBasic(context, radarSource(framePath))
    // Without this the map is never told a radar tile finished downloading, so nothing was
    // painted until some other gesture forced a redraw — which is exactly why the radar
    // "only worked if you zoomed".
    provider.setTileRequestCompleteHandler(
        org.osmdroid.tileprovider.util.SimpleInvalidationHandler(this)
    )
    val overlay = TilesOverlay(provider, context).apply {
        loadingBackgroundColor = android.graphics.Color.TRANSPARENT
        loadingLineColor = android.graphics.Color.TRANSPARENT
        // RainViewer's tiles are fully opaque, so drawn as-is the rain covers the map and the
        // rider cannot see the road they are asking about. A colour matrix that scales only the
        // alpha channel is the one public hook TilesOverlay gives us for this.
        setColorFilter(
            android.graphics.ColorMatrixColorFilter(
                android.graphics.ColorMatrix().apply { setScale(1f, 1f, 1f, RADAR_ALPHA) }
            )
        )
    }
    // Index 1 keeps it above the basemap but below markers and the route polyline.
    overlays.add(minOf(1, overlays.size), overlay)
    invalidate()
}
