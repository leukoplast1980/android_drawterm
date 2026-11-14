package com.claude.drawterm.session

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.claude.drawterm.R

/** Keys phone keyboards lack, shown on top of the soft keyboard. */
@SuppressLint("ViewConstructor")
class ExtraKeysView(context: Context, private val dt: DrawtermView, onHide: () -> Unit) :
    HorizontalScrollView(context) {

    val barHeight = (44 * resources.displayMetrics.density).toInt()
    private val row = LinearLayout(context)
    private val handler = Handler(Looper.getMainLooper())
    private val ctrl: TextView
    private val alt: TextView

    init {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        setBackgroundColor(BACKGROUND)
        addView(row)
        key("Esc") { dt.typeRune(Keymap.KESC) }
        ctrl = key("Ctrl") { dt.modifiers.toggleCtrl() }
        alt = key("Alt") { dt.modifiers.toggleAlt() }
        key("Tab") { dt.typeRune('\t'.code) }
        listOf(
            "←" to Keymap.KLEFT, "↓" to Keymap.KDOWN, "↑" to Keymap.KUP, "→" to Keymap.KRIGHT,
            "Home" to Keymap.KHOME, "End" to Keymap.KEND, "PgUp" to Keymap.KPGUP, "PgDn" to Keymap.KPGDOWN,
            "Del" to Keymap.KDEL,
        ).forEach { (label, rune) -> repeatingKey(label, rune) }
        key("⌄", context.getString(R.string.key_hide_keyboard)) { onHide() }
        dt.modifiers.listener = ::refresh
        refresh()
    }

    private fun refresh() {
        for ((v, state) in listOf(ctrl to dt.modifiers.ctrl, alt to dt.modifiers.alt)) {
            v.setTextColor(if (state == StickyModifiers.State.OFF) Color.WHITE else ACCENT)
            v.paintFlags = if (state == StickyModifiers.State.LOCKED) {
                v.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            } else {
                v.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
            }
        }
    }

    private fun button(label: String, description: String?) = TextView(context).apply {
        text = label
        contentDescription = description ?: label
        setTextColor(Color.WHITE)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minWidth = barHeight
        setPadding(barHeight / 4, 0, barHeight / 4, 0)
        background = RippleDrawable(ColorStateList.valueOf(PRESSED), null, ColorDrawable(Color.WHITE))
        isFocusable = false
        row.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, barHeight))
    }

    private fun key(label: String, description: String? = null, action: () -> Unit) =
        button(label, description).apply {
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                action()
            }
        }

    /** Repeats while held, like a keyboard key. */
    @SuppressLint("ClickableViewAccessibility")
    private fun repeatingKey(label: String, rune: Int) {
        val repeat = object : Runnable {
            override fun run() {
                dt.tapKey(rune)
                handler.postDelayed(this, 50)
            }
        }
        button(label, null).setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    dt.tapKey(rune)
                    handler.postDelayed(repeat, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeat)
                }
            }
            true
        }
    }

    private companion object {
        const val BACKGROUND = 0xFF1E1E24.toInt()
        const val PRESSED = 0x40FFFFFF
        const val ACCENT = 0xFF8AB4F8.toInt()
    }
}
