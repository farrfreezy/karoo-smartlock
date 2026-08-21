package io.github.farrfreezy.karoosmartlock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.farrfreezy.karoosmartlock.core.RainStatus
import io.github.farrfreezy.karoosmartlock.core.Ride
import io.github.farrfreezy.karoosmartlock.core.Sensor
import io.github.farrfreezy.karoosmartlock.sim.SimulatorClient
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Sensor readings older than LockReducer.SENSOR_STALE_MS are ignored — resend well inside that. */
private const val HEARTBEAT_MS = 3_000L

/**
 * Debug-build control panel that feeds the extension the ride/sensor events a real
 * Karoo would stream, so triggers can be exercised on an emulator. See docs/EMULATOR.md.
 */
@Composable
fun SimulatorPanel(simulator: SimulatorClient) {
    var ride by remember { mutableStateOf<Ride>(Ride.Idle) }
    var rain by remember { mutableStateOf(RainStatus.Unknown) }
    var speedKph by remember { mutableStateOf(25.0) }
    var rolling by remember { mutableStateOf(false) }
    var distanceM by remember { mutableStateOf(0.0) }

    var hr by remember { mutableStateOf(SimSensor(165.0)) }
    var cadence by remember { mutableStateOf(SimSensor(95.0)) }
    var power by remember { mutableStateOf(SimSensor(260.0)) }
    var temp by remember { mutableStateOf(SimSensor(30.0)) }

    fun setRide(next: Ride) {
        if (next == Ride.Idle) {
            distanceM = 0.0
            simulator.sensor(Sensor.DISTANCE_M, null)
        }
        ride = next
        simulator.ride(next)
    }

    // Karoo streams repeat; so must we, or readings go stale and conditions silently drop.
    LaunchedEffect(Unit) {
        while (true) {
            delay(HEARTBEAT_MS)
            if (rolling && ride == Ride.Recording) {
                distanceM += speedKph * 1000.0 / 3600.0 * (HEARTBEAT_MS / 1000.0)
            }
            if (rolling) simulator.sensor(Sensor.DISTANCE_M, distanceM)
            if (hr.enabled) simulator.sensor(Sensor.HEART_RATE_BPM, hr.value)
            if (cadence.enabled) simulator.sensor(Sensor.CADENCE_RPM, cadence.value)
            if (power.enabled) simulator.sensor(Sensor.POWER_W, power.value)
            if (temp.enabled) simulator.sensor(Sensor.TEMPERATURE_C, temp.value)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Simulator (debug build)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Feeds fake ride data to the extension so triggers work without a Karoo.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Ride state", style = MaterialTheme.typography.bodyMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SimChip("Idle", ride == Ride.Idle) { setRide(Ride.Idle) }
                SimChip("Ride", ride == Ride.Recording) { setRide(Ride.Recording) }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SimChip("Autopause", ride == Ride.Paused(auto = true)) { setRide(Ride.Paused(auto = true)) }
                SimChip("Pause", ride == Ride.Paused(auto = false)) { setRide(Ride.Paused(auto = false)) }
            }

            SimSensorRow(
                label = "Rolling ${"%.2f".format(distanceM / 1000.0)} km",
                enabled = rolling,
                valueText = "${speedKph.roundToInt()} km/h",
                onToggle = { on ->
                    rolling = on
                    if (on) simulator.sensor(Sensor.DISTANCE_M, distanceM)
                    else simulator.sensor(Sensor.DISTANCE_M, null)
                },
                onStep = { dir -> speedKph = (speedKph + dir * 5).coerceIn(5.0, 60.0) },
            )
            SimSensorRow(
                label = "Heart rate",
                enabled = hr.enabled,
                valueText = "${hr.value.roundToInt()} bpm",
                onToggle = { on ->
                    hr = hr.copy(enabled = on)
                    simulator.sensor(Sensor.HEART_RATE_BPM, hr.valueOrNull())
                },
                onStep = { dir ->
                    hr = hr.stepped(dir * 5.0, 40.0, 220.0)
                    if (hr.enabled) simulator.sensor(Sensor.HEART_RATE_BPM, hr.value)
                },
            )
            SimSensorRow(
                label = "Cadence",
                enabled = cadence.enabled,
                valueText = "${cadence.value.roundToInt()} rpm",
                onToggle = { on ->
                    cadence = cadence.copy(enabled = on)
                    simulator.sensor(Sensor.CADENCE_RPM, cadence.valueOrNull())
                },
                onStep = { dir ->
                    cadence = cadence.stepped(dir * 5.0, 0.0, 150.0)
                    if (cadence.enabled) simulator.sensor(Sensor.CADENCE_RPM, cadence.value)
                },
            )
            SimSensorRow(
                label = "Power",
                enabled = power.enabled,
                valueText = "${power.value.roundToInt()} W",
                onToggle = { on ->
                    power = power.copy(enabled = on)
                    simulator.sensor(Sensor.POWER_W, power.valueOrNull())
                },
                onStep = { dir ->
                    power = power.stepped(dir * 10.0, 0.0, 1500.0)
                    if (power.enabled) simulator.sensor(Sensor.POWER_W, power.value)
                },
            )
            SimSensorRow(
                label = "Temperature",
                enabled = temp.enabled,
                valueText = "${temp.value.roundToInt()}°C",
                onToggle = { on ->
                    temp = temp.copy(enabled = on)
                    simulator.sensor(Sensor.TEMPERATURE_C, temp.valueOrNull())
                },
                onStep = { dir ->
                    temp = temp.stepped(dir * 1.0, -20.0, 60.0)
                    if (temp.enabled) simulator.sensor(Sensor.TEMPERATURE_C, temp.value)
                },
            )

            Text("Rain (stands in for karoo-headwind)", style = MaterialTheme.typography.bodyMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SimChip("None", rain == RainStatus.Unknown) {
                    rain = RainStatus.Unknown
                    simulator.rain(RainStatus.Unknown)
                }
                SimChip("Dry", rain == RainStatus.NoRain) {
                    rain = RainStatus.NoRain
                    simulator.rain(RainStatus.NoRain)
                }
                SimChip("Rain", rain == RainStatus.Rain) {
                    rain = RainStatus.Rain
                    simulator.rain(RainStatus.Rain)
                }
            }

            OutlinedButton(
                onClick = { simulator.toggleLock() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Bonus action: toggle lock")
            }
        }
    }
}

private data class SimSensor(val value: Double, val enabled: Boolean = false)

private fun SimSensor.valueOrNull(): Double? = if (enabled) value else null

private fun SimSensor.stepped(delta: Double, min: Double, max: Double): SimSensor =
    copy(value = (value + delta).coerceIn(min, max))

@Composable
private fun SimSensorRow(
    label: String,
    enabled: Boolean,
    valueText: String,
    onToggle: (Boolean) -> Unit,
    onStep: (Int) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueText, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = { onStep(-1) }, contentPadding = SimChipPadding) { Text("−") }
        OutlinedButton(onClick = { onStep(+1) }, contentPadding = SimChipPadding) { Text("+") }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun RowScope.SimChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.weight(1f), contentPadding = SimChipPadding) {
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f), contentPadding = SimChipPadding) {
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private val SimChipPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
