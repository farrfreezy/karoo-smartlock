package io.github.farrfreezy.karoosmartlock.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class OpenMeteoRainTest {

    private val nowSec = 1_750_000_000L // arbitrary fixed clock
    private val nowMs = nowSec * 1000

    // --- request building ---

    @Test
    fun `url rounds coordinates and always uses a dot decimal separator`() {
        val default = Locale.getDefault()
        try {
            // German locale formats decimals with a comma, which would break the query.
            Locale.setDefault(Locale.GERMANY)
            val url = OpenMeteoRain.requestUrl(LatLon(51.507351, -0.127758))
            assertTrue(url, url.startsWith(OpenMeteoRain.BASE_URL))
            assertTrue(url, url.contains("latitude=51.5074"))
            assertTrue(url, url.contains("longitude=-0.1278"))
            assertTrue(url, url.contains("current=precipitation,weather_code"))
            assertTrue(url, url.contains("minutely_15=precipitation,weather_code"))
            assertTrue(url, url.contains("timeformat=unixtime"))
        } finally {
            Locale.setDefault(default)
        }
    }

    @Test
    fun `url pins the best-match model so a default change cannot downgrade riders`() {
        // best_match is what gets a London rider the Met Office UKV at 2 km rather
        // than a ~10 km global model.
        val url = OpenMeteoRain.requestUrl(LatLon(51.5074, -0.1278))
        assertTrue(url, url.contains("models=best_match"))
    }

    // --- minutely_15 preferred ---

    @Test
    fun `uses the minutely bucket covering now`() {
        // Buckets at -30, -15, 0 minutes; only the last one is current, and it is wet.
        val body = minutelyBody(
            offsetsMin = listOf(-30, -15, 0),
            precipitation = listOf(0.0, 0.0, 0.4),
            weatherCodes = listOf(3, 3, 61),
            currentPrecipitation = 0.0,
            currentCode = 3,
        )
        assertEquals(RainStatus.Rain, OpenMeteoRain.classify(body, nowMs))
    }

    @Test
    fun `ignores a later bucket that has not started yet`() {
        val body = minutelyBody(
            offsetsMin = listOf(0, 15),
            precipitation = listOf(0.0, 2.0),
            weatherCodes = listOf(3, 65),
            currentPrecipitation = 0.0,
            currentCode = 3,
        )
        assertEquals(RainStatus.NoRain, OpenMeteoRain.classify(body, nowMs))
    }

    @Test
    fun `falls back to current when the newest bucket has elapsed`() {
        // Last bucket started 20 min ago, so it no longer covers now.
        val body = minutelyBody(
            offsetsMin = listOf(-35, -20),
            precipitation = listOf(0.0, 0.0),
            weatherCodes = listOf(3, 3),
            currentPrecipitation = 1.2,
            currentCode = 3,
        )
        assertEquals(RainStatus.Rain, OpenMeteoRain.classify(body, nowMs))
    }

    @Test
    fun `falls back to current when minutely is absent`() {
        val body = """{"current":{"time":$nowSec,"precipitation":0.0,"weather_code":80}}"""
        assertEquals(RainStatus.Rain, OpenMeteoRain.classify(body, nowMs))
    }

    // --- classification ---

    @Test
    fun `weather code alone is enough when precipitation reads zero`() {
        val body = """{"current":{"time":$nowSec,"precipitation":0.0,"weather_code":51}}"""
        assertEquals(RainStatus.Rain, OpenMeteoRain.classify(body, nowMs))
    }

    @Test
    fun `precipitation alone is enough when the code looks dry`() {
        val body = """{"current":{"time":$nowSec,"precipitation":0.1,"weather_code":3}}"""
        assertEquals(RainStatus.Rain, OpenMeteoRain.classify(body, nowMs))
    }

    @Test
    fun `clear conditions report no rain`() {
        val body = """{"current":{"time":$nowSec,"precipitation":0.0,"weather_code":1}}"""
        assertEquals(RainStatus.NoRain, OpenMeteoRain.classify(body, nowMs))
    }

    @Test
    fun `fog is not rain`() {
        val body = """{"current":{"time":$nowSec,"precipitation":0.0,"weather_code":45}}"""
        assertEquals(RainStatus.NoRain, OpenMeteoRain.classify(body, nowMs))
    }

    @Test
    fun `wet codes cover drizzle rain snow showers and thunderstorms`() {
        listOf(51, 57, 61, 67, 71, 77, 80, 82, 85, 86, 95, 99).forEach {
            assertTrue("code $it should be wet", OpenMeteoRain.isWetCode(it))
        }
        listOf(0, 1, 2, 3, 45, 48).forEach {
            assertTrue("code $it should be dry", !OpenMeteoRain.isWetCode(it))
        }
    }

    // --- unknown rather than a guess ---

    @Test
    fun `malformed payloads are unknown`() {
        assertEquals(RainStatus.Unknown, OpenMeteoRain.classify("", nowMs))
        assertEquals(RainStatus.Unknown, OpenMeteoRain.classify("not json", nowMs))
        assertEquals(RainStatus.Unknown, OpenMeteoRain.classify("{}", nowMs))
    }

    @Test
    fun `an api error payload is unknown, not dry`() {
        val body = """{"error":true,"reason":"Cannot initialize WeatherVariable from invalid String"}"""
        assertEquals(RainStatus.Unknown, OpenMeteoRain.classify(body, nowMs))
    }

    @Test
    fun `a stale current block is unknown`() {
        val old = nowSec - OpenMeteoRain.CURRENT_MAX_AGE_SEC - 1
        val body = """{"current":{"time":$old,"precipitation":0.0,"weather_code":1}}"""
        assertEquals(RainStatus.Unknown, OpenMeteoRain.classify(body, nowMs))
    }

    @Test
    fun `a bucket with only nulls falls through to current`() {
        val body = minutelyBody(
            offsetsMin = listOf(0),
            precipitation = listOf(null),
            weatherCodes = listOf(null),
            currentPrecipitation = 0.0,
            currentCode = 61,
        )
        assertEquals(RainStatus.Rain, OpenMeteoRain.classify(body, nowMs))
    }

    @Test
    fun `unknown json fields are tolerated`() {
        val body = """
            {"latitude":51.5,"generationtime_ms":0.2,"utc_offset_seconds":0,
             "current_units":{"precipitation":"mm"},
             "current":{"time":$nowSec,"interval":900,"precipitation":0.0,"weather_code":1}}
        """.trimIndent()
        assertEquals(RainStatus.NoRain, OpenMeteoRain.classify(body, nowMs))
    }

    // --- distance ---

    @Test
    fun `distance between fixes is in meters`() {
        // ~1 degree of latitude is ~111 km.
        val a = LatLon(51.0, 0.0)
        val b = LatLon(52.0, 0.0)
        assertEquals(111_195.0, a.distanceToM(b), 500.0)
        assertEquals(0.0, a.distanceToM(a), 0.001)
        assertEquals(a.distanceToM(b), b.distanceToM(a), 0.001)
    }

    private fun minutelyBody(
        offsetsMin: List<Int>,
        precipitation: List<Double?>,
        weatherCodes: List<Int?>,
        currentPrecipitation: Double,
        currentCode: Int,
    ): String {
        val times = offsetsMin.joinToString(",") { (nowSec + it * 60).toString() }
        val precip = precipitation.joinToString(",") { it?.toString() ?: "null" }
        val codes = weatherCodes.joinToString(",") { it?.toString() ?: "null" }
        return """
            {"current":{"time":$nowSec,"precipitation":$currentPrecipitation,"weather_code":$currentCode},
             "minutely_15":{"time":[$times],"precipitation":[$precip],"weather_code":[$codes]}}
        """.trimIndent()
    }
}
