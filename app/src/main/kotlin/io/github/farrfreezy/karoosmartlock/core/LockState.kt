package io.github.farrfreezy.karoosmartlock.core

/** Ride recording state, mirrored from the Karoo system (kept SDK-free for testability). */
sealed interface Ride {
    data object Idle : Ride
    data object Recording : Ride
    data class Paused(val auto: Boolean) : Ride
}

enum class Sensor { DISTANCE_M, HEART_RATE_BPM, CADENCE_RPM, POWER_W, TEMPERATURE_C }

enum class RainStatus { Unknown, NoRain, Rain }

enum class LockReason {
    TIME_AFTER_START,
    DISTANCE_AFTER_START,
    TIME_AFTER_RESUME,
    DISTANCE_AFTER_RESUME,
    RAIN,
    HEART_RATE,
    CADENCE,
    POWER,
    TEMPERATURE,
    MANUAL,
}

/** What the overlay layer obeys. */
sealed interface LockCommand {
    data object Unlocked : LockCommand
    data class Locked(val reason: LockReason) : LockCommand
}

data class SensorReading(val value: Double, val atMs: Long)

data class ControllerState(
    val command: LockCommand = LockCommand.Unlocked,
    val ride: Ride = Ride.Idle,
    /** Accumulated time actually riding (excludes pauses), driven by Tick. */
    val ridingTimeMs: Long = 0L,
    val lastTickAtMs: Long? = null,
    val lastResumeAtMs: Long? = null,
    val distanceAtResumeM: Double? = null,
    /** Resume triggers are armed after the first pause→recording transition. */
    val resumeArmed: Boolean = false,
    /** One-shot triggers fire at most once per ride / per resume cycle. */
    val startFired: Boolean = false,
    val resumeFired: Boolean = false,
    /** Latched one-shot lock request; cleared by manual unlock, pause handling, or ride end. */
    val eventLatch: LockReason? = null,
    /**
     * Conditions that were true at the moment of a manual unlock. Each must go
     * false (fresh rising edge) before it may lock again. Cleared on the next
     * pause/resume cycle and at ride end.
     */
    val suppressed: Set<LockReason> = emptySet(),
    val pendingLockSinceMs: Long? = null,
    val pendingLockReason: LockReason? = null,
    val pendingUnlockSinceMs: Long? = null,
    val sensors: Map<Sensor, SensorReading> = emptyMap(),
    val rain: RainStatus = RainStatus.Unknown,
    val settings: SmartLockSettings = SmartLockSettings(),
)
