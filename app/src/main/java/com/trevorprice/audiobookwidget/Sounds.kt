package com.trevorprice.audiobookwidget

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * A short blip when a skip goes through, rising for forward and falling for
 * rewind. Made in code rather than shipped as a file, since it is only a
 * sweep of a sine. Quiet enough to sit on top of the book without drowning a
 * sentence, and silent whenever the phone is on vibrate or silent.
 */
object Sounds {

    private const val RATE = 44100
    private const val LENGTH_MS = 70
    private const val LOW_HZ = 440.0
    private const val HIGH_HZ = 587.0
    private const val VOLUME = 0.3f

    private val handler = Handler(Looper.getMainLooper())
    private val pcm = mutableMapOf<Feedback.Button, ShortArray>()

    fun skip(ctx: Context, button: Feedback.Button) {
        if (button == Feedback.Button.PLAY_PAUSE) return
        if (!MediaSessions.soundsEnabled(ctx)) return
        val audio = ctx.getSystemService(AudioManager::class.java) ?: return
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return

        val samples = pcm.getOrPut(button) { blip(rising = button == Feedback.Button.FORWARD) }
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
        } catch (e: Exception) {
            // Out of audio tracks or the like; the haptic already said "got it".
            return
        }
        track.write(samples, 0, samples.size)
        track.setVolume(VOLUME)
        track.play()
        // A static track holds its buffer until released, so let it go once
        // the blip has had time to finish.
        handler.postDelayed({ track.release() }, LENGTH_MS + 200L)
    }

    /** A sine sweeping between the two pitches, with a click-free attack and a quick decay. */
    private fun blip(rising: Boolean): ShortArray {
        val count = RATE * LENGTH_MS / 1000
        val attack = RATE * 3 / 1000
        val from = if (rising) LOW_HZ else HIGH_HZ
        val to = if (rising) HIGH_HZ else LOW_HZ
        var phase = 0.0
        return ShortArray(count) { i ->
            val t = i.toDouble() / count
            phase += 2 * PI * (from + (to - from) * t) / RATE
            val envelope = min(1.0, i.toDouble() / attack) * exp(-5.0 * t)
            (sin(phase) * envelope * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
