package com.fortress.upi.core.designsystem

import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * Haptic vocabulary. View-level constants respect the user's system haptics
 * setting; rich compositions are used only where the actuator supports them.
 */
class FortressHaptics internal constructor(private val view: View, private val vibrator: Vibrator?) {

    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** QR locked, payee verified. */
    fun confirm() {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    fun reject() {
        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    }

    /** Money moved: a rising swell then a crisp click. */
    fun success() {
        compose(
            VibrationEffect.Composition.PRIMITIVE_QUICK_RISE to 0.6f,
            VibrationEffect.Composition.PRIMITIVE_CLICK to 1f,
        ) ?: confirm()
    }

    /** Connectivity lost: three soft heartbeat ticks introduce the offline vault. */
    fun ambientPulse() {
        compose(
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK to 0.35f,
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK to 0.25f,
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK to 0.15f,
            delayMs = 140,
        ) ?: tick()
    }

    private fun compose(vararg primitives: Pair<Int, Float>, delayMs: Int = 80): Unit? {
        val v = vibrator ?: return null
        val ids = primitives.map { it.first }.toIntArray()
        if (!v.hasVibrator() || !v.areAllPrimitivesSupported(*ids)) return null
        val composition = VibrationEffect.startComposition()
        primitives.forEachIndexed { i, (id, scale) -> composition.addPrimitive(id, scale, if (i == 0) 0 else delayMs) }
        v.vibrate(composition.compose())
        return Unit
    }
}

@Composable
fun rememberHaptics(): FortressHaptics {
    val view = LocalView.current
    val context = LocalContext.current
    return remember(view) {
        FortressHaptics(view, context.getSystemService(VibratorManager::class.java)?.defaultVibrator)
    }
}
