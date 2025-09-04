package co.uk.doverguitarteacher.activpal.screens

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.navigation.NavHostController
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.EXTRA_STOPPED
import android.content.Context.RECEIVER_NOT_EXPORTED
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.ACTION_DISCARD
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.ACTION_PAUSE
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.ACTION_RESUME
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.ACTION_START_SIM
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.EXTRA_LAT
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.EXTRA_LNG
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.EXTRA_ROLLING_PACE_S_PER_KM
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.Polyline
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import kotlin.math.*
import android.util.Log
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.EXTRA_SIMULATING
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(navController: NavHostController) {
    val context = LocalContext.current

    // UI state updated from the foreground service via broadcasts
    var isRecording by remember { mutableStateOf(false) }
    var distanceMeters by remember { mutableStateOf(0f) }
    var elapsedMs by remember { mutableStateOf(0L) }
    // local start time used to compute elapsed if service doesn't provide it continuously
    var recordingStartMs by remember { mutableStateOf(0L) }
    var paceSecPerKm by remember { mutableStateOf(Float.POSITIVE_INFINITY) }
    var rollingPaceSecPerKm by remember { mutableStateOf(Float.NaN) }
    var lastLatLng by remember { mutableStateOf<LatLng?>(null) }
    var lastAccuracy by remember { mutableStateOf<Float?>(null) }
    var lastSpeedMps by remember { mutableStateOf<Float?>(null) }
    val trackPoints = remember { mutableStateListOf<LatLng>() }
    var isPaused by remember { mutableStateOf(false) }
    var isSimulating by remember { mutableStateOf(false) }
    var followCamera by remember { mutableStateOf(true) }

    // Permission launcher
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { perms ->
            val fine = perms[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarse = perms[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
            // Background and notification permissions may not be granted automatically; still attempt to start when fine/coarse exists
            if (fine || coarse) {
                // start the service
                startRecordingService(context)
                isRecording = true
            }
        }
    )

    // Background location permission launcher (requested separately on Android Q+)
    val bgLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (granted) {
            startRecordingService(context)
            isRecording = true
        }
        // If user denies, we still start if foreground location is available (optional)
    }

    // Notification permission launcher (Android 13+)
    val notifLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        // we don't strictly require notification permission to start the service, but it's recommended
    }

    // BroadcastReceiver to consume updates from the foreground service
    DisposableEffect(Unit) {
        val filter = IntentFilter(ForegroundLocationService.ACTION_UPDATE)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent ?: return

                // Service-provided values (may be missing or stale)
                val providedDistance = intent.getFloatExtra(ForegroundLocationService.EXTRA_DISTANCE_METERS, -1f)
                val providedElapsedMs = intent.getLongExtra(ForegroundLocationService.EXTRA_ELAPSED_MS, -1L)
                val providedPace = intent.getFloatExtra(ForegroundLocationService.EXTRA_PACE_S_PER_KM, Float.POSITIVE_INFINITY)
                val providedRolling = intent.getFloatExtra(EXTRA_ROLLING_PACE_S_PER_KM, Float.NaN)

                // Location and optional accuracy
                val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
                val lng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN)
                val accuracy = intent.getFloatExtra("EXTRA_ACCURACY", Float.NaN)

                // Keep a copy of previous distance to compute local increment
                val prevDistance = distanceMeters
                var computedDistance = prevDistance

                // Accept only reasonably accurate fixes (match service threshold)
                val MAX_ACCEPTABLE_ACCURACY_METERS = 100f

                if (!lat.isNaN() && !lng.isNaN()) {
                    val p = LatLng(lat, lng)
                    if (accuracy.isNaN() || accuracy <= MAX_ACCEPTABLE_ACCURACY_METERS) {
                        val last = lastLatLng
                        if (last != null) {
                            val inc = distanceBetween(last, p)
                            // lowered threshold to 1m so short indoor movement / GPS drift is reflected
                            if (inc >= 1.0f) computedDistance += inc
                        }
                        lastLatLng = p
                        lastAccuracy = if (accuracy.isNaN()) null else accuracy
                        // read speed using the service constant key
                        lastSpeedMps = intent.getFloatExtra(ForegroundLocationService.EXTRA_SPEED_M_S, Float.NaN).let { if (it.isFinite()) it else null }
                        trackPoints.add(p)
                    } else {
                        // noisy fix: ignore adding to track
                    }
                }

                // If we added points but the incremental threshold prevented accumulation, compute full cumulative distance as fallback
                if (computedDistance == prevDistance && trackPoints.size >= 2) {
                    var sum = 0f
                    for (i in 1 until trackPoints.size) {
                        sum += distanceBetween(trackPoints[i - 1], trackPoints[i])
                    }
                    // Only accept sum if it's meaningfully larger than prevDistance (to avoid regressions)
                    if (sum > prevDistance + 1.0f) {
                        computedDistance = sum
                    }
                }

                // Prefer valid service-provided distance; otherwise use computed fallback.
                distanceMeters = if (providedDistance >= 0f) {
                    // trust service distance (already filtered) and ensure we don't regress
                    if (providedDistance < computedDistance) computedDistance else providedDistance
                } else {
                    computedDistance
                }

                // Update elapsed time: prefer service value if present; otherwise set recordingStartMs so local timer drives UI
                if (providedElapsedMs > 0L) {
                    elapsedMs = providedElapsedMs
                    recordingStartMs = System.currentTimeMillis() - providedElapsedMs
                } else {
                    if (isRecording && recordingStartMs == 0L) {
                        // initialize local start time if not set
                        recordingStartMs = System.currentTimeMillis() - elapsedMs
                    }
                }

                // Pace: prefer service-provided pace, otherwise derive from elapsed + distance
                paceSecPerKm = if (providedPace.isFinite() && !providedPace.isNaN()) {
                    providedPace
                } else {
                    if (distanceMeters > 0f && elapsedMs > 0L) {
                        ((elapsedMs.toDouble() / 1000.0) / (distanceMeters.toDouble() / 1000.0)).toFloat()
                    } else {
                        Float.POSITIVE_INFINITY
                    }
                }

                rollingPaceSecPerKm = providedRolling

                // Simulation flag if present
                isSimulating = intent.getBooleanExtra(EXTRA_SIMULATING, false)

                // Log debug state
                Log.d(
                    "RecordScreen",
                    "RX update: pts=${trackPoints.size} dist=${String.format(Locale.US, "%.1f", distanceMeters)}m lat=${lastLatLng?.latitude} lng=${lastLatLng?.longitude} sim=${isSimulating} paused=${isPaused}"
                )

                if (intent.getBooleanExtra(EXTRA_STOPPED, false)) {
                    isRecording = false
                    isPaused = false
                    isSimulating = false
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }

    // Maintain a local elapsedMs based on recordingStartMs so UI updates smoothly
    LaunchedEffect(isRecording, recordingStartMs, isPaused) {
        while (isRecording && !isPaused) {
            val now = System.currentTimeMillis()
            if (recordingStartMs > 0L) {
                elapsedMs = now - recordingStartMs
            }
            delay(500L)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Record Activity") },
                // This adds a back button to the top bar
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            MapSection(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = 16.dp),
                points = trackPoints.toList(),
                latest = lastLatLng,
                follow = followCamera,
                distanceMeters = distanceMeters,
                isSim = isSimulating
            )

            // Placeholder for stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatText(title = "Distance", value = formatDistance(distanceMeters))
                StatText(title = "Time", value = formatElapsed(elapsedMs))
                StatText(title = "Pace", value = formatPace(paceSecPerKm))
                StatText(title = "Recent", value = formatPace(rollingPaceSecPerKm))
                StatText(title = "Speed", value = if (lastSpeedMps != null) String.format(Locale.US, "%.1f m/s", lastSpeedMps) else "--")
                StatText(title = "Acc", value = if (lastAccuracy != null) String.format(Locale.US, "%.1f m", lastAccuracy) else "--")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // --- All Controls Grouped Here ---
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Row for Start/Stop/Pause/Discard
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (!isRecording) {
                                val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                if (fine || coarse) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        val bgGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
                                        if (!bgGranted) {
                                            bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                            return@Button
                                        }
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        val notifGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                                        if (!notifGranted) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    // Reset runtime state for a fresh recording
                                    trackPoints.clear()
                                    lastLatLng = null
                                    lastAccuracy = null
                                    lastSpeedMps = null
                                    distanceMeters = 0f
                                    elapsedMs = 0L
                                    recordingStartMs = System.currentTimeMillis()
                                    paceSecPerKm = Float.POSITIVE_INFINITY
                                    rollingPaceSecPerKm = Float.NaN
                                    startRecordingService(context)
                                    isRecording = true
                                    isPaused = false
                                } else {
                                    launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                }
                            } else {
                                stopRecordingService(context)
                                isRecording = false
                                isPaused = false
                            }
                        }, modifier = Modifier.weight(1f)
                    ) { Text(if (isRecording) "Stop" else "Start") }

                    Button(
                        enabled = isRecording,
                        onClick = {
                            if (isPaused) {
                                sendServiceAction(context, ACTION_RESUME)
                                isPaused = false
                            } else {
                                sendServiceAction(context, ACTION_PAUSE)
                                isPaused = true
                            }
                        }, modifier = Modifier.weight(1f)
                    ) { Text(if (isPaused) "Resume" else "Pause") }

                    Button(
                        enabled = isRecording,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            // Discard: send discard action which prevents persistence
                            sendServiceAction(context, ACTION_DISCARD)
                            isRecording = false
                            isPaused = false
                            trackPoints.clear()
                            distanceMeters = 0f
                            elapsedMs = 0L
                            paceSecPerKm = Float.POSITIVE_INFINITY
                            rollingPaceSecPerKm = Float.NaN
                        }, modifier = Modifier.weight(1f)
                    ) { Text("Discard") }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Follow toggle
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = followCamera, onCheckedChange = { followCamera = it })
                    Text("Follow map")
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Always-available debug buttons: simulate service route and draw a quick test route
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            Log.d("RecordScreen", "Simulate button clicked")
                            // Reset runtime state then start simulation service action
                            trackPoints.clear()
                            lastLatLng = null
                            lastAccuracy = null
                            lastSpeedMps = null
                            distanceMeters = 0f
                            elapsedMs = 0L
                            recordingStartMs = System.currentTimeMillis()
                            paceSecPerKm = Float.POSITIVE_INFINITY
                            rollingPaceSecPerKm = Float.NaN
                            followCamera = true
                            sendServiceAction(context, ACTION_START_SIM)
                            isRecording = true
                            isPaused = false
                            isSimulating = true
                        }, modifier = Modifier.weight(1f)
                    ) { Text("Simulate") }

                    Button(
                        onClick = {
                            // Instant fake route (no service) to verify polyline rendering quickly
                            trackPoints.clear()
                            val baseLat = 51.1640514
                            val baseLng = 1.2890638
                            val deltas = listOf(
                                0.0 to 0.0,
                                0.00010 to 0.00000,
                                0.00020 to 0.00005,
                                0.00030 to 0.00010,
                                0.00040 to 0.00015,
                                0.00050 to 0.00020,
                                0.00060 to 0.00010,
                                0.00070 to 0.00000,
                                0.00080 to -0.00005,
                                0.00090 to -0.00010,
                                0.00100 to -0.00015,
                                0.00110 to -0.00010,
                                0.00120 to 0.00000
                            )
                            deltas.forEach { (dLat, dLng) ->
                                trackPoints.add(LatLng(baseLat + dLat, baseLng + dLng))
                            }
                            // Update last point & distance
                            lastLatLng = trackPoints.lastOrNull()
                            var dist = 0f
                            for (i in 1 until trackPoints.size) {
                                dist += distanceBetween(trackPoints[i - 1], trackPoints[i])
                            }
                            distanceMeters = dist
                            elapsedMs = 0L
                            recordingStartMs = 0L
                            paceSecPerKm = Float.POSITIVE_INFINITY
                            rollingPaceSecPerKm = Float.NaN
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) { Text("Draw Test Route") }
                }
            }
        }
    }
}

@Composable
fun StatText(title: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = title.uppercase(), fontSize = 14.sp)
        Text(text = value, fontSize = 28.sp)
    }
}

// Helper functions (non-composable)
private fun startRecordingService(context: Context) {
    val intent = Intent(context, ForegroundLocationService::class.java).apply { action = ForegroundLocationService.ACTION_START }
    try {
        ContextCompat.startForegroundService(context, intent)
    } catch (_: Exception) {
        // fallback
        context.startService(intent)
    }
}

private fun stopRecordingService(context: Context) {
    val intent = Intent(context, ForegroundLocationService::class.java).apply { action = ForegroundLocationService.ACTION_STOP }
    context.startService(intent)
}

// Helper to send an action (pause/resume/discard) to the running foreground service
private fun sendServiceAction(context: Context, action: String) {
    val intent = Intent(context, ForegroundLocationService::class.java).apply { this.action = action }
    context.startService(intent)
}

private fun formatDistance(m: Float): String {
    val km = m / 1000f
    return String.format(Locale.US, "%.2f km", km)
}

private fun formatElapsed(ms: Long): String {
    val s = ms / 1000
    val hh = s / 3600
    val mm = (s % 3600) / 60
    val ss = s % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hh, mm, ss)
}

// Extended pace formatting to handle NaN
private fun formatPace(paceSecPerKm: Float): String {
    return if (paceSecPerKm.isFinite() && !paceSecPerKm.isNaN()) {
        val seconds = paceSecPerKm.toInt()
        val mm = seconds / 60
        val ss = seconds % 60
        String.format(Locale.US, "%d:%02d /km", mm, ss)
    } else {
        "--:--"
    }
}

@Composable
private fun MapSection(
    modifier: Modifier,
    points: List<LatLng>,
    latest: LatLng?,
    follow: Boolean = true,
    distanceMeters: Float = 0f,
    isSim: Boolean = false
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    var polyline by remember { mutableStateOf<Polyline?>(null) }
    var latestMarker by remember { mutableStateOf<Marker?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Observe lifecycle and forward events to the MapView
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                try { mapView.onCreate(null) } catch (_: Exception) {}
            }

            override fun onStart(owner: LifecycleOwner) { mapView.onStart() }
            override fun onResume(owner: LifecycleOwner) { mapView.onResume() }
            override fun onPause(owner: LifecycleOwner) { mapView.onPause() }
            override fun onStop(owner: LifecycleOwner) { mapView.onStop() }
            override fun onDestroy(owner: LifecycleOwner) {
                try { mapView.onDestroy() } catch (_: Exception) {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try { mapView.onDestroy() } catch (_: Exception) {}
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            mapView.getMapAsync { gm ->
                googleMap = gm
                gm.uiSettings.isZoomControlsEnabled = true
                try {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        gm.isMyLocationEnabled = true
                        gm.uiSettings.isMyLocationButtonEnabled = true
                    }
                } catch (_: SecurityException) {}

                if (points.isNotEmpty()) {
                    if (polyline == null) {
                        polyline = gm.addPolyline(PolylineOptions().addAll(points).width(8f).color(0xFF007AFF.toInt()))
                    } else polyline?.points = points
                    latest?.let {
                        if (latestMarker == null) latestMarker = gm.addMarker(MarkerOptions().position(it).title("Current")) else latestMarker?.position = it
                        if (follow) moveCameraForPoint(gm, it, points.size <= 1)
                    }
                }
            }
            mapView
        },
        update = {
            googleMap?.let { gm ->
                if (polyline == null && points.isNotEmpty()) {
                    polyline = gm.addPolyline(PolylineOptions().addAll(points).width(8f).color(0xFF007AFF.toInt()))
                } else polyline?.points = points
                latest?.let {
                    if (latestMarker == null) latestMarker = gm.addMarker(MarkerOptions().position(it).title("Current")) else latestMarker?.position = it
                    if (follow) moveCameraForPoint(gm, it, false)
                }
                Log.d("RecordScreen", "Map update: points=${points.size}")
            }
        }
    )

    // Overlay for debug stats
    Column(modifier = Modifier.padding(6.dp)) {
        Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)) {
            Column(modifier = Modifier.padding(6.dp)) {
                Text(
                    text = "PTS ${points.size} Dist ${String.format(Locale.US, "%.1f", distanceMeters)}m",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = if (isSim) "SIM" else "LIVE",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSim) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

private fun moveCameraForPoint(map: GoogleMap, point: LatLng, instant: Boolean) {
    val cameraUpdate = CameraUpdateFactory.newLatLngZoom(point, 17f)
    if (instant) map.moveCamera(cameraUpdate) else map.animateCamera(cameraUpdate)
}

// Haversine distance in meters between two LatLng points
private fun distanceBetween(a: LatLng, b: LatLng): Float {
    val R = 6371000.0 // Earth radius in meters
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val sinDLat = sin(dLat / 2.0)
    val sinDLon = sin(dLon / 2.0)
    val aa = sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLon * sinDLon
    val c = 2.0 * atan2(sqrt(aa), sqrt(1.0 - aa))
    return (R * c).toFloat()
}
