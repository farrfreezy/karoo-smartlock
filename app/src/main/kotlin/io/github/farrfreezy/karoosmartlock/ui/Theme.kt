package io.github.farrfreezy.karoosmartlock.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/** Dark, high-contrast theme suited to the Karoo's sunlight-readable screen. */
@Composable
fun SmartLockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(),
        content = content,
    )
}
