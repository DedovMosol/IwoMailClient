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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.dedovmosol.iwomail.ui.LocalFontScale
import com.dedovmosol.iwomail.ui.components.ScrollColumnScrollbar
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
        primaryLight = Color(0xFF6200EE),
        primaryDark = Color(0xFFBB86FC),
        gradientStart = Color(0xFF5C00D4),
        gradientEnd = Color(0xFF3700B3)
    ),
    BLUE(
        code = "blue",
        primaryLight = Color(0xFF1565C0),
        primaryDark = Color(0xFF90CAF9),
        gradientStart = Color(0xFF1565C0),
        gradientEnd = Color(0xFF0D47A1)
    ),
    YELLOW(
        code = "yellow",
        primaryLight = Color(0xFFC77700),
        primaryDark = Color(0xFFFFCC80),
        gradientStart = Color(0xFFC77700),
        gradientEnd = Color(0xFFA85E00)
    ),
    GREEN(
        code = "green",
        primaryLight = Color(0xFF2E7D32),
        primaryDark = Color(0xFFA5D6A7),
        gradientStart = Color(0xFF2E7D32),
        gradientEnd = Color(0xFF1B5E20)
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
    scrollbarColor: com.dedovmosol.iwomail.ui.components.ScrollbarColor = com.dedovmosol.iwomail.ui.components.ScrollbarColor.BLUE,
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
        LocalAnimationsEnabled provides animationsEnabled,
        com.dedovmosol.iwomail.ui.components.LocalScrollbarColor provides scrollbarColor
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
                if (scrollable) {
                    // Скроллируемый режим: контент в Box со скроллбаром, кнопки фиксированы внизу
                    val dialogScrollState = rememberScrollState()
                    Column(modifier = androidx.compose.ui.Modifier.padding(24.dp)) {
                        // Иконка (вне скролла)
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
                        
                        // Заголовок (вне скролла)
                        title?.let {
                            Box(
                                modifier = androidx.compose.ui.Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CompositionLocalProvider(LocalContentColor provides titleContentColor) {
                                    ProvideTextStyle(MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center)) {
                                        Box(
                                            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            it()
                                        }
                                    }
                                }
                            }
                        }
                        
                        // Скроллируемый текст с видимым скроллбаром
                        text?.let {
                            Box(
                                modifier = androidx.compose.ui.Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false)
                                    .padding(bottom = 24.dp)
                            ) {
                                Column(
                                    modifier = androidx.compose.ui.Modifier
                                        .fillMaxWidth()
                                        .padding(end = 12.dp)
                                        .verticalScroll(dialogScrollState)
                                ) {
                                    CompositionLocalProvider(LocalContentColor provides textContentColor) {
                                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                                            Box(
                                                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                it()
                                            }
                                        }
                                    }
                                }
                                ScrollColumnScrollbar(dialogScrollState)
                            }
                        }
                        
                        // Кнопки по разным сторонам (фиксированы внизу)
                        Row(
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                            horizontalArrangement = if (dismissButton != null) Arrangement.SpaceBetween else Arrangement.Center
                        ) {
                            if (dismissButton != null) {
                                dismissButton()
                            }
                            confirmButton()
                        }
                    }
                } else {
                    // Нескроллируемый режим
                    Column(modifier = androidx.compose.ui.Modifier.padding(24.dp)) {
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
                        title?.let {
                            Box(
                                modifier = androidx.compose.ui.Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CompositionLocalProvider(LocalContentColor provides titleContentColor) {
                                    ProvideTextStyle(MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center)) {
                                        Box(
                                            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            it()
                                        }
                                    }
                                }
                            }
                        }
                        text?.let {
                            Box(
                                modifier = androidx.compose.ui.Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false)
                                    .padding(bottom = 24.dp)
                            ) {
                                CompositionLocalProvider(LocalContentColor provides textContentColor) {
                                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                                        Box(
                                            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            it()
                                        }
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                            horizontalArrangement = if (dismissButton != null) Arrangement.SpaceBetween else Arrangement.Center
                        ) {
                            if (dismissButton != null) {
                                dismissButton()
                            }
                            confirmButton()
                        }
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
        targetValue = if (animationsEnabled) { if (visible) 1f else 0.8f } else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.6f,
            stiffness = 400f
        ),
        label = "scale"
    )
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (animationsEnabled) { if (visible) 1f else 0f } else 1f,
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
                                ProvideTextStyle(MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center)) {
                                    Box(
                                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        it()
                                    }
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
                                    Box(
                                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        it()
                                    }
                                }
                            }
                        }
                        
                        Spacer(modifier = androidx.compose.ui.Modifier.height(24.dp))
                        
                        // Кнопки по разным углам
                        Row(
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                            horizontalArrangement = if (dismissButton != null) androidx.compose.foundation.layout.Arrangement.SpaceBetween else androidx.compose.foundation.layout.Arrangement.Center
                        ) {
                            if (dismissButton != null) {
                                dismissButton()
                            }
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
                        ThemeOutlinedButton(onClick = onDismiss, text = dismissText)
                        Spacer(modifier = androidx.compose.ui.Modifier.width(8.dp))
                        ThemeOutlinedButton(onClick = onConfirm, text = confirmText)
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
        title = {
            Text(
                text = title,
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Box(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                TimePicker(state = timeState)
            }
        },
        confirmButton = {
            ThemeOutlinedButton(
                onClick = { onConfirm(timeState.hour, timeState.minute) },
                text = confirmText
            )
        },
        dismissButton = {
            ThemeOutlinedButton(onClick = onDismiss, text = dismissText)
        }
    )
}

/**
 * Единые цвета иконок и действий приложения (DRY — один источник правды)
 */
object AppColors {
    // Папки
    val folder = Color(0xFFE6A800)          // Папки — жёлтый/янтарный
    val inbox = Color(0xFF1565C0)           // Входящие — синий
    val drafts = Color(0xFFE6A800)          // Черновики — янтарный
    val trash = Color(0xFFE53935)           // Удалённые — красный
    val sent = Color(0xFF5E35B1)            // Отправленные — фиолетовый
    val outbox = Color(0xFF00897B)          // Исходящие — бирюзовый
    val spam = Color(0xFFE53935)            // Спам — красный
    
    // Специальные разделы
    val contacts = Color(0xFF1565C0)        // Контакты — синий
    val notes = Color(0xFFFF9800)           // Заметки — оранжевый
    val calendar = Color(0xFF4CAF50)        // Календарь — зелёный
    val tasks = Color(0xFF9C27B0)           // Задачи — фиолетовый
    val favorites = Color(0xFFFFB300)       // Избранные — золотой
    
    // Утилиты
    val settings = Color(0xFF757575)        // Настройки — серый
    val createFolder = Color(0xFF00897B)    // Создать папку — бирюзовый
    
    // Действия
    val delete = Color(0xFFF44336)          // Удаление — красный

    /**
     * Цвет иконки папки по типу (FolderEntity.type)
     */
    fun folderTint(type: Int): Color = when (type) {
        2 -> inbox
        3 -> drafts
        4 -> trash
        5 -> sent
        6 -> outbox
        11 -> spam
        else -> folder
    }
}

/**
 * Кнопка с градиентной заливкой по цвету темы (для Сохранить/Закрыть/Отмена и т.д.)
 * Единый компонент — все тематические кнопки управляются из одного места.
 */
@Composable
fun ThemeOutlinedButton(
    onClick: () -> Unit,
    text: String,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false
) {
    val colorTheme = LocalColorTheme.current
    val isActive = enabled && !isLoading
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier,
        enabled = isActive,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    ) {
        Box(
            modifier = androidx.compose.ui.Modifier
                .background(
                    brush = if (isActive) {
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            colors = listOf(colorTheme.gradientStart, colorTheme.gradientEnd)
                        )
                    } else {
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            colors = listOf(
                                colorTheme.gradientStart.copy(alpha = 0.38f),
                                colorTheme.gradientEnd.copy(alpha = 0.38f)
                            )
                        )
                    },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 20.dp, vertical = 10.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            if (isLoading) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = androidx.compose.ui.Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
            } else {
                Text(
                    text = text,
                    color = if (isActive) Color.White else Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/**
 * Кнопка удаления — белый текст на красном фоне
 */
@Composable
fun DeleteButton(
    onClick: () -> Unit,
    text: String,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    enabled: Boolean = true
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = AppColors.delete,
            contentColor = Color.White,
            disabledContainerColor = AppColors.delete.copy(alpha = 0.38f),
            disabledContentColor = Color.White.copy(alpha = 0.38f)
        )
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

