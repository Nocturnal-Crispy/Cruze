package com.curv3.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.curv3.data.SavedRoute
import com.curv3.data.SavedTrack
import com.curv3.fmtDist
import com.curv3.fmtDur
import com.curv3.trackDistanceM
import com.curv3.trackMovingS

@Composable
fun RidesScreen(
    vm: AppViewModel,
    onOpenRoute: (SavedRoute) -> Unit,
    onExportRoute: (SavedRoute) -> Unit,
    onExportTrack: (SavedTrack) -> Unit,
) {
    LaunchedEffect(Unit) { vm.refreshLibrary() }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Header("Saved routes") }
        if (vm.routes.isEmpty()) item { Empty("Plan a route and tap Save.") }
        items(vm.routes) { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(r.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${fmtDist(r.plan.lengthM)} · ${fmtDur(r.plan.timeS)} · ${r.plan.curveLabel}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onOpenRoute(r) }) { Text("Open") }
                        TextButton(onClick = { onExportRoute(r) }) { Text("GPX") }
                        TextButton(onClick = { vm.deleteRoute(r) }) { Text("Delete") }
                    }
                }
            }
        }

        item { Header("Recorded rides") }
        if (vm.tracks.isEmpty()) item { Empty("Start a recording from the Ride tab.") }
        items(vm.tracks) { t ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(t.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${fmtDist(trackDistanceM(t.points))} · moving ${fmtDur(trackMovingS(t.points))} · ${t.points.size} points",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onExportTrack(t) }) { Text("GPX") }
                        TextButton(onClick = { vm.deleteTrack(t) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) =
    Text(text, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Empty(text: String) = Row(
    Modifier.fillMaxWidth().padding(vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
}
