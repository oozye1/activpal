package co.uk.doverguitarteacher.activpal.screens

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import com.google.android.gms.maps.model.MarkerOptions
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.ktx.Firebase
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
    var lastLatLng by remember { mutableStateOf<LatLng?>(null) }
    var lastSpeedMps by remember { mutableStateOf<Float?>(null) }
    var localDistanceMeters by rememberSaveable { mutableStateOf(0f) } // fallback accumulation

    val trackPoints = remember { mutableStateListOf<LatLng>() }
    var followCamera by rememberSaveable { mutableStateOf(true) }

    // Permission UX
    var showFgsDialog by rememberSaveable { mutableStateOf(false) }
    var pendingFgsAction by remember { mutableStateOf<String?>(null) } // START or SIM

    // Debug: last update timestamp from service
    var lastUpdateMs by rememberSaveable { mutableStateOf(0L) }
    // Whether we've requested a start and are awaiting the first broadcast
    var startPending by rememberSaveable { mutableStateOf(false) }

    fun resetSession(clearPath: Boolean = true) {
        if (clearPath) trackPoints.clear()
        distanceMeters = 0f
        localDistanceMeters = 0f
        paceSecPerKm = Float.NaN
        rollingPaceSecPerKm = Float.NaN
        lastLatLng = null
        lastSpeedMps = null
        elapsedMs = 0L
        recordingStartMs = 0L
    }

    fun sendAction(action: String) {
        val appCtx = context.applicationContext
        val intent = Intent(appCtx, ForegroundLocationService::class.java).apply { setAction(action) }
        try {
            if (action == ACTION_START || action == ACTION_START_SIM) {
                // Ensure any previous instance is stopped before starting fresh
                try { appCtx.stopService(Intent(appCtx, ForegroundLocationService::class.java)) } catch (_: Throwable) { }
                ContextCompat.startForegroundService(appCtx, intent)
                // Ask for a snapshot shortly after start to confirm it began
                Handler(Looper.getMainLooper()).postDelayed({
                    try { appCtx.startService(Intent(appCtx, ForegroundLocationService::class.java).apply { setAction(ACTION_REQUEST_SNAPSHOT) }) } catch (_: Throwable) { }
                }, 1200L)
            } else {
                appCtx.startService(intent)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "sendAction failed for $action, fallback start: ${t.message}")
            try { appCtx.startService(intent) } catch (_: Throwable) { }
        }
    }

    // Retry handler to ensure service actually starts if first start attempt stalls
    val startRetryHandler = remember { Handler(Looper.getMainLooper()) }
    val startRetries = remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        onDispose { startRetryHandler.removeCallbacksAndMessages(null) }
    }

    fun startRecording(sim: Boolean) {
        val locOk = (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) ||
                (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        if (!locOk) {
            pendingFgsAction = if (sim) "SIM" else "START"
            return
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val fgsOk = ContextCompat.checkSelfPermission(context, PERM_FGS_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!fgsOk) { pendingFgsAction = if (sim) "SIM" else "START"; showFgsDialog = true; return }
        }
        // Reset UI session state and request service start even if local flags are stale
        Log.d(TAG, "startRecording requested sim=$sim (wasRecording=$isRecording)")
        resetSession(clearPath = true)
        startPending = true
        sendAction(if (sim) ACTION_START_SIM else ACTION_START)
        // Optimistically set UI state; service broadcasts will correct if needed
        isRecording = true
        isPaused = false
        isSimulating = sim
        recordingStartMs = System.currentTimeMillis()

        // Schedule retries in case the service doesn't actually start (re-send START up to 3 times)
        startRetries.value = 0
        startRetryHandler.postDelayed(object : Runnable {
            override fun run() {
                if (startPending && startRetries.value < 3) {
                    startRetries.value = startRetries.value + 1
                    Log.d(TAG, "Retrying start attempt #${startRetries.value}")
                    sendAction(if (sim) ACTION_START_SIM else ACTION_START)
                    startRetryHandler.postDelayed(this, 1500L)
                }
            }
        }, 1500L)
    }

    fun stopRecording(discard: Boolean) {
        val appCtx = context.applicationContext
        val action = if (discard) ACTION_DISCARD else ACTION_STOP
        Log.d(TAG, "stopRecording requested discard=$discard")
        // Tell the service to stop gracefully
        sendAction(action)
        // cancel pending start retries
        startPending = false

        if (!discard) {
            val userId = Firebase.auth.currentUser?.uid
            if (userId != null && trackPoints.isNotEmpty()) {
                val dbRef = FirebaseDatabase.getInstance().getReference("routes")
                val routeKey = dbRef.child(userId).push().key
                if (routeKey != null) {
                    val routeData = hashMapOf(
                        "id" to routeKey,
                        "timestamp" to System.currentTimeMillis(),
                        "distanceMeters" to distanceMeters,
                        "elapsedMs" to elapsedMs,
                        "points" to trackPoints.map { mapOf("latitude" to it.latitude, "longitude" to it.longitude) }
                    )
                    dbRef.child(userId).child(routeKey).setValue(routeData).addOnCompleteListener { task ->
                        if (task.isSuccessful) Log.d(TAG, "Route saved successfully") else Log.w(TAG, "Failed to save route", task.exception)
                    }
                }
            }
        }

        // Also attempt to stop the service immediately to ensure it can be restarted cleanly
        try {
            val stopIntent = Intent(appCtx, ForegroundLocationService::class.java).apply { setAction(ACTION_STOP) }
            appCtx.stopService(stopIntent)
        } catch (t: Throwable) {
            Log.w(TAG, "Immediate stopService failed: ${t.message}")
        }
        // Reset UI state immediately so buttons are responsive
        isRecording = false
        isPaused = false
        isSimulating = false
        resetSession(clearPath = true)
        // Also attempt to stop the service if it lingers after a short delay
        try {
            Handler(Looper.getMainLooper()).postDelayed({
                try { appCtx.stopService(Intent(appCtx, ForegroundLocationService::class.java)) } catch (_: Throwable) { }
            }, 1500L)
        } catch (_: Throwable) { }
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
                // record that we received a broadcast (helps debug start/stop issues)
                lastUpdateMs = System.currentTimeMillis()
                // mark start fulfilled so retry stops
                startPending = false

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

                // If service distance still not helpful, compute from whole path
                if ((!serviceDistance.isFinite() || serviceDistance < 1f) && trackPoints.size >= 2) {
                    val computed = computePathDistance(trackPoints)
                    if (computed > distanceMeters) distanceMeters = computed
                }

                // Update timing & pace after distance resolved
                intent.getLongExtra(EXTRA_ELAPSED_MS, -1L).let { if (it > 0) elapsedMs = it }
                intent.getFloatExtra(EXTRA_PACE_S_PER_KM, Float.NaN).let { if (it.isFinite()) paceSecPerKm = it else if (distanceMeters > 1f && elapsedMs > 0L) {
                    paceSecPerKm = (elapsedMs / 1000f) / (distanceMeters / 1000f)
                } }
                intent.getFloatExtra(EXTRA_ROLLING_PACE_S_PER_KM, Float.NaN).let { if (it.isFinite()) rollingPaceSecPerKm = it }
                intent.getFloatExtra(EXTRA_SPEED_M_S, Float.NaN).let { if (it.isFinite()) lastSpeedMps = it }
                // accuracy not surfaced in UI; skip storing it to avoid unused warnings

                if (stopped) {
                    // Service has stopped: clear UI session so Start is available and state is fresh
                    resetSession(clearPath = true)
                    isRecording = false
                    isPaused = false
                    isSimulating = false
                    // clear last update marker so UI reflects service stopped
                    lastUpdateMs = 0L
                    startPending = false
                } else if (!isRecording) {
                    // If service started externally and we attach later
                    isRecording = true
                }
            }
        }
        // Always register with NOT_EXPORTED
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Ask service to send current snapshot (points + stats)
        context.startService(Intent(context, ForegroundLocationService::class.java).apply { setAction(ACTION_REQUEST_SNAPSHOT) })
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
                val local = System.currentTimeMillis() - recordingStartMs
                if (local > elapsedMs) elapsedMs = local
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
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
            Spacer(Modifier.height(6.dp))
            // Debug: show last received broadcast age
            val lastAgeText = remember(lastUpdateMs) {
                if (lastUpdateMs == 0L) "No updates"
                else {
                    val s = ((System.currentTimeMillis() - lastUpdateMs) / 1000L)
                    "$s s since last update"
                }
            }
            Text(text = "Status: ${if (isSimulating) "SIM" else if (isRecording) "RECORDING" else "IDLE"} • $lastAgeText", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatText("Distance", formatDistance(distanceMeters))
                StatText("Time", formatElapsed(elapsedMs))
                val speedForUi = run {
                    val s = lastSpeedMps ?: Float.NaN
                    when {
                        s.isFinite() && s >= 0.2f -> s
                        rollingPaceSecPerKm.isFinite() && rollingPaceSecPerKm > 0f -> (1000f / rollingPaceSecPerKm)
                        paceSecPerKm.isFinite() && paceSecPerKm > 0f -> (1000f / paceSecPerKm)
                        else -> Float.NaN
                    }
                }
                StatText("Pace", formatPace(paceSecPerKm))
                StatText("Speed", formatSpeed(speedForUi))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val hasSession = isRecording || (elapsedMs > 0L) || (lastLatLng != null) || trackPoints.isNotEmpty()
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (!hasSession) {
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
                ) { Text(if (hasSession) "Stop" else "Start") }

                Button(
                    modifier = Modifier.weight(1f),
                    enabled = hasSession,
                    onClick = {
                        if (!hasSession) return@Button
                        if (isPaused) { sendAction(ACTION_RESUME); isPaused = false } else { sendAction(ACTION_PAUSE); isPaused = true }
                    }
                ) { Text(if (isPaused) "Resume" else "Pause") }

                Button(
                    modifier = Modifier.weight(1f),
                    enabled = hasSession,
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
    val lifecycleOwner = LocalContext.current as LifecycleOwner
    val polyHelper = remember { PolylineHelper() }
    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    var latestMarker by remember { mutableStateOf<com.google.android.gms.maps.model.Marker?>(null) }
    val mapView = remember { MapView(context) }
    var didInitialCamera by remember { mutableStateOf(false) }

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
                    // Ensure initial camera regardless of follow setting
                    val target = latest ?: points.lastOrNull()
                    if (target != null) { moveCamera(gm, target, true); didInitialCamera = true }
                }
            } else {
                val gm = googleMap
                if (gm != null) {
                    polyHelper.updatePolyline(gm, points)
                    latest?.let { point ->
                        if (latestMarker == null) {
                            latestMarker = gm.addMarker(MarkerOptions().position(point).title("Current"))
                        } else {
                            latestMarker?.position = point
                        }
                        if (follow) moveCamera(gm, point, false)
                        else if (!didInitialCamera) { moveCamera(gm, point, true); didInitialCamera = true }
                    } ?: run {
                        if (!didInitialCamera && points.isNotEmpty()) {
                            moveCamera(gm, points.last(), true)
                            didInitialCamera = true
                        }
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

private fun computePathDistance(points: List<LatLng>): Float {
    if (points.size < 2) return 0f
    var d = 0f
    for (i in 1 until points.size) {
        d += haversine(points[i - 1], points[i])
    }
    return d
}
