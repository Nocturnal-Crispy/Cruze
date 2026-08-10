package com.cruze

import android.content.Intent
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.cruze.speedUnit
import com.cruze.data.SavedTrack
import com.cruze.garage.GarageViewModel
import com.cruze.nav.LocationSource
import com.cruze.service.RideService
import com.cruze.ui.AppViewModel
import com.cruze.ui.CruzeTheme
import com.cruze.sync.GroupState
import com.cruze.sync.Wire
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
        handleJoinLink(intent)
        setContent {
            CruzeTheme(dark = Settings.darkTheme) {
                App(onPermissionGranted = { location.start() }, onServiceIdle = { location.start() })
            }
        }
    }

    // singleTop, so a scan while the app is already open arrives here rather than in onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleJoinLink(intent)
    }

    /**
     * A scanned join QR carries the leader's relay as well as the code. Riders on different
     * relays are invisible to each other, so the relay is applied before the join — that is the
     * whole reason it travels in the link.
     */
    private fun handleJoinLink(intent: Intent?) {
        val link = Wire.parseJoinLink(intent?.dataString.orEmpty()) ?: return
        if (link.relay.isNotBlank() && link.relay != Settings.relayUrl) {
            Settings.updateRelayUrl(link.relay)
        }
        GroupState.offerJoinLink(link)
    }

    override fun onResume() {
        super.onResume()
        applyKeepScreenOn()
        // The map needs to know where the rider is even when no ride is running.
        location.start()
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        location.stop()
    }

    /**
     * A rider should never have the screen time out mid-corner — and turning the setting off
     * should hand control back straight away. This used to run only in onResume, so the switch
     * appeared to do nothing until the app had been backgrounded and reopened.
     */
    fun applyKeepScreenOn() {
        if (Settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
    onServiceIdle: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val navigating by RideState.navigating.collectAsStateWithLifecycle()
    val recording by RideState.recording.collectAsStateWithLifecycle()
    val status by RideState.status.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(Tab.MAP) }
    var hasLocation by remember { mutableStateOf(ctx.hasLocationPermission()) }
    // What to write once the rider picks a destination. The activity handles its own config
    // changes, so this survives rotation and folding; it cannot survive the process being
    // killed while the picker is open, and a lambda is not something rememberSaveable can
    // restore. In that case the export is simply announced as lost rather than silently
    // producing an empty file the rider only discovers later.
    var pendingWrite by remember { mutableStateOf<((Uri) -> Unit)?>(null) }

    // Whether the system dialog has been shown once already. After two refusals Android stops
    // showing it at all and auto-denies, so a second tap of "Grant" would do nothing visible —
    // at that point the only way through is the app's own settings page.
    var askedOnce by rememberSaveable { mutableStateOf(false) }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasLocation = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            ctx.hasLocationPermission()
        askedOnce = true
        if (hasLocation) onPermissionGranted()
    }

    fun askForPermissions() {
        permissions.launch(
            buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                // Requested alongside location rather than only when location is missing.
                // Bundled inside that branch, a rider who granted location on the first run
                // could never be asked for notifications at all — and the ride notification is
                // the only way to get back to guidance from another app.
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray()
        )
    }

    LaunchedEffect(Unit) {
        if (!hasLocation || (Build.VERSION.SDK_INT >= 33 && !ctx.hasNotificationPermission())) {
            askForPermissions()
        }
    }

    // Coming back from the system settings page, pick up whatever was granted there.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.addObserver(
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    val now = ctx.hasLocationPermission()
                    if (now && !hasLocation) onPermissionGranted()
                    hasLocation = now
                }
            }
        )
    }

    // Starting or leaving a group starts or stops the service that broadcasts position.
    LaunchedEffect(Unit) {
        GroupState.onSessionChanged = { active ->
            if (active) {
                RideService.send(ctx, RideService.ACTION_START_GROUP)
            } else if (!navigating && !recording) {
                RideService.send(ctx, RideService.ACTION_STOP_ALL)
                // The service was the only thing feeding the map a position; hand GPS back to
                // the foreground source, or the blue dot freezes until the app is reopened.
                onServiceIdle()
            }
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
    ) { uri ->
        val writer = pendingWrite
        pendingWrite = null
        if (uri == null) return@rememberLauncherForActivityResult
        if (writer == null) vm.message = "Export was interrupted. Try it again."
        else writer(uri)
    }

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
            if (!hasLocation) PermissionPrompt(askedOnce) { askForPermissions() }
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

/**
 * Shown whenever location is missing. Nothing in the app works without it — no map centring,
 * no guidance, no group position — so this is a banner over every screen rather than a page
 * the rider has to go and find.
 *
 * [askedOnce] changes what the button can usefully do: Android stops showing its dialog after
 * two refusals and silently auto-denies, so once we have already asked, the only route through
 * is the app's own settings page.
 */
@Composable
private fun PermissionPrompt(askedOnce: Boolean, onGrant: () -> Unit) {
    val ctx = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Cruze needs location access to plan from where you are, guide you, record " +
                    "rides, and show you to your group.",
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(
                onClick = {
                    if (askedOnce) {
                        ctx.startActivity(
                            Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", ctx.packageName, null),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } else {
                        onGrant()
                    }
                },
                modifier = Modifier.padding(top = 10.dp).height(GloveTarget),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(if (askedOnce) "Open app settings" else "Grant location access")
            }
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
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun android.content.Context.hasNotificationPermission() =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private fun appVersion(ctx: android.content.Context): String = runCatching {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.1"
}.getOrDefault("0.1")

private fun String.safe() = replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "cruze" }
