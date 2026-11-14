package com.claude.drawterm.session

import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.claude.drawterm.R
import com.claude.drawterm.data.Profile
import com.claude.drawterm.data.ProfileStore
import com.claude.drawterm.data.SecretStore
import com.claude.drawterm.data.Storage

/**
 * The session screen: nothing but drawterm. The key row appears only with
 * the soft keyboard; Back (or a keyboard's Menu key) opens a small menu.
 * Runs in the ":session" process with drawterm (see [Session]); the server
 * list gets the outcome as an activity result.
 */
class SessionActivity : ComponentActivity(), DrawtermView.Host {
    lateinit var drawterm: DrawtermView
        private set
    private lateinit var profile: Profile
    private lateinit var screen: FrameLayout
    private lateinit var keys: ExtraKeysView
    private lateinit var menuAnchor: View
    private var imeHeight = 0
    private var keyboardForPrompt = false
    private var finishing = false

    private var permissionCallback: ((Boolean) -> Unit)? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionCallback?.invoke(granted)
            permissionCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Session.init(this)
        if (savedInstanceState?.getBoolean(STATE_RUNNING) == true && Session.state == Session.State.IDLE) {
            // Android killed the session process; don't silently start a new one
            finishSession(1, getString(R.string.session_lost))
            return
        }
        profile = Session.profile ?: ProfileStore(this).get(intent.getStringExtra(EXTRA_PROFILE)) ?: run {
            finishSession(1, getString(R.string.profile_missing))
            return
        }
        warnIfOtherServer(intent)
        buildUi()
        Session.activity = this
        drawterm.updateCursor()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = showMenu()
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_RUNNING, Session.state == Session.State.RUNNING)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        warnIfOtherServer(intent)
    }

    // Hand Plan 9's snarf to Android when another app takes over (not when
    // our own menu takes focus, or Android's "copied" overlay would pop up).
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (!isTopResumedActivity) Session.syncClipboard()
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT < 29) Session.syncClipboard() // no onTopResumedActivityChanged
    }

    override fun onDestroy() {
        if (Session.activity === this) Session.activity = null
        super.onDestroy()
    }

    private fun warnIfOtherServer(intent: Intent) {
        val wanted = intent.getStringExtra(EXTRA_PROFILE) ?: return
        val running = Session.profile ?: return
        if (wanted != running.id) {
            Toast.makeText(this, getString(R.string.already_connected, running.title), Toast.LENGTH_LONG).show()
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        screen = FrameLayout(this)
        drawterm = DrawtermView(this, this)
        val overlay = CursorOverlay(this, drawterm)
        screen.addView(drawterm, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        screen.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        keys = ExtraKeysView(this, drawterm, ::hideKeyboard).apply { visibility = View.GONE }
        menuAnchor = View(this)
        root.addView(screen, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(keys, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(menuAnchor, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.END))
        setContentView(root)

        drawterm.cursorOverlay = overlay
        drawterm.scaleSetting = profile.scale
        drawterm.trackpad = profile.trackpad

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = if (profile.fullscreen) 0 else WindowInsetsCompat.Type.systemBars()
            val pad = insets.getInsets(WindowInsetsCompat.Type.displayCutout() or bars)
            screen.setPadding(pad.left, pad.top, pad.right, pad.bottom)
            menuAnchor.layoutParams = (menuAnchor.layoutParams as FrameLayout.LayoutParams)
                .apply { setMargins(0, pad.top, pad.right, 0) }
            val ime = WindowInsetsCompat.Type.ime()
            imeHeight = if (insets.isVisible(ime)) insets.getInsets(ime).bottom else 0
            fitKeyboard()
            WindowInsetsCompat.CONSUMED
        }
        WindowCompat.getInsetsController(window, root).systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        applyFullscreen()
    }

    private fun applyFullscreen() {
        val bars = WindowInsetsCompat.Type.systemBars()
        WindowCompat.getInsetsController(window, drawterm).run { if (profile.fullscreen) hide(bars) else show(bars) }
        ViewCompat.requestApplyInsets(window.decorView)
    }

    /**
     * Slide the screen up so the pointer stays above the soft keyboard.
     * Resizing instead would make every Plan 9 program redraw, over the
     * network, each time the keyboard comes or goes.
     */
    private fun fitKeyboard() {
        val showKeys = imeHeight > 0 && profile.extraKeys
        keys.visibility = if (showKeys) View.VISIBLE else View.GONE
        keys.translationY = -imeHeight.toFloat()
        val covered = if (imeHeight > 0) imeHeight + (if (showKeys) keys.barHeight else 0) else 0
        val pointer = screen.paddingTop + drawterm.pointerY * drawterm.scale + keys.barHeight
        screen.translationY = -(pointer - (screen.height - covered)).coerceIn(0f, covered.toFloat())
    }

    override fun onScreenSized() {
        if (Session.state != Session.State.IDLE) return
        val password = if (profile.savePassword) SecretStore(this).get(profile.id) else null
        Session.start(profile, password, Storage.root(this, profile).path, drawterm.dtWidth, drawterm.dtHeight, drawterm.scale)
        drawterm.requestFocus()
    }

    /** Close the screen, telling the server list how the session ended. */
    fun finishSession(code: Int, message: String) {
        if (finishing) return
        finishing = true
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_CODE, code)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_PROFILE, Session.profile?.id ?: intent.getStringExtra(EXTRA_PROFILE)),
        )
        finish()
    }

    fun requestPermission(permission: String, callback: (Boolean) -> Unit) {
        permissionCallback?.invoke(false)
        permissionCallback = callback
        permissionLauncher.launch(permission)
    }

    // --- keyboard ---

    private fun keyboardVisible() = imeHeight > 0

    private val hasHardwareKeyboard
        get() = resources.configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO

    private fun showKeyboard() {
        drawterm.requestFocus()
        WindowCompat.getInsetsController(window, drawterm).show(WindowInsetsCompat.Type.ime())
    }

    private fun hideKeyboard() {
        WindowCompat.getInsetsController(window, drawterm).hide(WindowInsetsCompat.Type.ime())
    }

    override fun toggleKeyboard() = if (keyboardVisible()) hideKeyboard() else showKeyboard()

    /** drawterm started or stopped prompting on its console (e.g. for a password). */
    fun promptKeyboard(prompting: Boolean) {
        if (prompting && !keyboardVisible() && !hasHardwareKeyboard) {
            keyboardForPrompt = true
            showKeyboard()
        } else if (!prompting && keyboardForPrompt) {
            keyboardForPrompt = false
            hideKeyboard()
        }
    }

    // --- menu ---

    override fun showMenu() {
        val popup = PopupMenu(this, menuAnchor, Gravity.END)
        val m = popup.menu
        m.add(0, KEYBOARD, 0, if (keyboardVisible()) R.string.menu_hide_keyboard else R.string.menu_show_keyboard)
        m.add(0, KEYS, 0, R.string.menu_extra_keys).setCheckable(true).setChecked(profile.extraKeys)
        m.add(0, TRACKPAD, 0, R.string.menu_trackpad).setCheckable(true).setChecked(drawterm.trackpad)
        m.add(0, FULLSCREEN, 0, R.string.fullscreen).setCheckable(true).setChecked(profile.fullscreen)
        m.addSubMenu(getString(R.string.menu_scale, drawterm.scale)).apply {
            for (s in 0..4) {
                add(SCALES, SCALE + s, 0, if (s == 0) getString(R.string.scale_auto, drawterm.autoScale) else "$s×")
                    .setChecked(drawterm.scaleSetting == s)
            }
            setGroupCheckable(SCALES, true, true)
        }
        m.add(0, PASTE, 0, R.string.menu_type_clipboard)
        m.add(0, DISCONNECT, 0, R.string.menu_disconnect)
        popup.setOnMenuItemClickListener { item ->
            when (val id = item.itemId) {
                KEYBOARD -> toggleKeyboard()
                KEYS -> {
                    remember { it.copy(extraKeys = !it.extraKeys) }
                    fitKeyboard()
                }
                TRACKPAD -> {
                    remember { it.copy(trackpad = !it.trackpad) }
                    drawterm.trackpad = profile.trackpad
                }
                FULLSCREEN -> {
                    remember { it.copy(fullscreen = !it.fullscreen) }
                    applyFullscreen()
                }
                in SCALE..SCALE + 4 -> {
                    remember { it.copy(scale = id - SCALE) }
                    drawterm.scaleSetting = profile.scale
                }
                PASTE -> Session.clipboardText()?.let { drawterm.typeText(it.take(MAX_TYPED)) }
                DISCONNECT -> confirmDisconnect()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun confirmDisconnect() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.disconnect_title, profile.title))
            .setPositiveButton(R.string.disconnect) { _, _ ->
                if (Session.state == Session.State.RUNNING) Session.disconnect() else finishSession(0, "")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Keep a setting changed during the session for next time. */
    private fun remember(change: (Profile) -> Profile) {
        profile = change(profile)
        ProfileStore(this).update(profile.id, change)
    }

    companion object {
        const val EXTRA_PROFILE = "com.claude.drawterm.PROFILE"
        const val EXTRA_CODE = "com.claude.drawterm.CODE"
        const val EXTRA_MESSAGE = "com.claude.drawterm.MESSAGE"

        private const val KEYBOARD = 1
        private const val KEYS = 2
        private const val TRACKPAD = 3
        private const val PASTE = 4
        private const val DISCONNECT = 5
        private const val FULLSCREEN = 6
        private const val SCALES = 1
        private const val SCALE = 10

        private const val STATE_RUNNING = "running"
        // drawterm queues ~6 bytes per key in 4 KB and drops the rest; long text goes through snarf
        private const val MAX_TYPED = 500
    }
}
