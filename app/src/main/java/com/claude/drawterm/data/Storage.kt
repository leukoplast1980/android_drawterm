package com.claude.drawterm.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * What a session shares with the server as /mnt/term: the app's own
 * drawterm folder (also browsable in the Files app, see HomeProvider), or,
 * if the user allows it, all of the phone's shared storage.
 */
object Storage {
    fun home(ctx: Context): File = File(ctx.filesDir, "home").apply { mkdirs() }

    fun hasPhoneAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    /** Android 11+: the settings page for "All files access"; null before. */
    fun phoneAccessSettings(ctx: Context): Intent? =
        if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
        } else {
            null
        }

    @Suppress("DEPRECATION") // still the POSIX path of shared storage
    fun root(ctx: Context, p: Profile): File =
        if (p.phoneStorage && hasPhoneAccess(ctx)) Environment.getExternalStorageDirectory() else home(ctx)
}
