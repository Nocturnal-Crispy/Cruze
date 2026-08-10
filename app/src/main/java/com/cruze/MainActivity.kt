package com.cruze

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cruze.Settings
import com.cruze.data.SavedTrack
import com.cruze.garage.GarageViewModel
import com.cruze.nav.LocationSource
import com.cruze.service.RideService
import com.cruze.ui.AppViewModel
import com.cruze.ui.CruzeTheme
import com.cruze.sync.GroupState
import com.cruze.ui.FallCountdownOverlay
import com.cruze.ui.GarageScreen
import com.cruze.ui.GroupScreen
import com.cruze.ui.GroupToast
import com.cruze.ui.GloveTarget
import com.cruze.ui.NavScreen
import com.cruze.ui.PlanScreen
import com.cruze.ui.RidesScreen
import com.cruze.ui.SettingsScreen
import com.cruze.ui.initOsmdroid

class MainActivity : ComponentActivity() {

    private lateinit var location: LocationSource

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        initOsmdroid(this)
        location = LocationSource(this)
        Settings.load(this)
        setContent {
            CruzeTheme(dark = Settings.darkTheme) {
                App(onPermissionGranted = { location.start() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A rider should never have the screen time out mid-corner.
        if (Settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The map needs to know where the rider is even when no ride is running.
        location.start()
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        location.stop()
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    MAP("Map", Icons.Default.Map),
    RIDE("Ride", Icons.Default.PlayArrow),
    GROUP("Group", Icons.Default.Group),
    RIDES("Rides", Icons.Default.Route),
    GARAGE("Garage", Icons.Default.DirectionsBike),
    SETTINGS("Settings", Icons.Default.Settings),
}

@Composable
private fun App(
    vm: AppViewModel = viewModel(),
    garage: GarageViewModel = viewModel(),
    onPermissionGranted: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val navigating by RideState.navigating.collectAsStateWithLifecycle()
    val recording by RideState.recording.collectAsStateWithLifecycle()
    val status by RideState.status.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(Tab.MAP) }
    var hasLocation by remember { mutableStateOf(ctx.hasLocationPermission()) }
    var pendingWrite by remember { mutableStateOf<((Uri) -> Unit)?>(null) }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasLocation = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ctx.hasLocationPermission()
        if (hasLocation) onPermissionGranted()
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

    // Starting or leaving a group starts or stops the service that broadcasts position.
    LaunchedEffect(Unit) {
        GroupState.onSessionChanged = { active ->
            if (active) RideService.send(ctx, RideService.ACTION_START_GROUP)
            else if (!navigating && !recording) RideService.send(ctx, RideService.ACTION_STOP_ALL)
        }
    }

    // Tell the rider why a join failed or a ride stopped, rather than silently dropping them.
    val joinState by GroupState.joinState.collectAsStateWithLifecycle()
    LaunchedEffect(joinState) {
        if (joinState == GroupState.JoinState.NOT_FOUND) {
            snackbar.showSnackbar("No ride found with that code. Check it, or ask the leader to start theirs first.")
        }
    }
    val endedBy by GroupState.endedBy.collectAsStateWithLifecycle()
    LaunchedEffect(endedBy) {
        endedBy?.let {
            snackbar.showSnackbar("$it ended the ride.")
            GroupState.consumeEnded()
        }
    }

    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }
    LaunchedEffect(garage.message) { garage.message?.let { snackbar.showSnackbar(it); garage.message = null } }
    LaunchedEffect(status) {
        if (status.isNotBlank()) { snackbar.showSnackbar(status); RideState.setStatus("") }
    }

    // Jump straight to the riding view whenever guidance starts.
    LaunchedEffect(navigating) { if (navigating) tab = Tab.RIDE }

    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let { pendingWrite?.invoke(it) }; pendingWrite = null }

    val openGpx = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importGpx(ctx, it) }
    }

    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()?.let { garage.restoreFrom(it) }
            ?: run { garage.message = "Could not read that file." }
    }

    fun saveAs(name: String, writer: (Uri) -> Unit) {
        pendingWrite = writer
        createFile.launch(name)
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = NavigationBarDefaults.Elevation,
            ) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label, fontSize = 10.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                        ),
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
                Tab.MAP -> PlanScreen(
                    vm = vm,
                    onStartNavigation = { vm.startNavigation(ctx) },
                    onExport = { saveAs("cruze-route.gpx") { uri -> vm.exportCurrentPlan(ctx, uri) } },
                    onImport = {
                        openGpx.launch(
                            arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*")
                        )
                    },
                )

                Tab.RIDE -> if (navigating) {
                    NavScreen(vm) { vm.stopNavigation(ctx) }
                } else {
                    RideIdle(
                        recording = recording,
                        onStartRecording = { vm.startRecording(ctx) },
                        // A finished ride adds its miles to the bike it was ridden on.
                        onStopRecording = {
                            vm.stopRecordingAndSave(ctx) { metres -> garage.addRideDistance(metres) }
                        },
                        onPlan = { tab = Tab.MAP },
                    )
                }

                Tab.GROUP -> GroupScreen(onShareRoute = {
                    val p = vm.plan
                    if (p == null) {
                        vm.message = "Plan a route first, then push it to the group."
                    } else {
                        GroupState.shareRoute(p)
                        vm.message = "Route pushed to the group."
                    }
                })

                Tab.RIDES -> RidesScreen(vm) { t: SavedTrack ->
                    saveAs("${t.name.safe()}.gpx") { uri -> vm.exportTrack(ctx, t, uri) }
                }

                Tab.GARAGE -> GarageScreen(
                    vm = garage,
                    onExportCsv = {
                        saveAs("cruze-fuel-log.csv") { uri ->
                            writeText(ctx, uri, garage.fuelCsv(),
                                { garage.message = "Fuel log exported." },
                                { garage.message = "Export failed: $it" })
                        }
                    },
                    onBackup = {
                        saveAs("cruze-garage-backup.json") { uri ->
                            writeText(ctx, uri, garage.backupJson(),
                                { garage.message = "Garage backed up." },
                                { garage.message = "Backup failed: $it" })
                        }
                    },
                    onRestore = { openBackup.launch(arrayOf("application/json", "text/plain", "*/*")) },
                )

                Tab.SETTINGS -> SettingsScreen(versionName = appVersion(ctx))
            }
        }
    }

    // Group chatter appears over the map and ride views, never over the group screen itself
    // (it is already listed there) and never over a fall countdown.
    if (tab == Tab.MAP || tab == Tab.RIDE) GroupToast()

    // Last child wins the z-order: a fall alert must cover every screen and the nav bar.
    FallCountdownOverlay()
    }
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Text("Cruze needs location access to plan from where you are, guide you, and record rides.")
        Button(onClick = onGrant, modifier = Modifier.padding(top = 8.dp)) {
            Text("Grant location access")
        }
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
    val fix by RideState.fix.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (recording) {
            Text(
                fmtDist(trackDistanceM(track)),
                fontSize = 52.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("recording · ${track.size} points", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${fmtSpeed(fix?.speedMps ?: 0f)} mph",
                fontSize = 20.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            Text("Ready to ride", style = MaterialTheme.typography.titleLarge)
            Text(
                "Record a ride on its own, or plan a route and get voice guidance.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Button(
            onClick = { if (recording) onStopRecording() else onStartRecording() },
            modifier = Modifier.fillMaxWidth().padding(top = 28.dp).height(GloveTarget),
            shape = RoundedCornerShape(16.dp),
            colors = if (recording) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else ButtonDefaults.buttonColors(),
        ) { Text(if (recording) "Stop and save ride" else "Start recording", fontSize = 17.sp) }

        if (!recording) {
            Button(
                onClick = onPlan,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(GloveTarget),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) { Text("Plan a route", fontSize = 17.sp) }
        }
    }
}

private fun writeText(
    ctx: android.content.Context,
    uri: Uri,
    text: String,
    onOk: () -> Unit,
    onErr: (String) -> Unit,
) {
    runCatching {
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            ?: error("could not open the file")
    }.onSuccess { onOk() }.onFailure { onErr(it.message ?: "unknown error") }
}

private fun android.content.Context.hasLocationPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun appVersion(ctx: android.content.Context): String = runCatching {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.1"
}.getOrDefault("0.1")

private fun String.safe() = replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "cruze" }
