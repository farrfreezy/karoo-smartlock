package io.github.farrfreezy.karoosmartlock.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePathTest {

    // The canonical example from Google's polyline format documentation.
    private val googleExample = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"

    @Test
    fun `decodes the reference polyline from the format spec`() {
        val points = Polyline.decode(googleExample)
        assertEquals(3, points.size)
        assertEquals(38.5, points[0].lat, 1e-5)
        assertEquals(-120.2, points[0].lon, 1e-5)
        assertEquals(40.7, points[1].lat, 1e-5)
        assertEquals(-120.95, points[1].lon, 1e-5)
        assertEquals(43.252, points[2].lat, 1e-5)
        assertEquals(-126.453, points[2].lon, 1e-5)
    }

    @Test
    fun `decodes negative and zero deltas`() {
        val encoded = encode(
            listOf(LatLon(51.5, -0.12), LatLon(51.5, -0.12), LatLon(51.49, -0.13)),
        )
        val points = Polyline.decode(encoded)
        assertEquals(3, points.size)
        assertEquals(51.5, points[0].lat, 1e-5)
        assertEquals(51.5, points[1].lat, 1e-5)
        assertEquals(-0.13, points[2].lon, 1e-5)
    }

    @Test
    fun `truncated input yields what was decodable rather than a garbage point`() {
        val truncated = googleExample.substring(0, googleExample.length - 3)
        val points = Polyline.decode(truncated)
        // The final coordinate pair is incomplete, so it is dropped, not guessed.
        assertEquals(2, points.size)
    }

    @Test
    fun `empty input decodes to nothing and builds no path`() {
        assertTrue(Polyline.decode("").isEmpty())
        assertNull(RoutePath.fromPolyline(""))
    }

    // --- geometry ---

    /** Straight line due north from the equator: ~111 km per degree of latitude. */
    private fun straightPath(): RoutePath {
        val encoded = encode(
            listOf(LatLon(0.0, 0.0), LatLon(1.0, 0.0), LatLon(2.0, 0.0)),
        )
        return requireNotNull(RoutePath.fromPolyline(encoded))
    }

    @Test
    fun `length is the sum of its segments`() {
        val path = straightPath()
        assertEquals(222_390.0, path.lengthM, 2_000.0)
    }

    @Test
    fun `point at distance interpolates and clamps at both ends`() {
        val path = straightPath()
        assertEquals(0.0, path.pointAtDistance(0.0).lat, 1e-6)
        assertEquals(0.0, path.pointAtDistance(-500.0).lat, 1e-6)
        assertEquals(2.0, path.pointAtDistance(path.lengthM).lat, 1e-6)
        assertEquals(2.0, path.pointAtDistance(path.lengthM * 2).lat, 1e-6)
        // Halfway along should be about one degree north.
        assertEquals(1.0, path.pointAtDistance(path.lengthM / 2).lat, 0.02)
    }

    @Test
    fun `sampling starts where the rider is and walks forward`() {
        val path = straightPath()
        val samples = path.sampleAhead(fromDistanceM = 0.0, spacingM = 50_000.0, maxPoints = 10)
        assertEquals(0.0, samples.first().distanceAlongRouteM, 1e-6)
        assertTrue(samples.size > 2)
        // Strictly increasing, and never past the end of the route.
        samples.zipWithNext { a, b -> assertTrue(b.distanceAlongRouteM > a.distanceAlongRouteM) }
        assertTrue(samples.last().distanceAlongRouteM <= path.lengthM + 1e-6)
    }

    @Test
    fun `sampling respects the point budget`() {
        val path = straightPath()
        val samples = path.sampleAhead(fromDistanceM = 0.0, spacingM = 1_000.0, maxPoints = 4)
        assertEquals(4, samples.size)
    }

    @Test
    fun `sampling from near the finish still yields the current position`() {
        val path = straightPath()
        val samples = path.sampleAhead(
            fromDistanceM = path.lengthM - 10.0,
            spacingM = 50_000.0,
            maxPoints = 10,
        )
        assertEquals(1, samples.size)
        assertEquals(path.lengthM - 10.0, samples.first().distanceAlongRouteM, 1e-6)
    }

    /**
     * Reference encoder for the test fixtures — the inverse of what [Polyline] decodes,
     * so a round trip actually proves the decoder rather than restating it.
     */
    private fun encode(points: List<LatLon>): String {
        val sb = StringBuilder()
        var lastLat = 0
        var lastLon = 0
        points.forEach { point ->
            val lat = Math.round(point.lat * 1e5).toInt()
            val lon = Math.round(point.lon * 1e5).toInt()
            encodeValue(lat - lastLat, sb)
            encodeValue(lon - lastLon, sb)
            lastLat = lat
            lastLon = lon
        }
        return sb.toString()
    }

    private fun encodeValue(delta: Int, sb: StringBuilder) {
        var value = if (delta < 0) (delta shl 1).inv() else delta shl 1
        while (value >= 0x20) {
            sb.append(((0x20 or (value and 0x1f)) + 63).toChar())
            value = value shr 5
        }
        sb.append((value + 63).toChar())
    }

    @Test
    fun `a single point route is usable`() {
        val encoded = encode(listOf(LatLon(51.5, -0.12)))
        val path = requireNotNull(RoutePath.fromPolyline(encoded))
        assertNotNull(path)
        assertEquals(0.0, path.lengthM, 1e-9)
        assertEquals(51.5, path.pointAtDistance(1000.0).lat, 1e-6)
        assertEquals(1, path.sampleAhead(0.0, 1_000.0, 5).size)
    }
}
