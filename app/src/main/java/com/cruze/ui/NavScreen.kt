package com.cruze.ui

import android.graphics.Color
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruze.RideState
import com.cruze.fmtDist
import com.cruze.fmtDur
import com.cruze.fmtSpeed
import com.cruze.fmtTurnDist
import com.cruze.sync.AlertKind
import com.cruze.sync.GroupState
import com.cruze.sync.PRESET_MESSAGES
import org.osmdroid.views.MapView

/**
 * Riding view: map locked to the rider, heading-up, with the next instruction large enough to
 * read at a glance through a visor.
 */
@Composable
fun NavScreen(vm: AppViewModel, onStop: () -> Unit) {
    val fix by RideState.fix.collectAsStateWithLifecycle()
    val progress by RideState.progress.collectAsStateWithLifecycle()
    val plan by RideState.plan.collectAsStateWithLifecycle()
    val recording by RideState.recording.collectAsStateWithLifecycle()
    val session by GroupState.session.collectAsStateWithLifecycle()
    val roster by GroupState.roster.collectAsStateWithLifecycle()
    val trails by GroupState.trails.collectAsStateWithLifecycle()
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    var sayOpen by remember { mutableStateOf(false) }

    val nextManeuver = remember(progress, plan) {
        val p = progress ?: return@remember null
        plan?.maneuvers?.getOrNull(p.nextManeuverIdx)
    }

    Box(Modifier.fillMaxSize()) {
        OsmMap(modifier = Modifier.fillMaxSize(), layer = vm.mapLayer, onReady = { mapRef = it }) { map ->
            map.clearDrawn()
            plan?.let { map.drawRoute(it.shape) }
            // The group belongs on the riding map, not only on a tab nobody opens mid-ride:
            // where the others are is the whole point of riding together.
            roster.forEach { r ->
                trails[r.riderId]?.takeIf { it.size > 1 }
                    ?.let { map.drawTrail(it, riderColor(r.riderId).toArgb()) }
                map.drawRider(r.pos, r.bearing, r.speedMps > 2f, riderColor(r.riderId).toArgb())
            }
            fix?.let { f ->
                map.drawRider(f.pos, f.bearing, f.speedMps > 2f, Color.parseColor("#FF4FA8FF"))
                map.controller.setCenter(f.pos.geo())
                // Heading-up only once actually moving; a stationary GPS bearing is noise.
                map.mapOrientation = if (f.speedMps > 2f) -f.bearing else map.mapOrientation
            }
            map.invalidate()
        }

        // Next instruction
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                val off = progress?.offRoute == true
                Text(
                    if (off) "Off route" else fmtTurnDist(progress?.distToManeuverM ?: 0.0),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    when {
                        off -> "Recalculating…"
                        nextManeuver != null -> nextManeuver.instruction
                        else -> "Continue to your destination"
                    },
                    fontSize = 19.sp,
                    lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        // Live stats + stop
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Big(fmtSpeed(fix?.speedMps ?: 0f), "mph")
                    Big(fmtDist(progress?.remainingM ?: plan?.lengthM ?: 0.0), "left")
                    Big(fmtDur(progress?.remainingS ?: plan?.timeS ?: 0.0), "to go")
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        modifier = Modifier.weight(1f),
                    ) { Text("Stop") }
                    // One glove-sized tap to whatever the group needs to hear, without
                    // leaving guidance to go find another screen.
                    if (session != null) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(GloveTarget),
                        ) {
                            Box(
                                Modifier.clickable { sayOpen = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Chat,
                                    "Say something to the group",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }
                    if (recording) {
                        Text(
                            "● recording",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = 8.dp),
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }

    if (sayOpen) SaySomethingSheet(onDismiss = { sayOpen = false })
}

/**
 * The one-tap things worth saying at 70 mph, big enough to hit with gloves on and gone again
 * the moment one is picked — a rider should be looking at the road, not at this.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaySomethingSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Say something", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            PRESET_MESSAGES.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { msg ->
                        Button(
                            onClick = { GroupState.sendPreset(msg); onDismiss() },
                            // heightIn, not height: a longer message wraps rather than being
                            // silently cut in half, which would change what it says.
                            modifier = Modifier.weight(1f).heightIn(min = GloveTarget),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            Text(
                                msg,
                                fontSize = 15.sp,
                                lineHeight = 18.sp,
                                maxLines = 2,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Button(
                onClick = { GroupState.sendAlert(AlertKind.SOS); onDismiss() },
                modifier = Modifier.fillMaxWidth().height(GloveTarget),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("SOS — I need help", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun Big(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
