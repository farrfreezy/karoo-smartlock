package io.github.farrfreezy.karoosmartlock.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
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

/** One time bucket of a forecast series. [spanSec] is how long the bucket covers. */
data class Bucket(
    val startSec: Long,
    val spanSec: Long,
    val precipMm: Double?,
    val weatherCode: Int?,
    val probabilityPct: Int? = null,
) {
    fun covers(atSec: Long): Boolean = atSec >= startSec && atSec < startSec + spanSec
}

/** The forecast for one place — the rider's position, or a sampled point up the route. */
data class PointForecast(
    val at: LatLon,
    val distanceAlongRouteM: Double,
    val current: Bucket?,
    val minutely: List<Bucket>,
    val hourly: List<Bucket>,
) {
    /**
     * Conditions here at [atSec], preferring the finest series that covers it.
     * `current` is only consulted for the present, since it describes the hour
     * just gone rather than any future hour.
     */
    fun statusAt(atSec: Long): RainStatus {
        minutely.firstOrNull { it.covers(atSec) }?.let { bucket ->
            OpenMeteoRain.statusOf(bucket).takeIf { it != RainStatus.Unknown }?.let { return it }
        }
        hourly.firstOrNull { it.covers(atSec) }?.let { bucket ->
            OpenMeteoRain.statusOf(bucket).takeIf { it != RainStatus.Unknown }?.let { return it }
        }
        val now = current ?: return RainStatus.Unknown
        val age = atSec - now.startSec
        if (age < 0 || age > OpenMeteoRain.CURRENT_MAX_AGE_SEC) return RainStatus.Unknown
        return OpenMeteoRain.statusOf(now)
    }

    /** Highest hourly chance of rain in `[fromSec, toSec]`, or null if unforecast. */
    fun maxProbabilityPct(fromSec: Long, toSec: Long): Int? = hourly
        .filter { it.startSec + it.spanSec > fromSec && it.startSec <= toSec }
        .mapNotNull { it.probabilityPct }
        .maxOrNull()
}

/**
 * A decoded forecast covering the rider's position and, when a route is loaded, the
 * places they are expected to reach.
 */
data class RainForecast(val points: List<PointForecast>) {

    /** The point nearest [distanceAlongRouteM]; the rider's own position when unrouted. */
    fun nearest(distanceAlongRouteM: Double): PointForecast? =
        points.minByOrNull { kotlin.math.abs(it.distanceAlongRouteM - distanceAlongRouteM) }

    /** Conditions where the rider is now. */
    fun statusNow(nowMs: Long, distanceAlongRouteM: Double): RainStatus =
        nearest(distanceAlongRouteM)?.statusAt(nowMs / 1000) ?: RainStatus.Unknown

    /**
     * Whether rain is expected at any point the rider passes between now and
     * `now + leadMs`, walking forward in [OpenMeteoRain.LEAD_STEP_SEC] steps and
     * advancing their position at [speedMps].
     *
     * With no route loaded there is a single point, so this degrades to "does the
     * forecast here turn wet within the lead" — still useful, just blind to the fact
     * that the rider is moving.
     */
    fun rainWithin(nowMs: Long, leadMs: Long, distanceAlongRouteM: Double, speedMps: Double): Boolean {
        if (leadMs <= 0) return false
        val nowSec = nowMs / 1000
        val leadSec = leadMs / 1000
        var offset = 0L
        while (offset <= leadSec) {
            val where = distanceAlongRouteM + speedMps.coerceAtLeast(0.0) * offset
            if (nearest(where)?.statusAt(nowSec + offset) == RainStatus.Rain) return true
            offset += OpenMeteoRain.LEAD_STEP_SEC
        }
        return false
    }

    /**
     * Highest chance of rain the rider is forecast to meet, matching each sampled
     * point against the hour they are expected to reach it (widened by
     * [OpenMeteoRain.ARRIVAL_TOLERANCE_SEC], because the arrival estimate is only as
     * good as the assumed speed).
     *
     * Returns null when no point carries a probability, which keeps "no data" distinct
     * from "0% chance".
     */
    fun maxProbabilityPct(nowMs: Long, distanceAlongRouteM: Double, speedMps: Double): Int? {
        val nowSec = nowMs / 1000
        val speed = speedMps.coerceAtLeast(MIN_PLANNING_SPEED_MPS)
        return points.mapNotNull { point ->
            val ahead = (point.distanceAlongRouteM - distanceAlongRouteM).coerceAtLeast(0.0)
            val arrival = nowSec + (ahead / speed).toLong()
            point.maxProbabilityPct(
                fromSec = arrival - OpenMeteoRain.ARRIVAL_TOLERANCE_SEC,
                toSec = arrival + OpenMeteoRain.ARRIVAL_TOLERANCE_SEC,
            )
        }.maxOrNull()
    }

    private companion object {
        /** Guards against dividing arrival times by a stationary rider's 0 m/s. */
        const val MIN_PLANNING_SPEED_MPS = 2.0
    }
}

/**
 * Request building and response decoding for the Open-Meteo forecast API, used by
 * [io.github.farrfreezy.karoosmartlock.weather.OpenMeteoRainSource].
 *
 * Pure Kotlin on purpose: the HTTP call itself is a thin Android-layer wrapper around
 * karoo-ext, so everything that can actually be wrong — URL formatting, JSON shape,
 * which bucket counts as "now", what counts as rain — is covered by plain JVM tests.
 *
 * Data is from [open-meteo.com](https://open-meteo.com) (CC BY 4.0). The free API
 * needs no key and allows non-commercial use under generous rate limits. Note that
 * multi-point requests are weighted per location, so a full route lookup costs about
 * as much quota as [ROUTE_MAX_POINTS] single requests — still trivial against a
 * 5,000/hour ceiling at one lookup per [io.github.farrfreezy.karoosmartlock.weather.OpenMeteoRainSource.REFRESH_INTERVAL_MS].
 *
 * ### Which forecast model a rider gets
 *
 * [MODELS] pins Open-Meteo's `best_match`, which picks the highest-resolution model
 * available for the coordinates and blends it with global models. In the UK and
 * Ireland that mix ends in the Met Office UKV at 2 km; Central Europe gets ICON-D2,
 * France AROME, North America HRRR, and everywhere else falls back to ~10 km global
 * models. It is also the only choice that degrades gracefully when a rider leaves a
 * regional model's coverage mid-ride — pinning a specific regional model would simply
 * stop returning data at its boundary.
 *
 * `best_match` is already the default for this endpoint, so sending it changes nothing
 * today; it is explicit so that a future change to that default cannot silently
 * downgrade riders to a coarser model.
 *
 * Grid cells are picked on land by default, which is what a road rider wants near a
 * coast, so `cell_selection` is left alone.
 */
object OpenMeteoRain {

    const val BASE_URL = "https://api.open-meteo.com/v1/forecast"

    /** Strictly-greater-than comparison, so any measurable precipitation counts. */
    const val RAIN_THRESHOLD_MM = 0.0

    /** Open-Meteo's seamless best-model-per-location selection. See the class docs. */
    const val MODELS = "best_match"

    /** Each `minutely_15` entry covers the 15 minutes starting at its timestamp. */
    const val MINUTELY_BUCKET_SEC = 15 * 60L

    const val HOURLY_BUCKET_SEC = 60 * 60L

    /** `current` is the preceding hour's sum, so it is useless once much older. */
    const val CURRENT_MAX_AGE_SEC = 2 * 60 * 60L

    /** Hours of forecast requested — comfortably longer than a lead time or a ride leg. */
    const val FORECAST_HOURS = 12

    /** Route points per request. Matches karoo-headwind, and keeps the query weight ~10. */
    const val ROUTE_MAX_POINTS = 10

    /** Granularity of the look-ahead walk in [RainForecast.rainWithin]. */
    const val LEAD_STEP_SEC = 5 * 60L

    /** Slack around an estimated arrival time, since the speed estimate is approximate. */
    const val ARRIVAL_TOLERANCE_SEC = 60 * 60L

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Coordinates are rounded to ~11 m: far more precision than weather needs, and it
     * lets Open-Meteo serve repeat requests from its cache.
     */
    fun requestUrl(samples: List<RouteSample>): String {
        require(samples.isNotEmpty()) { "at least one point is required" }
        val lats = samples.joinToString(",") { String.format(Locale.US, "%.4f", it.at.lat) }
        val lons = samples.joinToString(",") { String.format(Locale.US, "%.4f", it.at.lon) }
        return BASE_URL +
            "?latitude=" + lats +
            "&longitude=" + lons +
            "&current=precipitation,weather_code" +
            "&minutely_15=precipitation,weather_code" +
            "&hourly=precipitation,precipitation_probability,weather_code" +
            "&models=" + MODELS +
            "&forecast_days=1" +
            "&forecast_hours=" + FORECAST_HOURS +
            "&timeformat=unixtime"
    }

    fun requestUrl(fix: LatLon): String = requestUrl(listOf(RouteSample(fix, 0.0)))

    /**
     * Decode a response into [samples]-aligned forecasts. Open-Meteo returns a bare
     * object for a single coordinate and an array for several.
     *
     * Returns null for anything unparseable, which callers treat as "no reading" rather
     * than as "dry".
     */
    fun parse(body: String, samples: List<RouteSample>): RainForecast? {
        val locations = runCatching {
            if (samples.size == 1) {
                listOf(json.decodeFromString(Location.serializer(), body))
            } else {
                json.decodeFromString(ListSerializer(Location.serializer()), body)
            }
        }.getOrNull() ?: return null
        if (locations.isEmpty()) return null

        val points = locations.zip(samples) { location, sample ->
            val now = location.current
            PointForecast(
                at = sample.at,
                distanceAlongRouteM = sample.distanceAlongRouteM,
                current = if (now?.time == null) {
                    null
                } else {
                    Bucket(now.time, HOURLY_BUCKET_SEC, now.precipitationMm, now.weatherCode)
                },
                minutely = location.minutely15?.toBuckets(MINUTELY_BUCKET_SEC).orEmpty(),
                hourly = location.hourly?.toBuckets(HOURLY_BUCKET_SEC).orEmpty(),
            )
        }
        return RainForecast(points)
    }

    /**
     * Single-point convenience used when no route is loaded: decode and read the
     * conditions right now.
     */
    fun classify(body: String, nowMs: Long): RainStatus {
        val forecast = parse(body, listOf(RouteSample(LatLon(0.0, 0.0), 0.0))) ?: return RainStatus.Unknown
        return forecast.statusNow(nowMs, 0.0)
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

    internal fun statusOf(bucket: Bucket): RainStatus {
        val mm = bucket.precipMm
        val code = bucket.weatherCode
        val wet = (mm != null && mm > RAIN_THRESHOLD_MM) || (code != null && isWetCode(code))
        return when {
            wet -> RainStatus.Rain
            mm != null || code != null -> RainStatus.NoRain
            else -> RainStatus.Unknown
        }
    }

    private fun Series.toBuckets(spanSec: Long): List<Bucket> {
        val times = time ?: return emptyList()
        return times.mapIndexed { i, start ->
            Bucket(
                startSec = start,
                spanSec = spanSec,
                precipMm = precipitationMm?.getOrNull(i),
                weatherCode = weatherCode?.getOrNull(i),
                probabilityPct = probabilityPct?.getOrNull(i),
            )
        }
    }

    @Serializable
    private data class Location(
        val current: Current? = null,
        @SerialName("minutely_15") val minutely15: Series? = null,
        val hourly: Series? = null,
    )

    @Serializable
    private data class Current(
        val time: Long? = null,
        @SerialName("precipitation") val precipitationMm: Double? = null,
        @SerialName("weather_code") val weatherCode: Int? = null,
    )

    @Serializable
    private data class Series(
        val time: List<Long>? = null,
        @SerialName("precipitation") val precipitationMm: List<Double?>? = null,
        @SerialName("weather_code") val weatherCode: List<Int?>? = null,
        @SerialName("precipitation_probability") val probabilityPct: List<Int?>? = null,
    )
}
