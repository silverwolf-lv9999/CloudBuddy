package cn.cloudbuddy.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ─────────────────────────────────────────────────────────────────────────────
// CloudBuddy 主题：Material 3 Expressive（material3 1.4.x）。
// 蓝紫主色 + 5 级 surfaceContainer + M3 Expressive 动态效果。
// ─────────────────────────────────────────────────────────────────────────────

// ── Light scheme ────────────────────────────────────────────────────────────
private val LightScheme = lightColorScheme(
    primary = Color(0xFF5B53E0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3DFFF),
    onPrimaryContainer = Color(0xFF1A1661),
    secondary = Color(0xFF5D5C72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E0F9),
    onSecondaryContainer = Color(0xFF1A1A2C),
    tertiary = Color(0xFF7A5268),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8EA),
    onTertiaryContainer = Color(0xFF2E1124),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1A22),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1A22),
    surfaceVariant = Color(0xFFE4E1EC),
    onSurfaceVariant = Color(0xFF474553),
    outline = Color(0xFF787583),
    outlineVariant = Color(0xFFC8C5D3),
    inverseSurface = Color(0xFF302F37),
    inverseOnSurface = Color(0xFFF2EFF7),
    inversePrimary = Color(0xFFC5C0FF),
    surfaceTint = Color(0xFF5B53E0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBF8FF),
    surfaceDim = Color(0xFFDBD8E1),
    surfaceContainer = Color(0xFFEFEDF5),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE3E1E9),
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
)

// ── Dark scheme ─────────────────────────────────────────────────────────────
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFC5C0FF),
    onPrimary = Color(0xFF2B238F),
    primaryContainer = Color(0xFF433CB8),
    onPrimaryContainer = Color(0xFFE3DFFF),
    secondary = Color(0xFFC6C4DD),
    onSecondary = Color(0xFF2F2E42),
    secondaryContainer = Color(0xFF454559),
    onSecondaryContainer = Color(0xFFE2E0F9),
    tertiary = Color(0xFFE9B9D2),
    onTertiary = Color(0xFF47253A),
    tertiaryContainer = Color(0xFF603B50),
    onTertiaryContainer = Color(0xFFFFD8EA),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF131218),
    onBackground = Color(0xFFE5E1EA),
    surface = Color(0xFF131218),
    onSurface = Color(0xFFE5E1EA),
    surfaceVariant = Color(0xFF474553),
    onSurfaceVariant = Color(0xFFC8C5D3),
    outline = Color(0xFF918F9C),
    outlineVariant = Color(0xFF474553),
    inverseSurface = Color(0xFFE5E1EA),
    inverseOnSurface = Color(0xFF302F37),
    inversePrimary = Color(0xFF5B53E0),
    surfaceTint = Color(0xFFC5C0FF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF39383F),
    surfaceDim = Color(0xFF131218),
    surfaceContainer = Color(0xFF1F1E26),
    surfaceContainerHigh = Color(0xFF2A2931),
    surfaceContainerHighest = Color(0xFF35343C),
    surfaceContainerLow = Color(0xFF1B1A22),
    surfaceContainerLowest = Color(0xFF0E0D13),
)

// ── 语义扩展色（成功 / 警告）─────────────────────────────────────────────────
class ExtendColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

private val LightExtend = ExtendColors(
    success = Color(0xFF146C2E),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFA2F0A7),
    onSuccessContainer = Color(0xFF002106),
    warning = Color(0xFF825500),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFDDB3),
    onWarningContainer = Color(0xFF2A1800),
)

private val DarkExtend = ExtendColors(
    success = Color(0xFF87D38D),
    onSuccess = Color(0xFF00390F),
    successContainer = Color(0xFF00531C),
    onSuccessContainer = Color(0xFFA2F0A7),
    warning = Color(0xFFFFB951),
    onWarning = Color(0xFF452B00),
    warningContainer = Color(0xFF633F00),
    onWarningContainer = Color(0xFFFFDDB3),
)

val LocalExtendColors = compositionLocalOf { LightExtend }

val androidx.compose.material3.MaterialTheme.extendColors: ExtendColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendColors.current

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CloudBuddyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    val extend = if (darkTheme) DarkExtend else LightExtend

    // 状态栏图标颜色跟随主题：深色主题用浅色图标，浅色主题用深色图标。
    val view = LocalView.current
    val activity = view.context as? android.app.Activity
    if (!view.isInEditMode && activity != null) {
        DisposableEffect(activity, darkTheme) {
            val window = activity.window
            val controller = WindowCompat.getInsetsController(window, view)
            val previousLightStatusBars = controller.isAppearanceLightStatusBars
            val previousLightNavigationBars = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
            onDispose {
                controller.isAppearanceLightStatusBars = previousLightStatusBars
                controller.isAppearanceLightNavigationBars = previousLightNavigationBars
            }
        }
    }

    CompositionLocalProvider(LocalExtendColors provides extend) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}
