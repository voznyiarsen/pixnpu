package com.pixnpu.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

val TerminalTypography = androidx.compose.material3.Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        color = TerminalText,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = TerminalText,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        color = TerminalTextDim,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = TerminalTextDim,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 16.sp,
        color = TerminalText,
    ),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PixNpuTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = TerminalPrimary,
            onPrimary = TerminalBackground,
            secondary = TerminalAccent,
            onSecondary = TerminalOnAccent,
            background = TerminalBackground,
            onBackground = TerminalText,
            surface = TerminalSurface,
            onSurface = TerminalText,
            surfaceVariant = TerminalSurfaceVariant,
            onSurfaceVariant = TerminalTextDim,
            outline = TerminalLine,
            outlineVariant = TerminalLine,
            error = TerminalDanger,
        ),
        motionScheme = MotionScheme.expressive(),
        typography = TerminalTypography,
        content = content,
    )
}