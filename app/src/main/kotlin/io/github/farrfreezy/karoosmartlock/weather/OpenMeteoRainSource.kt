package io.github.farrfreezy.karoosmartlock.weather

import android.util.Log
import io.github.farrfreezy.karoosmartlock.core.LatLon
import io.github.farrfreezy.karoosmartlock.core.OpenMeteoRain
import io.github.farrfreezy.karoosmartlock.core.RainStatus
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
 * Rain detection by querying [Open-Meteo](https://open-meteo.com) directly over
 * karoo-ext's HTTP bridge, so the trigger works with no other extension installed.
 *
 * The bridge routes over wifi when connected and otherwise over Bluetooth to the
 * Companion app, which is the realistic in-ride path — and means requests fail
 * whenever the phone is out of reach. Failures and expired readings both surface
 * as [RainStatus.Unknown], which leaves the trigger inert rather than asserting
 * "dry" and releasing a lock the rider wants.
 *
 * Nothing is emitted until there is an actual answer, so the debug simulator can
 * still drive rain state on a device with no connectivity.
 */
class OpenMeteoRainSource(
    private val karoo: KarooSystemService,
    private val locations: Flow<LatLon>,
    private val refreshIntervalMs: Long = REFRESH_INTERVAL_MS,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    private val maxRetryDelayMs: Long = MAX_RETRY_DELAY_MS,
    private val moveThresholdM: Double = MOVE_THRESHOLD_M,
    private val staleAfterMs: Long = STALE_AFTER_MS,
    private val tickMs: Long = TICK_MS,
    private val requestTimeoutMs: Long = REQUEST_TIMEOUT_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : RainSource {

    override fun observeRain(): Flow<RainStatus> = channelFlow {
        val latest = MutableStateFlow<LatLon?>(null)
        launch { locations.collect { latest.value = it } }

        var lastFetchAtMs = 0L
        var nextFetchAtMs = 0L
        var backoffMs = retryDelayMs
        var fetchedAtPos: LatLon? = null
        var known: RainStatus? = null
        var knownAtMs = 0L
        var emitted: RainStatus? = null

        while (isActive) {
            val here = latest.value
            val since = fetchedAtPos
            val now = nowMs()
            val moved = here != null && since != null && here.distanceToM(since) >= moveThresholdM
            // Movement pulls the next poll forward, but never below the floor —
            // otherwise a fast descent would fire a request every tick.
            val due = now >= nextFetchAtMs || (moved && now - lastFetchAtMs >= minIntervalMs)

            if (here != null && due) {
                lastFetchAtMs = now
                val status = fetch(here)
                if (status == null) {
                    nextFetchAtMs = nowMs() + backoffMs
                    backoffMs = (backoffMs * 2).coerceAtMost(maxRetryDelayMs)
                } else {
                    fetchedAtPos = here
                    backoffMs = retryDelayMs
                    nextFetchAtMs = nowMs() + refreshIntervalMs
                    if (status != RainStatus.Unknown) {
                        known = status
                        knownAtMs = nowMs()
                    }
                }
            }

            // An old reading must not pin the lock open or shut indefinitely once
            // connectivity is gone.
            val current = when {
                known == null -> null
                nowMs() - knownAtMs > staleAfterMs -> RainStatus.Unknown
                else -> known
            }
            if (current != null && current != emitted) {
                emitted = current
                send(current)
            }
            delay(tickMs)
        }
    }

    private suspend fun fetch(fix: LatLon): RainStatus? {
        val response = httpGet(OpenMeteoRain.requestUrl(fix)) ?: return null
        if (response.statusCode !in 200..299) {
            Log.w(TAG, "Open-Meteo returned ${response.statusCode}: ${response.error}")
            return null
        }
        val body = response.body?.decodeToString() ?: return null
        return OpenMeteoRain.classify(body, nowMs())
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

        /** ~6 requests/hour at rest, an order of magnitude under Open-Meteo's free limits. */
        const val REFRESH_INTERVAL_MS = 10 * 60_000L
        const val MIN_INTERVAL_MS = 2 * 60_000L
        const val RETRY_DELAY_MS = 30_000L
        const val MAX_RETRY_DELAY_MS = 5 * 60_000L
        const val MOVE_THRESHOLD_M = 3_000.0
        const val STALE_AFTER_MS = 45 * 60_000L
        const val TICK_MS = 15_000L
        const val REQUEST_TIMEOUT_MS = 30_000L
    }
}
