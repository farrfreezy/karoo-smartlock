package io.github.farrfreezy.karoosmartlock.karoo

import io.github.farrfreezy.karoosmartlock.core.Ride
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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

fun StreamState.singleValueOrNull(): Double? =
    (this as? StreamState.Streaming)?.dataPoint?.singleValue

fun RideState.toRide(): Ride = when (this) {
    RideState.Idle -> Ride.Idle
    RideState.Recording -> Ride.Recording
    is RideState.Paused -> Ride.Paused(auto)
}
