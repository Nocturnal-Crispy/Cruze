package com.curv3.ui

import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color as ComposeColor
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

@OptIn(ExperimentalMaterial3Api::class)
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
    var centred by remember { mutableStateOf(false) }

    // Drop the user somewhere useful the first time a fix arrives.
    LaunchedEffect(fix != null) {
        val f = fix ?: return@LaunchedEffect
        if (!centred) {
            mapRef?.controller?.setZoom(12.0)
            mapRef?.controller?.setCenter(f.pos.geo())
            centred = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        OsmMap(
            modifier = Modifier.fillMaxSize(),
            onTap = { vm.clearSearch() },
            onLongPress = { vm.addWaypoint(it) },
            onReady = { mapRef = it },
        ) { map ->
            map.clearDrawn()
            vm.plan?.let { p ->
                map.drawRoute(p.shape)
            }
            vm.waypoints.forEachIndexed { i, w ->
                val hue = when (i) {
                    0 -> Color.parseColor("#FF2E7D32")
                    vm.waypoints.lastIndex -> Color.parseColor("#FFC62828")
                    else -> Color.parseColor("#FFF9A825")
                }
                map.drawMarker(w.pos, w.name.ifBlank { "Point ${i + 1}" }, hue)
            }
            fix?.let { map.drawMarker(it.pos, "You", Color.parseColor("#FF1565C0")) }
            map.invalidate()
        }

        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            SearchBar(
                query = query,
                onQuery = {
                    query = it
                    vm.search(it, mapRef?.mapCenter?.let { c -> LatLon(c.latitude, c.longitude) })
                },
                onClear = { query = ""; vm.clearSearch() },
                searching = vm.searching,
            )
            if (vm.searchResults.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(max = 260.dp),
                ) {
                    LazyColumn {
                        items(vm.searchResults) { place ->
                            Text(
                                place.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.addWaypoint(place.pos, place.name.split(",").take(2).joinToString(","))
                                        vm.clearSearch()
                                        query = ""
                                        mapRef?.controller?.animateTo(place.pos.geo())
                                    }
                                    .padding(14.dp),
                                fontSize = 14.sp,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }

        // Recentre on the rider.
        IconButton(
            onClick = { fix?.let { mapRef?.controller?.animateTo(it.pos.geo()) } },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(12.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp)),
        ) { Icon(Icons.Default.MyLocation, contentDescription = "Centre on me") }

        BottomPanel(vm, onStartNavigation, onExport, onImport) { mapRef }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
    searching: Boolean,
) {
    Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        TextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Search a place, or long-press the map") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                when {
                    searching -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    query.isNotEmpty() -> IconButton(onClick = onClear) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = ComposeColor.Transparent,
                unfocusedIndicatorColor = ComposeColor.Transparent,
            ),
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.BottomPanel(
    vm: AppViewModel,
    onStartNavigation: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    map: () -> MapView?,
) {
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        tonalElevation = 6.dp,
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RouteStyle.entries.forEach { s ->
                    FilterChip(
                        selected = vm.style == s,
                        onClick = { vm.chooseStyle(s) },
                        label = { Text(s.label) },
                    )
                }
            }

            if (vm.waypoints.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 116.dp)) {
                    itemsIndexed(vm.waypoints) { i, w ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${i + 1}.",
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(24.dp),
                                fontSize = 13.sp,
                            )
                            Text(
                                w.name.ifBlank { "%.4f, %.4f".format(w.pos.lat, w.pos.lon) },
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                fontSize = 13.sp,
                            )
                            IconButton(onClick = { vm.removeWaypoint(i) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            vm.plan?.let { p ->
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Stat(fmtDist(p.lengthM), "distance")
                    Stat(fmtDur(p.timeS), "riding")
                    Stat(p.curveLabel, "${p.curviness.toInt()}°/mi")
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (vm.plan == null) {
                    Button(
                        onClick = { vm.route() },
                        enabled = vm.waypoints.size >= 2 && !vm.busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (vm.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("Find route")
                    }
                } else {
                    Button(onClick = onStartNavigation, modifier = Modifier.weight(1f)) { Text("Ride") }
                    FilledTonalButton(onClick = { vm.saveCurrentRoute() }) { Text("Save") }
                    FilledTonalButton(onClick = onExport) { Text("GPX") }
                }
                if (vm.waypoints.isEmpty()) {
                    OutlinedButton(onClick = onImport) { Text("Import GPX") }
                } else {
                    OutlinedButton(onClick = { vm.clearPlan(); map()?.clearDrawn(); map()?.invalidate() }) {
                        Text("Clear")
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
