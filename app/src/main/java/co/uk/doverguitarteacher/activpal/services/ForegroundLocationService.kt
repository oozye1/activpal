package co.uk.doverguitarteacher.activpal.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import co.uk.doverguitarteacher.activpal.MainActivity
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
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
        const val EXTRA_SPEED_M_S = "speed_m_s"
        const val EXTRA_STOPPED = "stopped"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_ROLLING_PACE_S_PER_KM = "rolling_pace_s_per_km"
        const val EXTRA_SIMULATING = "simulating"

        const val ACTION_PAUSE = "co.uk.doverguitarteacher.activpal.action.PAUSE_TRACKING"
        const val ACTION_RESUME = "co.uk.doverguitarteacher.activpal.action.RESUME_TRACKING"
        const val ACTION_DISCARD = "co.uk.doverguitarteacher.activpal.action.DISCARD_TRACKING"
        const val ACTION_START_SIM = "co.uk.doverguitarteacher.activpal.action.START_SIM" // start with simulated GPS route

        private const val NOTIF_CHANNEL_ID = "activpal_location_channel"
        private const val NOTIF_ID = 2371
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var lastLocation: Location? = null
    private var totalDistanceMeters = 0f
    private var startTimeMillis = 0L
    private var isPaused = false
    private var discardRequested = false
    private val points = mutableListOf<TrackPoint>()
    private val recentWindowSeconds = 60
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var periodicPosted = false
    private var lastFixMs = 0L
    private var isSimulating = false
    private var simIndex = 0
    private var simPoints: List<Pair<Double, Double>> = emptyList()

    private data class TrackPoint(val lat: Double, val lng: Double, val timeMs: Long, val accuracy: Float)

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_START_SIM -> startSimulation()
            ACTION_STOP -> stopTrackingAndStopSelf()
            ACTION_PAUSE -> pauseTracking()
            ACTION_RESUME -> resumeTracking()
            ACTION_DISCARD -> { discardRequested = true; stopTrackingAndStopSelf() }
            else -> { }
        }
        return START_STICKY
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
            .setWaitForAccurateLocation(false) // don't stall indoors
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                super.onLocationResult(result)
                Log.d(TAG, "onLocationResult count=${result.locations.size}")
                for (loc in result.locations) {
                    handleNewLocation(loc)
                }
            }
            override fun onLocationAvailability(availability: LocationAvailability) {
                Log.d(TAG, "onLocationAvailability available=${availability.isLocationAvailable}")
            }
        }

        try {
            // Seed with last known to get polyline started sooner
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    Log.d(TAG, "Seed lastLocation lat=${loc.latitude} lon=${loc.longitude} acc=${loc.accuracy}")
                    handleNewLocation(loc, fromSeed = true)
                } else {
                    // even if null, push an initial status so UI starts timer
                    broadcastStatus()
                }
                startPeriodicBroadcasts()
            }
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (ise: SecurityException) {
            Log.e(TAG, "Missing location permission when requesting updates", ise)
            stopSelf()
        }
    }

    private fun pauseTracking() {
        if (!isPaused) {
            isPaused = true
            val elapsed = System.currentTimeMillis() - startTimeMillis
            val notif = buildNotification("Paused — ${formatDistance(totalDistanceMeters)} • ${formatElapsed(elapsed)}")
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notif)
        }
    }

    private fun resumeTracking() {
        if (isPaused) {
            isPaused = false
            // Reset lastLocation to avoid jump distance from stationary pause
            lastLocation = null
            val elapsed = System.currentTimeMillis() - startTimeMillis
            val notif = buildNotification("Recording — ${formatDistance(totalDistanceMeters)} • ${formatElapsed(elapsed)}")
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notif)
        }
    }

    private fun handleNewLocation(loc: Location, fromSeed: Boolean = false) {
        if (isPaused) {
            lastLocation = loc
            return
        }

        // Relaxed accuracy threshold to allow indoor/network fixes
        val accuracy = if (loc.hasAccuracy()) loc.accuracy else 999f
        val MAX_ACCURACY = 100f
        if (accuracy > MAX_ACCURACY) {
            Log.d(TAG, "Ignoring location due to poor accuracy=${accuracy}")
            return
        }
        val prev = lastLocation
        if (prev != null) {
            val delta = prev.distanceTo(loc)
            val timeDelta = loc.time - prev.time
            val allowJump = timeDelta > 10_000
            if (delta >= 0f && (delta < 150f || allowJump)) {
                totalDistanceMeters += delta
                Log.d(TAG, "Distance +${"%.1f".format(delta)} -> total=${"%.1f".format(totalDistanceMeters)}")
            } else {
                Log.d(TAG, "Spike ignored delta=${delta} timeDelta=${timeDelta}")
            }
        }
        lastLocation = loc
        lastFixMs = System.currentTimeMillis()

        // Save track point (use location timestamp for better accuracy)
        val timeMs = if (loc.time > 0L) loc.time else System.currentTimeMillis()
        points += TrackPoint(loc.latitude, loc.longitude, timeMs, loc.accuracy)

        broadcastStatus()

        // Also update ongoing notification
        val elapsed = System.currentTimeMillis() - startTimeMillis
        val notif = buildNotification("${if(isPaused) "Paused" else "Recording"} — ${formatDistance(totalDistanceMeters)} • ${formatElapsed(elapsed)}")
        val mgr = getSystemService(NotificationManager::class.java)
        mgr?.notify(NOTIF_ID, notif)
    }

    private fun startSimulation() {
        Log.d(TAG, "startSimulation")
        // Prepare synthetic short route (rectangle loop) near a fixed coordinate (adjust as needed)
        val baseLat = 51.1640514
        val baseLng = 1.2890638
        simPoints = listOf(
            0.0 to 0.0,
            0.00015 to 0.0,
            0.00030 to 0.00010,
            0.00045 to 0.00010,
            0.00060 to 0.00000,
            0.00075 to -0.00005,
            0.00090 to -0.00010,
            0.00105 to -0.00005,
            0.00120 to 0.0,
            0.00135 to 0.00010,
            0.00150 to 0.00020
        ).map { (dLat, dLng) -> Pair(baseLat + dLat, baseLng + dLng) }

        isSimulating = true
        discardRequested = false
        totalDistanceMeters = 0f
        lastLocation = null
        points.clear()
        startTimeMillis = System.currentTimeMillis()
        simIndex = 0
        createNotificationChannelIfNeeded()
        val notification = buildNotification("Simulating activity…")
        startForeground(NOTIF_ID, notification)
        broadcastStatus()
        startPeriodicBroadcasts()
        feedNextSimPoint() // kick off
    }

    private fun feedNextSimPoint() {
        if (!isSimulating) return
        if (simIndex >= simPoints.size) {
            // loop to keep movement continuous
            simIndex = 0
        }
        val (lat, lng) = simPoints[simIndex]
        simIndex++
        val loc = Location("sim").apply {
            latitude = lat
            longitude = lng
            accuracy = 5f
            time = System.currentTimeMillis()
            // Speed will be derived; we can approximate using last point distance / interval (~2s)
            val prev = lastLocation
            speed = if (prev != null) {
                val d = prev.distanceTo(this) // meters
                d / 2f // since we post every 2s
            } else 0f
        }
        Log.d(TAG, "SIM location index=${simIndex - 1} lat=${lat} lng=${lng} speed=${loc.speed}")
        handleNewLocation(loc)
        handler.postDelayed({ feedNextSimPoint() }, 2000L)
    }

    private fun stopTrackingAndStopSelf() {
        Log.d(TAG, "stopTrackingAndStopSelf")
        try {
            fusedClient.removeLocationUpdates(locationCallback)
        } catch (t: Throwable) {
            Log.w(TAG, "Error removing location updates", t)
        }
        handler.removeCallbacksAndMessages(null)
        periodicPosted = false
        isSimulating = false

        val endTime = System.currentTimeMillis()
        val duration = endTime - startTimeMillis
        val avgPace = if (totalDistanceMeters >= 1f) {
            (duration / 1000.0f) / (totalDistanceMeters / 1000f)
        } else Float.POSITIVE_INFINITY

        if (!discardRequested) {
            persistSummary(startTimeMillis, endTime, totalDistanceMeters, duration, avgPace)
        }

        // Broadcast final update indicating stopped state so UI can reset if needed
        broadcastStatus(stopped = true)

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
                put("points_count", points.size)
                val ptsArray = org.json.JSONArray()
                points.forEach { p ->
                    val pObj = JSONObject()
                    pObj.put("lat", p.lat)
                    pObj.put("lng", p.lng)
                    pObj.put("t", p.timeMs)
                    pObj.put("acc", p.accuracy)
                    ptsArray.put(pObj)
                }
                put("points", ptsArray)
                put("discarded", false)
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

    private fun computeRollingPace(): Float {
        if (points.size < 2) return Float.NaN
        val now = System.currentTimeMillis()
        val windowStart = now - recentWindowSeconds * 1000
        // Collect points in window
        val windowPoints = points.filter { it.timeMs >= windowStart }
        if (windowPoints.size < 2) return Float.NaN
        var dist = 0f
        for (i in 1 until windowPoints.size) {
            val a = windowPoints[i - 1]
            val b = windowPoints[i]
            val locA = Location("win").apply { latitude = a.lat; longitude = a.lng }
            val locB = Location("win").apply { latitude = b.lat; longitude = b.lng }
            dist += locA.distanceTo(locB)
        }
        if (dist < 10f) return Float.NaN // not enough movement
        val timeSpanSec = (windowPoints.last().timeMs - windowPoints.first().timeMs) / 1000f
        if (timeSpanSec <= 0f) return Float.NaN
        val km = dist / 1000f
        return (timeSpanSec / km)
    }

    private fun broadcastStatus(stopped: Boolean = false) {
        val elapsed = System.currentTimeMillis() - startTimeMillis
        val paceSecPerKm = if (totalDistanceMeters > 0.5f) {
            (elapsed / 1000.0f) / (totalDistanceMeters / 1000f)
        } else Float.NaN
        val rollingPaceSecPerKm = computeRollingPace()
        val last = lastLocation
        val update = Intent(ACTION_UPDATE).apply {
            putExtra(EXTRA_DISTANCE_METERS, totalDistanceMeters)
            putExtra(EXTRA_ELAPSED_MS, elapsed)
            putExtra(EXTRA_PACE_S_PER_KM, paceSecPerKm)
            putExtra(EXTRA_ROLLING_PACE_S_PER_KM, rollingPaceSecPerKm)
            if (last != null) {
                putExtra(EXTRA_LAT, last.latitude)
                putExtra(EXTRA_LNG, last.longitude)
                putExtra("EXTRA_ACCURACY", last.accuracy)
                putExtra("EXTRA_TIMESTAMP", if (last.time > 0L) last.time else System.currentTimeMillis())
                putExtra(EXTRA_SPEED_M_S, last.speed)
            }
            if (stopped) putExtra(EXTRA_STOPPED, true)
            putExtra(EXTRA_SIMULATING, isSimulating)
        }
        sendBroadcast(update)
    }

    private fun startPeriodicBroadcasts() {
        if (periodicPosted) return
        periodicPosted = true
        handler.post(object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                // If no fix for >7s, actively poll a single current location to kick GPS (helps indoors / vendor throttling)
                if (now - lastFixMs > 7000L) {
                    try {
                        val cts = CancellationTokenSource()
                        fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                            .addOnSuccessListener { loc ->
                                if (loc != null) {
                                    Log.d(TAG, "Polled single location lat=${loc.latitude} lon=${loc.longitude} acc=${loc.accuracy}")
                                    handleNewLocation(loc)
                                } else {
                                    Log.d(TAG, "Polled single location null")
                                }
                            }
                            .addOnFailureListener { e -> Log.w(TAG, "getCurrentLocation failed", e) }
                    } catch (se: SecurityException) {
                        Log.w(TAG, "Missing permission for getCurrentLocation")
                    }
                }
                broadcastStatus()
                if (!discardRequested) {
                    handler.postDelayed(this, 2000L)
                }
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
