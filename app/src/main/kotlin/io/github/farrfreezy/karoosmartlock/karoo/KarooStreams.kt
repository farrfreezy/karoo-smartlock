package io.github.farrfreezy.karoosmartlock.karoo

import io.github.farrfreezy.karoosmartlock.core.LatLon
import io.github.farrfreezy.karoosmartlock.core.Ride
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.mapNotNull

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

fun StreamState.singleValueOrNull(): Double? =
    (this as? StreamState.Streaming)?.dataPoint?.singleValue

fun RideState.toRide(): Ride = when (this) {
    RideState.Idle -> Ride.Idle
    RideState.Recording -> Ride.Recording
    is RideState.Paused -> Ride.Paused(auto)
}
