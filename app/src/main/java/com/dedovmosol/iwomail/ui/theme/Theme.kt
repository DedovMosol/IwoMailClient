package com.dedovmosol.iwomail.ui.theme

import android.os.Build
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.dedovmosol.iwomail.ui.LocalFontScale
import com.dedovmosol.iwomail.ui.utils.rememberPulseScale
import com.dedovmosol.iwomail.ui.utils.rememberWobble

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

/**
 * Анимированная FAB кнопка с пульсацией
 * Анимация отключается через настройки
 */
@Composable
fun AnimatedFab(
    onClick: () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    containerColor: Color = LocalColorTheme.current.gradientStart,
    contentColor: Color = Color.White,
    content: @Composable () -> Unit
) {
    val animationsEnabled = LocalAnimationsEnabled.current
    
    val scale = rememberPulseScale(animationsEnabled, from = 1f, to = 1.08f, durationMs = 800)
    val rotation = rememberWobble(animationsEnabled, amplitude = 8f, durationMs = 600)
    
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        containerColor = containerColor,
        contentColor = contentColor
    ) {
        Box(modifier = androidx.compose.ui.Modifier.graphicsLayer { rotationZ = rotation }) {
            content()
        }
    }
}

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
    scrollable: Boolean = true,
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
    // Ограничиваем высоту диалога: не больше 92% экрана и не больше 600dp
    val configuration = LocalConfiguration.current
    val maxDialogHeight = (configuration.screenHeightDp * 0.92f).dp.coerceAtMost(600.dp)
    
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties
    ) {
        // Box для центрирования + обработки tap-outside-to-dismiss.
        // КРИТИЧНО: Dialog с usePlatformDefaultWidth=false заполняет весь экран,
        // поэтому системный dismissOnClickOutside не работает (нет области "за диалогом").
        // Решение: clickable на Box для dismiss, clickable на Surface для потребления кликов.
        Box(
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismissRequest() },
            contentAlignment = Alignment.Center
        ) {
        CompositionLocalProvider(LocalDensity provides scaledDensity) {
            Surface(
                modifier = modifier
                    .wrapContentHeight()
                    .heightIn(max = maxDialogHeight)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { /* Потребляем клик, чтобы не закрывать диалог при тапе на его содержимое */ },
                shape = shape,
                color = containerColor,
                tonalElevation = tonalElevation
            ) {
                val contentModifier = if (scrollable) {
                    androidx.compose.ui.Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState())
                } else {
                    androidx.compose.ui.Modifier.padding(24.dp)
                }
                Column(
                    modifier = contentModifier
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
                    
                    // Текст: weight(1f, false) только в нескроллируемом режиме,
                    // в скроллируемом Column weight не работает (бесконечная высота)
                    text?.let {
                        val textBoxModifier = if (scrollable) {
                            androidx.compose.ui.Modifier
                                .fillMaxWidth()
                                .padding(bottom = 24.dp)
                        } else {
                            androidx.compose.ui.Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .padding(bottom = 24.dp)
                        }
                        Box(modifier = textBoxModifier) {
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
        } // Box centering
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
 * Компактный DatePicker диалог для задач
 * - Компактные отступы и высота
 * - Скейл контента в альбомной ориентации
 */
@Composable
fun CompactDatePickerDialog(
    onDismissRequest: () -> Unit,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val configuration = LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    val maxDialogHeight = (screenHeightDp.dp - if (isLandscape) 8.dp else 20.dp).coerceAtLeast(260.dp)
    val contentScaleBase = (screenHeightDp / 700f).coerceIn(0.55f, 1f)
    val contentScale = when {
        isLandscape -> minOf(0.6f, contentScaleBase)
        screenHeightDp < 600 -> minOf(0.9f, contentScaleBase)
        else -> 1f
    }
    val contentPadding = if (isLandscape) 6.dp else 12.dp
    val contentSpacing = if (isLandscape) 4.dp else 6.dp
    val buttonTopSpacing = if (isLandscape) 2.dp else 6.dp
    val buttonRowHeight = 44.dp
    val contentMaxHeight = (maxDialogHeight
        - (contentPadding * 2)
        - buttonRowHeight
        - buttonTopSpacing
        - contentSpacing).coerceAtLeast(200.dp)

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .padding(vertical = if (isLandscape) 4.dp else 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = androidx.compose.ui.Modifier
                    .widthIn(max = if (isLandscape) 320.dp else 360.dp)
                    .fillMaxWidth(if (isLandscape) 0.86f else 0.92f)
                    .heightIn(max = maxDialogHeight),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = AlertDialogDefaults.TonalElevation
            ) {
                Column(
                    modifier = androidx.compose.ui.Modifier
                        .padding(contentPadding)
                        .wrapContentHeight()
                ) {
Box(
    modifier = androidx.compose.ui.Modifier
        .fillMaxWidth(contentScale)
        .heightIn(max = contentMaxHeight)
        .then(if (isLandscape) androidx.compose.ui.Modifier.verticalScroll(rememberScrollState()) else androidx.compose.ui.Modifier),
    contentAlignment = Alignment.Center
) {
                        CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.textContentColor) {
                            Box(
                                modifier = androidx.compose.ui.Modifier.layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints)
                                    val scaledWidth = (placeable.width * contentScale).toInt()
                                    val scaledHeight = (placeable.height * contentScale).toInt()
                                    layout(scaledWidth, scaledHeight) {
                                        placeable.placeWithLayer(0, 0) {
                                            scaleX = contentScale
                                            scaleY = contentScale
                                        }
                                    }
                                },
                                contentAlignment = Alignment.Center
                            ) {
                                content()
                            }
                        }
                    }

                    Spacer(modifier = androidx.compose.ui.Modifier.height(contentSpacing))

                    Row(
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxWidth()
                            .height(buttonRowHeight),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) { Text(dismissText) }
                        Spacer(modifier = androidx.compose.ui.Modifier.width(8.dp))
                        TextButton(onClick = onConfirm) { Text(confirmText) }
                    }

                    Spacer(modifier = androidx.compose.ui.Modifier.height(buttonTopSpacing))
                }
            }
        }
    }
}

/**
 * Простой диалог ввода времени
 */
@Composable
fun SimpleTimeInputDialog(
    onDismissRequest: () -> Unit,
    title: String,
    initialHour: Int,
    initialMinute: Int,
    confirmText: String,
    dismissText: String,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val is24Hour = remember(context) { DateFormat.is24HourFormat(context) }
    val timeState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = is24Hour
    )

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            Box(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                TimePicker(state = timeState)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(timeState.hour, timeState.minute) }) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissText) }
        }
    )
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
