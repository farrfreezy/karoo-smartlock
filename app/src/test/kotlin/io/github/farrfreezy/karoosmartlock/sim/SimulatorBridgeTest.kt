package io.github.farrfreezy.karoosmartlock.sim

import io.github.farrfreezy.karoosmartlock.core.LockEvent
import io.github.farrfreezy.karoosmartlock.core.RainStatus
import io.github.farrfreezy.karoosmartlock.core.Ride
import io.github.farrfreezy.karoosmartlock.core.Sensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimulatorBridgeTest {

    @Test
    fun `parses ride states`() {
        assertEquals(LockEvent.RideStateChanged(Ride.Idle), SimulatorBridge.eventFor("ride", "idle"))
        assertEquals(
            LockEvent.RideStateChanged(Ride.Recording),
            SimulatorBridge.eventFor("ride", "recording"),
        )
        assertEquals(
            LockEvent.RideStateChanged(Ride.Paused(auto = true)),
            SimulatorBridge.eventFor("ride", "autopause"),
        )
        assertEquals(
            LockEvent.RideStateChanged(Ride.Paused(auto = false)),
            SimulatorBridge.eventFor("ride", "pause"),
        )
    }

    @Test
    fun `parses sensor values`() {
        assertEquals(
            LockEvent.SensorUpdate(Sensor.HEART_RATE_BPM, 165.0),
            SimulatorBridge.eventFor("sensor", "hr", "165"),
        )
        assertEquals(
            LockEvent.SensorUpdate(Sensor.TEMPERATURE_C, -3.5),
            SimulatorBridge.eventFor("sensor", "temp", "-3.5"),
        )
        assertEquals(
            LockEvent.SensorUpdate(Sensor.DISTANCE_M, 1200.0),
            SimulatorBridge.eventFor("SENSOR", "Distance", "1200"),
        )
    }

    @Test
    fun `missing or off sensor value clears the reading`() {
        assertEquals(
            LockEvent.SensorUpdate(Sensor.POWER_W, null),
            SimulatorBridge.eventFor("sensor", "power", "off"),
        )
        assertEquals(
            LockEvent.SensorUpdate(Sensor.CADENCE_RPM, null),
            SimulatorBridge.eventFor("sensor", "cadence"),
        )
        // Garbage is treated as "no reading" rather than silently becoming 0.
        assertEquals(
            LockEvent.SensorUpdate(Sensor.CADENCE_RPM, null),
            SimulatorBridge.eventFor("sensor", "cadence", "ninety"),
        )
    }

    @Test
    fun `parses rain and manual events`() {
        assertEquals(LockEvent.RainUpdate(RainStatus.Rain), SimulatorBridge.eventFor("rain", "rain"))
        assertEquals(LockEvent.RainUpdate(RainStatus.NoRain), SimulatorBridge.eventFor("rain", "dry"))
        assertEquals(
            LockEvent.RainUpdate(RainStatus.Unknown),
            SimulatorBridge.eventFor("rain", "unknown"),
        )
        assertEquals(LockEvent.ManualToggle, SimulatorBridge.eventFor("toggle"))
        assertEquals(LockEvent.ManualUnlock, SimulatorBridge.eventFor("unlock"))
    }

    @Test
    fun `unknown input yields no event`() {
        assertNull(SimulatorBridge.eventFor(null))
        assertNull(SimulatorBridge.eventFor("nonsense"))
        assertNull(SimulatorBridge.eventFor("ride", "wobbling"))
        assertNull(SimulatorBridge.eventFor("sensor", "vo2max", "60"))
        assertNull(SimulatorBridge.eventFor("rain", "drizzle"))
    }
}
