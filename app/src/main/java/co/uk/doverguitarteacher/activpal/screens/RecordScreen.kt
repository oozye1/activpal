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
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.EXTRA_LAT
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.EXTRA_LNG
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.EXTRA_ROLLING_PACE_S_PER_KM
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.PolylineOptions
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(navController: NavHostController) {
    val context = LocalContext.current

    // UI state updated from the foreground service via broadcasts
    var isRecording by remember { mutableStateOf(false) }
    var distanceMeters by remember { mutableStateOf(0f) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var paceSecPerKm by remember { mutableStateOf(Float.POSITIVE_INFINITY) }
    var rollingPaceSecPerKm by remember { mutableStateOf(Float.NaN) }
    var lastLatLng by remember { mutableStateOf<LatLng?>(null) }
    val trackPoints = remember { mutableStateListOf<LatLng>() }
    var isPaused by remember { mutableStateOf(false) }

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
                distanceMeters = intent.getFloatExtra(ForegroundLocationService.EXTRA_DISTANCE_METERS, 0f)
                elapsedMs = intent.getLongExtra(ForegroundLocationService.EXTRA_ELAPSED_MS, 0L)
                paceSecPerKm = intent.getFloatExtra(ForegroundLocationService.EXTRA_PACE_S_PER_KM, Float.POSITIVE_INFINITY)
                rollingPaceSecPerKm = intent.getFloatExtra(EXTRA_ROLLING_PACE_S_PER_KM, Float.NaN)
                val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
                val lng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN)
                if (!lat.isNaN() && !lng.isNaN()) {
                    val p = LatLng(lat, lng)
                    lastLatLng = p
                    trackPoints.add(p)
                }
                if (intent.getBooleanExtra(EXTRA_STOPPED, false)) {
                    isRecording = false
                    isPaused = false
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
                latest = lastLatLng
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
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Control buttons row
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                startRecordingService(context)
                                isRecording = true
                                isPaused = false
                                trackPoints.clear()
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

private fun formatDistance(m: Float): String {
    val km = m / 1000f
    return String.format(java.util.Locale.US, "%.2f km", km)
}

private fun formatElapsed(ms: Long): String {
    val s = ms / 1000
    val hh = s / 3600
    val mm = (s % 3600) / 60
    val ss = s % 60
    return String.format(java.util.Locale.US, "%02d:%02d:%02d", hh, mm, ss)
}

// Extended pace formatting to handle NaN
private fun formatPace(paceSecPerKm: Float): String {
    return if (paceSecPerKm.isFinite() && !paceSecPerKm.isNaN()) {
        val seconds = paceSecPerKm.toInt()
        val mm = seconds / 60
        val ss = seconds % 60
        String.format(java.util.Locale.US, "%d:%02d /km", mm, ss)
    } else {
        "--:--"
    }
}

@Composable
private fun MapSection(modifier: Modifier, points: List<LatLng>, latest: LatLng?) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
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
        modifier = modifier,
        factory = {
            mapView.getMapAsync { gm ->
                googleMap = gm
                gm.uiSettings.isZoomControlsEnabled = true
                if (points.isNotEmpty()) updatePolyline(gm, points, latest)
            }
            mapView
        },
        update = { mv ->
            googleMap?.let { gm ->
                if (points.isNotEmpty()) updatePolyline(gm, points, latest)
            }
        }
    )
}

private fun updatePolyline(map: GoogleMap, points: List<LatLng>, latest: LatLng?) {
    map.clear()
    if (points.isEmpty()) return
    val poly = PolylineOptions().addAll(points).width(8f)
        .color(0xFF007AFF.toInt())
    map.addPolyline(poly)
    val boundsBuilder = LatLngBounds.builder()
    points.forEach { boundsBuilder.include(it) }
    val bounds = boundsBuilder.build()
    try {
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
    } catch (_: Exception) {
        // ignore if map not laid out yet
    }
    if (latest != null) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(latest, 16f))
    }
}

private fun sendServiceAction(context: Context, action: String) {
    val i = Intent(context, ForegroundLocationService::class.java).apply { this.action = action }
    context.startService(i)
}
