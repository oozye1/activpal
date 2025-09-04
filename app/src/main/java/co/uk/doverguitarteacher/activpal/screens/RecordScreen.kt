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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(navController: NavHostController) {
    val context = LocalContext.current

    // UI state updated from the foreground service via broadcasts
    var isRecording by remember { mutableStateOf(false) }
    var distanceMeters by remember { mutableStateOf(0f) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var paceSecPerKm by remember { mutableStateOf(Float.POSITIVE_INFINITY) }

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
                if (intent.getBooleanExtra(EXTRA_STOPPED, false)) {
                    isRecording = false
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
            // Placeholder for the map
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Map will go here", fontSize = 24.sp)
            }

            // Placeholder for stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatText(title = "Distance", value = formatDistance(distanceMeters))
                StatText(title = "Time", value = formatElapsed(elapsedMs))
                StatText(title = "Pace", value = formatPace(paceSecPerKm))
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Start/Stop Button
            Button(
                onClick = {
                    if (!isRecording) {
                        // Check runtime permissions
                        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        if (fine || coarse) {
                            // For Android Q+ we should request background location separately if not granted
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val bgGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
                                if (!bgGranted) {
                                    bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                    return@Button
                                }
                            }
                            // On Android 13+ request notification permission (optional)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val notifGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                                if (!notifGranted) {
                                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            startRecordingService(context)
                            isRecording = true
                        } else {
                            // Ask for permissions (fine & coarse). Background location is requested separately by platform if needed.
                            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }
                    } else {
                        // Stop service
                        stopRecordingService(context)
                        isRecording = false
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (isRecording) "STOP" else "START", fontSize = 20.sp)
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

private fun formatPace(paceSecPerKm: Float): String {
    return if (paceSecPerKm.isFinite()) {
        val seconds = paceSecPerKm.toInt()
        val mm = seconds / 60
        val ss = seconds % 60
        String.format(java.util.Locale.US, "%d:%02d /km", mm, ss)
    } else {
        "--:--"
    }
}
