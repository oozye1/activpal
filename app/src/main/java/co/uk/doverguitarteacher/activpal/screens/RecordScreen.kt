package co.uk.doverguitarteacher.activpal.screens

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.ACTION_DISCARD
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.ACTION_PAUSE
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.ACTION_REQUEST_SNAPSHOT
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.ACTION_RESUME
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.ACTION_START
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.ACTION_START_SIM
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.ACTION_STOP
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.ACTION_UPDATE
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.EXTRA_DISTANCE_METERS
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.EXTRA_ELAPSED_MS
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.EXTRA_FGS_LOCATION_REQUIRED
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.EXTRA_LAT
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.EXTRA_LNG
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.EXTRA_PACE_S_PER_KM
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.EXTRA_POINT_LATS
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.EXTRA_POINT_LNGS
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.EXTRA_ROLLING_PACE_S_PER_KM
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.EXTRA_SIMULATING
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.EXTRA_SPEED_M_S
import co.uk.doverguitarteacher.activpal.services.ForegroundLocationService.Companion.EXTRA_STOPPED
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.*

private const val TAG = "RecordScreen"
private const val PERM_FGS_LOCATION = "android.permission.FOREGROUND_SERVICE_LOCATION"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(navController: NavHostController) {
    val context = LocalContext.current

    // Session state
    var isRecording by rememberSaveable { mutableStateOf(false) }
    var isPaused by rememberSaveable { mutableStateOf(false) }
    var isSimulating by rememberSaveable { mutableStateOf(false) }

    var distanceMeters by rememberSaveable { mutableStateOf(0f) }
    var paceSecPerKm by rememberSaveable { mutableStateOf(Float.NaN) }
    var rollingPaceSecPerKm by rememberSaveable { mutableStateOf(Float.NaN) }
    var elapsedMs by rememberSaveable { mutableStateOf(0L) }
    var recordingStartMs by rememberSaveable { mutableStateOf(0L) }
    var lastAccuracy by remember { mutableStateOf<Float?>(null) }
    var lastLatLng by remember { mutableStateOf<LatLng?>(null) }
    var lastSpeedMps by remember { mutableStateOf<Float?>(null) }
    var localDistanceMeters by rememberSaveable { mutableStateOf(0f) } // fallback accumulation

    val trackPoints = remember { mutableStateListOf<LatLng>() }
    var followCamera by rememberSaveable { mutableStateOf(true) }

    // Permission UX
    var showFgsDialog by rememberSaveable { mutableStateOf(false) }
    var pendingFgsAction by remember { mutableStateOf<String?>(null) } // START or SIM

    fun resetSession(clearPath: Boolean = true) {
        if (clearPath) trackPoints.clear()
        distanceMeters = 0f
        localDistanceMeters = 0f
        paceSecPerKm = Float.NaN
        rollingPaceSecPerKm = Float.NaN
        lastAccuracy = null
        lastSpeedMps = null
        lastLatLng = null
        elapsedMs = 0L
        recordingStartMs = 0L
    }

    fun sendAction(action: String) {
        context.startService(Intent(context, ForegroundLocationService::class.java).apply { this.action = action })
    }

    fun startRecording(sim: Boolean) {
        val locOk = (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) ||
                (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        if (!locOk) {
            pendingFgsAction = if (sim) if (sim) "SIM" else "START" else pendingFgsAction
            return
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val fgsOk = ContextCompat.checkSelfPermission(context, PERM_FGS_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!fgsOk) { pendingFgsAction = if (sim) "SIM" else "START"; showFgsDialog = true; return }
        }
        if (!isRecording) {
            resetSession(sim.not())
            if (sim) sendAction(ACTION_START_SIM) else sendAction(ACTION_START)
            isRecording = true
            isPaused = false
            isSimulating = sim
            recordingStartMs = System.currentTimeMillis()
        }
    }

    fun stopRecording(discard: Boolean) {
        if (!isRecording) return
        if (discard) sendAction(ACTION_DISCARD) else sendAction(ACTION_STOP)
        isPaused = false
        isSimulating = false
    }

    val locationPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) || (result[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
        if (granted) {
            val sim = pendingFgsAction == "SIM"
            startRecording(sim)
            pendingFgsAction = null
        } else Log.d(TAG, "Location permission denied")
    }

    val fgsLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (granted) {
            val sim = pendingFgsAction == "SIM"
            pendingFgsAction = null
            startRecording(sim)
        } else {
            showFgsDialog = true
        }
    }

    // Broadcast receiver to get updates from service
    DisposableEffect(Unit) {
        val filter = IntentFilter(ACTION_UPDATE)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent == null) return
                if (intent.getBooleanExtra(EXTRA_FGS_LOCATION_REQUIRED, false)) {
                    if (Build.VERSION.SDK_INT >= 34) showFgsDialog = true
                    return
                }
                val stopped = intent.getBooleanExtra(EXTRA_STOPPED, false)
                isSimulating = intent.getBooleanExtra(EXTRA_SIMULATING, isSimulating)

                // Full path snapshot
                intent.getDoubleArrayExtra(EXTRA_POINT_LATS)?.let { lats ->
                    intent.getDoubleArrayExtra(EXTRA_POINT_LNGS)?.let { lngs ->
                        if (lats.size == lngs.size && lats.isNotEmpty()) {
                            trackPoints.clear()
                            for (i in lats.indices) trackPoints += LatLng(lats[i], lngs[i])
                            lastLatLng = trackPoints.lastOrNull()
                        }
                    }
                }

                // Single latest point
                val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
                val lng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN)
                var usedServiceDistance = false
                val serviceDistance = intent.getFloatExtra(EXTRA_DISTANCE_METERS, Float.NaN)
                if (serviceDistance.isFinite() && serviceDistance >= 0f) {
                    distanceMeters = serviceDistance
                    usedServiceDistance = true
                }
                if (lat.isFinite() && lng.isFinite()) {
                    val newPoint = LatLng(lat, lng)
                    val prev = trackPoints.lastOrNull()
                    if (prev == null || prev.latitude != newPoint.latitude || prev.longitude != newPoint.longitude) {
                        // local distance fallback if service did not supply one
                        if (!usedServiceDistance && prev != null) {
                            val delta = haversine(prev, newPoint)
                            if (delta >= 2f) { // min segment threshold 2m to filter jitter
                                localDistanceMeters += delta
                                distanceMeters = max(distanceMeters, localDistanceMeters)
                            }
                        }
                        trackPoints += newPoint
                    }
                    lastLatLng = newPoint
                }

                // Update timing & pace after distance resolved
                intent.getLongExtra(EXTRA_ELAPSED_MS, -1L).let { if (it > 0) elapsedMs = it }
                intent.getFloatExtra(EXTRA_PACE_S_PER_KM, Float.NaN).let { if (it.isFinite()) paceSecPerKm = it else if (distanceMeters > 1f && elapsedMs > 0L) {
                    paceSecPerKm = (elapsedMs / 1000f) / (distanceMeters / 1000f)
                } }
                intent.getFloatExtra(EXTRA_ROLLING_PACE_S_PER_KM, Float.NaN).let { if (it.isFinite()) rollingPaceSecPerKm = it }
                intent.getFloatExtra(EXTRA_SPEED_M_S, Float.NaN).let { if (it.isFinite()) lastSpeedMps = it }
                intent.getFloatExtra("EXTRA_ACCURACY", Float.NaN).let { lastAccuracy = if (it.isFinite()) it else null }

                if (stopped) {
                    isRecording = false
                    isPaused = false
                    isSimulating = false
                } else if (!isRecording) {
                    // If service started externally and we attach later
                    isRecording = true
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        context.startService(Intent(context, ForegroundLocationService::class.java).apply { action = ACTION_REQUEST_SNAPSHOT })
        onDispose { context.unregisterReceiver(receiver) }
    }

    // Local elapsed fallback ticking when recording & not paused
    LaunchedEffect(isRecording, isPaused, recordingStartMs) {
        while (isRecording && !isPaused) {
            if (recordingStartMs > 0L && elapsedMs == 0L) {
                elapsedMs = System.currentTimeMillis() - recordingStartMs
            }
            delay(1000L)
            if (isRecording && !isPaused && recordingStartMs > 0L) {
                // Only adjust if service elapsed appears stale (< local)
                val local = System.currentTimeMillis() - recordingStartMs
                if (local > elapsedMs) elapsedMs = local
            }
        }
    }

    // UI ------------------------------------------------------
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Record Activity") }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MapSection(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
                points = trackPoints.toList(),
                latest = lastLatLng,
                follow = followCamera,
                isSim = isSimulating,
                distanceMeters = distanceMeters
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatText("Distance", formatDistance(distanceMeters))
                StatText("Time", formatElapsed(elapsedMs))
                StatText("Pace", formatPace(paceSecPerKm))
                StatText("Speed", formatSpeed(lastSpeedMps))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (!isRecording) {
                            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            if (!fine && !coarse) {
                                pendingFgsAction = "START"
                                locationPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                return@Button
                            }
                            startRecording(false)
                        } else {
                            stopRecording(false)
                        }
                    }
                ) { Text(if (isRecording) "Stop" else "Start") }

                Button(
                    modifier = Modifier.weight(1f),
                    enabled = isRecording,
                    onClick = {
                        if (!isRecording) return@Button
                        if (isPaused) { sendAction(ACTION_RESUME); isPaused = false } else { sendAction(ACTION_PAUSE); isPaused = true }
                    }
                ) { Text(if (isPaused) "Resume" else "Pause") }

                Button(
                    modifier = Modifier.weight(1f),
                    enabled = isRecording,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    onClick = { stopRecording(true) }
                ) { Text("Discard") }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !isRecording,
                    onClick = {
                        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        if (!fine && !coarse) {
                            pendingFgsAction = "SIM"
                            locationPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            return@Button
                        }
                        startRecording(true)
                    }
                ) { Text("Simulate") }

                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Follow", fontSize = 14.sp)
                    Switch(checked = followCamera, onCheckedChange = { followCamera = it })
                }
            }
        }
    }

    if (showFgsDialog && Build.VERSION.SDK_INT >= 34) {
        AlertDialog(
            onDismissRequest = { showFgsDialog = false },
            title = { Text("Foreground location permission required") },
            text = { Text("Grant the FOREGROUND_SERVICE_LOCATION permission so tracking can continue while the app is in background.") },
            confirmButton = {
                TextButton(onClick = {
                    showFgsDialog = false
                    val action = pendingFgsAction
                    if (ContextCompat.checkSelfPermission(context, PERM_FGS_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        fgsLauncher.launch(PERM_FGS_LOCATION)
                    } else if (action != null) {
                        startRecording(action == "SIM")
                        pendingFgsAction = null
                    }
                }) { Text("Grant") }
            },
            dismissButton = { TextButton(onClick = { showFgsDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun StatText(title: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = title.uppercase(Locale.getDefault()), fontSize = 12.sp)
        Text(text = value, fontSize = 20.sp)
    }
}

@Composable
private fun MapSection(
    modifier: Modifier,
    points: List<LatLng>,
    latest: LatLng?,
    follow: Boolean,
    isSim: Boolean,
    distanceMeters: Float
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val polyHelper = remember { PolylineHelper() }
    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    var latestMarker by remember { mutableStateOf<com.google.android.gms.maps.model.Marker?>(null) }
    val mapView = remember { MapView(context) }

    DisposableEffect(mapView, lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) { mapView.onCreate(null) }
            override fun onStart(owner: LifecycleOwner) { mapView.onStart() }
            override fun onResume(owner: LifecycleOwner) { mapView.onResume() }
            override fun onPause(owner: LifecycleOwner) { mapView.onPause() }
            override fun onStop(owner: LifecycleOwner) { mapView.onStop() }
            override fun onDestroy(owner: LifecycleOwner) {
                runCatching { mapView.onDestroy() }
                polyHelper.clear()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
            polyHelper.clear()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { mv ->
            if (googleMap == null) {
                mv.getMapAsync { gm ->
                    googleMap = gm
                    try {
                        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        if (fine || coarse) gm.isMyLocationEnabled = true
                    } catch (_: SecurityException) {}
                    gm.uiSettings.isZoomControlsEnabled = true
                    polyHelper.updatePolyline(gm, points)
                    latest?.let { moveCamera(gm, it, true) }
                }
            } else {
                val gm = googleMap
                if (gm != null) {
                    polyHelper.updatePolyline(gm, points)
                    latest?.let { point ->
                        if (latestMarker == null) latestMarker = gm.addMarker(MarkerOptions().position(point).title("Current")) else latestMarker?.position = point
                        if (follow) moveCamera(gm, point, false)
                    }
                }
            }
        }
    )
    Column(Modifier.padding(6.dp)) {
        Text(text = "PTS ${points.size} Dist ${String.format(Locale.US, "%.1f", distanceMeters)}m", style = MaterialTheme.typography.labelSmall)
        Text(text = if (isSim) "SIM" else "LIVE", style = MaterialTheme.typography.labelSmall, color = if (isSim) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
    }
}

private fun moveCamera(map: GoogleMap, point: LatLng, instant: Boolean) {
    val update = com.google.android.gms.maps.CameraUpdateFactory.newCameraPosition(
        CameraPosition.Builder()
            .target(point)
            .zoom(17f)
            .tilt(0f)
            .bearing(0f)
            .build()
    )
    if (instant) map.moveCamera(update) else map.animateCamera(update)
}

private fun formatDistance(m: Float): String = String.format(Locale.US, "%.2f km", m / 1000f)
private fun formatElapsed(ms: Long): String {
    val s = ms / 1000
    val hh = s / 3600
    val mm = (s % 3600) / 60
    val ss = s % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hh, mm, ss)
}
private fun formatPace(p: Float): String = if (p.isFinite()) { val sec = p.toInt(); val mm = sec / 60; val ss = sec % 60; String.format(Locale.US, "%d:%02d /km", mm, ss) } else "--:--"
private fun formatSpeed(mps: Float?): String = mps?.takeIf { it.isFinite() && it >= 0f }?.let { String.format(Locale.US, "%.1f km/h", it * 3.6f) } ?: "--.- km/h"

private fun haversine(a: LatLng, b: LatLng): Float {
    val R = 6371000.0 // meters
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val sinDLat = sin(dLat / 2)
    val sinDLon = sin(dLon / 2)
    val h = sinDLat * sinDLat + sinDLon * sinDLon * cos(lat1) * cos(lat2)
    val c = 2 * atan2(sqrt(h), sqrt(1 - h))
    return (R * c).toFloat()
}
