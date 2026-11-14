package com.claude.drawterm.session

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.text.InputType
import android.util.SparseIntArray
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The drawterm screen: libdrawterm draws into this SurfaceView, and touch,
 * mouse, stylus and keyboard input become Plan 9 mouse and keyboard events.
 */
@SuppressLint("ViewConstructor")
class DrawtermView(context: Context, val host: Host) : SurfaceView(context), SurfaceHolder.Callback {
    interface Host {
        fun onScreenSized()
        fun toggleKeyboard()
        fun showMenu()
    }

    /** Requested scale; 0 is automatic. */
    var scaleSetting = 0
        set(v) {
            field = v
            resize()
        }
    val autoScale get() = resources.displayMetrics.density.roundToInt().coerceIn(1, 4)
    var scale = 1
        private set
    var dtWidth = 0
        private set
    var dtHeight = 0
        private set
    private var surfaceW = 0
    private var surfaceH = 0

    /** Pointer position in drawterm pixels. */
    var pointerX = 0f
        private set
    var pointerY = 0f
        private set
    var touchButtons = 0
    private var mouseButtons = 0

    private val gestures = TouchGestures(this)
    var trackpad: Boolean
        get() = gestures.trackpad
        set(v) {
            gestures.trackpad = v
            cursorOverlay?.visibility = if (v) VISIBLE else GONE
        }
    var cursorOverlay: CursorOverlay? = null
    private var icon: PointerIcon? = null

    val modifiers = StickyModifiers()
    private val heldKeys = SparseIntArray() // keyCode -> rune sent on key down
    private var deadKey = 0
    private var wheelAcc = 0f

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.RGBX_8888)
        isFocusable = true
        isFocusableInTouchMode = true
        // the focus highlight would be drawn over the screen with a keyboard attached
        defaultFocusHighlightEnabled = false
    }

    // --- surface ---

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
        Native.setSurface(holder.surface)
        resize()
        host.onScreenSized()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = Native.setSurface(null)

    private fun resize() {
        if (surfaceW <= 0 || surfaceH <= 0) return
        scale = if (scaleSetting in 1..8) scaleSetting else autoScale
        dtWidth = max(64, surfaceW / scale)
        dtHeight = max(64, surfaceH / scale)
        pointerX = pointerX.coerceIn(0f, dtWidth - 1f)
        pointerY = pointerY.coerceIn(0f, dtHeight - 1f)
        Native.setSize(dtWidth, dtHeight, scale)
        updateCursor()
    }

    // --- pointer ---

    fun pointerFromView(x: Float, y: Float) {
        pointerX = (x / scale).coerceIn(0f, max(0, dtWidth - 1).toFloat())
        pointerY = (y / scale).coerceIn(0f, max(0, dtHeight - 1).toFloat())
        cursorOverlay?.invalidate()
    }

    fun movePointerBy(dx: Float, dy: Float) = pointerFromView(pointerX * scale + dx, pointerY * scale + dy)

    fun pointerFromDrawterm(x: Int, y: Int) = pointerFromView(x.toFloat() * scale, y.toFloat() * scale)

    fun sendMouse() = Native.mouse(pointerX.toInt(), pointerY.toInt(), touchButtons or mouseButtons)

    /** Press and release [button] on top of whatever is held. */
    fun click(button: Int) {
        val held = touchButtons or mouseButtons
        Native.mouse(pointerX.toInt(), pointerY.toInt(), held or button)
        Native.mouse(pointerX.toInt(), pointerY.toInt(), held)
    }

    fun wheel(up: Boolean) = click(if (up) 8 else 16)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            when (e.classification) {
                MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE -> {
                    // touchpad scrolling, same direction as finger scrolling
                    wheelBy(e.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE) / (24 * resources.displayMetrics.density))
                    return true
                }
                MotionEvent.CLASSIFICATION_PINCH -> return true
            }
        }
        if (isPointer(e)) {
            pointerFromView(e.x, e.y)
            mouseButtons = buttonsOf(e)
            sendMouse()
            return true
        }
        if (e.actionMasked == MotionEvent.ACTION_DOWN) requestFocus()
        return gestures.onTouchEvent(e)
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (!e.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return super.onGenericMotionEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE,
            -> {
                pointerFromView(e.x, e.y)
                mouseButtons = buttonsOf(e)
                sendMouse()
            }
            MotionEvent.ACTION_SCROLL -> wheelBy(e.getAxisValue(MotionEvent.AXIS_VSCROLL))
            MotionEvent.ACTION_HOVER_EXIT -> {}
            else -> return super.onGenericMotionEvent(e)
        }
        return true
    }

    private fun wheelBy(clicks: Float) {
        wheelAcc += clicks
        while (wheelAcc >= 1f) { wheelAcc -= 1f; wheel(up = true) }
        while (wheelAcc <= -1f) { wheelAcc += 1f; wheel(up = false) }
    }

    private fun isPointer(e: MotionEvent) = when (e.getToolType(0)) {
        MotionEvent.TOOL_TYPE_MOUSE, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> true
        MotionEvent.TOOL_TYPE_FINGER -> false
        else -> e.isFromSource(InputDevice.SOURCE_MOUSE)
    }

    /** Mouse buttons as such; for a stylus, the tip is 1 and barrel buttons are 3 and 2. */
    private fun buttonsOf(e: MotionEvent): Int {
        val bs = e.buttonState
        fun has(mask: Int) = bs and mask != 0
        val tool = e.getToolType(0)
        if (tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER) {
            return (if (has(MotionEvent.BUTTON_PRIMARY)) 1 else 0) or
                (if (has(MotionEvent.BUTTON_TERTIARY)) 2 else 0) or
                (if (has(MotionEvent.BUTTON_SECONDARY)) 4 else 0)
        }
        val touching = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN -> true
            else -> false
        }
        if (!touching) return 0
        val barrel = (if (has(MotionEvent.BUTTON_STYLUS_PRIMARY) || has(MotionEvent.BUTTON_SECONDARY)) 4 else 0) or
            (if (has(MotionEvent.BUTTON_STYLUS_SECONDARY) || has(MotionEvent.BUTTON_TERTIARY)) 2 else 0)
        return if (barrel != 0) barrel else 1
    }

    /** The Plan 9 cursor, scaled, as the mouse pointer and the trackpad cursor. */
    fun updateCursor() {
        val c = Session.cursor ?: return
        val small = Bitmap.createBitmap(c.pixels, 16, 16, Bitmap.Config.ARGB_8888)
        val big = if (scale == 1) small else Bitmap.createScaledBitmap(small, 16 * scale, 16 * scale, false)
        val hx = (c.hotX * scale).coerceIn(0, big.width - 1)
        val hy = (c.hotY * scale).coerceIn(0, big.height - 1)
        icon = PointerIcon.create(big, hx.toFloat(), hy.toFloat())
        pointerIcon = icon
        cursorOverlay?.setCursor(big, hx, hy)
    }

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? =
        icon ?: super.onResolvePointerIcon(event, pointerIndex)

    // --- keyboard ---

    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean {
        when {
            // a mouse's back button, or a right click Android turned into Back
            keyCode == KeyEvent.KEYCODE_BACK && e.isFromSource(InputDevice.SOURCE_MOUSE) -> return true
            keyCode == KeyEvent.KEYCODE_MENU -> {
                if (e.repeatCount == 0) host.showMenu()
                return true
            }
            e.isSystem -> return super.onKeyDown(keyCode, e)
        }
        var r = Keymap.rune(e)
        if (r == 0) return super.onKeyDown(keyCode, e)
        if (r and KeyCharacterMap.COMBINING_ACCENT != 0) {
            deadKey = r and KeyCharacterMap.COMBINING_ACCENT_MASK
            return true
        }
        if (deadKey != 0) {
            if (r < Keymap.KF) r = KeyCharacterMap.getDeadChar(deadKey, r).takeIf { it != 0 } ?: r
            deadKey = 0
        }
        if (r < Keymap.KF && modifiers.active) {
            typeRune(r)
            heldKeys.put(keyCode, -1)
        } else {
            if (e.repeatCount > 0) Native.key(r, false)
            heldKeys.put(keyCode, r)
            Native.key(r, true)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && e.isFromSource(InputDevice.SOURCE_MOUSE)) return true
        val r = heldKeys.get(keyCode, 0)
        if (r == 0) return super.onKeyUp(keyCode, e)
        heldKeys.delete(keyCode)
        if (r > 0) Native.key(r, false)
        return true
    }

    /** Release keys and buttons we would otherwise never see go up. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) return
        for (i in 0 until heldKeys.size()) heldKeys.valueAt(i).takeIf { it > 0 }?.let { Native.key(it, false) }
        heldKeys.clear()
        deadKey = 0
        if (touchButtons or mouseButtons != 0) {
            touchButtons = 0
            mouseButtons = 0
            sendMouse()
        }
    }

    /** Type a rune, with the bar's Ctrl and Alt. */
    fun typeRune(codePoint: Int) {
        var r = if (codePoint == '\r'.code) '\n'.code else codePoint
        val ctrl = modifiers.ctrl != StickyModifiers.State.OFF
        if (modifiers.alt != StickyModifiers.State.OFF) tapKey(Keymap.KALT) // Plan 9 compose
        if (ctrl) {
            r = Keymap.ctrl(r)
            Native.key(Keymap.KCTL, true)
        }
        tapKey(r)
        if (ctrl) Native.key(Keymap.KCTL, false)
        modifiers.consume()
    }

    fun typeText(s: CharSequence) = s.toString().codePoints().forEach { typeRune(it) }

    fun tapKey(rune: Int) {
        Native.key(rune, true)
        Native.key(rune, false)
    }

    // --- soft keyboard: a terminal, so no suggestions or autocorrect ---

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_ENTER_ACTION
        return TerminalInputConnection()
    }

    /** Typed text goes to drawterm; the editable only ever holds text being composed. */
    private inner class TerminalInputConnection : BaseInputConnection(this@DrawtermView, true) {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            super.commitText(text, newCursorPosition)
            return flush()
        }

        override fun finishComposingText(): Boolean {
            super.finishComposingText()
            return flush()
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (!editable.isNullOrEmpty()) return super.deleteSurroundingText(beforeLength, afterLength)
            repeat(beforeLength) { tapKey(Keymap.KBS) }
            repeat(afterLength) { tapKey(Keymap.KDEL) }
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            typeRune('\n'.code)
            return true
        }

        private fun flush(): Boolean {
            val e = editable ?: return true
            if (BaseInputConnection.getComposingSpanStart(e) < 0 && e.isNotEmpty()) {
                val text = e.toString()
                e.clear()
                typeText(text)
            }
            return true
        }
    }
}

/** The Plan 9 cursor drawn over the screen in trackpad mode. */
class CursorOverlay(context: Context, private val dt: DrawtermView) : View(context) {
    private var bitmap: Bitmap? = null
    private var hotX = 0
    private var hotY = 0
    private val paint = Paint() // no filtering: keep pixels sharp

    fun setCursor(b: Bitmap, hx: Int, hy: Int) {
        bitmap = b
        hotX = hx
        hotY = hy
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        canvas.drawBitmap(b, dt.pointerX * dt.scale - hotX, dt.pointerY * dt.scale - hotY, paint)
    }
}
