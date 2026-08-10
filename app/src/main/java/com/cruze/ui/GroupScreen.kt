package com.cruze.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.cruze.RideState
import com.cruze.distanceM
import com.cruze.fmtDist
import com.cruze.sync.AlertKind
import com.cruze.sync.GroupState
import com.cruze.sync.PRESET_MESSAGES
import com.cruze.sync.RiderPing
import com.cruze.sync.RiderRole
import com.cruze.sync.TransportKind
import com.cruze.sync.Wire
import com.cruze.sync.gapsToLeader
import com.cruze.sync.groupSpreadM
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** A stable, distinguishable colour per rider, derived from their id. */
fun riderColor(riderId: String): Color {
    val palette = listOf(
        0xFFFF3B30, 0xFF32D74B, 0xFF0A84FF, 0xFFFFD60A,
        0xFFBF5AF2, 0xFFFF9F0A, 0xFF64D2FF, 0xFFFF6482,
    )
    val idx = (riderId.hashCode().toLong() and 0xFFFFFFFFL).toInt() % palette.size
    return Color(palette[idx])
}

@Composable
fun GroupScreen(onShareRoute: () -> Unit) {
    val session by GroupState.session.collectAsStateWithLifecycle()
    if (session == null) StartOrJoin() else InRide(onShareRoute)
}

@Composable
private fun StartOrJoin() {
    var name by remember { mutableStateOf("") }
    var joining by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Ride together", style = MaterialTheme.typography.titleLarge)
        Text(
            "Everyone sees everyone on the map. Nobody gets left at a junction.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(16) },
            label = { Text("Your name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                scope.launch { GroupState.start(Wire.newJoinCode(), name.trim(), RiderRole.LEADER) }
            },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(GloveTarget).padding(top = 16.dp),
            shape = RoundedCornerShape(16.dp),
        ) { Text("Start a ride", fontSize = 17.sp) }

        Button(
            onClick = { joining = true },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(GloveTarget).padding(top = 10.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) { Text("Join with a code", fontSize = 17.sp) }

        Text(
            "Position sharing runs on a free public relay, so keep the code to your group and " +
                "it stops the moment the ride ends. No account, no personal data.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 24.dp),
        )
    }

    if (joining) {
        AlertDialog(
            onDismissRequest = { joining = false },
            title = { Text("Join a ride") },
            text = {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = Wire.normaliseCode(it).take(Wire.CODE_LENGTH) },
                    label = { Text("${Wire.CODE_LENGTH}-character code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch { GroupState.start(code, name.trim(), RiderRole.RIDER) }
                        joining = false
                    },
                    enabled = code.length == Wire.CODE_LENGTH,
                ) { Text("Join") }
            },
            dismissButton = { TextButton(onClick = { joining = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun InRide(onShareRoute: () -> Unit) {
    val session by GroupState.session.collectAsStateWithLifecycle()
    val roster by GroupState.roster.collectAsStateWithLifecycle()
    val status by GroupState.status.collectAsStateWithLifecycle()
    val alerts by GroupState.alerts.collectAsStateWithLifecycle()
    val fix by RideState.fix.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val s = session ?: return

    val everyone = remember(roster, fix) {
        val me = fix?.let {
            RiderPing(s.riderId, "${s.name} (you)", it.pos, it.bearing, it.speedMps, -1,
                System.currentTimeMillis(), s.role)
        }
        roster + listOfNotNull(me)
    }
    val spread = groupSpreadM(everyone.map { it.pos })
    val gaps = gapsToLeader(everyone, everyone.firstOrNull { it.role == RiderRole.LEADER }?.riderId)

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }

        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Join code", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Text(
                        s.joinCode,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 6.sp,
                    )
                    qrBitmap(s.joinCode)?.let {
                        Image(
                            it.asImageBitmap(), "Join QR code",
                            Modifier.padding(top = 12.dp).size(160.dp),
                        )
                    }
                    TransportChip(status.kind, status.connected, status.queued)
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                GroupStat("${everyone.size}", "riders")
                GroupStat(fmtDist(spread), "spread")
                GroupStat("${gaps.count { it.lost }}", "behind")
            }
        }

        items(everyone) { r ->
            val gap = gaps.firstOrNull { it.ping.riderId == r.riderId }
            RiderRow(r, gap?.metresBehindLeader, gap?.lost == true, fix?.pos?.let { distanceM(it, r.pos) })
        }

        item {
            Text("Say something", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp))
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PRESET_MESSAGES.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { msg ->
                            Button(
                                onClick = { GroupState.sendPreset(msg) },
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                            ) { Text(msg, fontSize = 14.sp, maxLines = 1) }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        item {
            Button(
                onClick = { GroupState.sendAlert(AlertKind.SOS) },
                modifier = Modifier.fillMaxWidth().height(GloveTarget).padding(top = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = Color.White,
                ),
            ) { Text("SOS — alert the group", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        }

        if (alerts.isNotEmpty()) {
            item { Text("Alerts", style = MaterialTheme.typography.titleMedium) }
            items(alerts.reversed()) { a ->
                Card(
                    Modifier.fillMaxWidth().clickable { GroupState.dismissAlert(a) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${a.name}: ${a.kind.name.replace('_', ' ').lowercase()}",
                            fontWeight = FontWeight.Bold)
                        Text("Tap to dismiss", fontSize = 12.sp)
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (s.role == RiderRole.LEADER) {
                    Button(
                        onClick = onShareRoute,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) { Text("Push my route", maxLines = 1) }
                }
                Button(
                    onClick = { scope.launch { GroupState.stop() } },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Leave ride", maxLines = 1) }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun RiderRow(r: RiderPing, behindLeaderM: Double?, lost: Boolean, fromMeM: Double?) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).background(riderColor(r.riderId), CircleShape))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (r.role != RiderRole.RIDER) {
                    Text(
                        "  ${r.role.name.lowercase()}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                buildString {
                    fromMeM?.let { append("${fmtDist(it)} from you") }
                    behindLeaderM?.takeIf { it > 1 }?.let {
                        if (isNotEmpty()) append(" · ")
                        append("${fmtDist(it)} off leader")
                    }
                    if (r.batteryPct in 0..100) {
                        if (isNotEmpty()) append(" · ")
                        append("${r.batteryPct}%")
                    }
                },
                fontSize = 12.sp,
                color = if (lost) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (lost) {
            Text("BEHIND", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Shows which pipe the group is really using — invaluable when debugging a dropout later. */
@Composable
fun TransportChip(kind: TransportKind, connected: Boolean, queued: Int) {
    val color = when {
        !connected -> MaterialTheme.colorScheme.error
        kind == TransportKind.PEER -> Color(0xFF32D74B)
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.padding(top = 12.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Text(
                if (queued > 0) "${kind.label} · $queued queued" else kind.label,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun GroupStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp,
            color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Renders the join code as a QR so a pillion can scan it without typing at a petrol stop. */
private fun qrBitmap(code: String, size: Int = 320): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode("cruze://join/$code", BarcodeFormat.QR_CODE, size, size)
    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until size) {
            for (y in 0 until size) {
                setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
    }
}.getOrNull()
