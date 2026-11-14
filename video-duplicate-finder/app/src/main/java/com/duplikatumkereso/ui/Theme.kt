package com.duplikatumkereso.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** A "B – sötét, idővonalas" kinézet színei. */
object C {
    val bg = Color(0xFF111316)
    val surface = Color(0xFF1D2026)
    val surface2 = Color(0xFF24282F)
    val track = Color(0xFF2A2E36)
    val outline = Color(0xFF343842)
    val text = Color(0xFFECEEF2)
    val text2 = Color(0xFFA3A9B4)
    val text3 = Color(0xFF8F96A3)
    val accent = Color(0xFFFF9A55)
    val accentText = Color(0xFFFFB98A)
    val onAccent = Color(0xFF1A1208)
    val thumb = Color(0xFF4A3A2C)
    val keepBg = Color(0xFF1F3A2B)
    val keepText = Color(0xFF8FD6A8)
    val danger = Color(0xFFFF8A80)
}

@Composable
fun DupTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent,
            onPrimary = C.onAccent,
            background = C.bg,
            onBackground = C.text,
            surface = C.surface,
            onSurface = C.text,
            surfaceVariant = C.surface2,
            onSurfaceVariant = C.text2,
            outline = C.outline,
        ),
        content = content,
    )
}
