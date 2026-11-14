package com.claude.drawterm.session

import android.view.KeyCharacterMap
import android.view.KeyEvent

/** Android keys to Plan 9 runes (drawterm/include/keyboard.h). */
object Keymap {
    const val KF = 0xF000
    private const val SPEC = 0xF800

    const val KHOME = KF or 0x0D
    const val KUP = KF or 0x0E
    const val KPGUP = KF or 0x0F
    const val KPRINT = KF or 0x10
    const val KLEFT = KF or 0x11
    const val KRIGHT = KF or 0x12
    const val KPGDOWN = KF or 0x13
    const val KINS = KF or 0x14
    const val KALT = KF or 0x15
    const val KSHIFT = KF or 0x16
    const val KCTL = KF or 0x17
    const val KEND = KF or 0x18
    const val KSCROLL = KF or 0x19
    const val KDOWN = SPEC
    const val KBREAK = SPEC or 0x61
    const val KCAPS = SPEC or 0x64
    const val KNUM = SPEC or 0x65
    const val KMOD4 = SPEC or 0x68
    const val KBS = 0x08
    const val KDEL = 0x7F
    const val KESC = 0x1B

    /** Keys that are not characters, or 0. */
    fun special(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_ESCAPE -> KESC
        KeyEvent.KEYCODE_DEL -> KBS
        KeyEvent.KEYCODE_FORWARD_DEL -> KDEL
        KeyEvent.KEYCODE_TAB -> '\t'.code
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> '\n'.code
        KeyEvent.KEYCODE_DPAD_UP -> KUP
        KeyEvent.KEYCODE_DPAD_DOWN -> KDOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> KLEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> KRIGHT
        KeyEvent.KEYCODE_MOVE_HOME -> KHOME
        KeyEvent.KEYCODE_MOVE_END -> KEND
        KeyEvent.KEYCODE_PAGE_UP -> KPGUP
        KeyEvent.KEYCODE_PAGE_DOWN -> KPGDOWN
        KeyEvent.KEYCODE_INSERT -> KINS
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> KF or (keyCode - KeyEvent.KEYCODE_F1 + 1)
        KeyEvent.KEYCODE_SYSRQ -> KPRINT
        KeyEvent.KEYCODE_BREAK -> KBREAK
        KeyEvent.KEYCODE_SCROLL_LOCK -> KSCROLL
        KeyEvent.KEYCODE_CAPS_LOCK -> KCAPS
        KeyEvent.KEYCODE_NUM_LOCK -> KNUM
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> KSHIFT
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> KCTL
        // left Alt is Plan 9's compose; right Alt stays AltGr for @, {, |...
        KeyEvent.KEYCODE_ALT_LEFT -> KALT
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> KMOD4
        else -> 0
    }

    /**
     * The rune for a key press, 0 if none. Dead keys come back with
     * KeyCharacterMap.COMBINING_ACCENT set.
     */
    fun rune(e: KeyEvent): Int {
        special(e.keyCode).let { if (it != 0) return it }
        var meta = e.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK).inv()
        // left Alt is compose, sent as a key of its own; only AltGr picks characters
        if (meta and KeyEvent.META_ALT_RIGHT_ON == 0) meta = meta and KeyEvent.META_ALT_MASK.inv()
        var c = e.getUnicodeChar(meta)
        if (c == 0 && meta and KeyEvent.META_ALT_MASK != 0) c = e.getUnicodeChar(meta and KeyEvent.META_ALT_MASK.inv())
        if (e.isCtrlPressed && c and KeyCharacterMap.COMBINING_ACCENT == 0) c = ctrl(c)
        return c
    }

    /** The control character for c, as terminals send it. */
    fun ctrl(c: Int): Int = when (c) {
        in 0x40..0x5F -> c - 0x40
        in 0x61..0x7A -> c - 0x60
        '?'.code -> 0x7F
        else -> c
    }
}

/** Ctrl and Alt from the extra-keys bar: off, for the next key, or locked. */
class StickyModifiers {
    enum class State { OFF, ONCE, LOCKED }

    var ctrl = State.OFF
        private set
    var alt = State.OFF
        private set
    var listener: (() -> Unit)? = null
    val active get() = ctrl != State.OFF || alt != State.OFF

    fun toggleCtrl() {
        ctrl = next(ctrl)
        listener?.invoke()
    }

    fun toggleAlt() {
        alt = next(alt)
        listener?.invoke()
    }

    /** A key was typed: one-shot modifiers are used up. */
    fun consume() {
        var changed = false
        if (ctrl == State.ONCE) { ctrl = State.OFF; changed = true }
        if (alt == State.ONCE) { alt = State.OFF; changed = true }
        if (changed) listener?.invoke()
    }

    private fun next(s: State) = when (s) {
        State.OFF -> State.ONCE
        State.ONCE -> State.LOCKED
        State.LOCKED -> State.OFF
    }
}
