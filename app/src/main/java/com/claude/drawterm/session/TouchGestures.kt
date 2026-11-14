package com.claude.drawterm.session

import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Fingers to Plan 9's three-button mouse.
 *
 *   tap                      button 1 click
 *   drag                     button 1 (select, sweep)
 *     + tap a second finger  chord 1-2: cut
 *     + tap two more         chord 1-3: paste
 *   long press, drag         button 3 (menus: lift to choose)
 *   two-finger tap / hold    button 2 click / menu
 *   two-finger drag          scroll
 *   three-finger tap         show or hide the keyboard
 *
 * In trackpad mode a finger moves the cursor instead of pointing, and
 * double-tap-and-drag holds button 1.
 */
class TouchGestures(private val view: DrawtermView) {
    var trackpad = false

    private enum class S { IDLE, PENDING, MOVE, DRAG, MULTI, SCROLL, DONE }

    private var s = S.IDLE
    private var pid = -1 // the finger that points
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var fingers = 0 // MULTI: most fingers down; DRAG: extra chord fingers
    private var multiY = 0f
    private var scrollAcc = 0f
    private var tapTime = 0L
    private var tapX = 0f
    private var tapY = 0f

    private val config = ViewConfiguration.get(view.context)
    private val slop = config.scaledTouchSlop.toFloat()
    private val doubleTapSlop = config.scaledDoubleTapSlop.toFloat()
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val scrollStep = 28 * view.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private val longPress = Runnable {
        if (s == S.PENDING) {
            s = S.DRAG
            press(4)
        }
    }
    private val multiLongPress = Runnable {
        if (s == S.MULTI && fingers == 2) {
            s = S.DRAG
            fingers = 0
            press(2)
        }
    }

    fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> down(e)
            MotionEvent.ACTION_POINTER_DOWN -> when (s) {
                S.PENDING -> {
                    cancelTimers()
                    s = S.MULTI
                    fingers = e.pointerCount
                    multiY = avgY(e)
                    handler.postDelayed(multiLongPress, longPressMs)
                }
                S.MULTI -> fingers = max(fingers, e.pointerCount)
                S.DRAG -> if (view.touchButtons == 1) fingers = max(fingers, e.pointerCount - 1)
                else -> {}
            }
            MotionEvent.ACTION_MOVE -> move(e)
            MotionEvent.ACTION_POINTER_UP -> pointerUp(e)
            MotionEvent.ACTION_UP -> up(e)
            MotionEvent.ACTION_CANCEL -> {
                cancelTimers()
                release()
                s = S.IDLE
            }
        }
        return true
    }

    private fun down(e: MotionEvent) {
        cancelTimers()
        pid = e.getPointerId(0)
        downX = e.x; downY = e.y
        lastX = e.x; lastY = e.y
        fingers = 0
        if (!trackpad) view.pointerFromView(e.x, e.y)
        if (trackpad && e.eventTime - tapTime < doubleTapMs && hypot(e.x - tapX, e.y - tapY) < doubleTapSlop) {
            s = S.DRAG
            press(1)
        } else {
            s = S.PENDING
            handler.postDelayed(longPress, longPressMs)
        }
    }

    private fun move(e: MotionEvent) {
        val i = e.findPointerIndex(pid)
        if (i < 0) return
        val x = e.getX(i)
        val y = e.getY(i)
        when (s) {
            S.PENDING -> if (hypot(x - downX, y - downY) > slop) {
                cancelTimers()
                if (trackpad) {
                    s = S.MOVE
                } else {
                    s = S.DRAG
                    press(1) // where the finger went down
                }
                moveTo(x, y)
            }
            S.MOVE, S.DRAG -> moveTo(x, y)
            S.MULTI -> if (abs(avgY(e) - multiY) > slop) {
                cancelTimers()
                s = if (fingers == 2) S.SCROLL else S.DONE
                multiY = avgY(e)
            }
            S.SCROLL -> {
                val y2 = avgY(e)
                scrollAcc += y2 - multiY
                multiY = y2
                // fingers moving down pull the text down: scroll up
                while (scrollAcc >= scrollStep) { scrollAcc -= scrollStep; view.wheel(up = true) }
                while (scrollAcc <= -scrollStep) { scrollAcc += scrollStep; view.wheel(up = false) }
            }
            else -> {}
        }
    }

    private fun pointerUp(e: MotionEvent) {
        when (s) {
            S.MULTI -> {
                cancelTimers()
                if (fingers >= 3) view.host.toggleKeyboard() else view.click(2)
                s = S.DONE
            }
            S.SCROLL -> s = S.DONE
            S.MOVE, S.DRAG -> if (e.getPointerId(e.actionIndex) == pid) {
                val other = if (e.actionIndex == 0) 1 else 0
                pid = e.getPointerId(other)
                lastX = e.getX(other)
                lastY = e.getY(other)
            } else if (e.pointerCount == 2 && fingers > 0) {
                // the last chord finger lifted
                if (view.touchButtons == 1) view.click(if (fingers == 1) 2 else 4)
                fingers = 0
            }
            else -> {}
        }
    }

    private fun up(e: MotionEvent) {
        cancelTimers()
        when (s) {
            S.PENDING -> {
                view.click(1)
                tapTime = e.eventTime
                tapX = e.x
                tapY = e.y
            }
            S.DRAG -> release()
            else -> {}
        }
        s = S.IDLE
    }

    private fun moveTo(x: Float, y: Float) {
        if (trackpad) view.movePointerBy((x - lastX) * TRACKPAD_SPEED, (y - lastY) * TRACKPAD_SPEED)
        else view.pointerFromView(x, y)
        lastX = x
        lastY = y
        view.sendMouse()
    }

    private fun press(button: Int) {
        if (button != 1) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        view.touchButtons = button
        view.sendMouse()
    }

    private fun release() {
        if (view.touchButtons == 0) return
        view.touchButtons = 0
        view.sendMouse()
    }

    private fun cancelTimers() {
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(multiLongPress)
    }

    private fun avgY(e: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until e.pointerCount) sum += e.getY(i)
        return sum / e.pointerCount
    }

    private companion object {
        const val TRACKPAD_SPEED = 1.6f
    }
}
