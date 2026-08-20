package io.github.farrfreezy.karoosmartlock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.farrfreezy.karoosmartlock.BuildConfig
import io.github.farrfreezy.karoosmartlock.core.SmartLockSettings
import io.github.farrfreezy.karoosmartlock.core.TempMode
import io.github.farrfreezy.karoosmartlock.core.ThresholdTrigger
import io.github.farrfreezy.karoosmartlock.core.UnlockMode
import io.github.farrfreezy.karoosmartlock.data.SettingsRepository
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val METERS_PER_MILE = 1609.344
private const val IMPERIAL_DISTANCE_STEP_M = METERS_PER_MILE / 10.0

@Composable
fun SettingsScreen(
    repository: SettingsRepository,
    overlayGranted: Boolean,
    karooConnected: Boolean,
    profile: UserProfile?,
    onRequestOverlayPermission: () -> Unit,
    onPreviewLock: () -> Unit,
) {
    val settings by repository.settingsFlow.collectAsStateWithLifecycle(SmartLockSettings())
    val scope = rememberCoroutineScope()
    val update: ((SmartLockSettings) -> SmartLockSettings) -> Unit = { transform ->
        scope.launch { repository.update(transform) }
    }

    val imperialDistance =
        profile?.preferredUnit?.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL
    val imperialTemp =
        profile?.preferredUnit?.temperature == UserProfile.PreferredUnit.UnitType.IMPERIAL

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                StatusCard(
                    overlayGranted = overlayGranted,
                    karooConnected = karooConnected,
                    onRequestOverlayPermission = onRequestOverlayPermission,
                    onPreviewLock = onPreviewLock,
                )
            }

            item { SectionHeader("Ride start") }
            item {
                TriggerCard(
                    title = "Lock after riding time",
                    trigger = settings.timeAfterStartSec,
                    valueText = formatSeconds(settings.timeAfterStartSec.value),
                    onToggle = { on -> update { it.copy(timeAfterStartSec = it.timeAfterStartSec.copy(enabled = on)) } },
                    onStep = { dir ->
                        update {
                            it.copy(timeAfterStartSec = it.timeAfterStartSec.step(dir * 10.0, min = 10.0))
                        }
                    },
                )
            }
            item {
                TriggerCard(
                    title = "Lock after distance",
                    trigger = settings.distanceAfterStartM,
                    valueText = formatDistance(settings.distanceAfterStartM.value, imperialDistance),
                    onToggle = { on -> update { it.copy(distanceAfterStartM = it.distanceAfterStartM.copy(enabled = on)) } },
                    onStep = { dir ->
                        val step = if (imperialDistance) IMPERIAL_DISTANCE_STEP_M else 100.0
                        update {
                            it.copy(distanceAfterStartM = it.distanceAfterStartM.step(dir * step, min = step))
                        }
                    },
                )
            }

            item { SectionHeader("Autopause") }
            item {
                SwitchCard(
                    title = "Unlock while paused",
                    subtitle = "Release the lock whenever the ride is (auto)paused",
                    checked = settings.unlockWhilePaused,
                    onToggle = { on -> update { it.copy(unlockWhilePaused = on) } },
                )
            }
            item {
                TriggerCard(
                    title = "Re-lock after time riding again",
                    trigger = settings.timeAfterResumeSec,
                    valueText = formatSeconds(settings.timeAfterResumeSec.value),
                    onToggle = { on -> update { it.copy(timeAfterResumeSec = it.timeAfterResumeSec.copy(enabled = on)) } },
                    onStep = { dir ->
                        update { it.copy(timeAfterResumeSec = it.timeAfterResumeSec.step(dir * 5.0, min = 5.0)) }
                    },
                )
            }
            item {
                TriggerCard(
                    title = "Re-lock after distance riding again",
                    trigger = settings.distanceAfterResumeM,
                    valueText = formatDistance(settings.distanceAfterResumeM.value, imperialDistance),
                    onToggle = { on -> update { it.copy(distanceAfterResumeM = it.distanceAfterResumeM.copy(enabled = on)) } },
                    onStep = { dir ->
                        val step = if (imperialDistance) IMPERIAL_DISTANCE_STEP_M else 50.0
                        update {
                            it.copy(distanceAfterResumeM = it.distanceAfterResumeM.step(dir * step, min = step))
                        }
                    },
                )
            }

            item { SectionHeader("Sensors") }
            item {
                TriggerCard(
                    title = "Heart rate above",
                    trigger = settings.hrAboveBpm,
                    valueText = "${settings.hrAboveBpm.value.roundToInt()} bpm",
                    onToggle = { on -> update { it.copy(hrAboveBpm = it.hrAboveBpm.copy(enabled = on)) } },
                    onStep = { dir -> update { it.copy(hrAboveBpm = it.hrAboveBpm.step(dir * 5.0, min = 60.0)) } },
                )
            }
            item {
                TriggerCard(
                    title = "Cadence above",
                    trigger = settings.cadenceAboveRpm,
                    valueText = "${settings.cadenceAboveRpm.value.roundToInt()} rpm",
                    onToggle = { on -> update { it.copy(cadenceAboveRpm = it.cadenceAboveRpm.copy(enabled = on)) } },
                    onStep = { dir -> update { it.copy(cadenceAboveRpm = it.cadenceAboveRpm.step(dir * 5.0, min = 30.0)) } },
                )
            }
            item {
                TriggerCard(
                    title = "Power above",
                    trigger = settings.powerAboveW,
                    valueText = "${settings.powerAboveW.value.roundToInt()} W",
                    onToggle = { on -> update { it.copy(powerAboveW = it.powerAboveW.copy(enabled = on)) } },
                    onStep = { dir -> update { it.copy(powerAboveW = it.powerAboveW.step(dir * 10.0, min = 50.0)) } },
                )
            }

            item { SectionHeader("Temperature (device sensor)") }
            item {
                TemperatureCard(
                    settings = settings,
                    imperial = imperialTemp,
                    update = update,
                )
            }

            item { SectionHeader("Weather") }
            item {
                SwitchCard(
                    title = "Lock when raining",
                    subtitle = "Requires the karoo-headwind extension for precipitation data",
                    checked = settings.rainEnabled,
                    onToggle = { on -> update { it.copy(rainEnabled = on) } },
                )
            }

            item { SectionHeader("Unlock behavior") }
            item { UnlockModeCard(settings = settings, update = update) }

            item {
                Text(
                    text = "SmartLock v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    overlayGranted: Boolean,
    karooConnected: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onPreviewLock: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!overlayGranted) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Display-over-apps permission is required to lock the screen.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = onRequestOverlayPermission) { Text("Grant permission") }
                    }
                }
            }
            Text(
                text = if (karooConnected) "Karoo system: connected" else "Karoo system: not connected",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onPreviewLock, enabled = overlayGranted) {
                Text("Preview lock (10 s)")
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SwitchCard(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            Switch(checked = checked, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun TriggerCard(
    title: String,
    trigger: ThresholdTrigger,
    valueText: String,
    onToggle: (Boolean) -> Unit,
    onStep: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = trigger.enabled, onCheckedChange = onToggle)
            }
            if (trigger.enabled) {
                Stepper(valueText = valueText, onStep = onStep)
            }
        }
    }
}

@Composable
private fun Stepper(valueText: String, onStep: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = { onStep(-1) }) { Text("−") }
        Text(
            text = valueText,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { onStep(+1) }) { Text("+") }
    }
}

@Composable
private fun TemperatureCard(
    settings: SmartLockSettings,
    imperial: Boolean,
    update: ((SmartLockSettings) -> SmartLockSettings) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ModeChip("Off", settings.tempMode == TempMode.OFF) {
                    update { it.copy(tempMode = TempMode.OFF) }
                }
                ModeChip("Hot", settings.tempMode == TempMode.ABOVE) {
                    update { it.copy(tempMode = TempMode.ABOVE) }
                }
                ModeChip("Cold", settings.tempMode == TempMode.BELOW) {
                    update { it.copy(tempMode = TempMode.BELOW) }
                }
                ModeChip("Both", settings.tempMode == TempMode.OUTSIDE_RANGE) {
                    update { it.copy(tempMode = TempMode.OUTSIDE_RANGE) }
                }
            }
            val tempStepC = if (imperial) 5.0 / 9.0 else 1.0
            if (settings.tempMode == TempMode.ABOVE || settings.tempMode == TempMode.OUTSIDE_RANGE) {
                Text("Lock above", style = MaterialTheme.typography.bodyMedium)
                Stepper(
                    valueText = formatTemperature(settings.tempHotAboveC, imperial),
                    onStep = { dir -> update { it.copy(tempHotAboveC = it.tempHotAboveC + dir * tempStepC) } },
                )
            }
            if (settings.tempMode == TempMode.BELOW || settings.tempMode == TempMode.OUTSIDE_RANGE) {
                Text("Lock below", style = MaterialTheme.typography.bodyMedium)
                Stepper(
                    valueText = formatTemperature(settings.tempColdBelowC, imperial),
                    onStep = { dir -> update { it.copy(tempColdBelowC = it.tempColdBelowC + dir * tempStepC) } },
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.weight(1f)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f)) { Text(label) }
    }
}

@Composable
private fun UnlockModeCard(
    settings: SmartLockSettings,
    update: ((SmartLockSettings) -> SmartLockSettings) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = settings.unlockMode == UnlockMode.AUTO,
                    onClick = { update { it.copy(unlockMode = UnlockMode.AUTO) } },
                )
                Text("Auto-unlock when condition clears")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = settings.unlockMode == UnlockMode.MANUAL_ONLY,
                    onClick = { update { it.copy(unlockMode = UnlockMode.MANUAL_ONLY) } },
                )
                Text("Stay locked until manual unlock")
            }
            if (settings.unlockMode == UnlockMode.AUTO) {
                Text("Unlock after clear for", style = MaterialTheme.typography.bodyMedium)
                Stepper(
                    valueText = formatSeconds(settings.autoUnlockHoldSec.toDouble()),
                    onStep = { dir ->
                        update {
                            it.copy(autoUnlockHoldSec = (it.autoUnlockHoldSec + dir * 5).coerceAtLeast(5))
                        }
                    },
                )
            }
            Text("Lock when condition holds for", style = MaterialTheme.typography.bodyMedium)
            Stepper(
                valueText = formatSeconds(settings.lockDebounceSec.toDouble()),
                onStep = { dir ->
                    update {
                        it.copy(lockDebounceSec = (it.lockDebounceSec + dir).coerceAtLeast(0))
                    }
                },
            )
        }
    }
}

private fun ThresholdTrigger.step(delta: Double, min: Double): ThresholdTrigger =
    copy(value = (value + delta).coerceAtLeast(min))

private fun formatSeconds(seconds: Double): String {
    val total = seconds.roundToInt()
    return if (total >= 60 && total % 60 == 0) "${total / 60} min" else "$total s"
}

private fun formatDistance(meters: Double, imperial: Boolean): String = if (imperial) {
    String.format("%.1f mi", meters / METERS_PER_MILE)
} else if (meters >= 1000) {
    String.format("%.1f km", meters / 1000.0)
} else {
    "${meters.roundToInt()} m"
}

private fun formatTemperature(celsius: Double, imperial: Boolean): String = if (imperial) {
    "${(celsius * 9.0 / 5.0 + 32.0).roundToInt()}°F"
} else {
    "${celsius.roundToInt()}°C"
}
