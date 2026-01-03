package com.iwo.mailclient.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.core.*
import com.iwo.mailclient.ui.LocalFontScale
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
 * AlertDialog с поддержкой масштабирования шрифта и кнопками по разным сторонам
 * - Кнопки разнесены: dismiss слева, confirm справа
 * - Поддержка масштабирования шрифта
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
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(28.dp),
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
    
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties
    ) {
        CompositionLocalProvider(LocalDensity provides scaledDensity) {
            Surface(
                modifier = modifier,
                shape = shape,
                color = containerColor,
                tonalElevation = tonalElevation
            ) {
                Column(
                    modifier = androidx.compose.ui.Modifier.padding(24.dp)
                ) {
                    // Иконка
                    icon?.let {
                        Box(
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CompositionLocalProvider(LocalContentColor provides iconContentColor) {
                                it()
                            }
                        }
                    }
                    
                    // Заголовок
                    title?.let {
                        Box(
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            contentAlignment = if (icon != null) Alignment.Center else Alignment.CenterStart
                        ) {
                            CompositionLocalProvider(LocalContentColor provides titleContentColor) {
                                ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                                    it()
                                }
                            }
                        }
                    }
                    
                    // Текст
                    text?.let {
                        Box(
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxWidth()
                                .padding(bottom = 24.dp)
                        ) {
                            CompositionLocalProvider(LocalContentColor provides textContentColor) {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                                    it()
                                }
                            }
                        }
                    }
                    
                    // Кнопки по разным сторонам
                    Row(
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (dismissButton != null) {
                            dismissButton()
                        } else {
                            Spacer(modifier = androidx.compose.ui.Modifier.width(1.dp))
                        }
                        confirmButton()
                    }
                }
            }
        }
    }
}

/**
 * Стилизованный диалог с градиентом под дизайн приложения
 * - Градиентная полоска сверху
 * - Иконка в градиентном круге (опционально)
 * - Кнопки по разным углам
 * - Главная кнопка с градиентом
 * - Анимация появления
 */
@Composable
fun StyledAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties()
) {
    val fontScale = LocalFontScale.current
    val baseDensity = LocalDensity.current
    val scaledDensity = Density(
        density = baseDensity.density,
        fontScale = baseDensity.fontScale * fontScale
    )
    val colorTheme = LocalColorTheme.current
    val animationsEnabled = LocalAnimationsEnabled.current
    
    // Анимация появления
    var visible by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { visible = true }
    
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible && animationsEnabled) 1f else 0.8f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.6f,
            stiffness = 400f
        ),
        label = "scale"
    )
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(200),
        label = "alpha"
    )
    
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties
    ) {
        CompositionLocalProvider(LocalDensity provides scaledDensity) {
            androidx.compose.material3.Card(
                modifier = modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column {
                    // Градиентная полоска сверху
                    Box(
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(
                                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    colors = listOf(colorTheme.gradientStart, colorTheme.gradientEnd)
                                )
                            )
                    )
                    
                    Column(
                        modifier = androidx.compose.ui.Modifier.padding(24.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                    ) {
                        // Иконка в градиентном круге
                        icon?.let {
                            Box(
                                modifier = androidx.compose.ui.Modifier
                                    .size(56.dp)
                                    .background(
                                        brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                            colors = listOf(colorTheme.gradientStart, colorTheme.gradientEnd)
                                        ),
                                        shape = androidx.compose.foundation.shape.CircleShape
                                    ),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                CompositionLocalProvider(
                                    androidx.compose.material3.LocalContentColor provides Color.White
                                ) {
                                    it()
                                }
                            }
                            Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
                        }
                        
                        // Заголовок
                        title?.let {
                            CompositionLocalProvider(
                                androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface
                            ) {
                                ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                                    it()
                                }
                            }
                            Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
                        }
                        
                        // Текст
                        text?.let {
                            CompositionLocalProvider(
                                androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
                            ) {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                                    it()
                                }
                            }
                        }
                        
                        Spacer(modifier = androidx.compose.ui.Modifier.height(24.dp))
                        
                        // Кнопки по разным углам
                        Row(
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                        ) {
                            // Dismiss кнопка слева
                            if (dismissButton != null) {
                                dismissButton()
                            } else {
                                Spacer(modifier = androidx.compose.ui.Modifier.width(1.dp))
                            }
                            
                            // Confirm кнопка справа
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Градиентная кнопка для диалогов
 */
@Composable
fun GradientDialogButton(
    onClick: () -> Unit,
    text: String,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    enabled: Boolean = true
) {
    val colorTheme = LocalColorTheme.current
    
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    ) {
        Box(
            modifier = androidx.compose.ui.Modifier
                .background(
                    brush = if (enabled) {
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            colors = listOf(colorTheme.gradientStart, colorTheme.gradientEnd)
                        )
                    } else {
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            colors = listOf(Color.Gray, Color.Gray)
                        )
                    },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 20.dp, vertical = 10.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Text(
                text = text,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
