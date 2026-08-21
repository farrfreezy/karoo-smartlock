package io.github.farrfreezy.karoosmartlock.karoo

import io.github.farrfreezy.karoosmartlock.core.LatLon
import io.github.farrfreezy.karoosmartlock.core.Ride
import io.github.farrfreezy.karoosmartlock.core.RoutePath
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart

fun KarooSystemService.streamRideState(): Flow<RideState> = callbackFlow {
    val id = addConsumer { rideState: RideState -> trySendBlocking(rideState) }
    awaitClose { removeConsumer(id) }
}

fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> = callbackFlow {
    val id = addConsumer(OnStreamState.StartStreaming(dataTypeId)) { event: OnStreamState ->
        trySendBlocking(event.state)
    }
    awaitClose { removeConsumer(id) }
}

fun KarooSystemService.streamUserProfile(): Flow<UserProfile> = callbackFlow {
    val id = addConsumer { profile: UserProfile -> trySendBlocking(profile) }
    awaitClose { removeConsumer(id) }
}

/**
 * Current position, dropping fixes the Karoo reports as too imprecise to be worth
 * a weather lookup. Uses the LOCATION data type rather than [io.hammerhead.karooext.models.OnLocationChanged]
 * precisely because it carries an accuracy field.
 */
fun KarooSystemService.streamLocation(maxAccuracyM: Double = MAX_LOCATION_ACCURACY_M): Flow<LatLon> =
    streamDataFlow(DataType.Type.LOCATION).mapNotNull { state ->
        val values = (state as? StreamState.Streaming)?.dataPoint?.values ?: return@mapNotNull null
        val lat = values[DataType.Field.LOC_LATITUDE] ?: return@mapNotNull null
        val lon = values[DataType.Field.LOC_LONGITUDE] ?: return@mapNotNull null
        val accuracy = values[DataType.Field.LOC_ACCURACY]
        if (accuracy != null && accuracy > maxAccuracyM) null else LatLon(lat, lon)
    }

private const val MAX_LOCATION_ACCURACY_M = 500.0

/** Current ground speed in m/s, used to estimate where the rider will be. */
fun KarooSystemService.streamSpeed(): Flow<Double> =
    streamDataFlow(DataType.Type.SPEED).mapNotNull { it.singleValueOrNull() }

fun KarooSystemService.streamNavigationState(): Flow<OnNavigationState> = callbackFlow {
    val id = addConsumer { state: OnNavigationState -> trySendBlocking(state) }
    awaitClose { removeConsumer(id) }
}

/**
 * The loaded route and how far along it the rider is, or null when not navigating a
 * route. The polyline is decoded only when it actually changes — a reroute produces a
 * new one, ordinary progress does not.
 */
fun KarooSystemService.streamRoute(): Flow<RouteProgress?> {
    val loaded = streamNavigationState()
        .map { it.state as? OnNavigationState.NavigationState.NavigatingRoute }
        .distinctUntilChangedBy { it?.routePolyline }
        .map { nav ->
            nav ?: return@map null
            RoutePath.fromPolyline(nav.routePolyline)?.let { LoadedRoute(it, nav.routeDistance) }
        }

    // Starting with null keeps the combine alive when nothing is navigating, since
    // DISTANCE_TO_DESTINATION only streams during navigation.
    val progress = streamDataFlow(DataType.Type.DISTANCE_TO_DESTINATION)
        .map { (it as? StreamState.Streaming)?.dataPoint?.values }
        .onStart { emit(null) }

    return combine(loaded, progress) { route, values ->
        route ?: return@combine null
        val toDestination = values?.get(DataType.Field.DISTANCE_TO_DESTINATION)
        RouteProgress(
            path = route.path,
            distanceAlongRouteM = toDestination
                ?.let { (route.lengthM - it).coerceIn(0.0, route.lengthM) }
                ?: 0.0,
            // Absent means navigation has not reported yet; assume on route rather
            // than discarding a route the rider deliberately loaded.
            onRoute = values?.get(DataType.Field.ON_ROUTE)?.let { it > 0.5 } ?: true,
        )
    }
}

/** Route geometry paired with the length the Karoo reports for it. */
private data class LoadedRoute(val path: RoutePath, val lengthM: Double)

data class RouteProgress(
    val path: RoutePath,
    val distanceAlongRouteM: Double,
    /** False when the rider has left the route, so forecasts along it no longer apply. */
    val onRoute: Boolean,
)

fun StreamState.singleValueOrNull(): Double? =
    (this as? StreamState.Streaming)?.dataPoint?.singleValue

fun RideState.toRide(): Ride = when (this) {
    RideState.Idle -> Ride.Idle
    RideState.Recording -> Ride.Recording
    is RideState.Paused -> Ride.Paused(auto)
}
