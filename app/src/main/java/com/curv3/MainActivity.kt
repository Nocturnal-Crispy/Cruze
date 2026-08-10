package com.curv3

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.curv3.data.SavedRoute
import com.curv3.data.SavedTrack
import com.curv3.service.RideService
import com.curv3.ui.AppViewModel
import com.curv3.ui.NavScreen
import com.curv3.ui.PlanScreen
import com.curv3.ui.RidesScreen
import com.curv3.ui.initOsmdroid

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        initOsmdroid(this)
        setContent { Curv3Theme { App() } }
    }

    override fun onResume() {
        super.onResume()
        // A rider should never have the screen time out mid-corner.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
private fun Curv3Theme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

private enum class Tab(val label: String) { PLAN("Plan"), RIDE("Ride"), LIBRARY("Rides") }

@Composable
private fun App(vm: AppViewModel = viewModel()) {
    val ctx = LocalContext.current
    val navigating by RideState.navigating.collectAsStateWithLifecycle()
    val recording by RideState.recording.collectAsStateWithLifecycle()
    val status by RideState.status.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(Tab.PLAN) }
    var hasLocation by remember { mutableStateOf(ctx.hasLocationPermission()) }
    var pendingExport by remember { mutableStateOf<((Uri) -> Unit)?>(null) }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasLocation = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ctx.hasLocationPermission()
    }

    LaunchedEffect(Unit) {
        if (!hasLocation) {
            permissions.launch(
                buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }.toTypedArray()
            )
        }
    }

    // Surface one-off messages from either the view model or the service.
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }
    LaunchedEffect(status) { if (status.isNotBlank()) { snackbar.showSnackbar(status); RideState.setStatus("") } }

    // Jump straight to the riding view whenever guidance starts.
    LaunchedEffect(navigating) { if (navigating) tab = Tab.RIDE }

    val createGpx = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri -> uri?.let { pendingExport?.invoke(it) }; pendingExport = null }

    val openGpx = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importGpx(ctx, it) }
    }

    fun export(name: String, writer: (Uri) -> Unit) {
        pendingExport = writer
        createGpx.launch("$name.gpx")
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.PLAN -> Icons.Default.Map
                                    Tab.RIDE -> Icons.Default.FiberManualRecord
                                    Tab.LIBRARY -> Icons.Default.Menu
                                },
                                contentDescription = t.label,
                            )
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (!hasLocation) {
                PermissionPrompt {
                    permissions.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
                }
            }
            when (tab) {
                Tab.PLAN -> PlanScreen(
                    vm = vm,
                    onStartNavigation = { vm.startNavigation(ctx) },
                    onExport = { export("curv3-route") { uri -> vm.exportCurrentPlan(ctx, uri) } },
                    onImport = { openGpx.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*")) },
                )

                Tab.RIDE -> if (navigating) {
                    NavScreen(vm) { vm.stopNavigation(ctx) }
                } else {
                    RideIdle(
                        recording = recording,
                        onStartRecording = { vm.startRecording(ctx) },
                        onStopRecording = { vm.stopRecordingAndSave(ctx) },
                        onPlan = { tab = Tab.PLAN },
                    )
                }

                Tab.LIBRARY -> RidesScreen(
                    vm = vm,
                    onOpenRoute = { r: SavedRoute -> vm.openRoute(r); tab = Tab.PLAN },
                    onExportRoute = { r: SavedRoute -> export(r.name.safe()) { uri -> vm.exportRoute(ctx, r, uri) } },
                    onExportTrack = { t: SavedTrack -> export(t.name.safe()) { uri -> vm.exportTrack(ctx, t, uri) } },
                )
            }
        }
    }
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Text("Curv3 needs location access to plan from where you are, guide you, and record rides.")
        Button(onClick = onGrant, modifier = Modifier.padding(top = 8.dp)) { Text("Grant location access") }
    }
}

@Composable
private fun RideIdle(
    recording: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onPlan: () -> Unit,
) {
    val track by RideState.track.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("No navigation running.", style = MaterialTheme.typography.titleMedium)
        Button(onClick = onPlan, modifier = Modifier.padding(top = 12.dp)) { Text("Plan a route") }

        Text(
            if (recording) "Recording: ${fmtDist(trackDistanceM(track))} over ${track.size} points"
            else "Record a ride without a route — just go and log the track.",
            modifier = Modifier.padding(top = 28.dp),
        )
        Button(
            onClick = { if (recording) onStopRecording() else onStartRecording() },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text(if (recording) "Stop and save ride" else "Start recording") }
    }
}

private fun android.content.Context.hasLocationPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun String.safe() = replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "curv3" }

@Suppress("unused")
private val keepServiceImport = RideService::class
