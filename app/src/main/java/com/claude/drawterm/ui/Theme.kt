package com.claude.drawterm.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// rio's colours: the blue-grey desktop and the pale yellow of acme.
private val Rio = Color(0xFF444488)
private val Acme = Color(0xFFFFFFEA)
private val Pale = Color(0xFFEAFFFF)

private val Light = lightColorScheme(
    primary = Rio,
    onPrimary = Color.White,
    secondary = Color(0xFF55AAAA),
    background = Acme,
    surface = Acme,
    surfaceVariant = Pale,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB5B5F0),
    onPrimary = Color(0xFF1A1A40),
    secondary = Color(0xFF8CCFCF),
)

@Composable
fun DrawtermTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
