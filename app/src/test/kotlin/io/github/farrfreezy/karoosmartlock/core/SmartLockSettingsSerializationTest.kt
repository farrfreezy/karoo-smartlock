package io.github.farrfreezy.karoosmartlock.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartLockSettingsSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    @Test
    fun `round trip preserves settings`() {
        val settings = SmartLockSettings(
            timeAfterStartSec = ThresholdTrigger(true, 90.0),
            rainEnabled = true,
            rainSource = RainDataSource.HEADWIND,
            rainLeadSec = 900,
            rainWholeRideEnabled = true,
            rainWholeRideProbabilityPct = 70,
            tempMode = TempMode.OUTSIDE_RANGE,
            unlockMode = UnlockMode.MANUAL_ONLY,
        )
        val decoded = json.decodeFromString(
            SmartLockSettings.serializer(),
            json.encodeToString(SmartLockSettings.serializer(), settings),
        )
        assertEquals(settings, decoded)
    }

    @Test
    fun `unknown keys are tolerated for forward compatibility`() {
        val decoded = json.decodeFromString(
            SmartLockSettings.serializer(),
            """{"rainEnabled":true,"someFutureSetting":42}""",
        )
        assertEquals(true, decoded.rainEnabled)
    }

    @Test
    fun `missing keys fall back to defaults`() {
        val decoded = json.decodeFromString(SmartLockSettings.serializer(), "{}")
        assertEquals(SmartLockSettings(), decoded)
    }
}
