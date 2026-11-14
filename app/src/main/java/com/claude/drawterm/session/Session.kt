package com.claude.drawterm.session

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Display
import androidx.lifecycle.Lifecycle
import com.claude.drawterm.data.Profile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The drawterm session of the ":session" process. drawterm's emulated kernel
 * is process-wide and can't be restarted, so each session gets a fresh
 * process and ending the session ends it; the server list lives on in the
 * main process.
 */
object Session {
    enum class State { IDLE, RUNNING }

    @Volatile var state = State.IDLE
        private set

    @Volatile var profile: Profile? = null
        private set

    lateinit var app: Context
        private set

    /** The session screen while it exists. Main thread only. */
    var activity: SessionActivity? = null

    class Cursor(val pixels: IntArray, val hotX: Int, val hotY: Int)

    @Volatile var cursor: Cursor? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private lateinit var clipboard: ClipboardManager

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        clipboard = app.getSystemService(ClipboardManager::class.java)
        Notifications.createChannels(app)
    }

    /** Run [block] on the main thread with the session screen, if any. */
    fun ui(block: (SessionActivity) -> Unit) {
        main.post { activity?.let(block) }
    }

    fun start(p: Profile, password: String?, root: String, width: Int, height: Int, scale: Int) {
        if (state != State.IDLE) return
        state = State.RUNNING
        profile = p
        Native.setSize(width, height, scale)
        val args = p.args(root).map { it.toByteArray() }.toTypedArray()
        val pass = password?.ifEmpty { null }?.toByteArray()
        Thread(null, {
            val code = runCatching { Native.run(app.packageName, args, pass) }
                .getOrElse { Log.e("drawterm", "drawterm failed", it); 1 }
            exited(code, "") // main() returned instead of calling exit()
            Process.killProcess(Process.myPid())
        }, "drawterm", 16L shl 20).start()
        if (p.keepAlive) SessionService.start(app)
    }

    /** drawterm is exiting; returns once the UI has been told why. */
    fun exited(code: Int, message: String) {
        val done = CountDownLatch(1)
        main.post {
            try {
                val act = activity
                act?.finishSession(code, message)
                val seen = act != null && act.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (!seen && code != 0) Notifications.sessionEnded(app, profile?.title.orEmpty(), message)
                SessionService.stop(app)
            } finally {
                done.countDown()
            }
        }
        done.await(3, TimeUnit.SECONDS)
    }

    fun disconnect() {
        main.post {
            activity?.finishSession(0, "")
            if (::app.isInitialized) SessionService.stop(app)
            Process.killProcess(Process.myPid())
        }
    }

    // Plan 9's snarf buffer and Android's clipboard are synced lazily, so
    // Android's "pasted" toast and "copied" overlay don't interrupt work:
    // snarfs reach Android when another app comes to the front, and Android's
    // clipboard is only read when it has changed since.

    private var snarf: String? = null
    private var snarfTime = 0L // when the newest text we hold was set, in ClipDescription's wall-clock time
    private var snarfUnsynced = false

    fun setSnarf(text: String) = synchronized(this) {
        snarf = text
        snarfTime = System.currentTimeMillis()
        snarfUnsynced = true
    }

    fun clipboardText(): String? {
        val stamp = runCatching { clipboard.primaryClipDescription?.timestamp }.getOrNull() ?: 0L
        synchronized(this) {
            if (stamp > snarfTime) {
                runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString() }
                    .getOrNull()?.let {
                        snarf = it
                        snarfUnsynced = false
                    }
                snarfTime = stamp
            }
            return snarf
        }
    }

    /** Give Android Plan 9's latest snarf. Main thread. */
    fun syncClipboard() {
        val text = synchronized(this) {
            if (!snarfUnsynced) return
            snarfUnsynced = false
            snarf
        } ?: return
        runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText("Plan 9", text))
            val stamp = clipboard.primaryClipDescription?.timestamp ?: 0L
            synchronized(this) { snarfTime = maxOf(snarfTime, stamp) }
        }
    }

    fun setCursor(pixels: IntArray, hotX: Int, hotY: Int) {
        cursor = Cursor(pixels, hotX, hotY)
        ui { it.drawterm.updateCursor() }
    }

    fun takePicture(camera: Int): ByteArray? =
        if (ensurePermission(Manifest.permission.CAMERA)) {
            val rotation = app.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
            CameraCapture.take(app, camera, rotation)
        } else {
            null
        }

    fun notify(text: String) {
        if (Build.VERSION.SDK_INT < 33 || ensurePermission(Manifest.permission.POST_NOTIFICATIONS)) {
            Notifications.message(app, text)
        }
    }

    /**
     * Ask for [permission] through the session screen, the first time a Plan 9
     * program needs it. Blocks the calling drawterm thread.
     */
    private fun ensurePermission(permission: String): Boolean {
        if (app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return true
        val granted = AtomicBoolean(false)
        val done = CountDownLatch(1)
        main.post {
            val act = activity
            // Android won't show the request over another app
            if (act == null || act.isFinishing || !act.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                done.countDown()
            } else {
                act.requestPermission(permission) { ok ->
                    granted.set(ok)
                    done.countDown()
                }
            }
        }
        done.await(2, TimeUnit.MINUTES)
        return granted.get()
    }
}
