package io.github.farrfreezy.karoosmartlock.core

sealed interface LockEvent {
    data class RideStateChanged(val ride: Ride) : LockEvent

    /** null value = stream not available / searching → reading removed. */
    data class SensorUpdate(val sensor: Sensor, val value: Double?) : LockEvent

    data class RainUpdate(val status: RainStatus) : LockEvent

    data class SettingsChanged(val settings: SmartLockSettings) : LockEvent

    /** Padlock long-press on the overlay. */
    data object ManualUnlock : LockEvent

    /** BonusAction bound to a hardware/remote button. */
    data object ManualToggle : LockEvent

    /** Periodic evaluation pulse (~1 Hz) while a ride is active. */
    data object Tick : LockEvent
}
