package io.github.farrfreezy.karoosmartlock.core

import kotlinx.serialization.Serializable

/**
 * A single on/off trigger with a numeric threshold.
 *
 * Thresholds are stored in canonical units (meters, seconds, °C, bpm, rpm, W);
 * the UI converts for display according to the rider's preferred units.
 */
@Serializable
data class ThresholdTrigger(
    val enabled: Boolean = false,
    val value: Double = 0.0,
)

@Serializable
enum class TempMode { OFF, ABOVE, BELOW, OUTSIDE_RANGE }

@Serializable
enum class UnlockMode { AUTO, MANUAL_ONLY }

@Serializable
data class SmartLockSettings(
    // One-shot triggers relative to ride start
    val timeAfterStartSec: ThresholdTrigger = ThresholdTrigger(value = 60.0),
    val distanceAfterStartM: ThresholdTrigger = ThresholdTrigger(value = 500.0),
    // One-shot triggers relative to resuming from (auto)pause
    val timeAfterResumeSec: ThresholdTrigger = ThresholdTrigger(value = 15.0),
    val distanceAfterResumeM: ThresholdTrigger = ThresholdTrigger(value = 200.0),
    // Suspend the lock while the ride is paused (autopause or manual pause)
    val unlockWhilePaused: Boolean = true,
    // Condition triggers over live sensor values
    val rainEnabled: Boolean = false,
    val hrAboveBpm: ThresholdTrigger = ThresholdTrigger(value = 160.0),
    val cadenceAboveRpm: ThresholdTrigger = ThresholdTrigger(value = 90.0),
    val powerAboveW: ThresholdTrigger = ThresholdTrigger(value = 250.0),
    // Device-recorded temperature
    val tempMode: TempMode = TempMode.OFF,
    val tempHotAboveC: Double = 28.0,
    val tempColdBelowC: Double = 5.0,
    // Unlock behavior
    val unlockMode: UnlockMode = UnlockMode.AUTO,
    val autoUnlockHoldSec: Int = 10,
    val lockDebounceSec: Int = 3,
)
