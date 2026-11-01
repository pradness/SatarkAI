package com.satarkai.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// SatarkAI Brand Color Palette (Teal & Navy Cyber Defense)
val SatarkTeal = Color(0xFF0D9488)
val SatarkTealDark = Color(0xFF0F766E)
val SatarkTealLight = Color(0xFF14B8A6)
val SatarkTealSoft = Color(0xFFF0FDFA)

val SatarkNavy = Color(0xFF0F172A)
val SatarkNavyLight = Color(0xFF1E293B)
val SatarkNavyDark = Color(0xFF0B0F19)

val SatarkBgLight = Color(0xFFF8FAFC)
val SatarkCardLight = Color(0xFFFFFFFF)
val SatarkCardAltLight = Color(0xFFF1F5F9)
val SatarkBorderLight = Color(0xFFE2E8F0)
val SatarkTextPrimaryLight = Color(0xFF0F172A)
val SatarkTextSecondaryLight = Color(0xFF475569)
val SatarkTextMutedLight = Color(0xFF64748B)

val SatarkBgDark = Color(0xFF0B0F19)
val SatarkCardDark = Color(0xFF162032)
val SatarkCardAltDark = Color(0xFF1E293B)
val SatarkBorderDark = Color(0xFF2A384C)
val SatarkTextPrimaryDark = Color(0xFFF8FAFC)
val SatarkTextSecondaryDark = Color(0xFFCBD5E1)
val SatarkTextMutedDark = Color(0xFF94A3B8)

val SatarkScamRed = Color(0xFFDC2626)
val SatarkScamRedBg = Color(0xFFFEF2F2)
val SatarkScamBorder = Color(0xFFFCA5A5)
val SatarkSafeGreen = Color(0xFF10B981)
val SatarkSafeGreenBg = Color(0xFFECFDF5)
val SatarkWarningAmber = Color(0xFFD97706)

// Brand Fallback Light Palette (Android 11 and lower)
val SatarkLightColorScheme = lightColorScheme(
    primary = SatarkTeal,
    onPrimary = Color.White,
    primaryContainer = SatarkTealSoft,
    onPrimaryContainer = SatarkTealDark,
    secondary = SatarkNavyLight,
    onSecondary = Color.White,
    secondaryContainer = SatarkCardAltLight,
    onSecondaryContainer = SatarkNavy,
    tertiary = SatarkSafeGreen,
    onTertiary = Color.White,
    background = SatarkBgLight,
    onBackground = SatarkTextPrimaryLight,
    surface = SatarkCardLight,
    onSurface = SatarkTextPrimaryLight,
    surfaceVariant = SatarkCardAltLight,
    onSurfaceVariant = SatarkTextSecondaryLight,
    outline = SatarkBorderLight,
    error = SatarkScamRed,
    onError = Color.White,
    errorContainer = SatarkScamRedBg,
    onErrorContainer = SatarkScamRed
)

// Brand Fallback Dark Palette (Android 11 and lower)
val SatarkDarkColorScheme = darkColorScheme(
    primary = SatarkTealLight,
    onPrimary = SatarkNavyDark,
    primaryContainer = Color(0xFF132E35),
    onPrimaryContainer = SatarkTealLight,
    secondary = Color(0xFF94A3B8),
    onSecondary = SatarkNavyDark,
    secondaryContainer = SatarkCardAltDark,
    onSecondaryContainer = SatarkTextPrimaryDark,
    tertiary = Color(0xFF34D399),
    onTertiary = SatarkNavyDark,
    background = SatarkBgDark,
    onBackground = SatarkTextPrimaryDark,
    surface = SatarkCardDark,
    onSurface = SatarkTextPrimaryDark,
    surfaceVariant = SatarkCardAltDark,
    onSurfaceVariant = SatarkTextSecondaryDark,
    outline = SatarkBorderDark,
    error = Color(0xFFEF4444),
    onError = Color.White,
    errorContainer = Color(0xFF3E1212),
    onErrorContainer = Color(0xFFFCA5A5)
)

/**
 * SatarkAI Material 3 Theme with Android 12+ Monet Dynamic Theming
 * and fixed brand fallback on Android 11 or lower.
 */
@Composable
fun SatarkAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> SatarkDarkColorScheme
        else -> SatarkLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
