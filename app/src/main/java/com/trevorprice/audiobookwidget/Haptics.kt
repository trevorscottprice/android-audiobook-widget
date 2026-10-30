package com.trevorprice.audiobookwidget

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * A buzz when a widget tap reaches us. The launcher draws the ripple, but
 * only we know the broadcast actually arrived, so this is the part that says
 * "got it". Off when the user turns it off on the setup screen.
 */
object Haptics {

    fun tap(ctx: Context) = play(
        ctx,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        } else {
            VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    )

    fun error(ctx: Context) = play(
        ctx,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
        } else {
            VibrationEffect.createWaveform(longArrayOf(0, 20, 80, 20), -1)
        }
    )

    private fun play(ctx: Context, effect: VibrationEffect) {
        if (!MediaSessions.hapticsEnabled(ctx)) return
        val vibrator = vibrator(ctx) ?: return
        if (!vibrator.hasVibrator()) return
        // Touch usage, so the system's own touch-feedback setting still applies.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(
                effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
            )
        } else {
            vibrator.vibrate(effect)
        }
    }

    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            ctx.getSystemService(Vibrator::class.java)
        }
}
