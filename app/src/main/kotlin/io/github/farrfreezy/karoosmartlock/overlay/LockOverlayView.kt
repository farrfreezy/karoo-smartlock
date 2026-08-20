package io.github.farrfreezy.karoosmartlock.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import io.github.farrfreezy.karoosmartlock.R
import io.github.farrfreezy.karoosmartlock.core.LockReason
import kotlin.math.hypot

/**
 * Transparent full-screen view that swallows all touch input (custom rain lock).
 * A padlock affordance sits bottom-center; holding it for [HOLD_MS] unlocks.
 * Taps elsewhere briefly show a hint so the lock is discoverable.
 */
class LockOverlayView(
    context: Context,
    private val onUnlockRequested: () -> Unit,
) : View(context) {

    var reason: LockReason? = null
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val badgeRadius = 28f * density
    private val badgeMarginBottom = 48f * density
    private val touchSlop = 24f * density

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 20, 20, 20)
        style = Paint.Style.FILL
    }
    private val badgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 20, 20, 20)
        style = Paint.Style.FILL
    }
    private val lockIcon = ContextCompat.getDrawable(context, R.drawable.ic_lock)?.mutate()

    private var holdStartedAt = 0L
    private var holding = false
    private var downX = 0f
    private var downY = 0f
    private var hintUntil = 0L

    private val unlockRunnable = Runnable {
        if (holding) {
            holding = false
            onUnlockRequested()
        }
    }
    private val hintExpireRunnable = Runnable { invalidate() }
    private val animateRunnable = object : Runnable {
        override fun run() {
            if (holding) {
                invalidate()
                postDelayed(this, 16L)
            }
        }
    }

    private val badgeCenterX: Float get() = width / 2f
    private val badgeCenterY: Float get() = height - badgeMarginBottom - badgeRadius

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                val onBadge = hypot(event.x - badgeCenterX, event.y - badgeCenterY) <= badgeRadius * 1.4f
                if (onBadge) {
                    holding = true
                    holdStartedAt = SystemClock.uptimeMillis()
                    postDelayed(unlockRunnable, HOLD_MS)
                    post(animateRunnable)
                } else {
                    showHint()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (holding && hypot(event.x - downX, event.y - downY) > touchSlop) cancelHold()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelHold()
        }
        // Swallow every touch — that is the whole point of the lock.
        return true
    }

    private fun cancelHold() {
        if (holding) {
            holding = false
            removeCallbacks(unlockRunnable)
            invalidate()
        }
    }

    private fun showHint() {
        hintUntil = SystemClock.uptimeMillis() + HINT_MS
        invalidate()
        postDelayed(hintExpireRunnable, HINT_MS + 50L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(unlockRunnable)
        removeCallbacks(hintExpireRunnable)
        removeCallbacks(animateRunnable)
        holding = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = badgeCenterX
        val cy = badgeCenterY

        canvas.drawCircle(cx, cy, badgeRadius, badgePaint)
        canvas.drawCircle(cx, cy, badgeRadius, badgeStrokePaint)

        lockIcon?.let {
            val iconHalf = (20f * density).toInt()
            it.setBounds(
                (cx - iconHalf).toInt(),
                (cy - iconHalf).toInt(),
                (cx + iconHalf).toInt(),
                (cy + iconHalf).toInt(),
            )
            it.draw(canvas)
        }

        if (holding) {
            val progress = ((SystemClock.uptimeMillis() - holdStartedAt).toFloat() / HOLD_MS)
                .coerceIn(0f, 1f)
            val arcRadius = badgeRadius + 6f * density
            val rect = RectF(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius)
            canvas.drawArc(rect, -90f, 360f * progress, false, progressPaint)
        }

        if (SystemClock.uptimeMillis() < hintUntil) {
            val hint = context.getString(R.string.unlock_hint)
            val textY = cy - badgeRadius - 24f * density
            val halfWidth = textPaint.measureText(hint) / 2f + 12f * density
            val bg = RectF(
                cx - halfWidth,
                textY - 22f * density,
                cx + halfWidth,
                textY + 10f * density,
            )
            canvas.drawRoundRect(bg, 8f * density, 8f * density, textBgPaint)
            canvas.drawText(hint, cx, textY, textPaint)
        }

        reason?.let {
            val label = reasonLabel(it)
            val labelY = cy + badgeRadius + 20f * density
            canvas.drawText(label, cx, labelY, textPaint)
        }
    }

    private fun reasonLabel(reason: LockReason): String = when (reason) {
        LockReason.TIME_AFTER_START, LockReason.TIME_AFTER_RESUME,
        LockReason.DISTANCE_AFTER_START, LockReason.DISTANCE_AFTER_RESUME,
        -> context.getString(R.string.reason_auto)
        LockReason.RAIN -> context.getString(R.string.reason_rain)
        LockReason.HEART_RATE -> context.getString(R.string.reason_hr)
        LockReason.CADENCE -> context.getString(R.string.reason_cadence)
        LockReason.POWER -> context.getString(R.string.reason_power)
        LockReason.TEMPERATURE -> context.getString(R.string.reason_temperature)
        LockReason.MANUAL -> context.getString(R.string.reason_manual)
    }

    companion object {
        const val HOLD_MS = 1_000L
        const val HINT_MS = 2_000L
    }
}
