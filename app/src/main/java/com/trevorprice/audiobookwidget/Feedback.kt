package com.trevorprice.audiobookwidget

import android.media.session.PlaybackState
import android.os.SystemClock
import kotlin.math.abs

/**
 * Short-lived state that lets the widget answer a tap before the player does.
 *
 * A command takes anywhere from a moment to a few seconds to show up in the
 * player's session, and until it does a repaint would draw exactly what was
 * there before the tap. So we remember what we asked for and draw that until
 * the player catches up or the wait runs out.
 *
 * Held in memory only: the receiver and the listener service share a process,
 * so every repaint sees it, and if the process dies it was stale anyway.
 */
object Feedback {

    enum class Button { REWIND, PLAY_PAUSE, FORWARD }

    /** How long a confirmation or error tint stays on a button. */
    const val FLASH_MS = 600L

    /** How long an error message replaces the subtitle. */
    const val MESSAGE_MS = 4000L

    /** How long to show what we asked for before trusting the player again. */
    const val EXPECT_MS = 2500L

    /** A reported position this close to the seek target means it landed. */
    private const val LANDED_MS = 1500L

    private var flashButton: Button? = null
    private var flashOk = true
    private var flashUntil = 0L

    private var message: String? = null
    private var messageUntil = 0L

    private var expectPlaying: Boolean? = null
    private var expectPlayingUntil = 0L

    private var expectPositionMs: Long? = null
    private var expectPositionAt = 0L

    /** How long a skip's spin and clock fade run, and a little over. */
    private const val MOTION_MS = 600L

    /** How long the highlight behind the time takes to fade, and a little over. */
    const val GLOW_MS = 1400L

    private var skipButton: Button? = null
    private var skipAt = 0L
    private val skipPlayed = mutableSetOf<WidgetRender.Size>()
    private val glowShown = mutableSetOf<WidgetRender.Size>()
    private val spins = IntArray(Button.entries.size)

    /** Which of each time's two TextViews is showing. */
    var clockSide = 0
        private set

    /** True while a player with no session is being woken up. */
    var resuming = false

    private fun now() = SystemClock.elapsedRealtime()

    fun flash(button: Button, ok: Boolean) {
        flashButton = button
        flashOk = ok
        flashUntil = now() + FLASH_MS
    }

    /** Null when [button] is not flashing, otherwise whether it is good news. */
    fun flashing(button: Button): Boolean? =
        if (flashButton == button && now() < flashUntil) flashOk else null

    /**
     * Says what went wrong in the widget itself. A toast would be the usual
     * place, but Android hides toasts from apps that may not post
     * notifications, which this one never asks to.
     */
    fun message(text: String) {
        message = text
        messageUntil = now() + MESSAGE_MS
    }

    /** The error to show in place of the subtitle, or null. */
    fun message(): String? = if (now() < messageUntil) message else null

    fun expectPlaying(playing: Boolean) {
        expectPlaying = playing
        expectPlayingUntil = now() + EXPECT_MS
    }

    /**
     * What the play button should show: what we asked for until the player
     * agrees or the wait runs out, then whatever the player says. An expiry
     * is left for [playUnanswered] to find rather than cleared here.
     */
    fun playing(actual: Boolean): Boolean {
        val expected = expectPlaying ?: return actual
        if (expected == actual) {
            expectPlaying = null
            return actual
        }
        return if (now() < expectPlayingUntil) expected else actual
    }

    /**
     * Whether the last play or pause expired without the player following it.
     * False while a newer tap's wait is still running, since that tap owns the
     * expectation now and will be checked on its own schedule.
     */
    fun playUnanswered(actual: Boolean): Boolean {
        val expected = expectPlaying ?: return false
        if (now() < expectPlayingUntil) return false
        expectPlaying = null
        return expected != actual
    }

    /**
     * A skip went through, so its arc should spin and the clock fade to the
     * new time. Each flips to the other of its pair of views, which is what
     * plays the animation.
     */
    fun skipped(button: Button) {
        skipButton = button
        skipAt = now()
        skipPlayed.clear()
        spins[button.ordinal]++
        clockSide = 1 - clockSide
    }

    /**
     * The skip a render of [size] should animate, handed out once per size:
     * a flipper replays its animation every time it is told which view to
     * show, so any later repaint must leave that out.
     */
    fun takeSkip(size: WidgetRender.Size): Button? =
        skipButton?.takeIf { now() - skipAt < MOTION_MS && skipPlayed.add(size) }
            ?.also { glowShown.add(size) }

    /** True while the latest skip's highlight is still fading. */
    fun glowing(): Boolean = now() - skipAt < GLOW_MS

    /** Whether a render of [size] should take away a highlight that has faded. */
    fun takeGlowEnd(size: WidgetRender.Size): Boolean = !glowing() && glowShown.remove(size)

    /** Which of the skip arc's two copies is showing. */
    fun spinSide(button: Button): Int = spins[button.ordinal] % 2

    /** True while the clock is still fading from one time to the next. */
    fun clockFading(): Boolean = now() - skipAt < MOTION_MS

    fun expectPosition(ms: Long) {
        expectPositionMs = ms
        expectPositionAt = now()
    }

    /**
     * Where playback should be drawn: the seek target, carried forward while
     * playing, until the player reports something near it. Also the base for
     * the next seek, so quick repeated taps add up instead of each jumping
     * from the stale position.
     */
    fun position(state: PlaybackState?): Long {
        val actual = MediaSessions.currentPosition(state)
        val target = expectPositionMs ?: return actual
        val elapsed = now() - expectPositionAt
        val projected = target +
            if (MediaSessions.isPlaying(state)) (elapsed * state!!.playbackSpeed).toLong() else 0L
        if (elapsed >= EXPECT_MS || abs(actual - projected) < LANDED_MS) {
            expectPositionMs = null
            return actual
        }
        return projected
    }
}
