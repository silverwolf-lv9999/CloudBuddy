package cn.cloudbuddy.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Brand = Color(0xFF0B63F6)
private val BrandDark = Color(0xFF9EC0FF)
private val Ink = Color(0xFF0E1524)
private val Cloud = Color(0xFFF6F8FC)

private val LightScheme = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF001A43),
    secondary = Color(0xFF3F5C8C),
    secondaryContainer = Color(0xFFE4ECFB),
    onSecondaryContainer = Color(0xFF10203A),
    tertiary = Color(0xFF0E8172),
    tertiaryContainer = Color(0xFFC8F3EA),
    background = Cloud,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDF1F8),
    onSurfaceVariant = Color(0xFF57637A),
    outline = Color(0xFFC8D0DE),
    outlineVariant = Color(0xFFE2E8F1),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC)
)

private val DarkScheme = darkColorScheme(
    primary = BrandDark,
    onPrimary = Color(0xFF00286B),
    primaryContainer = Color(0xFF0B3E92),
    onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = Color(0xFFB4C7E8),
    secondaryContainer = Color(0xFF2A3A55),
    onSecondaryContainer = Color(0xFFDCE7FF),
    tertiary = Color(0xFF7FD8C8),
    tertiaryContainer = Color(0xFF005147),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF141A23),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF1E2632),
    onSurfaceVariant = Color(0xFFA9B5C7),
    outline = Color(0xFF3A4557),
    outlineVariant = Color(0xFF2A333F),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF8C1D18)
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp)
)

@Composable
fun CloudBuddyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
