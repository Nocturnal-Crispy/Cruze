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
    "RainViewer-$framePath", 0, 12, 256, "",
    arrayOf(framePath),
    "RainViewer.com",
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        "$baseUrl/256/${MapTileIndex.getZoom(pMapTileIndex)}/" +
            "${MapTileIndex.getX(pMapTileIndex)}/${MapTileIndex.getY(pMapTileIndex)}/2/1_1.png"
}

/** Removes any radar layer currently on the map. */
fun MapView.clearRadar() {
    overlays.removeAll { it is TilesOverlay }
}

/**
 * Shows [framePath]'s radar imagery. Rain is drawn semi-transparent and beneath the route so
 * the line the rider is following is never obscured by weather.
 */
fun MapView.showRadar(framePath: String) {
    clearRadar()
    val provider = MapTileProviderBasic(context, radarSource(framePath))
    val overlay = TilesOverlay(provider, context).apply {
        loadingBackgroundColor = android.graphics.Color.TRANSPARENT
        loadingLineColor = android.graphics.Color.TRANSPARENT
        setColorFilter(null)
    }
    // Index 1 keeps it above the basemap but below markers and the route polyline.
    overlays.add(minOf(1, overlays.size), overlay)
    invalidate()
}
