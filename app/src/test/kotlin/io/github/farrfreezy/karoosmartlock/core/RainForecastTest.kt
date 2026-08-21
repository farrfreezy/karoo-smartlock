package io.github.farrfreezy.karoosmartlock.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route-aware behaviour: reading the forecast for where the rider will be, looking
 * ahead by a lead time, and judging the whole ride from precipitation probability.
 */
class RainForecastTest {

    private val nowSec = 1_750_000_000L
    private val nowMs = nowSec * 1000
    private val hour = 3600L

    /** 10 m/s ≈ 36 km/h, so an hour of riding covers 36 km. */
    private val speed = 10.0

    // --- multi-point parsing ---

    @Test
    fun `an array response is aligned with the requested points`() {
        val samples = listOf(
            RouteSample(LatLon(51.5, -0.1), 0.0),
            RouteSample(LatLon(51.6, -0.2), 36_000.0),
        )
        val body = "[" + location(hourlyPrecip = listOf(0.0, 0.0)) + "," +
            location(hourlyPrecip = listOf(0.0, 1.0)) + "]"
        val forecast = requireNotNull(OpenMeteoRain.parse(body, samples))

        assertEquals(2, forecast.points.size)
        assertEquals(0.0, forecast.points[0].distanceAlongRouteM, 1e-9)
        assertEquals(36_000.0, forecast.points[1].distanceAlongRouteM, 1e-9)
        assertEquals(LatLon(51.6, -0.2), forecast.points[1].at)
    }

    @Test
    fun `a single point response is still accepted as a bare object`() {
        val samples = listOf(RouteSample(LatLon(51.5, -0.1), 0.0))
        val forecast = requireNotNull(OpenMeteoRain.parse(location(hourlyPrecip = listOf(2.0)), samples))
        assertEquals(1, forecast.points.size)
        assertEquals(RainStatus.Rain, forecast.statusNow(nowMs, 0.0))
    }

    @Test
    fun `a malformed body is no forecast at all`() {
        assertNull(OpenMeteoRain.parse("not json", listOf(RouteSample(LatLon(0.0, 0.0), 0.0))))
    }

    // --- reading the right point ---

    @Test
    fun `status is read from the point nearest the rider`() {
        // Dry here, wet 36 km up the road.
        val forecast = routeForecast(
            listOf(0.0, 36_000.0),
            hourlyPrecipPerPoint = listOf(listOf(0.0, 0.0, 0.0), listOf(2.0, 2.0, 2.0)),
        )
        assertEquals(RainStatus.NoRain, forecast.statusNow(nowMs, 0.0))
        // Once the rider has covered that ground, the wet point is the nearest one.
        assertEquals(RainStatus.Rain, forecast.statusNow(nowMs, 36_000.0))
    }

    // --- lead time ---

    @Test
    fun `no lead means only rain falling on you counts`() {
        val forecast = routeForecast(
            listOf(0.0, 36_000.0),
            hourlyPrecipPerPoint = listOf(listOf(0.0, 0.0, 0.0), listOf(2.0, 2.0, 2.0)),
        )
        assertFalse(forecast.rainWithin(nowMs, leadMs = 0, distanceAlongRouteM = 0.0, speedMps = speed))
    }

    @Test
    fun `a lead sees the rain the rider is riding into`() {
        // Wet only at the point an hour ahead; at 10 m/s that is 36 km away.
        val forecast = routeForecast(
            listOf(0.0, 36_000.0),
            hourlyPrecipPerPoint = listOf(listOf(0.0, 0.0, 0.0), listOf(0.0, 2.0, 2.0)),
        )
        assertEquals(RainStatus.NoRain, forecast.statusNow(nowMs, 0.0))
        assertTrue(forecast.rainWithin(nowMs, 70 * 60_000L, 0.0, speed))
    }

    @Test
    fun `a lead shorter than the rain is still dry`() {
        val forecast = routeForecast(
            listOf(0.0, 36_000.0),
            hourlyPrecipPerPoint = listOf(listOf(0.0, 0.0, 0.0), listOf(0.0, 2.0, 2.0)),
        )
        assertFalse(forecast.rainWithin(nowMs, 10 * 60_000L, 0.0, speed))
    }

    @Test
    fun `a stationary rider still sees rain arriving in time where they stand`() {
        // Dry this hour here, wet the next — speed 0 must not stall the walk forward.
        val forecast = routeForecast(
            listOf(0.0),
            hourlyPrecipPerPoint = listOf(listOf(0.0, 3.0, 3.0)),
        )
        assertTrue(forecast.rainWithin(nowMs, 90 * 60_000L, 0.0, speedMps = 0.0))
    }

    // --- whole-ride probability ---

    @Test
    fun `probability is taken from the hour the rider reaches each point`() {
        // 10% here now, 80% at the far point in the hour the rider gets there.
        val forecast = routeForecast(
            listOf(0.0, 36_000.0),
            hourlyPrecipPerPoint = listOf(listOf(0.0, 0.0, 0.0), listOf(0.0, 0.0, 0.0)),
            hourlyProbPerPoint = listOf(listOf(10, 10, 10), listOf(0, 80, 0)),
        )
        assertEquals(80, forecast.maxProbabilityPct(nowMs, 0.0, speed))
    }

    @Test
    fun `probability ignores points already behind the rider`() {
        val forecast = routeForecast(
            listOf(0.0, 36_000.0),
            hourlyPrecipPerPoint = listOf(listOf(0.0, 0.0, 0.0), listOf(0.0, 0.0, 0.0)),
            hourlyProbPerPoint = listOf(listOf(5, 5, 5), listOf(5, 5, 5)),
        )
        assertEquals(5, forecast.maxProbabilityPct(nowMs, 0.0, speed))
    }

    @Test
    fun `no probability data reads as unknown rather than zero percent`() {
        val forecast = routeForecast(
            listOf(0.0),
            hourlyPrecipPerPoint = listOf(listOf(0.0, 0.0, 0.0)),
            hourlyProbPerPoint = null,
        )
        assertNull(forecast.maxProbabilityPct(nowMs, 0.0, speed))
    }

    // --- fixtures ---

    private fun routeForecast(
        distances: List<Double>,
        hourlyPrecipPerPoint: List<List<Double>>,
        hourlyProbPerPoint: List<List<Int>>? = null,
    ): RainForecast {
        val samples = distances.mapIndexed { i, d -> RouteSample(LatLon(51.0 + i, 0.0), d) }
        val bodies = hourlyPrecipPerPoint.mapIndexed { i, precip ->
            location(hourlyPrecip = precip, hourlyProb = hourlyProbPerPoint?.get(i))
        }
        val body = if (bodies.size == 1) bodies.first() else "[" + bodies.joinToString(",") + "]"
        return requireNotNull(OpenMeteoRain.parse(body, samples))
    }

    /** Hourly series starting at the top of the current hour, no minutely block. */
    private fun location(hourlyPrecip: List<Double>, hourlyProb: List<Int>? = null): String {
        val start = nowSec - (nowSec % hour)
        val times = hourlyPrecip.indices.joinToString(",") { (start + it * hour).toString() }
        val precip = hourlyPrecip.joinToString(",")
        val prob = hourlyProb?.joinToString(",")
        val probField = if (prob == null) "" else ""","precipitation_probability":[$prob]"""
        return """{"hourly":{"time":[$times],"precipitation":[$precip]$probField}}"""
    }
}
