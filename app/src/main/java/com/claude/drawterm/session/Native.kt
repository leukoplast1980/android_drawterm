package com.claude.drawterm.session

import android.view.Surface

/** libdrawterm; registered by name in jni.c, kept by proguard-rules.pro. */
object Native {
    init {
        System.loadLibrary("drawterm")
    }

    /** Runs drawterm's main() on this thread; it normally ends in exit(). */
    @JvmStatic external fun run(packageName: String, args: Array<ByteArray>, password: ByteArray?): Int

    @JvmStatic external fun setSurface(surface: Surface?)

    /** Screen size in drawterm pixels and the whole-pixel scale. */
    @JvmStatic external fun setSize(width: Int, height: Int, scale: Int)

    /** Pointer in drawterm pixels; Plan 9 buttons 1, 2, 4, wheel 8 and 16. */
    @JvmStatic external fun mouse(x: Int, y: Int, buttons: Int)

    @JvmStatic external fun key(rune: Int, down: Boolean)
}

/** Called from drawterm's threads; names and signatures are in jni.c. */
object NativeCallbacks {
    @JvmStatic fun clipRead(): ByteArray? = Session.clipboardText()?.toByteArray()

    @JvmStatic fun clipWrite(text: ByteArray) = Session.setSnarf(String(text))

    @JvmStatic fun notify(text: ByteArray) = Session.notify(String(text))

    @JvmStatic fun numCameras(): Int = CameraCapture.count(Session.app)

    @JvmStatic fun takePicture(camera: Int): ByteArray? = Session.takePicture(camera)

    @JvmStatic fun setCursor(argb: IntArray, hotX: Int, hotY: Int) = Session.setCursor(argb, hotX, hotY)

    @JvmStatic fun moveCursor(x: Int, y: Int) = Session.ui { it.drawterm.pointerFromDrawterm(x, y) }

    @JvmStatic fun wantKeyboard(show: Boolean) = Session.ui { it.promptKeyboard(show) }

    @JvmStatic fun exited(code: Int, message: ByteArray) = Session.exited(code, String(message))
}
