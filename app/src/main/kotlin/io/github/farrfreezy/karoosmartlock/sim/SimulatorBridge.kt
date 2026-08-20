package io.github.farrfreezy.karoosmartlock.sim

import io.github.farrfreezy.karoosmartlock.core.LockEvent
import io.github.farrfreezy.karoosmartlock.core.RainStatus
import io.github.farrfreezy.karoosmartlock.core.Ride
import io.github.farrfreezy.karoosmartlock.core.Sensor

/**
 * Debug-only protocol for injecting [LockEvent]s into the running extension without a
 * Karoo attached, so the whole state machine + overlay can be exercised on a plain
 * Android emulator (see docs/EMULATOR.md).
 *
 * The wire format is deliberately all-strings so the same events can be sent from the
 * in-app simulator panel and from a shell:
 *
 * ```
 * adb shell am startservice -n <pkg>/.KarooSmartLockExtension \
 *   -a io.github.farrfreezy.karoosmartlock.SIM --es kind ride --es arg recording
 * ```
 *
 * [eventFor] is a pure function (no Android types) so it is covered by unit tests.
 * The service only honors these intents in debug builds.
 */
object SimulatorBridge {
    const val ACTION_SIM = "io.github.farrfreezy.karoosmartlock.SIM"
    const val EXTRA_KIND = "kind"
    const val EXTRA_ARG = "arg"
    const val EXTRA_VALUE = "value"

    /** Sentinel [EXTRA_VALUE] meaning "stream unavailable" — clears the reading. */
    const val VALUE_OFF = "off"

    fun eventFor(kind: String?, arg: String? = null, value: String? = null): LockEvent? =
        when (kind?.lowercase()) {
            "ride" -> ride(arg)?.let { LockEvent.RideStateChanged(it) }
            "sensor" -> sensor(arg)?.let { LockEvent.SensorUpdate(it, sensorValue(value)) }
            "rain" -> rain(arg)?.let { LockEvent.RainUpdate(it) }
            "toggle" -> LockEvent.ManualToggle
            "unlock" -> LockEvent.ManualUnlock
            else -> null
        }

    private fun ride(arg: String?): Ride? = when (arg?.lowercase()) {
        "idle", "stop" -> Ride.Idle
        "recording", "record", "start", "resume" -> Ride.Recording
        "autopause", "auto_pause" -> Ride.Paused(auto = true)
        "pause", "paused" -> Ride.Paused(auto = false)
        else -> null
    }

    private fun sensor(arg: String?): Sensor? = when (arg?.lowercase()) {
        "distance", "dist" -> Sensor.DISTANCE_M
        "hr", "heartrate", "heart_rate" -> Sensor.HEART_RATE_BPM
        "cadence", "cad" -> Sensor.CADENCE_RPM
        "power", "pwr" -> Sensor.POWER_W
        "temp", "temperature" -> Sensor.TEMPERATURE_C
        else -> null
    }

    private fun rain(arg: String?): RainStatus? = when (arg?.lowercase()) {
        "rain", "wet", "on" -> RainStatus.Rain
        "dry", "norain", "no_rain", "off" -> RainStatus.NoRain
        "unknown" -> RainStatus.Unknown
        else -> null
    }

    /** null → the stream reports "not available", which drops the reading in the reducer. */
    private fun sensorValue(value: String?): Double? =
        if (value == null || value.equals(VALUE_OFF, ignoreCase = true)) null else value.toDoubleOrNull()
}
