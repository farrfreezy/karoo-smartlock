package io.github.farrfreezy.karoosmartlock.weather

import android.util.Log
import io.github.farrfreezy.karoosmartlock.core.LatLon
import io.github.farrfreezy.karoosmartlock.core.OpenMeteoRain
import io.github.farrfreezy.karoosmartlock.core.RainForecast
import io.github.farrfreezy.karoosmartlock.core.RainStatus
import io.github.farrfreezy.karoosmartlock.core.RouteSample
import io.github.farrfreezy.karoosmartlock.karoo.RouteProgress
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

/**
 * How the rider wants rain translated into a lock, read live from settings so an edit
 * mid-ride takes effect without restarting the poller.
 */
data class RainPolicy(
    val leadMs: Long = 0L,
    val wholeRideEnabled: Boolean = false,
    val wholeRideProbabilityPct: Int = 65,
)

/**
 * Rain detection by querying [Open-Meteo](https://open-meteo.com) directly over
 * karoo-ext's HTTP bridge, so the trigger works with no other extension installed.
 *
 * The bridge routes over wifi when connected and otherwise over Bluetooth to the
 * Companion app. That connection is the weak link on a ride, which is why this fetches
 * a *forecast* rather than an observation: when a route is loaded it asks for the
 * places the rider is heading, then answers from that cache on every tick. One
 * successful fetch keeps rain locking working after the phone drops out.
 *
 * Failures and expired forecasts both surface as [RainStatus.Unknown], which leaves the
 * trigger inert rather than asserting "dry" and releasing a lock the rider wants.
 * Nothing is emitted until there is an actual answer, so the debug simulator can still
 * drive rain state on a device with no connectivity.
 */
class OpenMeteoRainSource(
    private val karoo: KarooSystemService,
    private val locations: Flow<LatLon>,
    private val routes: Flow<RouteProgress?>,
    private val speeds: Flow<Double>,
    private val policies: Flow<RainPolicy>,
    private val refreshIntervalMs: Long = REFRESH_INTERVAL_MS,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    private val maxRetryDelayMs: Long = MAX_RETRY_DELAY_MS,
    private val moveThresholdM: Double = MOVE_THRESHOLD_M,
    private val forecastMaxAgeMs: Long = FORECAST_MAX_AGE_MS,
    private val tickMs: Long = TICK_MS,
    private val requestTimeoutMs: Long = REQUEST_TIMEOUT_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : RainSource {

    override fun observeRain(): Flow<RainStatus> = channelFlow {
        val latestFix = MutableStateFlow<LatLon?>(null)
        val latestRoute = MutableStateFlow<RouteProgress?>(null)
        val latestSpeed = MutableStateFlow(DEFAULT_SPEED_MPS)
        val latestPolicy = MutableStateFlow(RainPolicy())
        launch { locations.collect { latestFix.value = it } }
        launch { routes.collect { latestRoute.value = it } }
        launch { speeds.collect { latestSpeed.value = it } }
        launch { policies.collect { latestPolicy.value = it } }

        var lastFetchAtMs = 0L
        var nextFetchAtMs = 0L
        var backoffMs = retryDelayMs
        var fetchedAtPos: LatLon? = null
        var forecast: RainForecast? = null
        var forecastAtMs = 0L
        /** Once the ride is judged wet, it stays wet — that is the point of the mode. */
        var wholeRideLatched = false
        var emitted: RainStatus? = null

        while (isActive) {
            val here = latestFix.value
            val route = latestRoute.value?.takeIf { it.onRoute }
            val speed = latestSpeed.value
            val policy = latestPolicy.value
            val since = fetchedAtPos
            val now = nowMs()

            val moved = here != null && since != null && here.distanceToM(since) >= moveThresholdM
            val due = now >= nextFetchAtMs || (moved && now - lastFetchAtMs >= minIntervalMs)

            if (here != null && due) {
                lastFetchAtMs = now
                val samples = samplesFor(here, route, speed)
                val fetched = fetch(samples)
                if (fetched == null) {
                    nextFetchAtMs = nowMs() + backoffMs
                    backoffMs = (backoffMs * 2).coerceAtMost(maxRetryDelayMs)
                } else {
                    fetchedAtPos = here
                    backoffMs = retryDelayMs
                    nextFetchAtMs = nowMs() + refreshIntervalMs
                    forecast = fetched
                    forecastAtMs = nowMs()
                }
            }

            // Answer from the cached forecast every tick, so the lock keeps working
            // between fetches and after the phone goes out of range.
            val cached = forecast?.takeIf { nowMs() - forecastAtMs <= forecastMaxAgeMs }
            val distance = route?.distanceAlongRouteM ?: 0.0
            val current = if (cached == null) {
                null
            } else {
                if (policy.wholeRideEnabled && !wholeRideLatched) {
                    val chance = cached.maxProbabilityPct(nowMs(), distance, speed)
                    if (chance != null && chance >= policy.wholeRideProbabilityPct) {
                        Log.i(TAG, "Rain chance $chance% along route, locking for the ride")
                        wholeRideLatched = true
                    }
                }
                when {
                    wholeRideLatched -> RainStatus.Rain
                    else -> {
                        val nowStatus = cached.statusNow(nowMs(), distance)
                        if (nowStatus != RainStatus.Rain && policy.leadMs > 0 &&
                            cached.rainWithin(nowMs(), policy.leadMs, distance, speed)
                        ) {
                            RainStatus.Rain
                        } else {
                            nowStatus
                        }
                    }
                }
            }

            if (current != null && current != emitted) {
                emitted = current
                send(current)
            }
            delay(tickMs)
        }
    }

    /**
     * Where to ask about: the rider's position, plus — when a route is loaded — the
     * points roughly an hour of riding apart that they are expected to reach.
     */
    private fun samplesFor(here: LatLon, route: RouteProgress?, speedMps: Double): List<RouteSample> {
        if (route == null) return listOf(RouteSample(here, 0.0))
        val spacing = (speedMps.coerceAtLeast(MIN_SAMPLING_SPEED_MPS) * 3600.0)
            .coerceIn(MIN_SPACING_M, MAX_SPACING_M)
        return route.path.sampleAhead(
            fromDistanceM = route.distanceAlongRouteM,
            spacingM = spacing,
            maxPoints = OpenMeteoRain.ROUTE_MAX_POINTS,
        )
    }

    private suspend fun fetch(samples: List<RouteSample>): RainForecast? {
        val response = httpGet(OpenMeteoRain.requestUrl(samples)) ?: return null
        if (response.statusCode !in 200..299) {
            Log.w(TAG, "Open-Meteo returned ${response.statusCode}: ${response.error}")
            return null
        }
        val body = response.body?.decodeToString() ?: return null
        return OpenMeteoRain.parse(body, samples)
    }

    @OptIn(FlowPreview::class)
    private suspend fun httpGet(url: String): HttpResponseState.Complete? = try {
        callbackFlow {
            val id = karoo.addConsumer(
                OnHttpResponse.MakeHttpRequest(
                    method = "GET",
                    url = url,
                    headers = mapOf("User-Agent" to USER_AGENT),
                    // A queued request would deliver weather long after it mattered;
                    // failing fast and retrying is the right shape for a rain lock.
                    waitForConnection = false,
                ),
                onError = { message -> close(IOException(message)) },
            ) { event: OnHttpResponse ->
                (event.state as? HttpResponseState.Complete)?.let {
                    trySend(it)
                    close()
                }
            }
            awaitClose { karoo.removeConsumer(id) }
        }.timeout(requestTimeoutMs.milliseconds).first()
    } catch (e: TimeoutCancellationException) {
        Log.w(TAG, "Open-Meteo request timed out")
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Open-Meteo request failed: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "SmartLock"
        private const val USER_AGENT = "karoo-smartlock"

        /** ~6 fetches/hour. Multi-point route requests are weighted per location, so a
         * full route lookup costs about 10 calls of quota — still far under the limits. */
        const val REFRESH_INTERVAL_MS = 10 * 60_000L
        const val MIN_INTERVAL_MS = 2 * 60_000L
        const val RETRY_DELAY_MS = 30_000L
        const val MAX_RETRY_DELAY_MS = 5 * 60_000L
        const val MOVE_THRESHOLD_M = 3_000.0

        /** A forecast this old is discarded outright, however far ahead it still reaches. */
        const val FORECAST_MAX_AGE_MS = 3 * 60 * 60_000L
        const val TICK_MS = 15_000L
        const val REQUEST_TIMEOUT_MS = 30_000L

        /** Used for sampling and arrival estimates before any speed has been seen (~20 km/h). */
        const val DEFAULT_SPEED_MPS = 5.5
        const val MIN_SAMPLING_SPEED_MPS = 2.8
        const val MIN_SPACING_M = 5_000.0
        const val MAX_SPACING_M = 60_000.0
    }
}
