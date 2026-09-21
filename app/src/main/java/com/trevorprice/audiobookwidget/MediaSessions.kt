package com.trevorprice.audiobookwidget

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
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

    fun isPlaying(state: PlaybackState?): Boolean =
        state?.state == PlaybackState.STATE_PLAYING

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
