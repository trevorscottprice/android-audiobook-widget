package com.trevorprice.audiobookwidget

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * One-time setup: grant notification access, confirm which app to control,
 * and pick how far the skip buttons jump.
 */
class MainActivity : Activity() {

    private val skipChoices = intArrayOf(10, 15, 30, 60)

    private lateinit var accessStatus: TextView
    private lateinit var accessButton: Button
    private lateinit var appStatus: TextView
    private lateinit var skipButtons: List<Button>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hidden so the only inset to account for is the status bar, which the
        // insets API reports exactly. Left visible, the action bar overlays the
        // content on Android 15+ and buries the top of the screen.
        actionBar?.hide()
        setContentView(R.layout.activity_main)
        applyWindowInsets()

        accessStatus = findViewById(R.id.access_status)
        accessButton = findViewById(R.id.access_button)
        appStatus = findViewById(R.id.app_status)
        skipButtons = listOf(
            findViewById(R.id.skip_10),
            findViewById(R.id.skip_15),
            findViewById(R.id.skip_30),
            findViewById(R.id.skip_60)
        )

        accessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.app_button).setOnClickListener { showAppPicker() }

        skipButtons.forEachIndexed { index, button ->
            button.setOnClickListener {
                MediaSessions.setSkipSeconds(this, skipChoices[index])
                refresh()
            }
        }

        // First run: take a guess at which installed app is the player.
        if (MediaSessions.targetPackage(this) == null) {
            MediaSessions.autoDetectPackage(this)?.let { MediaSessions.setTargetPackage(this, it) }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val granted = MediaSessions.isListenerEnabled(this)
        accessStatus.text = getString(
            if (granted) R.string.access_granted else R.string.access_missing
        )
        accessButton.text = getString(
            if (granted) R.string.access_button_change else R.string.access_button_grant
        )
        if (granted) SessionListenerService.requestRebind(this)

        val pkg = MediaSessions.targetPackage(this)
        appStatus.text = if (pkg == null) {
            getString(R.string.app_none)
        } else {
            getString(R.string.app_selected, labelFor(pkg), pkg)
        }

        val skip = MediaSessions.skipSeconds(this)
        skipButtons.forEachIndexed { index, button ->
            button.isEnabled = skipChoices[index] != skip
        }

        AudiobookWidgetProvider.updateAll(this)
    }

    /**
     * From targetSdk 35 on Android 15+ the window is edge to edge, so content
     * would otherwise run under the status bar and the gesture bar. Pad by the
     * system bar insets on top of the base inset.
     */
    private fun applyWindowInsets() {
        val content = findViewById<View>(R.id.content)
        val base = (24 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                base + bars.left,
                base + bars.top,
                base + bars.right,
                base + bars.bottom
            )
            insets
        }
    }

    private fun labelFor(pkg: String): String =
        MediaSessions.launchableApps(this).firstOrNull { it.first == pkg }?.second ?: pkg

    private fun showAppPicker() {
        val apps = MediaSessions.launchableApps(this)
        if (apps.isEmpty()) return
        val labels = apps.map { "${it.second}\n${it.first}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.app_picker_title)
            .setItems(labels) { _, which ->
                MediaSessions.setTargetPackage(this, apps[which].first)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
