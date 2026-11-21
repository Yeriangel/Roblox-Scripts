package com.diagnosepro.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Accent = Color(0xFFFF6D00)
val Ok = Color(0xFF2EC27E)
val Warn = Color(0xFFF5C211)
val Danger = Color(0xFFE5484D)

private val Dark = darkColorScheme(
    primary = Accent, onPrimary = Color.Black,
    secondary = Color(0xFF4FC3F7), onSecondary = Color.Black,
    background = Color(0xFF0E1116), onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF161B22), onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF1F2630), onSurfaceVariant = Color(0xFFB0B8C4),
    surfaceContainer = Color(0xFF161B22), surfaceContainerHigh = Color(0xFF1F2630),
    error = Danger, outline = Color(0xFF3A4350),
)

private val Light = lightColorScheme(
    primary = Color(0xFFD35400), onPrimary = Color.White,
    secondary = Color(0xFF0277BD), onSecondary = Color.White,
    background = Color(0xFFF6F7F9), onBackground = Color(0xFF14171C),
    surface = Color.White, onSurface = Color(0xFF14171C),
    surfaceVariant = Color(0xFFE9ECF0), onSurfaceVariant = Color(0xFF4A525E),
    error = Color(0xFFC62828),
)

// System font: renders Hebrew well (Noto Sans Hebrew) and Latin digits consistently.
private val AppTypography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
    )
}

val GaugeValueStyle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold)

@Composable
fun DiagnoseProTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, content = content)
}
