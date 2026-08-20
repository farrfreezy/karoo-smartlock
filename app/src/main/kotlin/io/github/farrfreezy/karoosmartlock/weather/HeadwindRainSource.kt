package io.github.farrfreezy.karoosmartlock.weather

import io.github.farrfreezy.karoosmartlock.core.RainStatus
import io.github.farrfreezy.karoosmartlock.karoo.streamDataFlow
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Rain detection via the karoo-headwind extension's `precipitation` data type
 * (current precipitation in mm, sourced from Open-Meteo). If karoo-headwind is
 * not installed the stream reports NotAvailable and the trigger stays inert.
 */
class HeadwindRainSource(private val karoo: KarooSystemService) : RainSource {

    override fun observeRain(): Flow<RainStatus> =
        karoo.streamDataFlow(PRECIPITATION_TYPE_ID)
            .map { state ->
                when (state) {
                    is StreamState.Streaming -> {
                        val mm = state.dataPoint.singleValue
                        when {
                            mm == null -> RainStatus.Unknown
                            mm > RAIN_THRESHOLD_MM -> RainStatus.Rain
                            else -> RainStatus.NoRain
                        }
                    }
                    else -> RainStatus.Unknown
                }
            }
            .distinctUntilChanged()

    companion object {
        // Cross-extension data type id: TYPE_EXT::<extension>::<typeId>
        const val PRECIPITATION_TYPE_ID = "TYPE_EXT::karoo-headwind::precipitation"
        const val RAIN_THRESHOLD_MM = 0.0
    }
}
