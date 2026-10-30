package com.trevorprice.audiobookwidget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.media.MediaBrowserService
import android.provider.Settings

/**
 * Everything to do with finding the player's media session and talking to it.
 *
 * The player is a third-party app, so we drive it the way the lockscreen does:
 * through the MediaSession it publishes. Reading other apps' sessions requires
 * notification-listener access, which is why [SessionListenerService] exists.
 */
object MediaSessions {

    private const val PREFS = "audiobook_widget"
    private const val KEY_PKG = "target_pkg"
    private const val KEY_SKIP = "skip_seconds"
    private const val KEY_HAPTICS = "haptics"

    const val DEFAULT_SKIP_SECONDS = 30

    /** Tried first during auto-detection, before falling back to a name match. */
    private val KNOWN_CHIRP_PACKAGES = listOf(
        "com.chirpbooks.chirp",
        "com.bookbub.chirp",
        "com.chirp.audiobooks"
    )

    fun listenerComponent(ctx: Context): ComponentName =
        ComponentName(ctx, SessionListenerService::class.java)

    fun isListenerEnabled(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(
            ctx.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        val mine = listenerComponent(ctx)
        return flat.split(":").any { ComponentName.unflattenFromString(it) == mine }
    }

    // ---- preferences -------------------------------------------------------

    fun targetPackage(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PKG, null)

    fun setTargetPackage(ctx: Context, pkg: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PKG, pkg).apply()
    }

    fun skipSeconds(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SKIP, DEFAULT_SKIP_SECONDS)

    fun setSkipSeconds(ctx: Context, seconds: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SKIP, seconds).apply()
    }

    fun hapticsEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HAPTICS, true)

    fun setHapticsEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_HAPTICS, enabled).apply()
    }

    /** The chosen player's name as the user knows it, for messages. */
    fun playerLabel(ctx: Context): String {
        val pkg = targetPackage(ctx) ?: return ctx.getString(R.string.player_fallback_name)
        val pm = ctx.packageManager
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            ctx.getString(R.string.player_fallback_name)
        }
    }

    /**
     * Looks through installed launchable apps for something that looks like a player.
     * Returns the package name, or null if nothing matched.
     */
    fun autoDetectPackage(ctx: Context): String? {
        val pm = ctx.packageManager
        for (candidate in KNOWN_CHIRP_PACKAGES) {
            if (pm.getLaunchIntentForPackage(candidate) != null) return candidate
        }
        return launchableApps(ctx).firstOrNull { (pkg, label) ->
            pkg.contains("chirp", true) || label.contains("chirp", true)
        }?.first
    }

    /** All launchable apps as (package, label), sorted by label. */
    fun launchableApps(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val main = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    // ---- sessions ----------------------------------------------------------

    fun activeControllers(ctx: Context): List<MediaController> {
        if (!isListenerEnabled(ctx)) return emptyList()
        val msm = ctx.getSystemService(MediaSessionManager::class.java) ?: return emptyList()
        return try {
            msm.getActiveSessions(listenerComponent(ctx))
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /** The controller for the chosen player, or null when it has no live session. */
    fun controller(ctx: Context): MediaController? {
        val sessions = activeControllers(ctx)
        if (sessions.isEmpty()) return null
        val target = targetPackage(ctx)
        if (target != null) return sessions.firstOrNull { it.packageName == target }
        return sessions.firstOrNull { it.packageName in KNOWN_CHIRP_PACKAGES }
            ?: sessions.firstOrNull { it.packageName.contains("chirp", true) }
    }

    /** The player's media browser service, or null if it does not publish one. */
    fun browserService(ctx: Context, pkg: String): ComponentName? {
        val intent = Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(pkg)
        val info = ctx.packageManager.queryIntentServices(intent, 0)
            .firstOrNull()?.serviceInfo ?: return null
        return ComponentName(info.packageName, info.name)
    }

    /**
     * Starts playback in a player that has no live session, by connecting to
     * its media browser and asking the session behind it to play. Players
     * built on Media3 treat that as "resume where I left off".
     *
     * [done] runs exactly once, whether or not the player could be reached.
     */
    fun resume(ctx: Context, done: () -> Unit) {
        val app = ctx.applicationContext
        val component = targetPackage(app)?.let { browserService(app, it) }
            ?: return done()

        val handler = Handler(Looper.getMainLooper())
        var finished = false
        lateinit var browser: MediaBrowser
        fun finish() {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            browser.disconnect()
            done()
        }

        browser = MediaBrowser(app, component, object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val controller = MediaController(app, browser.sessionToken)
                controller.transportControls.play()
                restoreSpeed(app, controller)
                // Stay bound briefly: the player needs a moment to start and
                // take itself to the foreground before we let go of it.
                handler.postDelayed(::finish, RESUME_HOLD_MS)
            }

            override fun onConnectionFailed() = finish()
            override fun onConnectionSuspended() = finish()
        }, null)
        handler.postDelayed(::finish, RESUME_TIMEOUT_MS)
        browser.connect()
    }

    /**
     * Not gated on ACTION_SET_PLAYBACK_SPEED: a freshly woken session may not
     * have published its actions yet, and a player that cannot change speed
     * just ignores the command.
     */
    private fun restoreSpeed(ctx: Context, controller: MediaController) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val speed = LastPlayed.speed(ctx) ?: return
        controller.transportControls.setPlaybackSpeed(speed)
    }

    private const val RESUME_HOLD_MS = 3000L
    // A player evicted hours ago starts cold, which can take a while. A
    // widget broadcast is a background one, allowed up to a minute.
    private const val RESUME_TIMEOUT_MS = 15000L

    fun isPlaying(state: PlaybackState?): Boolean =
        state?.state == PlaybackState.STATE_PLAYING

    /**
     * Playing or on its way there. A player still buffering after Play has
     * heard us, and the button it needs next is Pause.
     */
    fun isActive(state: PlaybackState?): Boolean = when (state?.state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
        else -> false
    }

    /**
     * PlaybackState.position is a snapshot from lastPositionUpdateTime, so while
     * playing we extrapolate forward to get a position that actually moves.
     */
    fun currentPosition(state: PlaybackState?): Long {
        if (state == null) return 0L
        if (!isPlaying(state)) return state.position
        val drift = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        return state.position + (drift * state.playbackSpeed).toLong()
    }
}
