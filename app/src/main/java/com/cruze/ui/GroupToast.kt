package com.cruze.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruze.sync.GroupState
import kotlinx.coroutines.delay

private data class Toast(val riderId: String, val who: String, val what: String, val urgent: Boolean)

/**
 * A brief banner for whatever the group just said, over whichever screen the rider is on.
 *
 * It is deliberately secondary to the spoken version: a rider should never need to look at the
 * screen to know what was said, so this only confirms what they already heard and disappears
 * on its own. Alerts stay up longer and are tinted, because those are worth a glance.
 */
@Composable
fun BoxScope.GroupToast() {
    val messages by GroupState.messages.collectAsStateWithLifecycle()
    val alerts by GroupState.alerts.collectAsStateWithLifecycle()

    var shown by remember { mutableStateOf<Toast?>(null) }
    var lastMessageAt by remember { mutableStateOf(0L) }
    var lastAlertAt by remember { mutableStateOf(0L) }

    LaunchedEffect(messages) {
        val m = messages.lastOrNull() ?: return@LaunchedEffect
        if (m.atMs <= lastMessageAt) return@LaunchedEffect
        lastMessageAt = m.atMs
        shown = Toast(m.riderId, m.name, m.message, urgent = false)
        delay(5000)
        if (shown?.what == m.message) shown = null
    }

    LaunchedEffect(alerts) {
        val a = alerts.lastOrNull() ?: return@LaunchedEffect
        if (a.atMs <= lastAlertAt) return@LaunchedEffect
        lastAlertAt = a.atMs
        shown = Toast(
            a.riderId, a.name,
            a.kind.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
            urgent = true,
        )
        delay(12_000)
        if (shown?.urgent == true) shown = null
    }

    AnimatedVisibility(
        visible = shown != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        // statusBarsPadding keeps it clear of the clock and signal icons: this sits outside
        // the Scaffold, so it is not inset for the system bars by anything else.
        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
    ) {
        val t = shown ?: return@AnimatedVisibility
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = if (t.urgent) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .heightIn(min = 96.dp)
                .clickable { shown = null },
        ) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(22.dp).background(riderColor(t.riderId), CircleShape))
                Column(Modifier.padding(start = 16.dp)) {
                    Text(
                        t.who.uppercase(),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        color = if (t.urgent) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        t.what,
                        fontWeight = FontWeight.Bold,
                        fontSize = 30.sp,
                        lineHeight = 34.sp,
                        color = if (t.urgent) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}
