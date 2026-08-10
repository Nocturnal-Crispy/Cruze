package com.curv3.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.curv3.LatLon
import com.curv3.RideState
import com.curv3.fmtDist
import com.curv3.fmtDur
import com.curv3.route.RouteStyle
import org.osmdroid.views.MapView

@Composable
fun PlanScreen(
    vm: AppViewModel,
    onStartNavigation: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    val fix by RideState.fix.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    var followedOnce by remember { mutableStateOf(false) }

    // Open the map on the rider, once, without fighting them if they then pan away.
    LaunchedEffect(fix, mapRef) {
        val f = fix ?: return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        if (!followedOnce) {
            map.controller.setZoom(13.0)
            map.controller.setCenter(f.pos.geo())
            followedOnce = true
        }
    }

    // Frame the whole route the moment one is found.
    LaunchedEffect(vm.plan) { vm.plan?.let { mapRef?.zoomTo(it.shape) } }

    Box(Modifier.fillMaxSize()) {
        OsmMap(
            modifier = Modifier.fillMaxSize(),
            layer = vm.mapLayer,
            onTap = { vm.clearSearch() },
            onLongPress = { vm.addDestination(it) },
            onReady = { mapRef = it },
        ) { map ->
            map.clearDrawn()
            vm.plan?.let { map.drawRoute(it.shape) }
            vm.waypoints.forEachIndexed { i, w ->
                val last = i == vm.waypoints.lastIndex
                val color = when {
                    i == 0 -> Color(0xFF35C759)
                    last -> Curv3Colors.Red
                    else -> Color(0xFFFFB300)
                }
                map.drawMarker(w.pos, w.name.ifBlank { "Point ${i + 1}" }, color.toArgb(), "${i + 1}")
            }
            fix?.let {
                map.drawRider(it.pos, it.bearing, it.speedMps > 2f, Color(0xFF4FA8FF).toArgb())
            }
            map.invalidate()
        }

        TopControls(vm, query, onQuery = { q ->
            query = q
            vm.search(q, mapRef?.mapCenter?.let { LatLon(it.latitude, it.longitude) })
        }, onClear = { query = ""; vm.clearSearch() }, onPick = { place ->
            vm.addDestination(place.pos, place.name.split(",").take(2).joinToString(","))
            query = ""
            mapRef?.controller?.animateTo(place.pos.geo())
        })

        SideControls(
            vm = vm,
            onRecentre = {
                fix?.let { mapRef?.controller?.animateTo(it.pos.geo()) }
                    ?: run { vm.message = "No GPS fix yet." }
            },
        )

        Sheet(vm, onStartNavigation, onExport, onImport) { mapRef }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.TopControls(
    vm: AppViewModel,
    query: String,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
    onPick: (com.curv3.route.Place) -> Unit,
) {
    Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp)) {
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().heightIn(min = GloveTarget),
        ) {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Where to?", maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary) },
                trailingIcon = {
                    when {
                        vm.searching -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        query.isNotEmpty() -> Box(
                            Modifier.size(GloveTarget).clickable(onClick = onClear),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.Close, "Clear") }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        }

        AnimatedVisibility(vm.searchResults.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(max = 300.dp),
            ) {
                LazyColumn {
                    items(vm.searchResults) { place ->
                        val parts = place.name.split(",")
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(place) }
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                        ) {
                            Text(parts.first().trim(), fontWeight = FontWeight.SemiBold, maxLines = 1)
                            if (parts.size > 1) {
                                Text(
                                    parts.drop(1).joinToString(",").trim(),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.SideControls(vm: AppViewModel, onRecentre: () -> Unit) {
    var layersOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box {
            RoundControl(Icons.Default.Layers, "Map style") { layersOpen = true }
            DropdownMenu(expanded = layersOpen, onDismissRequest = { layersOpen = false }) {
                MapLayer.entries.forEach { l ->
                    DropdownMenuItem(
                        text = { Text(l.label) },
                        onClick = { vm.setLayer(l); layersOpen = false },
                        leadingIcon = {
                            if (vm.mapLayer == l) {
                                Icon(Icons.Default.MyLocation, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                    )
                }
            }
        }
        RoundControl(Icons.Default.MyLocation, "Centre on me", onRecentre)
    }
}

@Composable
private fun RoundControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.size(GloveTarget),
    ) {
        Box(Modifier.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun BoxScope.Sheet(
    vm: AppViewModel,
    onStartNavigation: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    map: () -> MapView?,
) {
    Surface(
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.width(38.dp).height(4.dp)
                        .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
                )
            }
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RouteStyle.entries.forEach { s ->
                    FilterChip(
                        selected = vm.style == s,
                        onClick = { vm.chooseStyle(s) },
                        label = { Text(s.label, maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
                Spacer(Modifier.weight(1f))
                FilterChip(
                    selected = vm.roundTrip,
                    onClick = { vm.toggleRoundTrip() },
                    label = { Text("Loop", maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }

            if (vm.waypoints.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Search for a place, or press and hold the map to drop a destination.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
            } else {
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.heightIn(max = 132.dp)) {
                    itemsIndexed(vm.waypoints) { i, w ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(22.dp).background(
                                    when {
                                        i == 0 -> Color(0xFF35C759)
                                        i == vm.waypoints.lastIndex -> Curv3Colors.Red
                                        else -> Color(0xFFFFB300)
                                    },
                                    CircleShape,
                                ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${i + 1}", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                w.name.ifBlank { "%.4f, %.4f".format(w.pos.lat, w.pos.lon) },
                                modifier = Modifier.weight(1f).padding(start = 10.dp),
                                maxLines = 1,
                                fontSize = 14.sp,
                            )
                            Box(
                                Modifier.size(40.dp).clickable { vm.removeWaypoint(i) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Close, "Remove point ${i + 1}",
                                    Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (!vm.startsFromMyLocation) {
                    Text(
                        "Start from my location",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable { vm.useMyLocationAsStart() }
                            .padding(vertical = 8.dp),
                    )
                }
            }

            vm.plan?.let { p ->
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat(fmtDist(p.lengthM), "distance")
                    Stat(fmtDur(p.timeS), "riding")
                    Stat(p.curveLabel, "${p.curviness.toInt()}°/mi")
                }
            }

            Spacer(Modifier.height(12.dp))
            if (vm.plan == null) {
                Button(
                    onClick = { vm.route() },
                    enabled = vm.waypoints.size >= 2 && !vm.busy,
                    modifier = Modifier.fillMaxWidth().height(GloveTarget),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (vm.busy) {
                        CircularProgressIndicator(
                            Modifier.size(22.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else Text(if (vm.roundTrip) "Find loop" else "Find route", fontSize = 17.sp)
                }
            } else {
                Button(
                    onClick = onStartNavigation,
                    modifier = Modifier.fillMaxWidth().height(GloveTarget),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("Ride", fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                if (vm.plan != null) {
                    Secondary("Save", Modifier.weight(1f)) { vm.saveCurrentRoute() }
                    Secondary("GPX", Modifier.weight(1f), onExport)
                }
                if (vm.waypoints.isEmpty()) {
                    Secondary("Import GPX", Modifier.weight(1f), onImport)
                } else {
                    Secondary("Clear", Modifier.weight(1f)) {
                        vm.clearPlan(); map()?.clearDrawn(); map()?.invalidate()
                    }
                }
            }

            Text(
                vm.mapLayer.attribution,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Secondary(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    ) { Text(label, maxLines = 1) }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
