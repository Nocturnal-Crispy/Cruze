package com.cruze.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cruze.data.SavedTrack
import com.cruze.fmtDist
import com.cruze.fmtDur
import com.cruze.metresToMiles
import com.cruze.trackDistanceM
import com.cruze.trackMovingS

/** Rides you have actually ridden. Planned routes live on the map until you export them. */
@Composable
fun RidesScreen(vm: AppViewModel, onExportTrack: (SavedTrack) -> Unit) {
    LaunchedEffect(Unit) { vm.refreshLibrary() }

    val total = vm.tracks.sumOf { trackDistanceM(it.points) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }

        if (vm.tracks.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Summary("${vm.tracks.size}", "rides")
                    // fmtDist already switches on Settings.metric; the label has to follow it, or a
                    // metric rider reads kilometres under the word "miles".
                    Summary(fmtDist(total), "ridden")
                    Summary(
                        fmtDur(vm.tracks.sumOf { trackMovingS(it.points) }),
                        "moving",
                    )
                }
            }
        } else {
            item {
                Text(
                    "No rides recorded yet. Start a recording from the Ride tab and every mile " +
                        "lands here — and on your bike's odometer.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }

        items(vm.tracks) { t ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(t.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        "${fmtDist(trackDistanceM(t.points))} · moving ${fmtDur(trackMovingS(t.points))}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        TextButton(onClick = { onExportTrack(t) }) { Text("Share GPX") }
                        TextButton(onClick = { vm.deleteTrack(t) }) { Text("Delete") }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Summary(value: String, label: String) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
