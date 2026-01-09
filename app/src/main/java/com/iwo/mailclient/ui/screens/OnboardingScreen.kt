package com.iwo.mailclient.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iwo.mailclient.data.repository.SettingsRepository
import com.iwo.mailclient.ui.Strings
import com.iwo.mailclient.ui.theme.AppColorTheme
import com.iwo.mailclient.ui.theme.AppIcons
import com.iwo.mailclient.ui.theme.LocalColorTheme
import kotlinx.coroutines.launch

/**
 * Данные для слайда onboarding
 */
private data class OnboardingPage(
    val icon: ImageVector,
    val titleRu: String,
    val titleEn: String,
    val descriptionRu: String,
    val descriptionEn: String,
    val color: Color
)

/**
 * Экран приветствия с описанием возможностей
 * @param isFirstLaunch true если первый запуск (показываем выбор языка и анимаций)
 * @param onComplete вызывается после завершения onboarding
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    isFirstLaunch: Boolean = true,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { SettingsRepository.getInstance(context) }
    val colorTheme = LocalColorTheme.current
    
    // Состояние языка и анимаций (для первого запуска используем локальное состояние)
    var selectedLanguage by remember { mutableStateOf(settingsRepo.getLanguageSync()) }
    var selectedAnimations by remember { mutableStateOf(settingsRepo.getAnimationsEnabledSync()) }
    var selectedTheme by remember { mutableStateOf(settingsRepo.getColorThemeSync()) }
    
    // Определяем русский ли язык
    val isRussian = selectedLanguage == "ru"
    
    // Слайды с возможностями — объединённые
    val pages = listOf(
        // Слайд 1: Почта и уведомления
        OnboardingPage(
            icon = AppIcons.Email,
            titleRu = "Почта и уведомления",
            titleEn = "Mail & Notifications",
            descriptionRu = "",
            descriptionEn = "",
            color = Color(0xFF5C6BC0)
        ),
        // Слайд 2: Органайзер
        OnboardingPage(
            icon = AppIcons.CalendarMonth,
            titleRu = "Органайзер",
            titleEn = "Organizer",
            descriptionRu = "",
            descriptionEn = "",
            color = Color(0xFF42A5F5)
        ),
        // Слайд 3: Настройки и обновления
        OnboardingPage(
            icon = AppIcons.Settings,
            titleRu = "Настройки",
            titleEn = "Settings",
            descriptionRu = "",
            descriptionEn = "",
            color = Color(0xFF78909C)
        )
    )
    
    // Добавляем слайды выбора языка, анимаций и темы в начало для первого запуска
    val totalPages = if (isFirstLaunch) pages.size + 3 else pages.size
    val pagerState = rememberPagerState(pageCount = { totalPages })
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        colorTheme.gradientStart.copy(alpha = 0.1f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Pager
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                if (isFirstLaunch) {
                    when (page) {
                        0 -> LanguageSelectionPage(
                            selectedLanguage = selectedLanguage,
                            onLanguageSelected = { lang ->
                                selectedLanguage = lang
                                scope.launch {
                                    settingsRepo.setLanguage(lang)
                                }
                            },
                            colorTheme = colorTheme,
                            animationsEnabled = selectedAnimations
                        )
                        1 -> AnimationsSelectionPage(
                            animationsEnabled = selectedAnimations,
                            onAnimationsChanged = { enabled ->
                                selectedAnimations = enabled
                                scope.launch {
                                    settingsRepo.setAnimationsEnabled(enabled)
                                }
                            },
                            colorTheme = colorTheme,
                            isRussian = isRussian
                        )
                        2 -> ThemeSelectionPage(
                            selectedTheme = selectedTheme,
                            onThemeSelected = { theme ->
                                selectedTheme = theme
                                scope.launch {
                                    settingsRepo.setColorTheme(theme)
                                }
                            },
                            isRussian = isRussian,
                            animationsEnabled = selectedAnimations
                        )
                        else -> {
                            val pageData = pages[page - 3]
                            FeaturePage(
                                page = pageData,
                                isRussian = isRussian,
                                animationsEnabled = selectedAnimations
                            )
                        }
                    }
                } else {
                    FeaturePage(
                        page = pages[page],
                        isRussian = isRussian,
                        animationsEnabled = selectedAnimations
                    )
                }
            }
            
            // Индикаторы и кнопки
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Индикаторы страниц
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    repeat(totalPages) { index ->
                        val isSelected = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .size(if (isSelected) 10.dp else 8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) colorTheme.gradientStart
                                    else colorTheme.gradientStart.copy(alpha = 0.3f)
                                )
                        )
                    }
                }
                
                // Кнопки
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Кнопка "Пропустить" (только не на последней странице)
                    if (pagerState.currentPage < totalPages - 1) {
                        TextButton(onClick = {
                            scope.launch {
                                if (isFirstLaunch) {
                                    settingsRepo.setOnboardingShown(true)
                                }
                                onComplete()
                            }
                        }) {
                            Text(if (isRussian) "Пропустить" else "Skip")
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }
                    
                    // Кнопка "Далее" / "Начать"
                    val isLastPage = pagerState.currentPage == totalPages - 1
                    Button(
                        onClick = {
                            scope.launch {
                                if (isLastPage) {
                                    if (isFirstLaunch) {
                                        settingsRepo.setOnboardingShown(true)
                                    }
                                    onComplete()
                                } else {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colorTheme.gradientStart
                        )
                    ) {
                        Text(
                            if (isLastPage) {
                                if (isRussian) "Начать" else "Start"
                            } else {
                                if (isRussian) "Далее" else "Next"
                            }
                        )
                    }
                }
            }
        }
    }
}


/**
 * Страница выбора языка
 */
@Composable
private fun LanguageSelectionPage(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    colorTheme: AppColorTheme,
    animationsEnabled: Boolean
) {
    // Для первого слайда анимация всегда включена по умолчанию
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
    }
    
    // Анимация иконки - всегда для первого слайда
    val scale by animateFloatAsState(
        targetValue = if (visible) 1.1f else 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "iconScale"
    )
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Иконка с анимацией появления
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500)) + scaleIn(
                initialScale = 0.5f,
                animationSpec = tween(500)
            )
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(colorTheme.gradientStart, colorTheme.gradientEnd)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AppIcons.Language,
                    contentDescription = null,
                    modifier = Modifier.size(60.dp),
                    tint = Color.White
                )
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Заголовок с анимацией
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500, delayMillis = 200)) + slideInVertically(
                initialOffsetY = { 50 },
                animationSpec = tween(500, delayMillis = 200)
            )
        ) {
            Text(
                text = "Выберите язык / Choose language",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Кнопки выбора языка с анимацией
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500, delayMillis = 400)) + slideInVertically(
                initialOffsetY = { 50 },
                animationSpec = tween(500, delayMillis = 400)
            )
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                LanguageButton(
                    text = "🇷🇺 Русский",
                    isSelected = selectedLanguage == "ru",
                    onClick = { onLanguageSelected("ru") },
                    colorTheme = colorTheme
                )
                LanguageButton(
                    text = "🇬🇧 English",
                    isSelected = selectedLanguage == "en",
                    onClick = { onLanguageSelected("en") },
                    colorTheme = colorTheme
                )
            }
        }
    }
}

@Composable
private fun LanguageButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    colorTheme: AppColorTheme
) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (isSelected) colorTheme.gradientStart.copy(alpha = 0.1f) else Color.Transparent
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 2.dp,
            color = if (isSelected) colorTheme.gradientStart else MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier.height(56.dp)
    ) {
        Text(
            text = text,
            fontSize = 18.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/**
 * Страница выбора анимаций
 */
@Composable
private fun AnimationsSelectionPage(
    animationsEnabled: Boolean,
    onAnimationsChanged: (Boolean) -> Unit,
    colorTheme: AppColorTheme,
    isRussian: Boolean
) {
    // Анимация появления - всегда для этого слайда
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
    }
    
    // Бесконечное вращение иконки (когда анимации включены)
    val infiniteTransition = rememberInfiniteTransition(label = "rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "iconRotation"
    )
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Иконка с анимацией появления
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500)) + scaleIn(
                initialScale = 0.5f,
                animationSpec = tween(500)
            )
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(colorTheme.gradientStart, colorTheme.gradientEnd)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AppIcons.Animation,
                    contentDescription = null,
                    modifier = Modifier
                        .size(60.dp)
                        .graphicsLayer { rotationZ = if (animationsEnabled) rotation else 0f },
                    tint = Color.White
                )
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500, delayMillis = 200)) + slideInVertically(
                initialOffsetY = { 50 },
                animationSpec = tween(500, delayMillis = 200)
            )
        ) {
            Text(
                text = if (isRussian) "Анимации интерфейса" else "Interface animations",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500, delayMillis = 300)) + slideInVertically(
                initialOffsetY = { 50 },
                animationSpec = tween(500, delayMillis = 300)
            )
        ) {
            Text(
                text = if (isRussian) 
                    "Включите анимации для плавного интерфейса или отключите для экономии заряда"
                else 
                    "Enable animations for a smooth interface or disable to save battery",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Переключатель с анимацией
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500, delayMillis = 400)) + slideInVertically(
                initialOffsetY = { 50 },
                animationSpec = tween(500, delayMillis = 400)
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (isRussian) "Анимации" else "Animations",
                    style = MaterialTheme.typography.titleMedium
                )
                Switch(
                    checked = animationsEnabled,
                    onCheckedChange = onAnimationsChanged,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = colorTheme.gradientStart
                    )
                )
            }
        }
    }
}

/**
 * Страница выбора цветовой темы
 */
@Composable
private fun ThemeSelectionPage(
    selectedTheme: String,
    onThemeSelected: (String) -> Unit,
    isRussian: Boolean,
    animationsEnabled: Boolean
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
    }
    
    val currentTheme = AppColorTheme.fromCode(selectedTheme)
    
    // Пульсация для выбранной темы - создаём infiniteTransition всегда
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAnimated by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val pulse = if (visible && animationsEnabled) pulseAnimated else 1f
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Иконка с анимацией появления
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500)) + scaleIn(
                initialScale = 0.5f,
                animationSpec = tween(500)
            )
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(currentTheme.gradientStart, currentTheme.gradientEnd)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AppIcons.Palette,
                    contentDescription = null,
                    modifier = Modifier.size(50.dp),
                    tint = Color.White
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500, delayMillis = 200)) + slideInVertically(
                initialOffsetY = { 50 },
                animationSpec = tween(500, delayMillis = 200)
            )
        ) {
            Text(
                text = if (isRussian) "Цветовая тема" else "Color theme",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500, delayMillis = 300)) + slideInVertically(
                initialOffsetY = { 50 },
                animationSpec = tween(500, delayMillis = 300)
            )
        ) {
            Text(
                text = if (isRussian) 
                    "Выберите цвет оформления приложения"
                else 
                    "Choose the app color scheme",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Цветовые кружки с горизонтальным скроллом
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500, delayMillis = 400)) + slideInVertically(
                initialOffsetY = { 50 },
                animationSpec = tween(500, delayMillis = 400)
            )
        ) {
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)
            ) {
                items(AppColorTheme.entries.size) { index ->
                    val theme = AppColorTheme.entries[index]
                    val isSelected = theme.code == selectedTheme
                    Box(
                        modifier = Modifier
                            .size(if (isSelected) 48.dp else 40.dp)
                            .scale(if (isSelected && animationsEnabled) pulse else 1f)
                            .clip(CircleShape)
                            .background(theme.gradientStart)
                            .clickable { onThemeSelected(theme.code) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = AppIcons.Check,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Страница с описанием функции — объединённая версия с несколькими иконками
 */
@Composable
private fun FeaturePage(
    page: OnboardingPage,
    isRussian: Boolean,
    animationsEnabled: Boolean
) {
    // Анимация появления - сбрасываем при каждом показе страницы
    var visible by remember(page.titleRu) { mutableStateOf(false) }
    LaunchedEffect(page.titleRu) {
        visible = false
        kotlinx.coroutines.delay(50)
        visible = true
    }
    
    // Бесконечные анимации для иконок - создаём всегда, чтобы не было скачков
    val infiniteTransition = rememberInfiniteTransition(label = "iconAnim")
    
    val pulse = if (visible && animationsEnabled) {
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        ).value
    } else {
        1f
    }
    
    val shake = if (visible && animationsEnabled) {
        infiniteTransition.animateFloat(
            initialValue = -10f,
            targetValue = 10f,
            animationSpec = infiniteRepeatable(
                animation = tween(300, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "shake"
        ).value
    } else {
        0f
    }
    
    val rotation = if (visible && animationsEnabled) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(3000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "rotation"
        ).value
    } else {
        0f
    }
    
    val scrollState = rememberScrollState()
    
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
        // Главная иконка
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(400)) + scaleIn(
                initialScale = 0.5f,
                animationSpec = tween(400)
            )
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .scale(if (animationsEnabled) pulse else 1f)
                    .clip(CircleShape)
                    .background(page.color),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = page.icon,
                    contentDescription = null,
                    modifier = Modifier.size(50.dp),
                    tint = Color.White
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Заголовок
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500, delayMillis = 150)) + slideInVertically(
                initialOffsetY = { 50 },
                animationSpec = tween(500, delayMillis = 150)
            )
        ) {
            Text(
                text = if (isRussian) page.titleRu else page.titleEn,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Контент в зависимости от типа страницы
        when (page.titleRu) {
            "Почта и уведомления" -> {
                FeatureItem(
                    visible = visible,
                    delay = 200,
                    icon = AppIcons.Email,
                    iconModifier = if (animationsEnabled) Modifier.scale(pulse) else Modifier,
                    color = Color(0xFF5C6BC0),
                    titleRu = "Почта",
                    titleEn = "Mail",
                    descRu = "Exchange ActiveSync, включая Exchange 2007",
                    descEn = "Exchange ActiveSync, including Exchange 2007",
                    isRussian = isRussian
                )
                Spacer(modifier = Modifier.height(16.dp))
                FeatureItem(
                    visible = visible,
                    delay = 350,
                    icon = AppIcons.Notifications,
                    iconModifier = if (animationsEnabled) Modifier.graphicsLayer { rotationZ = shake } else Modifier,
                    color = Color(0xFFEF5350),
                    titleRu = "Уведомления",
                    titleEn = "Notifications",
                    descRu = "Push-уведомления о новых письмах",
                    descEn = "Push notifications for new emails",
                    isRussian = isRussian
                )
                Spacer(modifier = Modifier.height(16.dp))
                FeatureItem(
                    visible = visible,
                    delay = 500,
                    icon = AppIcons.Info,
                    iconModifier = Modifier,
                    color = Color(0xFFFF9800),
                    titleRu = "Exchange 2007",
                    titleEn = "Exchange 2007",
                    descRu = "Для стабильной работы требуется EWS",
                    descEn = "EWS required for stable operation",
                    isRussian = isRussian
                )
            }
            "Органайзер" -> {
                FeatureItem(
                    visible = visible,
                    delay = 200,
                    icon = AppIcons.Contacts,
                    iconModifier = if (animationsEnabled) Modifier.scale(pulse) else Modifier,
                    color = Color(0xFF4FC3F7),
                    titleRu = "Контакты",
                    titleEn = "Contacts",
                    descRu = "Личные и корпоративные (GAL)",
                    descEn = "Personal and corporate (GAL)",
                    isRussian = isRussian
                )
                Spacer(modifier = Modifier.height(12.dp))
                FeatureItem(
                    visible = visible,
                    delay = 300,
                    icon = AppIcons.CalendarMonth,
                    iconModifier = if (animationsEnabled) Modifier.scale(pulse) else Modifier,
                    color = Color(0xFF42A5F5),
                    titleRu = "Календарь",
                    titleEn = "Calendar",
                    descRu = "События, напоминания, приглашения",
                    descEn = "Events, reminders, invitations",
                    isRussian = isRussian
                )
                Spacer(modifier = Modifier.height(12.dp))
                FeatureItem(
                    visible = visible,
                    delay = 400,
                    icon = AppIcons.Task,
                    iconModifier = if (animationsEnabled) Modifier.scale(pulse) else Modifier,
                    color = Color(0xFFAB47BC),
                    titleRu = "Задачи",
                    titleEn = "Tasks",
                    descRu = "Приоритеты, сроки, напоминания",
                    descEn = "Priorities, due dates, reminders",
                    isRussian = isRussian
                )
                Spacer(modifier = Modifier.height(12.dp))
                FeatureItem(
                    visible = visible,
                    delay = 500,
                    icon = AppIcons.StickyNote,
                    iconModifier = if (animationsEnabled) Modifier.scale(pulse) else Modifier,
                    color = Color(0xFF81C784),
                    titleRu = "Заметки",
                    titleEn = "Notes",
                    descRu = "Синхронизация с сервером",
                    descEn = "Server synchronization",
                    isRussian = isRussian
                )
            }
            "Настройки" -> {
                FeatureItem(
                    visible = visible,
                    delay = 200,
                    icon = AppIcons.Settings,
                    iconModifier = if (animationsEnabled) Modifier.graphicsLayer { rotationZ = rotation } else Modifier,
                    color = Color(0xFF78909C),
                    titleRu = "Персонализация",
                    titleEn = "Personalization",
                    descRu = "7 тем, мультиаккаунт, индивидуальные подписи",
                    descEn = "7 themes, multi-account, individual signatures",
                    isRussian = isRussian
                )
                Spacer(modifier = Modifier.height(16.dp))
                FeatureItem(
                    visible = visible,
                    delay = 350,
                    icon = AppIcons.Update,
                    iconModifier = if (animationsEnabled) Modifier.graphicsLayer { rotationZ = rotation } else Modifier,
                    color = Color(0xFF26A69A),
                    titleRu = "Обновления",
                    titleEn = "Updates",
                    descRu = "Встроенные OTA-обновления",
                    descEn = "Built-in OTA updates",
                    isRussian = isRussian
                )
            }
        }
        }
        
        // Кастомный скроллбар
        if (scrollState.maxValue > 0) {
            val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            Canvas(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(4.dp)
                    .padding(vertical = 4.dp)
            ) {
                val scrollFraction = scrollState.value.toFloat() / scrollState.maxValue.toFloat()
                val viewportFraction = size.height / (size.height + scrollState.maxValue)
                val scrollbarHeight = (viewportFraction * size.height).coerceAtLeast(20.dp.toPx())
                val scrollbarY = scrollFraction * (size.height - scrollbarHeight)
                
                drawRoundRect(
                    color = scrollbarColor,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, scrollbarY),
                    size = androidx.compose.ui.geometry.Size(size.width, scrollbarHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
            }
        }
    }
}

/**
 * Элемент функции с иконкой и описанием
 */
@Composable
private fun FeatureItem(
    visible: Boolean,
    delay: Int,
    icon: ImageVector,
    iconModifier: Modifier,
    color: Color,
    titleRu: String,
    titleEn: String,
    descRu: String,
    descEn: String,
    isRussian: Boolean
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(400, delayMillis = delay)) + slideInHorizontally(
            initialOffsetX = { -100 },
            animationSpec = tween(400, delayMillis = delay)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = iconModifier.size(28.dp),
                    tint = color
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = if (isRussian) titleRu else titleEn,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (isRussian) descRu else descEn,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
