package io.github.farrfreezy.karoosmartlock.core

/**
 * Pure state machine deciding when the screen lock engages and releases.
 *
 * All time is passed in via [reduce]'s `nowMs`, so every behavior is unit-testable
 * with a hand-advanced clock.
 *
 * Precedence (highest first):
 * 1. Ride Idle → unlocked and automation state reset (an explicit manual lock survives).
 * 2. `unlockWhilePaused` and ride paused → unlocked, automation state preserved.
 * 3. Manual lock → only manual unlock (or the rules above) releases it.
 * 4. Manual unlock suppression → conditions true at unlock time need a fresh
 *    rising edge before they may re-lock; one-shot triggers won't refire until
 *    the next pause/resume cycle.
 * 5. Otherwise: one-shot triggers lock immediately when they fire; condition
 *    triggers lock after [SmartLockSettings.lockDebounceSec] sustained-true and,
 *    in [UnlockMode.AUTO], unlock after [SmartLockSettings.autoUnlockHoldSec]
 *    sustained-false (one-shot latches never auto-unlock).
 */
object LockReducer {
    const val SENSOR_STALE_MS = 15_000L

    fun reduce(state: ControllerState, event: LockEvent, nowMs: Long): ControllerState {
        val s = when (event) {
            is LockEvent.SettingsChanged -> state.copy(settings = event.settings)
            is LockEvent.RideStateChanged -> onRideChanged(state, event.ride, nowMs)
            is LockEvent.SensorUpdate -> state.copy(
                sensors = if (event.value == null) {
                    state.sensors - event.sensor
                } else {
                    state.sensors + (event.sensor to SensorReading(event.value, nowMs))
                },
            )
            is LockEvent.RainUpdate -> state.copy(rain = event.status)
            LockEvent.ManualUnlock -> manualUnlock(state, nowMs)
            LockEvent.ManualToggle ->
                if (state.command is LockCommand.Locked) {
                    manualUnlock(state, nowMs)
                } else {
                    state.copy(command = LockCommand.Locked(LockReason.MANUAL))
                }
            LockEvent.Tick -> accumulateRidingTime(state, nowMs)
        }
        return evaluate(s, nowMs)
    }

    private fun accumulateRidingTime(state: ControllerState, nowMs: Long): ControllerState {
        if (state.ride != Ride.Recording) return state
        val last = state.lastTickAtMs ?: return state.copy(lastTickAtMs = nowMs)
        return state.copy(
            ridingTimeMs = state.ridingTimeMs + (nowMs - last).coerceAtLeast(0),
            lastTickAtMs = nowMs,
        )
    }

    private fun onRideChanged(state: ControllerState, newRide: Ride, nowMs: Long): ControllerState {
        if (state.ride == newRide) return state
        return when (newRide) {
            Ride.Idle -> state.copy(ride = Ride.Idle)
            Ride.Recording -> when (state.ride) {
                is Ride.Paused -> {
                    val resumeTriggersEnabled = state.settings.timeAfterResumeSec.enabled ||
                        state.settings.distanceAfterResumeM.enabled
                    state.copy(
                        ride = Ride.Recording,
                        lastTickAtMs = nowMs,
                        lastResumeAtMs = nowMs,
                        distanceAtResumeM = state.sensors[Sensor.DISTANCE_M]?.value,
                        resumeArmed = true,
                        resumeFired = false,
                        suppressed = emptySet(),
                        // Resume triggers gate re-locking after a pause; without them the
                        // pre-pause latch persists and re-locks immediately on resume.
                        eventLatch = if (resumeTriggersEnabled) null else state.eventLatch,
                    )
                }
                else -> freshRide(state).copy(ride = Ride.Recording, lastTickAtMs = nowMs)
            }
            is Ride.Paused -> accumulateRidingTime(state, nowMs).copy(ride = newRide, lastTickAtMs = null)
        }
    }

    private fun freshRide(state: ControllerState): ControllerState = state.copy(
        ridingTimeMs = 0L,
        lastTickAtMs = null,
        lastResumeAtMs = null,
        distanceAtResumeM = null,
        resumeArmed = false,
        startFired = false,
        resumeFired = false,
        eventLatch = null,
        suppressed = emptySet(),
        pendingLockSinceMs = null,
        pendingLockReason = null,
        pendingUnlockSinceMs = null,
    )

    private fun manualUnlock(state: ControllerState, nowMs: Long): ControllerState = state.copy(
        command = LockCommand.Unlocked,
        eventLatch = null,
        suppressed = activeConditions(state, nowMs),
        pendingLockSinceMs = null,
        pendingLockReason = null,
        pendingUnlockSinceMs = null,
    )

    private fun evaluate(state: ControllerState, nowMs: Long): ControllerState {
        // Rule 1: no active ride — reset automation; a manual lock survives so the
        // BonusAction can be used as a plain rain lock outside rides too.
        if (state.ride == Ride.Idle) {
            val manual = (state.command as? LockCommand.Locked)?.reason == LockReason.MANUAL
            return freshRide(state).copy(
                command = if (manual) state.command else LockCommand.Unlocked,
            )
        }

        // Rule 2: paused with unlock-while-paused → forced unlocked, state kept.
        if (state.ride is Ride.Paused && state.settings.unlockWhilePaused) {
            return state.copy(
                command = LockCommand.Unlocked,
                pendingLockSinceMs = null,
                pendingLockReason = null,
                pendingUnlockSinceMs = null,
            )
        }

        // Rule 3: manual lock is sticky.
        if ((state.command as? LockCommand.Locked)?.reason == LockReason.MANUAL) return state

        var s = state

        // Fire one-shot triggers.
        if (!s.startFired) {
            startTriggerReason(s)?.let { reason ->
                s = s.copy(startFired = true, eventLatch = s.eventLatch ?: reason)
            }
        }
        if (s.resumeArmed && !s.resumeFired) {
            resumeTriggerReason(s, nowMs)?.let { reason ->
                s = s.copy(resumeFired = true, eventLatch = s.eventLatch ?: reason)
            }
        }

        // Evaluate condition triggers, honoring the manual-unlock suppression set.
        val active = activeConditions(s, nowMs)
        val suppressed = s.suppressed intersect active
        s = s.copy(suppressed = suppressed)
        val effective = active - suppressed

        return when (s.command) {
            is LockCommand.Unlocked -> {
                val latch = s.eventLatch
                when {
                    latch != null -> s.copy(
                        command = LockCommand.Locked(latch),
                        pendingLockSinceMs = null,
                        pendingLockReason = null,
                    )
                    effective.isNotEmpty() -> {
                        val since = s.pendingLockSinceMs
                        val reason = s.pendingLockReason?.takeIf { it in effective } ?: effective.first()
                        if (since == null) {
                            s.copy(pendingLockSinceMs = nowMs, pendingLockReason = reason)
                        } else if (nowMs - since >= s.settings.lockDebounceSec * 1000L) {
                            s.copy(
                                command = LockCommand.Locked(reason),
                                pendingLockSinceMs = null,
                                pendingLockReason = null,
                            )
                        } else {
                            s.copy(pendingLockReason = reason)
                        }
                    }
                    else -> s.copy(pendingLockSinceMs = null, pendingLockReason = null)
                }
            }
            is LockCommand.Locked -> {
                s = s.copy(pendingLockSinceMs = null, pendingLockReason = null)
                val autoUnlockApplies = s.settings.unlockMode == UnlockMode.AUTO && s.eventLatch == null
                if (autoUnlockApplies && active.isEmpty()) {
                    val since = s.pendingUnlockSinceMs
                    if (since == null) {
                        s.copy(pendingUnlockSinceMs = nowMs)
                    } else if (nowMs - since >= s.settings.autoUnlockHoldSec * 1000L) {
                        s.copy(command = LockCommand.Unlocked, pendingUnlockSinceMs = null)
                    } else {
                        s
                    }
                } else {
                    s.copy(pendingUnlockSinceMs = null)
                }
            }
        }
    }

    private fun startTriggerReason(s: ControllerState): LockReason? {
        val cfg = s.settings
        if (cfg.timeAfterStartSec.enabled && s.ridingTimeMs >= (cfg.timeAfterStartSec.value * 1000).toLong()) {
            return LockReason.TIME_AFTER_START
        }
        val distance = s.sensors[Sensor.DISTANCE_M]?.value
        if (cfg.distanceAfterStartM.enabled && distance != null && distance >= cfg.distanceAfterStartM.value) {
            return LockReason.DISTANCE_AFTER_START
        }
        return null
    }

    private fun resumeTriggerReason(s: ControllerState, nowMs: Long): LockReason? {
        val cfg = s.settings
        val resumeAt = s.lastResumeAtMs
        if (cfg.timeAfterResumeSec.enabled && resumeAt != null &&
            nowMs - resumeAt >= (cfg.timeAfterResumeSec.value * 1000).toLong()
        ) {
            return LockReason.TIME_AFTER_RESUME
        }
        val distance = s.sensors[Sensor.DISTANCE_M]?.value
        val distanceAtResume = s.distanceAtResumeM
        if (cfg.distanceAfterResumeM.enabled && distance != null && distanceAtResume != null &&
            distance - distanceAtResume >= cfg.distanceAfterResumeM.value
        ) {
            return LockReason.DISTANCE_AFTER_RESUME
        }
        return null
    }

    /** Condition triggers that are currently true, based on fresh sensor readings only. */
    fun activeConditions(s: ControllerState, nowMs: Long): Set<LockReason> = buildSet {
        val cfg = s.settings

        fun fresh(sensor: Sensor): Double? =
            s.sensors[sensor]?.takeIf { nowMs - it.atMs <= SENSOR_STALE_MS }?.value

        if (cfg.hrAboveBpm.enabled) {
            fresh(Sensor.HEART_RATE_BPM)?.let { if (it > cfg.hrAboveBpm.value) add(LockReason.HEART_RATE) }
        }
        if (cfg.cadenceAboveRpm.enabled) {
            fresh(Sensor.CADENCE_RPM)?.let { if (it > cfg.cadenceAboveRpm.value) add(LockReason.CADENCE) }
        }
        if (cfg.powerAboveW.enabled) {
            fresh(Sensor.POWER_W)?.let { if (it > cfg.powerAboveW.value) add(LockReason.POWER) }
        }
        if (cfg.tempMode != TempMode.OFF) {
            fresh(Sensor.TEMPERATURE_C)?.let { temp ->
                val hot = temp > cfg.tempHotAboveC
                val cold = temp < cfg.tempColdBelowC
                val triggered = when (cfg.tempMode) {
                    TempMode.ABOVE -> hot
                    TempMode.BELOW -> cold
                    TempMode.OUTSIDE_RANGE -> hot || cold
                    TempMode.OFF -> false
                }
                if (triggered) add(LockReason.TEMPERATURE)
            }
        }
        if (cfg.rainEnabled && s.rain == RainStatus.Rain) add(LockReason.RAIN)
    }
}
