package io.github.farrfreezy.karoosmartlock.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LockReducerTest {

    private class Harness(settings: SmartLockSettings) {
        var now: Long = 1_000_000L
        var state: ControllerState = ControllerState()

        init {
            send(LockEvent.SettingsChanged(settings))
        }

        fun send(event: LockEvent) {
            state = LockReducer.reduce(state, event, now)
        }

        /** Advance in 1 s ticks, mirroring the controller's ticker. */
        fun tickSeconds(seconds: Int) {
            repeat(seconds) {
                now += 1_000
                send(LockEvent.Tick)
            }
        }

        fun sensor(sensor: Sensor, value: Double?) = send(LockEvent.SensorUpdate(sensor, value))

        val locked: Boolean get() = state.command is LockCommand.Locked
        val reason: LockReason? get() = (state.command as? LockCommand.Locked)?.reason
    }

    private fun settings(block: SmartLockSettings.() -> SmartLockSettings = { this }): SmartLockSettings =
        SmartLockSettings().block()

    // --- one-shot start triggers ---

    @Test
    fun `time after start locks once threshold reached and not before`() {
        val h = Harness(settings { copy(timeAfterStartSec = ThresholdTrigger(true, 30.0)) })
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.tickSeconds(29)
        assertTrue(!h.locked)
        h.tickSeconds(1)
        assertEquals(LockReason.TIME_AFTER_START, h.reason)
    }

    @Test
    fun `time after start does not fire while idle`() {
        val h = Harness(settings { copy(timeAfterStartSec = ThresholdTrigger(true, 30.0)) })
        h.now += 60_000
        h.send(LockEvent.Tick)
        assertTrue(!h.locked)
    }

    @Test
    fun `riding time excludes paused time`() {
        val h = Harness(settings { copy(timeAfterStartSec = ThresholdTrigger(true, 30.0), unlockWhilePaused = true) })
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.tickSeconds(20)
        h.send(LockEvent.RideStateChanged(Ride.Paused(auto = true)))
        h.now += 120_000 // 2 minutes paused
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.tickSeconds(9)
        assertTrue("still below 30 s of riding time", !h.locked)
        h.tickSeconds(1)
        assertEquals(LockReason.TIME_AFTER_START, h.reason)
    }

    @Test
    fun `distance after start locks at threshold`() {
        val h = Harness(settings { copy(distanceAfterStartM = ThresholdTrigger(true, 500.0)) })
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.sensor(Sensor.DISTANCE_M, 499.0)
        assertTrue(!h.locked)
        h.sensor(Sensor.DISTANCE_M, 500.0)
        assertEquals(LockReason.DISTANCE_AFTER_START, h.reason)
    }

    @Test
    fun `ride end unlocks and resets`() {
        val h = Harness(settings { copy(timeAfterStartSec = ThresholdTrigger(true, 10.0)) })
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.tickSeconds(10)
        assertTrue(h.locked)
        h.send(LockEvent.RideStateChanged(Ride.Idle))
        assertTrue(!h.locked)
        // new ride starts from scratch
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.tickSeconds(9)
        assertTrue(!h.locked)
        h.tickSeconds(1)
        assertTrue(h.locked)
    }

    // --- autopause ---

    @Test
    fun `autopause unlocks while paused and resume triggers re-lock`() {
        val h = Harness(
            settings {
                copy(
                    timeAfterStartSec = ThresholdTrigger(true, 10.0),
                    timeAfterResumeSec = ThresholdTrigger(true, 15.0),
                    unlockWhilePaused = true,
                )
            },
        )
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.tickSeconds(10)
        assertTrue(h.locked)
        h.send(LockEvent.RideStateChanged(Ride.Paused(auto = true)))
        assertTrue("unlocked while autopaused", !h.locked)
        h.now += 30_000
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        assertTrue("not re-locked immediately on resume", !h.locked)
        h.tickSeconds(14)
        assertTrue(!h.locked)
        h.tickSeconds(1)
        assertEquals(LockReason.TIME_AFTER_RESUME, h.reason)
    }

    @Test
    fun `resume distance trigger uses delta from resume point`() {
        val h = Harness(
            settings {
                copy(
                    distanceAfterResumeM = ThresholdTrigger(true, 200.0),
                    unlockWhilePaused = true,
                )
            },
        )
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.sensor(Sensor.DISTANCE_M, 5_000.0)
        h.send(LockEvent.RideStateChanged(Ride.Paused(auto = true)))
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.sensor(Sensor.DISTANCE_M, 5_199.0)
        assertTrue(!h.locked)
        h.sensor(Sensor.DISTANCE_M, 5_200.0)
        assertEquals(LockReason.DISTANCE_AFTER_RESUME, h.reason)
    }

    @Test
    fun `without resume triggers the pre-pause lock returns on resume`() {
        val h = Harness(
            settings {
                copy(timeAfterStartSec = ThresholdTrigger(true, 10.0), unlockWhilePaused = true)
            },
        )
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.tickSeconds(10)
        assertTrue(h.locked)
        h.send(LockEvent.RideStateChanged(Ride.Paused(auto = true)))
        assertTrue(!h.locked)
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        assertTrue("latch persisted, re-locked on resume", h.locked)
    }

    @Test
    fun `unlockWhilePaused disabled keeps lock during pause`() {
        val h = Harness(
            settings {
                copy(timeAfterStartSec = ThresholdTrigger(true, 10.0), unlockWhilePaused = false)
            },
        )
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.tickSeconds(10)
        assertTrue(h.locked)
        h.send(LockEvent.RideStateChanged(Ride.Paused(auto = true)))
        assertTrue("stays locked while paused", h.locked)
    }

    // --- condition triggers: debounce + hysteresis ---

    @Test
    fun `hr spike shorter than debounce does not lock`() {
        val h = Harness(settings { copy(hrAboveBpm = ThresholdTrigger(true, 160.0), lockDebounceSec = 3) })
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.sensor(Sensor.HEART_RATE_BPM, 170.0)
        h.tickSeconds(2)
        assertTrue(!h.locked)
        h.sensor(Sensor.HEART_RATE_BPM, 150.0)
        h.tickSeconds(1)
        assertTrue(!h.locked)
    }

    @Test
    fun `sustained hr locks after debounce`() {
        val h = Harness(settings { copy(hrAboveBpm = ThresholdTrigger(true, 160.0), lockDebounceSec = 3) })
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.sensor(Sensor.HEART_RATE_BPM, 170.0)
        h.tickSeconds(3)
        assertEquals(LockReason.HEART_RATE, h.reason)
    }

    @Test
    fun `auto unlock after condition clear for hold duration`() {
        val h = Harness(
            settings {
                copy(
                    hrAboveBpm = ThresholdTrigger(true, 160.0),
                    lockDebounceSec = 0,
                    autoUnlockHoldSec = 10,
                    unlockMode = UnlockMode.AUTO,
                )
            },
        )
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.sensor(Sensor.HEART_RATE_BPM, 170.0)
        h.tickSeconds(1)
        assertTrue(h.locked)
        h.sensor(Sensor.HEART_RATE_BPM, 150.0)
        h.tickSeconds(9)
        assertTrue("still within hold window", h.locked)
        h.tickSeconds(1)
        assertTrue("unlocked after 10 s clear", !h.locked)
    }

    @Test
    fun `condition returning true cancels pending auto unlock`() {
        val h = Harness(
            settings {
                copy(
                    hrAboveBpm = ThresholdTrigger(true, 160.0),
                    lockDebounceSec = 0,
                    autoUnlockHoldSec = 10,
                )
            },
        )
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.sensor(Sensor.HEART_RATE_BPM, 170.0)
        h.tickSeconds(1)
        assertTrue(h.locked)
        h.sensor(Sensor.HEART_RATE_BPM, 150.0)
        h.tickSeconds(8)
        h.sensor(Sensor.HEART_RATE_BPM, 170.0)
        h.tickSeconds(1)
        h.sensor(Sensor.HEART_RATE_BPM, 150.0)
        h.tickSeconds(9)
        assertTrue("hold restarts after retrigger", h.locked)
        h.tickSeconds(1)
        assertTrue(!h.locked)
    }

    @Test
    fun `manual only mode never auto unlocks`() {
        val h = Harness(
            settings {
                copy(
                    hrAboveBpm = ThresholdTrigger(true, 160.0),
                    lockDebounceSec = 0,
                    autoUnlockHoldSec = 5,
                    unlockMode = UnlockMode.MANUAL_ONLY,
                )
            },
        )
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.sensor(Sensor.HEART_RATE_BPM, 170.0)
        h.tickSeconds(1)
        assertTrue(h.locked)
        h.sensor(Sensor.HEART_RATE_BPM, 100.0)
        h.tickSeconds(60)
        assertTrue(h.locked)
    }

    @Test
    fun `one shot latch never auto unlocks`() {
        val h = Harness(
            settings {
                copy(
                    timeAfterStartSec = ThresholdTrigger(true, 5.0),
                    unlockMode = UnlockMode.AUTO,
                    autoUnlockHoldSec = 5,
                )
            },
        )
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.tickSeconds(5)
        assertTrue(h.locked)
        h.tickSeconds(120)
        assertTrue(h.locked)
    }

    // --- manual interactions ---

    @Test
    fun `manual unlock suppresses still-true condition until fresh rising edge`() {
        val h = Harness(settings { copy(hrAboveBpm = ThresholdTrigger(true, 160.0), lockDebounceSec = 0) })
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.sensor(Sensor.HEART_RATE_BPM, 170.0)
        h.tickSeconds(1)
        assertTrue(h.locked)
        h.send(LockEvent.ManualUnlock)
        assertTrue(!h.locked)
        h.sensor(Sensor.HEART_RATE_BPM, 175.0)
        h.tickSeconds(30)
        assertTrue("HR stayed high, no re-lock", !h.locked)
        h.sensor(Sensor.HEART_RATE_BPM, 150.0)
        h.tickSeconds(1)
        h.sensor(Sensor.HEART_RATE_BPM, 175.0)
        h.tickSeconds(1)
        assertTrue("fresh rising edge re-locks", h.locked)
    }

    @Test
    fun `pause resume cycle clears manual suppression`() {
        val h = Harness(
            settings {
                copy(hrAboveBpm = ThresholdTrigger(true, 160.0), lockDebounceSec = 0, unlockWhilePaused = true)
            },
        )
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.sensor(Sensor.HEART_RATE_BPM, 170.0)
        h.tickSeconds(1)
        h.send(LockEvent.ManualUnlock)
        h.send(LockEvent.RideStateChanged(Ride.Paused(auto = true)))
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.sensor(Sensor.HEART_RATE_BPM, 170.0)
        h.tickSeconds(1)
        assertTrue("suppression cleared by pause cycle", h.locked)
    }

    @Test
    fun `manual unlock clears one shot latch for the rest of the segment`() {
        val h = Harness(settings { copy(timeAfterStartSec = ThresholdTrigger(true, 5.0)) })
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.tickSeconds(5)
        assertTrue(h.locked)
        h.send(LockEvent.ManualUnlock)
        assertTrue(!h.locked)
        h.tickSeconds(120)
        assertTrue("one-shot already fired, no re-lock", !h.locked)
    }

    @Test
    fun `manual toggle locks and unlocks including while idle`() {
        val h = Harness(settings())
        h.send(LockEvent.ManualToggle)
        assertEquals(LockReason.MANUAL, h.reason)
        h.send(LockEvent.ManualToggle)
        assertTrue(!h.locked)
    }

    @Test
    fun `manual lock survives pause only when unlockWhilePaused disabled`() {
        val h = Harness(settings { copy(unlockWhilePaused = true) })
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.send(LockEvent.ManualToggle)
        assertTrue(h.locked)
        h.send(LockEvent.RideStateChanged(Ride.Paused(auto = true)))
        assertTrue("paused suspension overrides manual lock", !h.locked)
    }

    // --- temperature ---

    @Test
    fun `temperature above mode`() {
        val h = Harness(settings { copy(tempMode = TempMode.ABOVE, tempHotAboveC = 28.0, lockDebounceSec = 0) })
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.sensor(Sensor.TEMPERATURE_C, 27.0)
        h.tickSeconds(1)
        assertTrue(!h.locked)
        h.sensor(Sensor.TEMPERATURE_C, 29.0)
        h.tickSeconds(1)
        assertEquals(LockReason.TEMPERATURE, h.reason)
    }

    @Test
    fun `temperature below mode for winter gloves`() {
        val h = Harness(settings { copy(tempMode = TempMode.BELOW, tempColdBelowC = 5.0, lockDebounceSec = 0) })
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.sensor(Sensor.TEMPERATURE_C, 3.0)
        h.tickSeconds(1)
        assertEquals(LockReason.TEMPERATURE, h.reason)
    }

    @Test
    fun `temperature outside range mode triggers on either side`() {
        val h = Harness(
            settings {
                copy(tempMode = TempMode.OUTSIDE_RANGE, tempHotAboveC = 28.0, tempColdBelowC = 5.0, lockDebounceSec = 0)
            },
        )
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.sensor(Sensor.TEMPERATURE_C, 15.0)
        h.tickSeconds(1)
        assertTrue(!h.locked)
        h.sensor(Sensor.TEMPERATURE_C, 30.0)
        h.tickSeconds(1)
        assertTrue(h.locked)
    }

    // --- rain ---

    @Test
    fun `rain locks and unknown never locks`() {
        val h = Harness(settings { copy(rainEnabled = true, lockDebounceSec = 0) })
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.send(LockEvent.RainUpdate(RainStatus.Unknown))
        h.tickSeconds(1)
        assertTrue(!h.locked)
        h.send(LockEvent.RainUpdate(RainStatus.Rain))
        h.tickSeconds(1)
        assertEquals(LockReason.RAIN, h.reason)
    }

    // --- robustness ---

    @Test
    fun `stale sensor reading counts as absent and releases lock in auto mode`() {
        val h = Harness(
            settings {
                copy(hrAboveBpm = ThresholdTrigger(true, 160.0), lockDebounceSec = 0, autoUnlockHoldSec = 5)
            },
        )
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.sensor(Sensor.HEART_RATE_BPM, 170.0)
        h.tickSeconds(1)
        assertTrue(h.locked)
        // sensor drops out — no further updates; reading goes stale after 15 s
        h.tickSeconds(15)
        h.tickSeconds(5)
        assertTrue("stale high HR must not hold the lock forever", !h.locked)
    }

    @Test
    fun `missing streams never lock`() {
        val h = Harness(
            settings {
                copy(
                    hrAboveBpm = ThresholdTrigger(true, 160.0),
                    powerAboveW = ThresholdTrigger(true, 200.0),
                    lockDebounceSec = 0,
                )
            },
        )
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.sensor(Sensor.HEART_RATE_BPM, null)
        h.tickSeconds(60)
        assertTrue(!h.locked)
    }

    @Test
    fun `disabled triggers never lock`() {
        val h = Harness(settings())
        h.send(LockEvent.RideStateChanged(Ride.Recording))
        h.sensor(Sensor.HEART_RATE_BPM, 220.0)
        h.sensor(Sensor.POWER_W, 900.0)
        h.sensor(Sensor.TEMPERATURE_C, 45.0)
        h.send(LockEvent.RainUpdate(RainStatus.Rain))
        h.tickSeconds(120)
        assertTrue(!h.locked)
    }
}
