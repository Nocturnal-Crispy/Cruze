package com.cruze.ui

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex

/**
 * Basemaps. All keyless and free to use within their published fair-use terms, which is why
 * each carries the attribution it requires.
 */
enum class MapLayer(val label: String, val attribution: String) {
    DARK("Dark", "© OpenStreetMap contributors © CARTO"),
    STANDARD("Standard", "© OpenStreetMap contributors"),
    TOPO("Terrain", "© OpenStreetMap contributors, SRTM | © OpenTopoMap (CC-BY-SA)"),
    SATELLITE("Satellite", "Source: Esri, Maxar, Earthstar Geographics"),
}

private val cartoDark = XYTileSource(
    "CartoDark", 0, 20, 256, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/dark_all/",
        "https://b.basemaps.cartocdn.com/dark_all/",
        "https://c.basemaps.cartocdn.com/dark_all/",
    ),
    MapLayer.DARK.attribution,
)

private val openTopo = XYTileSource(
    "OpenTopoMap", 0, 17, 256, ".png",
    arrayOf(
        "https://a.tile.opentopomap.org/",
        "https://b.tile.opentopomap.org/",
        "https://c.tile.opentopomap.org/",
    ),
    MapLayer.TOPO.attribution,
)

/** Esri's imagery service uses {z}/{y}/{x} ordering, so it needs a custom URL builder. */
private val esriImagery = object : OnlineTileSourceBase(
    "EsriImagery", 0, 19, 256, "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    MapLayer.SATELLITE.attribution,
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl +
            MapTileIndex.getZoom(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) + "/" +
            MapTileIndex.getX(pMapTileIndex)
}

fun MapLayer.tileSource() = when (this) {
    MapLayer.DARK -> cartoDark
    MapLayer.STANDARD -> TileSourceFactory.MAPNIK
    MapLayer.TOPO -> openTopo
    MapLayer.SATELLITE -> esriImagery
}
