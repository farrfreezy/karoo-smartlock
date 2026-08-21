package io.github.farrfreezy.karoosmartlock.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A GPS fix, kept SDK-free so the weather logic stays unit-testable. */
data class LatLon(val lat: Double, val lon: Double) {
    /** Great-circle distance in meters (haversine; good enough at ride scale). */
    fun distanceToM(other: LatLon): Double {
        val dLat = Math.toRadians(other.lat - lat)
        val dLon = Math.toRadians(other.lon - lon)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat)) * cos(Math.toRadians(other.lat)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    private companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
    }
}

/**
 * Request building and response classification for the Open-Meteo forecast API,
 * used by [io.github.farrfreezy.karoosmartlock.weather.OpenMeteoRainSource].
 *
 * Pure Kotlin on purpose: the HTTP call itself is a thin Android-layer wrapper
 * around karoo-ext, so everything that can actually be wrong — URL formatting,
 * JSON shape, which bucket counts as "now", what counts as rain — is covered by
 * plain JVM unit tests.
 *
 * Data is from [open-meteo.com](https://open-meteo.com) (CC BY 4.0). The free
 * API needs no key and allows non-commercial use under generous rate limits; a
 * ride polls it a handful of times per hour.
 */
object OpenMeteoRain {

    const val BASE_URL = "https://api.open-meteo.com/v1/forecast"

    /** Strictly-greater-than comparison, so any measurable precipitation counts. */
    const val RAIN_THRESHOLD_MM = 0.0

    /** Each `minutely_15` entry covers the 15 minutes starting at its timestamp. */
    const val MINUTELY_BUCKET_SEC = 15 * 60L

    /** `current` is the preceding hour's sum, so it is useless once much older. */
    const val CURRENT_MAX_AGE_SEC = 2 * 60 * 60L

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Coordinates are rounded to ~11 m: far more precision than weather needs, and
     * it lets Open-Meteo serve repeat requests from its cache.
     */
    fun requestUrl(fix: LatLon): String = BASE_URL +
        "?latitude=" + String.format(Locale.US, "%.4f", fix.lat) +
        "&longitude=" + String.format(Locale.US, "%.4f", fix.lon) +
        "&current=precipitation,weather_code" +
        "&minutely_15=precipitation,weather_code" +
        "&forecast_days=1" +
        "&timeformat=unixtime"

    /**
     * Classify a forecast response into a [RainStatus].
     *
     * Prefers the `minutely_15` bucket covering [nowMs] — 15-minute resolution
     * where the underlying model provides it — and falls back to `current`,
     * whose precipitation is the *preceding hour's* total and therefore lags
     * both the start and the end of a shower.
     *
     * Anything unparseable, absent, or too old yields [RainStatus.Unknown], which
     * leaves the rain trigger inert rather than guessing "dry".
     */
    fun classify(body: String, nowMs: Long): RainStatus {
        val forecast = runCatching {
            json.decodeFromString(OpenMeteoForecast.serializer(), body)
        }.getOrNull() ?: return RainStatus.Unknown
        val nowSec = nowMs / 1000

        forecast.minutely15?.bucketAt(nowSec)?.let { return it }

        val current = forecast.current ?: return RainStatus.Unknown
        if (current.time != null && nowSec - current.time > CURRENT_MAX_AGE_SEC) {
            return RainStatus.Unknown
        }
        return statusOf(current.precipitationMm, current.weatherCode)
    }

    /** WMO weather codes that mean water (or snow) is landing on the screen. */
    fun isWetCode(code: Int): Boolean = when (code) {
        in 51..57 -> true // drizzle, freezing drizzle
        in 61..67 -> true // rain, freezing rain
        in 71..77 -> true // snow fall, snow grains
        in 80..82 -> true // rain showers
        85, 86 -> true // snow showers
        95, 96, 99 -> true // thunderstorm
        else -> false // includes 0-3 clear/cloudy and 45/48 fog
    }

    private fun Minutely15.bucketAt(nowSec: Long): RainStatus? {
        val times = time ?: return null
        // Latest bucket that has already started and has not yet elapsed.
        val index = times.indexOfLast { it <= nowSec }
        if (index < 0 || nowSec >= times[index] + MINUTELY_BUCKET_SEC) return null
        val mm = precipitationMm?.getOrNull(index)
        val code = weatherCode?.getOrNull(index)
        if (mm == null && code == null) return null
        return statusOf(mm, code)
    }

    private fun statusOf(mm: Double?, code: Int?): RainStatus {
        val wet = (mm != null && mm > RAIN_THRESHOLD_MM) || (code != null && isWetCode(code))
        return when {
            wet -> RainStatus.Rain
            mm != null || code != null -> RainStatus.NoRain
            else -> RainStatus.Unknown
        }
    }

    @Serializable
    private data class OpenMeteoForecast(
        val current: Current? = null,
        @SerialName("minutely_15") val minutely15: Minutely15? = null,
    )

    @Serializable
    private data class Current(
        val time: Long? = null,
        @SerialName("precipitation") val precipitationMm: Double? = null,
        @SerialName("weather_code") val weatherCode: Int? = null,
    )

    @Serializable
    private data class Minutely15(
        val time: List<Long>? = null,
        @SerialName("precipitation") val precipitationMm: List<Double?>? = null,
        @SerialName("weather_code") val weatherCode: List<Int?>? = null,
    )
}
