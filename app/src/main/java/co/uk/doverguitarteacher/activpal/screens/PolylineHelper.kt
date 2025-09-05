package co.uk.doverguitarteacher.activpal.screens

import androidx.core.graphics.toColorInt
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions

/**
 * Simple helper to create/update/clear a polyline on a GoogleMap.
 */
class PolylineHelper {
    private var polyline: Polyline? = null

    /**
     * Create the polyline on the given map if needed, or update the points if already present.
     * If path is empty the existing polyline will be removed.
     */
    fun updatePolyline(map: GoogleMap?, path: List<LatLng>) {
        if (map == null) return

        if (path.isEmpty()) {
            clear()
            return
        }

        if (polyline == null) {
            val opts = PolylineOptions()
                .addAll(path)
                .width(10f)
                .color("#007AFF".toColorInt())
                .geodesic(true)
            polyline = map.addPolyline(opts)
        } else {
            polyline?.points = path
        }
    }

    /** Remove the current polyline from the map. */
    fun clear() {
        try {
            polyline?.remove()
        } catch (_: Throwable) { }
        polyline = null
    }
}
