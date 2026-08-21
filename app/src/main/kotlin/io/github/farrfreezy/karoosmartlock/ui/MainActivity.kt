package io.github.farrfreezy.karoosmartlock.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.farrfreezy.karoosmartlock.BuildConfig
import io.github.farrfreezy.karoosmartlock.KarooSmartLockExtension
import io.github.farrfreezy.karoosmartlock.data.SettingsRepository
import io.github.farrfreezy.karoosmartlock.sim.SimulatorClient
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.UserProfile

class MainActivity : ComponentActivity() {

    private val karoo by lazy { KarooSystemService(this) }
    private val repository by lazy { SettingsRepository(this) }
    private val simulator by lazy { if (BuildConfig.DEBUG) SimulatorClient(this) else null }

    private var karooConnected by mutableStateOf(false)
    private var profile by mutableStateOf<UserProfile?>(null)
    private var overlayGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        karoo.connect { connected -> runOnUiThread { karooConnected = connected } }
        karoo.addConsumer { userProfile: UserProfile ->
            runOnUiThread { profile = userProfile }
        }
        setContent {
            SmartLockTheme {
                SettingsScreen(
                    repository = repository,
                    overlayGranted = overlayGranted,
                    karooConnected = karooConnected,
                    profile = profile,
                    onRequestOverlayPermission = ::requestOverlayPermission,
                    onPreviewLock = ::previewLock,
                    simulator = simulator,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayGranted = Settings.canDrawOverlays(this)
    }

    override fun onDestroy() {
        karoo.disconnect()
        super.onDestroy()
    }

    private fun requestOverlayPermission() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun previewLock() {
        startService(
            Intent(this, KarooSmartLockExtension::class.java)
                .setAction(KarooSmartLockExtension.ACTION_PREVIEW_LOCK),
        )
    }
}
