package com.claude.drawterm.data

import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.FileNotFoundException

/**
 * A small JSON document in the app's private files, written atomically.
 * Used from both the app and the session process; within a process, calls
 * are serialised on [lock].
 */
internal class JsonFile(dir: File, name: String) {
    private val file = AtomicFile(File(dir, name))

    fun read(): String? = try {
        String(file.readFully(), Charsets.UTF_8)
    } catch (e: FileNotFoundException) {
        null
    } catch (e: Exception) {
        Log.w(TAG, "cannot read ${file.baseFile.name}", e)
        null
    }

    fun write(text: String) {
        val out = file.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.w(TAG, "cannot write ${file.baseFile.name}", e)
        }
    }

    companion object {
        const val TAG = "drawterm"
        val lock = Any()
    }
}
