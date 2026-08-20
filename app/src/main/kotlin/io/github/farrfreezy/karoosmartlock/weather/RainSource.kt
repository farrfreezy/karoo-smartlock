package io.github.farrfreezy.karoosmartlock.weather

import io.github.farrfreezy.karoosmartlock.core.RainStatus
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over where rain information comes from, so the source can be
 * swapped (karoo-headwind stream today, direct Open-Meteo via karoo-ext HTTP
 * later) without touching the lock controller.
 */
interface RainSource {
    fun observeRain(): Flow<RainStatus>
}
