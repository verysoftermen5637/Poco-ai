package com.poco.aistudio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AmoledBlack = Color(0xFF000000)
val SurfaceDark = Color(0xFF0C0C10)
val SurfaceElevated = Color(0xFF14141B)
val SurfaceOutline = Color(0xFF242430)
val PocoYellow = Color(0xFFFFD400)
val ElectricCyan = Color(0xFF00E5FF)
val EmeraldGreen = Color(0xFF00E676)
val CoralError = Color(0xFFFF5252)
val TextPrimary = Color(0xFFF4F4F8)
val TextSecondary = Color(0xFF9A9AA8)

private val PocoDarkColorScheme = darkColorScheme(
    primary = PocoYellow,
    onPrimary = Color.Black,
    secondary = ElectricCyan,
    onSecondary = Color.Black,
    tertiary = EmeraldGreen,
    background = AmoledBlack,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = SurfaceOutline,
    error = CoralError,
    onError = Color.Black
)

private val PocoTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        color = TextPrimary
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        color = TextPrimary
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = TextPrimary
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        color = TextSecondary
    )
)

@Composable
fun PocoAiStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PocoDarkColorScheme,
        typography = PocoTypography,
        content = content
    )
}
