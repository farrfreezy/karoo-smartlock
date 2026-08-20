package io.github.farrfreezy.karoosmartlock.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Thin coroutine driver around [LockReducer]. All calls are expected on a single
 * (main) dispatcher; the karoo/service layer marshals events onto it.
 */
class LockController(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val tickIntervalMs: Long = 1_000L,
) {
    private val _state = MutableStateFlow(ControllerState())
    val state: StateFlow<ControllerState> = _state.asStateFlow()
    val commands: Flow<LockCommand> = _state.map { it.command }.distinctUntilChanged()

    private var tickJob: Job? = null

    fun onEvent(event: LockEvent) {
        _state.value = LockReducer.reduce(_state.value, event, nowMs())
        manageTicker()
    }

    private fun manageTicker() {
        val rideActive = _state.value.ride != Ride.Idle
        if (rideActive && tickJob == null) {
            tickJob = scope.launch {
                while (isActive && _state.value.ride != Ride.Idle) {
                    delay(tickIntervalMs)
                    _state.value = LockReducer.reduce(_state.value, LockEvent.Tick, nowMs())
                }
                tickJob = null
            }
        } else if (!rideActive) {
            tickJob?.cancel()
            tickJob = null
        }
    }
}
