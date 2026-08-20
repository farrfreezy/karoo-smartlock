package io.github.farrfreezy.karoosmartlock.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import io.github.farrfreezy.karoosmartlock.core.LockReason

/**
 * Adds/removes the touch-blocking overlay window. The window is touchable
 * (it swallows every touch) but not focusable, so the Karoo's hardware
 * buttons keep working while locked.
 */
class LockOverlayManager(
    private val context: Context,
    private val onUnlockRequested: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: LockOverlayView? = null

    val isShown: Boolean get() = view != null

    fun show(reason: LockReason?) {
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission not granted; cannot lock screen")
            return
        }
        view?.let {
            it.reason = reason
            return
        }
        val overlay = LockOverlayView(context, onUnlockRequested).apply { this.reason = reason }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        runCatching { windowManager.addView(overlay, params) }
            .onSuccess { view = overlay }
            .onFailure { Log.e(TAG, "Failed to add overlay", it) }
    }

    fun hide() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    companion object {
        private const val TAG = "SmartLock"
    }
}
