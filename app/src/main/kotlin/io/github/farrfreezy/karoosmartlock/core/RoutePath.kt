package io.github.farrfreezy.karoosmartlock.core

/**
 * A loaded route, decoded from the Google-encoded polyline the Karoo hands out via
 * `OnNavigationState.NavigatingRoute`.
 *
 * Pure Kotlin so the geometry is unit-testable. karoo-headwind pulls in Mapbox Turf
 * for the same job, but all SmartLock needs is "where am I at distance N along this
 * line", which is a handful of lines on top of [LatLon.distanceToM].
 */
class RoutePath private constructor(
    val points: List<LatLon>,
    /** Cumulative distance from the start to each point, so `cumulative[0] == 0`. */
    private val cumulativeM: DoubleArray,
) {
    val lengthM: Double get() = cumulativeM.lastOrNull() ?: 0.0

    /**
     * Position at [distanceM] along the route, clamped to the endpoints. Interpolates
     * linearly within the segment that contains it — route points are close enough
     * together that treating a segment as straight costs nothing.
     */
    fun pointAtDistance(distanceM: Double): LatLon {
        if (points.size == 1 || distanceM <= 0.0) return points.first()
        if (distanceM >= lengthM) return points.last()

        var hi = cumulativeM.indexOfFirst { it >= distanceM }
        if (hi <= 0) hi = 1
        val lo = hi - 1
        val segment = cumulativeM[hi] - cumulativeM[lo]
        if (segment <= 0.0) return points[lo]
        val t = (distanceM - cumulativeM[lo]) / segment
        val a = points[lo]
        val b = points[hi]
        return LatLon(
            lat = a.lat + (b.lat - a.lat) * t,
            lon = a.lon + (b.lon - a.lon) * t,
        )
    }

    /**
     * Up to [maxPoints] positions the rider is expected to reach, starting from
     * [fromDistanceM] and spaced [spacingM] apart, stopping at the end of the route.
     *
     * The first entry is always the current position, so a caller with no route still
     * gets a usable one-element forecast.
     */
    fun sampleAhead(fromDistanceM: Double, spacingM: Double, maxPoints: Int): List<RouteSample> {
        require(spacingM > 0) { "spacing must be positive" }
        val start = fromDistanceM.coerceIn(0.0, lengthM)
        val samples = mutableListOf(RouteSample(pointAtDistance(start), start))
        var d = start + spacingM
        while (d < lengthM && samples.size < maxPoints) {
            samples += RouteSample(pointAtDistance(d), d)
            d += spacingM
        }
        // Always include the finish if there is room and it is not already covered.
        if (samples.size < maxPoints && lengthM - samples.last().distanceAlongRouteM > spacingM / 2) {
            samples += RouteSample(pointAtDistance(lengthM), lengthM)
        }
        return samples
    }

    companion object {
        fun fromPolyline(encoded: String): RoutePath? {
            val points = Polyline.decode(encoded)
            if (points.isEmpty()) return null
            val cumulative = DoubleArray(points.size)
            for (i in 1 until points.size) {
                cumulative[i] = cumulative[i - 1] + points[i - 1].distanceToM(points[i])
            }
            return RoutePath(points, cumulative)
        }
    }
}

/** A point on the route together with how far along the route it sits. */
data class RouteSample(val at: LatLon, val distanceAlongRouteM: Double)

/**
 * Google encoded polyline decoder.
 *
 * The Karoo emits precision 5 for route geometry (precision 1 is used for the
 * separate elevation polyline, which SmartLock does not read).
 */
object Polyline {
    fun decode(encoded: String, precision: Int = 5): List<LatLon> {
        val scale = Math.pow(10.0, precision.toDouble())
        val out = mutableListOf<LatLon>()
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            val dLat = decodeValue(encoded, index) ?: return out
            index = dLat.nextIndex
            val dLon = decodeValue(encoded, index) ?: return out
            index = dLon.nextIndex
            lat += dLat.value
            lon += dLon.value
            out += LatLon(lat / scale, lon / scale)
        }
        return out
    }

    private class Chunk(val value: Int, val nextIndex: Int)

    /** Returns null on a truncated chunk rather than emitting a garbage coordinate. */
    private fun decodeValue(encoded: String, start: Int): Chunk? {
        var index = start
        var shift = 0
        var result = 0
        while (true) {
            if (index >= encoded.length) return null
            val b = encoded[index++].code - 63
            if (b < 0) return null
            result = result or ((b and 0x1f) shl shift)
            if (b < 0x20) break
            shift += 5
            if (shift > 30) return null
        }
        val value = if (result and 1 != 0) (result shr 1).inv() else result shr 1
        return Chunk(value, index)
    }
}
