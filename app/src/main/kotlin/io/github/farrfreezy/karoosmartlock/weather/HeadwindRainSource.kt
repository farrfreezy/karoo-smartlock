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
 * (sourced from Open-Meteo). If karoo-headwind is not installed, or the rider has
 * not completed its setup, the stream reports NotAvailable and the trigger stays
 * inert.
 *
 * Two caveats make [OpenMeteoRainSource] the default:
 *  - headwind converts the value to the rider's preferred units, so this is mm for
 *    a metric profile and *inches* for an imperial one. Only the "any measurable
 *    precipitation" threshold below is unit-agnostic — a configurable mm threshold
 *    could not be implemented on this source.
 *  - it exposes Open-Meteo's `current.precipitation`, the preceding hour's total,
 *    which lags both the start and the end of a shower.
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
                            mm > RAIN_THRESHOLD -> RainStatus.Rain
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

        /** Unit-agnostic on purpose — see the caveat above. */
        const val RAIN_THRESHOLD = 0.0
    }
}
