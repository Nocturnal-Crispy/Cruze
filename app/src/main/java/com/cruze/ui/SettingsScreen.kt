package com.cruze.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cruze.Settings
import com.cruze.fmtDist
import com.cruze.route.RouteStyle

@Composable
fun SettingsScreen(versionName: String) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }

        item { Section("Riding") }
        item {
            SwitchRow(
                "Voice guidance",
                "Speak turn instructions and duck music instead of stopping it.",
                Settings.voiceGuidance,
            ) { Settings.updateVoiceGuidance(it) }
        }
        item {
            SwitchRow(
                "Keep the screen on",
                "Never time out mid-corner while the app is open.",
                Settings.keepScreenOn,
            ) {
                Settings.updateKeepScreenOn(it)
                // Apply it now rather than at the next onResume, or the switch looks broken.
                (activity as? com.cruze.MainActivity)?.applyKeepScreenOn()
            }
        }
        item {
            SwitchRow(
                "Light theme",
                "Dark is the default, and easier to read at night behind a visor.",
                !Settings.darkTheme,
            ) {
                Settings.updateDarkTheme(!it)
                // The theme is read once when the activity builds its content, so it has to be
                // recreated for the change to be visible at all.
                activity?.recreate()
            }
        }
        item {
            SwitchRow(
                "Metric units",
                if (Settings.metric) "Kilometres, metres, km/h." else "Miles, feet, mph.",
                Settings.metric,
            ) { Settings.updateMetric(it) }
        }

        item { Section("Route preferences") }
        item {
            ChipRow(
                "Default style",
                RouteStyle.entries.map { it.name to it.label },
                Settings.defaultStyle,
            ) { Settings.updateDefaultStyle(it) }
        }
        item {
            ChipRow(
                "Default map",
                MapLayer.entries.map { it.name to it.label },
                Settings.defaultLayer,
            ) { Settings.updateDefaultLayer(it) }
        }
        item {
            SwitchRow("Avoid tolls", null, Settings.avoidTolls) { Settings.updateAvoidTolls(it) }
        }
        item {
            SwitchRow("Avoid ferries", null, Settings.avoidFerries) { Settings.updateAvoidFerries(it) }
        }
        item {
            SwitchRow(
                "Avoid unpaved roads",
                "Turn off if you are on an adventure bike and want the gravel.",
                Settings.avoidUnpaved,
            ) { Settings.updateAvoidUnpaved(it) }
        }

        item { Section("Group rides") }
        item {
            OutlinedTextField(
                value = Settings.riderName,
                onValueChange = { Settings.updateRiderName(it.take(16)) },
                label = { Text("Your name") },
                supportingText = { Text("Shown to the rest of the group.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            SliderRow(
                title = "Lost rider alert",
                value = Settings.lostRiderThresholdM.toFloat(),
                range = 200f..8000f,
                display = "when someone is ${fmtDist(Settings.lostRiderThresholdM)} behind the leader",
            ) { Settings.updateLostRiderThreshold(it.toDouble()) }
        }

        item {
            var relayText by remember { mutableStateOf(Settings.relayUrl) }
            LaunchedEffect(relayText) {
                delay(600)
                if (relayText.trim().trimEnd('/') != Settings.relayUrl) {
                    Settings.updateRelayUrl(relayText)
                }
            }
            OutlinedTextField(
                value = relayText,
                // Normalising on every keystroke made the field unusable: trimEnd('/') ate the
                // slashes of "https://" as they were typed. Keep what the rider types, and
                // clean it up only once they stop.
                onValueChange = { relayText = it },
                label = { Text("Group relay server") },
                placeholder = { Text("Automatic", maxLines = 1) },
                supportingText = {
                    Text(
                        if (Settings.usingPublicRelay)
                            "Leave blank to use ${Settings.PUBLIC_RELAYS.size} free public " +
                                "relays together, so the ride survives one going down. Enter " +
                                "your own ntfy server to use only that — everyone in the group " +
                                "must set the same one."
                        else "Using only your relay. Everyone in the group must set this same " +
                            "address, or they will not see each other. Clear it to go back to " +
                            "the public relays.",
                        fontSize = 11.sp,
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item { Section("Safety") }
        item {
            SwitchRow(
                "Rider-down detection",
                "Alerts the group only — never sends an SMS or calls anyone.",
                Settings.fallDetection,
            ) { Settings.updateFallDetection(it) }
        }
        if (Settings.fallDetection) {
            item {
                SliderRow(
                    title = "Cancel window",
                    value = Settings.fallCountdownSec.toFloat(),
                    range = 10f..120f,
                    display = "${Settings.fallCountdownSec}s to tap I'M OK before the group is told",
                ) { Settings.updateFallCountdown(it.toInt()) }
            }
        }

        item { Section("About") }
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Cruze $versionName", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Routing by Valhalla (FOSSGIS). Maps © OpenStreetMap contributors, " +
                            "© CARTO, © OpenTopoMap, Esri. Search by Nominatim. Radar by " +
                            "RainViewer. Warnings by the US National Weather Service. Group " +
                            "position relay by public ntfy servers.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        "No accounts and no API keys. Nothing leaves this phone except the " +
                            "positions you share during a group ride, and those stop when the " +
                            "ride ends.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Section(title: String) =
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp),
    )

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = GloveTarget).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            subtitle?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChipRow(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (key, label) ->
                FilterChip(
                    selected = selected == key,
                    onClick = { onSelect(key) },
                    label = { Text(label, maxLines = 1, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        Text(display, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}