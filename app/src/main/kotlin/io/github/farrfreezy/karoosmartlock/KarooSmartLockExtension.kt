package io.github.farrfreezy.karoosmartlock

import android.content.Intent
import io.github.farrfreezy.karoosmartlock.core.LockCommand
import io.github.farrfreezy.karoosmartlock.core.LockController
import io.github.farrfreezy.karoosmartlock.core.LockEvent
import io.github.farrfreezy.karoosmartlock.core.RainStatus
import io.github.farrfreezy.karoosmartlock.core.Sensor
import io.github.farrfreezy.karoosmartlock.core.SmartLockSettings
import io.github.farrfreezy.karoosmartlock.core.TempMode
import io.github.farrfreezy.karoosmartlock.data.SettingsRepository
import io.github.farrfreezy.karoosmartlock.karoo.singleValueOrNull
import io.github.farrfreezy.karoosmartlock.karoo.streamDataFlow
import io.github.farrfreezy.karoosmartlock.karoo.streamRideState
import io.github.farrfreezy.karoosmartlock.karoo.toRide
import io.github.farrfreezy.karoosmartlock.overlay.LockOverlayManager
import io.github.farrfreezy.karoosmartlock.sim.SimulatorBridge
import io.github.farrfreezy.karoosmartlock.weather.HeadwindRainSource
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class KarooSmartLockExtension : KarooExtension("karoo-smartlock", BuildConfig.VERSION_NAME) {

    private val karoo by lazy { KarooSystemService(this) }
    private lateinit var scope: CoroutineScope
    private lateinit var controller: LockController
    private lateinit var overlay: LockOverlayManager
    private lateinit var settingsRepository: SettingsRepository
    private var previewJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        settingsRepository = SettingsRepository(this)
        controller = LockController(scope)
        overlay = LockOverlayManager(this) {
            scope.launch {
                previewJob?.cancel()
                previewJob = null
                controller.onEvent(LockEvent.ManualUnlock)
                syncOverlay()
            }
        }
        karoo.connect()

        scope.launch {
            settingsRepository.settingsFlow.collect {
                controller.onEvent(LockEvent.SettingsChanged(it))
            }
        }

        scope.launch {
            karoo.streamRideState().collect { rideState ->
                controller.onEvent(LockEvent.RideStateChanged(rideState.toRide()))
            }
        }

        // Subscribe only the sensor streams the current settings actually need.
        scope.launch {
            settingsRepository.settingsFlow
                .map { neededSensors(it) }
                .distinctUntilChanged()
                .collectLatest { sensors ->
                    coroutineScope {
                        sensors.forEach { sensor ->
                            launch {
                                karoo.streamDataFlow(sensor.dataTypeId()).collect { state ->
                                    controller.onEvent(
                                        LockEvent.SensorUpdate(sensor, state.singleValueOrNull()),
                                    )
                                }
                            }
                        }
                    }
                }
        }

        scope.launch {
            settingsRepository.settingsFlow
                .map { it.rainEnabled }
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    if (enabled) {
                        HeadwindRainSource(karoo).observeRain().collect {
                            controller.onEvent(LockEvent.RainUpdate(it))
                        }
                    } else {
                        controller.onEvent(LockEvent.RainUpdate(RainStatus.Unknown))
                    }
                }
        }

        scope.launch {
            controller.commands.collect { syncOverlay() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREVIEW_LOCK -> startPreview()
            // Debug-only: lets the emulator drive the state machine with no Karoo attached.
            SimulatorBridge.ACTION_SIM -> if (BuildConfig.DEBUG) handleSimulatedEvent(intent)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun handleSimulatedEvent(intent: Intent?) {
        if (intent == null) return
        val event = SimulatorBridge.eventFor(
            kind = intent.getStringExtra(SimulatorBridge.EXTRA_KIND),
            arg = intent.getStringExtra(SimulatorBridge.EXTRA_ARG),
            value = intent.getStringExtra(SimulatorBridge.EXTRA_VALUE),
        ) ?: return
        scope.launch {
            previewJob?.cancel()
            previewJob = null
            controller.onEvent(event)
            // commands is distinctUntilChanged, so nudge the overlay for events that
            // land on the same command (e.g. a lock reason change, or preview cleanup).
            syncOverlay()
        }
    }

    override fun onBonusAction(actionId: String) {
        if (actionId == ACTION_ID_TOGGLE_LOCK) {
            scope.launch {
                controller.onEvent(LockEvent.ManualToggle)
            }
        }
    }

    override fun onDestroy() {
        overlay.hide()
        scope.cancel()
        karoo.disconnect()
        super.onDestroy()
    }

    private fun syncOverlay() {
        if (previewJob != null) return
        when (val command = controller.state.value.command) {
            is LockCommand.Locked -> overlay.show(command.reason)
            LockCommand.Unlocked -> overlay.hide()
        }
    }

    private fun startPreview() {
        previewJob?.cancel()
        previewJob = scope.launch {
            overlay.show(null)
            delay(PREVIEW_DURATION_MS)
            previewJob = null
            syncOverlay()
        }
    }

    private fun neededSensors(settings: SmartLockSettings): Set<Sensor> = buildSet {
        if (settings.distanceAfterStartM.enabled || settings.distanceAfterResumeM.enabled) {
            add(Sensor.DISTANCE_M)
        }
        if (settings.hrAboveBpm.enabled) add(Sensor.HEART_RATE_BPM)
        if (settings.cadenceAboveRpm.enabled) add(Sensor.CADENCE_RPM)
        if (settings.powerAboveW.enabled) add(Sensor.POWER_W)
        if (settings.tempMode != TempMode.OFF) add(Sensor.TEMPERATURE_C)
    }

    private fun Sensor.dataTypeId(): String = when (this) {
        Sensor.DISTANCE_M -> DataType.Type.DISTANCE
        Sensor.HEART_RATE_BPM -> DataType.Type.HEART_RATE
        Sensor.CADENCE_RPM -> DataType.Type.CADENCE
        Sensor.POWER_W -> DataType.Type.POWER
        Sensor.TEMPERATURE_C -> DataType.Type.TEMPERATURE
    }

    companion object {
        const val ACTION_PREVIEW_LOCK = "io.github.farrfreezy.karoosmartlock.PREVIEW_LOCK"
        const val ACTION_ID_TOGGLE_LOCK = "toggle_lock"
        const val PREVIEW_DURATION_MS = 10_000L
    }
}
