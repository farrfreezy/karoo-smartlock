package io.github.farrfreezy.karoosmartlock.weather

import io.github.farrfreezy.karoosmartlock.core.RainStatus
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over where rain information comes from, so the source can be
 * swapped without touching the lock controller.
 *
 * @see OpenMeteoRainSource fetched directly over karoo-ext's HTTP bridge (default)
 * @see HeadwindRainSource read from the karoo-headwind extension's stream
 */
interface RainSource {
    fun observeRain(): Flow<RainStatus>
}
