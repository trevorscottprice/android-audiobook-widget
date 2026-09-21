package com.trevorprice.audiobookwidget

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService

/**
 * Exists for two reasons:
 *   1. Being an enabled notification listener is what grants us permission to
 *      read and control other apps' media sessions.
 *   2. It is long-lived, so it can watch the player's session and push the widget
 *      an update the instant playback changes.
 */
class SessionListenerService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private var watched: MediaController? = null

    private val sessionCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = pushUpdate()
        override fun onMetadataChanged(metadata: MediaMetadata?) = pushUpdate()
        override fun onSessionDestroyed() {
            watched = null
            attachToPlayer()
            pushUpdate()
        }
    }

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener {
            attachToPlayer()
            pushUpdate()
        }

    /** While playing, keep the progress bar and remaining time moving. */
    private val ticker = object : Runnable {
        override fun run() {
            AudiobookWidgetProvider.updateAll(this@SessionListenerService)
            if (MediaSessions.isPlaying(watched?.playbackState)) {
                handler.postDelayed(this, TICK_MS)
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        sessionManager = getSystemService(MediaSessionManager::class.java)
        try {
            sessionManager?.addOnActiveSessionsChangedListener(
                sessionsChanged, MediaSessions.listenerComponent(this), handler
            )
        } catch (e: SecurityException) {
            // Access was revoked between binding and now; nothing to watch.
        }
        attachToPlayer()
        pushUpdate()
    }

    override fun onListenerDisconnected() {
        handler.removeCallbacks(ticker)
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsChanged)
        watched?.unregisterCallback(sessionCallback)
        watched = null
        super.onListenerDisconnected()
    }

    private fun attachToPlayer() {
        val next = MediaSessions.controller(this)
        if (next?.sessionToken == watched?.sessionToken) return
        watched?.unregisterCallback(sessionCallback)
        watched = next
        watched?.registerCallback(sessionCallback, handler)
    }

    private fun pushUpdate() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    companion object {
        private const val TICK_MS = 5000L

        /** Ask the system to (re)bind us, e.g. right after access is granted. */
        fun requestRebind(context: Context) {
            try {
                NotificationListenerService.requestRebind(
                    ComponentName(context, SessionListenerService::class.java)
                )
            } catch (e: Exception) {
                // Not yet permitted -- the system will bind us once access is on.
            }
        }
    }
}
