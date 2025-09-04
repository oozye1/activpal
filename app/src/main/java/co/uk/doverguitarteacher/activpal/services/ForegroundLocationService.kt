package co.uk.doverguitarteacher.activpal.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import co.uk.doverguitarteacher.activpal.MainActivity
import com.google.android.gms.location.*
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class ForegroundLocationService : Service() {
    companion object {
        private const val TAG = "ForegroundLocationSvc"
        const val ACTION_START = "co.uk.doverguitarteacher.activpal.action.START_TRACKING"
        const val ACTION_STOP = "co.uk.doverguitarteacher.activpal.action.STOP_TRACKING"
        const val ACTION_UPDATE = "co.uk.doverguitarteacher.activpal.action.LOCATION_UPDATE"
        const val EXTRA_DISTANCE_METERS = "distance_meters"
        const val EXTRA_ELAPSED_MS = "elapsed_ms"
        const val EXTRA_PACE_S_PER_KM = "pace_s_per_km"
        const val EXTRA_STOPPED = "stopped"
        private const val NOTIF_CHANNEL_ID = "activpal_location_channel"
        private const val NOTIF_ID = 2371
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var lastLocation: Location? = null
    private var totalDistanceMeters = 0f
    private var startTimeMillis = 0L

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTrackingAndStopSelf()
            else -> {
                // no-op
            }
        }
        return START_NOT_STICKY
    }

    private fun startTracking() {
        Log.d(TAG, "startTracking")
        totalDistanceMeters = 0f
        lastLocation = null
        startTimeMillis = System.currentTimeMillis()

        createNotificationChannelIfNeeded()
        val notification = buildNotification("Recording activity…")
        startForeground(NOTIF_ID, notification)

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateDistanceMeters(1f)
            .setMinUpdateIntervalMillis(1000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                super.onLocationResult(result)
                for (loc in result.locations) {
                    handleNewLocation(loc)
                }
            }
        }

        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (ise: SecurityException) {
            Log.e(TAG, "Missing location permission when requesting updates", ise)
            stopSelf()
        }
    }

    private fun handleNewLocation(loc: Location) {
        val prev = lastLocation
        if (prev != null) {
            val delta = prev.distanceTo(loc)
            if (delta >= 0f) {
                totalDistanceMeters += delta
            }
        }
        lastLocation = loc

        val elapsed = System.currentTimeMillis() - startTimeMillis
        val paceSecPerKm = if (totalDistanceMeters >= 1f) {
            // seconds per km
            (elapsed / 1000.0f) / (totalDistanceMeters / 1000f)
        } else {
            Float.POSITIVE_INFINITY
        }

        // Broadcast a simple update Intent
        val update = Intent(ACTION_UPDATE)
        update.putExtra(EXTRA_DISTANCE_METERS, totalDistanceMeters)
        update.putExtra(EXTRA_ELAPSED_MS, elapsed)
        update.putExtra(EXTRA_PACE_S_PER_KM, paceSecPerKm)
        sendBroadcast(update)

        // Also update ongoing notification
        val notif = buildNotification("Recording — ${formatDistance(totalDistanceMeters)} • ${formatElapsed(elapsed)}")
        val mgr = getSystemService(NotificationManager::class.java)
        mgr?.notify(NOTIF_ID, notif)
    }

    private fun stopTrackingAndStopSelf() {
        Log.d(TAG, "stopTrackingAndStopSelf")
        try {
            fusedClient.removeLocationUpdates(locationCallback)
        } catch (t: Throwable) {
            Log.w(TAG, "Error removing location updates", t)
        }

        val endTime = System.currentTimeMillis()
        val duration = endTime - startTimeMillis
        val avgPace = if (totalDistanceMeters >= 1f) {
            (duration / 1000.0f) / (totalDistanceMeters / 1000f)
        } else Float.POSITIVE_INFINITY

        persistSummary(startTimeMillis, endTime, totalDistanceMeters, duration, avgPace)

        // Broadcast final update indicating stopped state so UI can reset if needed
        val final = Intent(ACTION_UPDATE).apply {
            putExtra(EXTRA_DISTANCE_METERS, totalDistanceMeters)
            putExtra(EXTRA_ELAPSED_MS, duration)
            putExtra(EXTRA_PACE_S_PER_KM, avgPace)
            putExtra(EXTRA_STOPPED, true)
        }
        sendBroadcast(final)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun persistSummary(startMs: Long, endMs: Long, distanceMeters: Float, durationMs: Long, avgPaceSecPerKm: Float) {
        try {
            val dir = File(filesDir, "activities")
            if (!dir.exists()) dir.mkdirs()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startMs))
            val activityFile = File(dir, "activity_$timestamp.json")
            val obj = JSONObject().apply {
                put("start_ms", startMs)
                put("end_ms", endMs)
                put("distance_meters", distanceMeters)
                put("duration_ms", durationMs)
                if (avgPaceSecPerKm.isFinite()) put("avg_pace_s_per_km", avgPaceSecPerKm)
                put("created_at", System.currentTimeMillis())
            }
            FileWriter(activityFile).use { it.write(obj.toString()) }
            Log.d(TAG, "Saved activity to ${activityFile.absolutePath}")
        } catch (t: Throwable) {
            Log.e(TAG, "Error persisting activity summary", t)
        }
    }

    private fun formatDistance(m: Float): String {
        val km = m / 1000.0f
        return String.format(Locale.US, "%.2f km", km)
    }

    private fun formatElapsed(ms: Long): String {
        val s = ms / 1000
        val hh = s / 3600
        val mm = (s % 3600) / 60
        val ss = s % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hh, mm, ss)
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val chan = NotificationChannel(NOTIF_CHANNEL_ID, "Location tracking", NotificationManager.IMPORTANCE_LOW)
            mgr.createNotificationChannel(chan)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("ActivPal — Recording")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
