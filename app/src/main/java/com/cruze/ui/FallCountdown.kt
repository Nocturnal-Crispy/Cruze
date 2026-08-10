package com.cruze.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruze.sync.GroupState
import kotlinx.coroutines.delay

/**
 * Shown when the detector thinks the rider went down.
 *
 * Deliberately impossible to miss and trivial to dismiss: the whole screen, one button that
 * fills the bottom third, and a vibration pattern that carries through gloves and a jacket.
 * A false positive must cost one tap, never an embarrassing call to a friend.
 */
@Composable
fun FallCountdownOverlay() {
    val deadline by GroupState.fallDeadline.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val target = deadline ?: return

    var secondsLeft by remember { mutableIntStateOf(30) }

    LaunchedEffect(target) {
        while (true) {
            val remaining = ((target - System.currentTimeMillis()) / 1000).toInt()
            secondsLeft = remaining.coerceAtLeast(0)
            if (remaining <= 0) break
            vibrate(ctx)
            delay(1000)
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xF2B00020)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Are you OK?",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Text(
                "Your group will be told you may be down.",
                fontSize = 18.sp,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "$secondsLeft",
                fontSize = 140.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Button(
                onClick = { GroupState.cancelFallCountdown() },
                modifier = Modifier.fillMaxWidth().height(140.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFFB00020),
                ),
            ) { Text("I'M OK", fontSize = 44.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Suppress("DEPRECATION")
private fun vibrate(ctx: android.content.Context) {
    val v = ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        ?: return
    runCatching {
        v.vibrate(android.os.VibrationEffect.createOneShot(400, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
