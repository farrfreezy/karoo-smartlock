package io.github.farrfreezy.karoosmartlock.sim

import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.farrfreezy.karoosmartlock.KarooSmartLockExtension
import io.github.farrfreezy.karoosmartlock.core.RainStatus
import io.github.farrfreezy.karoosmartlock.core.Ride
import io.github.farrfreezy.karoosmartlock.core.Sensor

/** Sends [SimulatorBridge] intents to the extension service. Debug builds only. */
class SimulatorClient(private val context: Context) {

    fun ride(ride: Ride) = send("ride", rideArg(ride))

    fun sensor(sensor: Sensor, value: Double?) =
        send("sensor", sensorArg(sensor), value?.toString() ?: SimulatorBridge.VALUE_OFF)

    fun rain(status: RainStatus) = send(
        "rain",
        when (status) {
            RainStatus.Rain -> "rain"
            RainStatus.NoRain -> "dry"
            RainStatus.Unknown -> "unknown"
        },
    )

    fun toggleLock() = send("toggle")

    private fun send(kind: String, arg: String? = null, value: String? = null) {
        val intent = Intent(context, KarooSmartLockExtension::class.java)
            .setAction(SimulatorBridge.ACTION_SIM)
            .putExtra(SimulatorBridge.EXTRA_KIND, kind)
        arg?.let { intent.putExtra(SimulatorBridge.EXTRA_ARG, it) }
        value?.let { intent.putExtra(SimulatorBridge.EXTRA_VALUE, it) }
        runCatching { context.startService(intent) }
            .onFailure { Log.w(TAG, "Simulator event $kind/$arg not delivered", it) }
    }

    private fun rideArg(ride: Ride): String = when (ride) {
        Ride.Idle -> "idle"
        Ride.Recording -> "recording"
        is Ride.Paused -> if (ride.auto) "autopause" else "pause"
    }

    private fun sensorArg(sensor: Sensor): String = when (sensor) {
        Sensor.DISTANCE_M -> "distance"
        Sensor.HEART_RATE_BPM -> "hr"
        Sensor.CADENCE_RPM -> "cadence"
        Sensor.POWER_W -> "power"
        Sensor.TEMPERATURE_C -> "temp"
    }

    private companion object {
        const val TAG = "SmartLockSim"
    }
}
