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
    // Zoom 15, not 12. RainViewer's radar is generated at low resolution, but it serves tiles
    // at any zoom by upscaling — and osmdroid simply requests nothing above a source's declared
    // maximum. Capping at 12 is why the radar vanished as soon as a rider zoomed in far enough
    // to see the road they were on, which is the zoom they actually ride at.
    "RainViewer-$framePath", 0, 15, 256, "",
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
        setColorFilter(null)
    }
    // Index 1 keeps it above the basemap but below markers and the route polyline.
    overlays.add(minOf(1, overlays.size), overlay)
    invalidate()
}
