package com.curv3.ui

import android.content.Context
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.curv3.LatLon
import com.curv3.route.USER_AGENT
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

fun LatLon.geo() = GeoPoint(lat, lon)
fun GeoPoint.latLon() = LatLon(latitude, longitude)

/**
 * osmdroid must be configured before the first MapView is created. The OSM tile policy
 * requires an identifying User-Agent, and tiles are cached inside the app's own storage so
 * we never need an external-storage permission.
 */
fun initOsmdroid(ctx: Context) {
    val cfg = Configuration.getInstance()
    cfg.load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    cfg.userAgentValue = USER_AGENT
    cfg.osmdroidBasePath = File(ctx.cacheDir, "osmdroid").apply { mkdirs() }
    cfg.osmdroidTileCache = File(cfg.osmdroidBasePath, "tiles").apply { mkdirs() }
}

/**
 * A lifecycle-aware osmdroid map. [onReady] runs once with the MapView; [update] runs on every
 * recomposition and is where overlays get rebuilt.
 */
@Composable
fun OsmMap(
    modifier: Modifier = Modifier,
    onTap: ((LatLon) -> Unit)? = null,
    onLongPress: ((LatLon) -> Unit)? = null,
    onReady: (MapView) -> Unit = {},
    update: (MapView) -> Unit,
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current

    val map = remember {
        initOsmdroid(ctx)
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            controller.setZoom(11.0)
            onReady(this)
        }
    }

    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            map.onDetach()
        }
    }

    DisposableEffect(onTap, onLongPress) {
        val overlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                p ?: return false
                onTap?.invoke(p.latLon()) ?: return false
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean {
                p ?: return false
                onLongPress?.invoke(p.latLon()) ?: return false
                return true
            }
        })
        map.overlays.add(0, overlay)
        onDispose { map.overlays.remove(overlay) }
    }

    AndroidView(factory = { map }, modifier = modifier, update = update)
}

/** Overlays are cheap to rebuild and impossible to diff correctly — replace them wholesale. */
fun MapView.clearDrawn() {
    overlays.removeAll { it is Polyline || it is Marker }
}

fun MapView.drawRoute(shape: List<LatLon>, color: Int = Color.parseColor("#FF2962FF"), width: Float = 12f) {
    if (shape.size < 2) return
    val line = Polyline(this).apply {
        outlinePaint.color = color
        outlinePaint.strokeWidth = width
        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        setPoints(shape.map { it.geo() })
    }
    overlays.add(line)
}

fun MapView.drawMarker(p: LatLon, title: String, hue: Int) {
    val m = Marker(this).apply {
        position = p.geo()
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        this.title = title
        icon = dotDrawable(context, hue)
    }
    overlays.add(m)
}

private fun dotDrawable(ctx: Context, color: Int): android.graphics.drawable.Drawable {
    val size = (ctx.resources.displayMetrics.density * 18).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.color = Color.WHITE
    c.drawCircle(size / 2f, size / 2f, size / 2f, p)
    p.color = color
    c.drawCircle(size / 2f, size / 2f, size / 2f - ctx.resources.displayMetrics.density * 3, p)
    return android.graphics.drawable.BitmapDrawable(ctx.resources, bmp)
}

fun MapView.zoomTo(shape: List<LatLon>, padding: Int = 120) {
    if (shape.isEmpty()) return
    val box = org.osmdroid.util.BoundingBox.fromGeoPoints(shape.map { it.geo() })
    post { runCatching { zoomToBoundingBox(box, false, padding) } }
}
