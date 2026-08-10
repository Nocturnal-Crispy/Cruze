package com.curv3.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.curv3.LatLon
import com.curv3.route.USER_AGENT
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

fun LatLon.geo() = GeoPoint(lat, lon)
fun GeoPoint.latLon() = LatLon(latitude, longitude)

/**
 * osmdroid must be configured before the first MapView exists. Tile providers require an
 * identifying User-Agent, and tiles cache inside app storage so no storage permission is needed.
 */
fun initOsmdroid(ctx: Context) {
    val cfg = Configuration.getInstance()
    cfg.load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    cfg.userAgentValue = USER_AGENT
    cfg.osmdroidBasePath = File(ctx.cacheDir, "osmdroid").apply { mkdirs() }
    cfg.osmdroidTileCache = File(cfg.osmdroidBasePath, "tiles").apply { mkdirs() }
}

@Composable
fun OsmMap(
    modifier: Modifier = Modifier,
    layer: MapLayer = MapLayer.DARK,
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
            setTileSource(layer.tileSource())
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            controller.setZoom(12.0)
            onReady(this)
        }
    }

    DisposableEffect(layer) {
        map.setTileSource(layer.tileSource())
        map.invalidate()
        onDispose {}
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

/**
 * Draws the route as a dark casing under a red line, so it stays readable over a dark basemap,
 * satellite imagery, or the red motorway shields on the standard OSM style alike.
 */
fun MapView.drawRoute(shape: List<LatLon>, color: Int = Curv3Colors.RouteLine.toArgb()) {
    if (shape.size < 2) return
    val pts = shape.map { it.geo() }
    val density = resources.displayMetrics.density
    overlays.add(Polyline(this).apply {
        outlinePaint.color = Curv3Colors.RouteCasing.toArgb()
        outlinePaint.strokeWidth = 11f * density
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
        setPoints(pts)
    })
    overlays.add(Polyline(this).apply {
        outlinePaint.color = color
        outlinePaint.strokeWidth = 6f * density
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
        setPoints(pts)
    })
}

fun MapView.drawTrack(shape: List<LatLon>) {
    if (shape.size < 2) return
    overlays.add(Polyline(this).apply {
        outlinePaint.color = Curv3Colors.TrackLine.toArgb()
        outlinePaint.strokeWidth = 5f * resources.displayMetrics.density
        outlinePaint.strokeCap = Paint.Cap.ROUND
        setPoints(shape.map { it.geo() })
    })
}

fun MapView.drawMarker(p: LatLon, title: String, color: Int, label: String? = null) {
    overlays.add(Marker(this).apply {
        position = p.geo()
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        this.title = title
        icon = pinDrawable(context, color, label)
    })
}

/** A rider arrow when moving, a plain dot when stationary. */
fun MapView.drawRider(p: LatLon, bearing: Float, moving: Boolean, color: Int) {
    overlays.add(Marker(this).apply {
        position = p.geo()
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        title = "You"
        icon = if (moving) arrowDrawable(context, color) else pinDrawable(context, color, null)
        rotation = if (moving) bearing else 0f
        isFlat = true
    })
}

private fun pinDrawable(ctx: Context, color: Int, label: String?): Drawable {
    val d = ctx.resources.displayMetrics.density
    val size = (d * 22).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = Curv3Colors.RouteCasing.toArgb()
    c.drawCircle(size / 2f, size / 2f, size / 2f, p)
    p.color = color
    c.drawCircle(size / 2f, size / 2f, size / 2f - d * 2.5f, p)
    if (label != null) {
        p.color = android.graphics.Color.WHITE
        p.textSize = d * 11
        p.textAlign = Paint.Align.CENTER
        p.isFakeBoldText = true
        val fm = p.fontMetrics
        c.drawText(label, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2, p)
    }
    return BitmapDrawable(ctx.resources, bmp)
}

private fun arrowDrawable(ctx: Context, color: Int): Drawable {
    val d = ctx.resources.displayMetrics.density
    val size = (d * 30).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val path = android.graphics.Path().apply {
        moveTo(size / 2f, d * 3)
        lineTo(size - d * 5, size - d * 4)
        lineTo(size / 2f, size - d * 10)
        lineTo(d * 5, size - d * 4)
        close()
    }
    p.color = Curv3Colors.RouteCasing.toArgb()
    p.style = Paint.Style.STROKE
    p.strokeWidth = d * 3
    p.strokeJoin = Paint.Join.ROUND
    c.drawPath(path, p)
    p.style = Paint.Style.FILL
    p.color = color
    c.drawPath(path, p)
    return BitmapDrawable(ctx.resources, bmp)
}

fun MapView.zoomTo(shape: List<LatLon>, padding: Int = 140) {
    if (shape.size < 2) return
    val box = BoundingBox.fromGeoPoints(shape.map { it.geo() })
    post { runCatching { zoomToBoundingBox(box, false, padding) } }
}
