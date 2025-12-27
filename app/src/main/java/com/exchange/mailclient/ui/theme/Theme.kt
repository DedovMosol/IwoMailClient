package com.exchange.mailclient.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.DialogProperties
import com.exchange.mailclient.ui.LocalFontScale
import java.util.Calendar

/**
 * Цветовые темы приложения
 */
enum class AppColorTheme(
    val code: String,
    val primaryLight: Color,
    val primaryDark: Color,
    val gradientStart: Color,
    val gradientEnd: Color
) {
    PURPLE(
        code = "purple",
        primaryLight = Color(0xFF7C4DFF),
        primaryDark = Color(0xFFB388FF),
        gradientStart = Color(0xFF7C4DFF),
        gradientEnd = Color(0xFF448AFF)
    ),
    BLUE(
        code = "blue",
        primaryLight = Color(0xFF1976D2),
        primaryDark = Color(0xFF90CAF9),
        gradientStart = Color(0xFF1976D2),
        gradientEnd = Color(0xFF42A5F5)
    ),
    RED(
        code = "red",
        primaryLight = Color(0xFFD32F2F),
        primaryDark = Color(0xFFEF9A9A),
        gradientStart = Color(0xFFD32F2F),
        gradientEnd = Color(0xFFFF5252)
    ),
    YELLOW(
        code = "yellow",
        primaryLight = Color(0xFFF9A825),
        primaryDark = Color(0xFFFFF59D),
        gradientStart = Color(0xFFF9A825),
        gradientEnd = Color(0xFFFFD54F)
    ),
    ORANGE(
        code = "orange",
        primaryLight = Color(0xFFFF6D00),
        primaryDark = Color(0xFFFFAB91),
        gradientStart = Color(0xFFFF6D00),
        gradientEnd = Color(0xFFFFAB40)
    ),
    GREEN(
        code = "green",
        primaryLight = Color(0xFF388E3C),
        primaryDark = Color(0xFFA5D6A7),
        gradientStart = Color(0xFF388E3C),
        gradientEnd = Color(0xFF66BB6A)
    ),
    PINK(
        code = "pink",
        primaryLight = Color(0xFFE91E63),
        primaryDark = Color(0xFFF48FB1),
        gradientStart = Color(0xFFE91E63),
        gradientEnd = Color(0xFFFF4081)
    );
    
    companion object {
        fun fromCode(code: String): AppColorTheme {
            return entries.find { it.code == code } ?: PURPLE
        }
    }
}

/**
 * CompositionLocal для текущей цветовой темы
 */
val LocalColorTheme = compositionLocalOf { AppColorTheme.PURPLE }

/**
 * CompositionLocal для включения/выключения анимаций
 */
val LocalAnimationsEnabled = compositionLocalOf { true }

@Composable
fun ExchangeMailTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    fontScale: Float = 1.0f,
    colorTheme: AppColorTheme = AppColorTheme.PURPLE,
    animationsEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme(
            primary = colorTheme.primaryDark,
            secondary = Color(0xFF80DEEA),
            tertiary = Color(0xFFA5D6A7),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            onPrimary = Color.Black,
            onSecondary = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White
        )
        else -> lightColorScheme(
            primary = colorTheme.primaryLight,
            secondary = Color(0xFF0097A7),
            tertiary = Color(0xFF388E3C),
            background = Color(0xFFFAFAFA),
            surface = Color.White,
            onPrimary = Color.White,
            onSecondary = Color.White,
            onBackground = Color.Black,
            onSurface = Color.Black
        )
    }
    
    val currentDensity = LocalDensity.current
    val scaledDensity = Density(
        density = currentDensity.density,
        fontScale = currentDensity.fontScale * fontScale
    )

    CompositionLocalProvider(
        LocalDensity provides scaledDensity,
        LocalFontScale provides fontScale,
        LocalColorTheme provides colorTheme,
        LocalAnimationsEnabled provides animationsEnabled
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            content = content
        )
    }
}



/**
 * AlertDialog с поддержкой масштабирования шрифта
 * Решает проблему того, что стандартный AlertDialog не наследует LocalDensity
 */
@Composable
fun ScaledAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: androidx.compose.ui.graphics.Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: androidx.compose.ui.unit.Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties()
) {
    val fontScale = LocalFontScale.current
    val baseDensity = LocalDensity.current
    val scaledDensity = Density(
        density = baseDensity.density,
        fontScale = baseDensity.fontScale * fontScale
    )
    
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            CompositionLocalProvider(LocalDensity provides scaledDensity) {
                confirmButton()
            }
        },
        modifier = modifier,
        dismissButton = dismissButton?.let { btn ->
            {
                CompositionLocalProvider(LocalDensity provides scaledDensity) {
                    btn()
                }
            }
        },
        icon = icon?.let { ic ->
            {
                CompositionLocalProvider(LocalDensity provides scaledDensity) {
                    ic()
                }
            }
        },
        title = title?.let { t ->
            {
                CompositionLocalProvider(LocalDensity provides scaledDensity) {
                    t()
                }
            }
        },
        text = text?.let { txt ->
            {
                CompositionLocalProvider(LocalDensity provides scaledDensity) {
                    txt()
                }
            }
        },
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties
    )
}
