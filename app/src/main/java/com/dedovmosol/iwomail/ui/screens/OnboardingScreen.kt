package com.dedovmosol.iwomail.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import com.dedovmosol.iwomail.ui.components.ScrollColumnScrollbar
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.OnboardingStrings
import com.dedovmosol.iwomail.ui.theme.AppColorTheme
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
import com.dedovmosol.iwomail.ui.utils.rememberPulseScale
import com.dedovmosol.iwomail.ui.utils.rememberRotation
import com.dedovmosol.iwomail.ui.utils.rememberShake
import kotlinx.coroutines.launch

/**
 * Данные для слайда onboarding
 */
private enum class OnboardingPageType {
    Mail,
    Organizer,
    Settings
}

private data class OnboardingPage(
    val type: OnboardingPageType,
    val icon: ImageVector,
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
    val configuration = LocalConfiguration.current
    val isPhoneLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        configuration.smallestScreenWidthDp < 600
    val isCompactHeight = configuration.screenHeightDp < 500 || isPhoneLandscape
    val pageScale = if (isPhoneLandscape) 0.9f else 1f
    
    // Состояние языка и анимаций (для первого запуска используем локальное состояние)
    var selectedLanguage by remember { mutableStateOf(settingsRepo.getLanguageSync()) }
    var selectedAnimations by remember { mutableStateOf(settingsRepo.getAnimationsEnabledSync()) }
    var selectedTheme by remember { mutableStateOf(settingsRepo.getColorThemeSync()) }
    
    // Определяем русский ли язык
    val isRussian = selectedLanguage == AppLanguage.RUSSIAN.code
    
    // Слайды с возможностями — объединённые
    val pages = listOf(
        // Слайд 1: Почта и уведомления
        OnboardingPage(
            type = OnboardingPageType.Mail,
            icon = AppIcons.Email,
            color = Color(0xFF5C6BC0)
        ),
        // Слайд 2: Органайзер
        OnboardingPage(
            type = OnboardingPageType.Organizer,
            icon = AppIcons.CalendarMonth,
            color = Color(0xFF42A5F5)
        ),
        // Слайд 3: Настройки и обновления
        OnboardingPage(
            type = OnboardingPageType.Settings,
            icon = AppIcons.Settings,
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = pageScale
                            scaleY = pageScale
                        },
                    contentAlignment = Alignment.Center
                ) {
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
            }
            
            // Индикаторы и кнопки
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = if (isCompactHeight) 12.dp else 24.dp)
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Индикаторы страниц
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = if (isCompactHeight) 12.dp else 24.dp)
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
                        Text(OnboardingStrings.skip(isRussian))
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
                                OnboardingStrings.start(isRussian)
                            } else {
                                OnboardingStrings.next(isRussian)
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
    val isRussian = selectedLanguage == AppLanguage.RUSSIAN.code
    
    // Для первого слайда анимация всегда включена по умолчанию
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
    }
    
    // Адаптивные размеры для маленьких экранов
    val configuration = LocalConfiguration.current
    val isCompactHeight = configuration.screenHeightDp < 500 ||
        (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            configuration.smallestScreenWidthDp < 600)
    val isSmallScreen = configuration.screenHeightDp < 600
    val iconSize = if (isCompactHeight) 72.dp else if (isSmallScreen) 80.dp else 120.dp
    val iconInnerSize = if (isCompactHeight) 36.dp else if (isSmallScreen) 40.dp else 60.dp
    val spacerHeight = if (isCompactHeight) 12.dp else if (isSmallScreen) 16.dp else 32.dp
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = 32.dp,
                vertical = if (isCompactHeight) 12.dp else if (isSmallScreen) 16.dp else 32.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (isCompactHeight) Arrangement.Top else Arrangement.Center
    ) {
        // Иконка с анимацией появления
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500)) + scaleIn(
                initialScale = 0.5f,
                animationSpec = tween(500)
            )
        ) {
            Box(modifier = Modifier.size(iconSize)) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
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
                        modifier = Modifier.size(iconInnerSize),
                        tint = Color.White
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(spacerHeight))
        
        // Заголовок с анимацией
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500, delayMillis = 200)) + slideInVertically(
                initialOffsetY = { 50 },
                animationSpec = tween(500, delayMillis = 200)
            )
        ) {
            Text(
                text = OnboardingStrings.chooseLanguageTitle(isRussian),
                style = if (isSmallScreen) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        
        Spacer(modifier = Modifier.height(spacerHeight))
        
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
                    text = AppLanguage.RUSSIAN.displayName,
                    isSelected = selectedLanguage == AppLanguage.RUSSIAN.code,
                    onClick = { onLanguageSelected(AppLanguage.RUSSIAN.code) },
                    colorTheme = colorTheme
                )
                LanguageButton(
                    text = AppLanguage.ENGLISH.displayName,
                    isSelected = selectedLanguage == AppLanguage.ENGLISH.code,
                    onClick = { onLanguageSelected(AppLanguage.ENGLISH.code) },
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
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false
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
    val rotation = rememberRotation(animationsEnabled, durationMs = 2000)
    
    val configuration = LocalConfiguration.current
    val isCompactHeight = configuration.screenHeightDp < 500 ||
        (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            configuration.smallestScreenWidthDp < 600)
    val iconSize = if (isCompactHeight) 72.dp else 120.dp
    val iconInnerSize = if (isCompactHeight) 36.dp else 60.dp
    val spacerHeight = if (isCompactHeight) 12.dp else 32.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = if (isCompactHeight) 12.dp else 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (isCompactHeight) Arrangement.Top else Arrangement.Center
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
                    .size(iconSize)
                    .graphicsLayer {
                        clip = true
                        shape = CircleShape
                    }
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
                        .size(iconInnerSize)
                        .graphicsLayer { rotationZ = if (animationsEnabled) rotation else 0f },
                    tint = Color.White
                )
            }
        }
        
        Spacer(modifier = Modifier.height(spacerHeight))
        
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500, delayMillis = 200)) + slideInVertically(
                initialOffsetY = { 50 },
                animationSpec = tween(500, delayMillis = 200)
            )
        ) {
            Text(
                text = OnboardingStrings.animationsTitle(isRussian),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        
        Spacer(modifier = Modifier.height(if (isCompactHeight) 8.dp else 16.dp))
        
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500, delayMillis = 300)) + slideInVertically(
                initialOffsetY = { 50 },
                animationSpec = tween(500, delayMillis = 300)
            )
        ) {
            Text(
                text = OnboardingStrings.animationsDescription(isRussian),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Spacer(modifier = Modifier.height(spacerHeight))
        
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
                    text = OnboardingStrings.animationsLabel(isRussian),
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
    
    val configuration = LocalConfiguration.current
    val isCompactHeight = configuration.screenHeightDp < 500 ||
        (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            configuration.smallestScreenWidthDp < 600)

    val currentTheme = AppColorTheme.fromCode(selectedTheme)
    
    // Пульсация для выбранной темы
    val pulse = rememberPulseScale(animationsEnabled, from = 1f, to = 1.1f, durationMs = 1000)
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = if (isCompactHeight) 12.dp else 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (isCompactHeight) Arrangement.Top else Arrangement.Center
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
                    .size(if (isCompactHeight) 72.dp else 100.dp)
                    .graphicsLayer {
                        clip = true
                        shape = CircleShape
                    }
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
                    modifier = Modifier.size(if (isCompactHeight) 36.dp else 50.dp),
                    tint = Color.White
                )
            }
        }
        
        Spacer(modifier = Modifier.height(if (isCompactHeight) 12.dp else 24.dp))
        
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500, delayMillis = 200)) + slideInVertically(
                initialOffsetY = { 50 },
                animationSpec = tween(500, delayMillis = 200)
            )
        ) {
            Text(
                text = OnboardingStrings.themeTitle(isRussian),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        
        Spacer(modifier = Modifier.height(if (isCompactHeight) 8.dp else 12.dp))
        
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500, delayMillis = 300)) + slideInVertically(
                initialOffsetY = { 50 },
                animationSpec = tween(500, delayMillis = 300)
            )
        ) {
            Text(
                text = OnboardingStrings.themeDescription(isRussian),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Spacer(modifier = Modifier.height(if (isCompactHeight) 12.dp else 24.dp))
        
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
                    val circleSize = if (isSelected) 48.dp else 40.dp
                    val circleScale = if (isSelected && animationsEnabled) pulse else 1f
                    Box(
                        modifier = Modifier
                            .size(circleSize)
                            .graphicsLayer {
                                scaleX = circleScale
                                scaleY = circleScale
                                clip = false
                            }
                            .clickable { onThemeSelected(theme.code) }
                    ) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(CircleShape)
                                .background(theme.gradientStart),
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
    var visible by remember(page.type) { mutableStateOf(false) }
    LaunchedEffect(page.type) {
        visible = false
        kotlinx.coroutines.delay(100) // Увеличена задержка для надёжности анимации
        visible = true
    }
    
    // Бесконечные анимации для иконок
    val pulse = rememberPulseScale(animationsEnabled, from = 1f, to = 1.1f, durationMs = 1000)
    val shake = rememberShake(animationsEnabled, amplitude = 10f, durationMs = 300)
    val rotation = rememberRotation(animationsEnabled, durationMs = 3000)
    
    val configuration = LocalConfiguration.current
    val isCompactHeight = configuration.screenHeightDp < 500 ||
        (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            configuration.smallestScreenWidthDp < 600)
    val scrollState = rememberScrollState()
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(if (isCompactHeight) 16.dp else 24.dp),
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
            val pageTitle = when (page.type) {
                OnboardingPageType.Mail -> OnboardingStrings.pageMailTitle(isRussian)
                OnboardingPageType.Organizer -> OnboardingStrings.pageOrganizerTitle(isRussian)
                OnboardingPageType.Settings -> OnboardingStrings.pageSettingsTitle(isRussian)
            }
            Text(
                text = pageTitle,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Контент в зависимости от типа страницы
        when (page.type) {
            OnboardingPageType.Mail -> {
                FeatureItem(
                    visible = visible,
                    delay = 200,
                    icon = AppIcons.Email,
                    iconModifier = if (animationsEnabled) Modifier.scale(pulse) else Modifier,
                    color = Color(0xFF5C6BC0),
                    title = OnboardingStrings.mailTitle(isRussian),
                    description = OnboardingStrings.mailDescription(isRussian)
                )
                Spacer(modifier = Modifier.height(16.dp))
                FeatureItem(
                    visible = visible,
                    delay = 350,
                    icon = AppIcons.Notifications,
                    iconModifier = if (animationsEnabled) Modifier.graphicsLayer { rotationZ = shake } else Modifier,
                    color = Color(0xFFEF5350),
                    title = OnboardingStrings.notificationsTitle(isRussian),
                    description = OnboardingStrings.notificationsDescription(isRussian)
                )
                Spacer(modifier = Modifier.height(16.dp))
                FeatureItem(
                    visible = visible,
                    delay = 500,
                    icon = AppIcons.Info,
                    iconModifier = Modifier,
                    color = Color(0xFFFF9800),
                    title = OnboardingStrings.exchangeTitle(isRussian),
                    description = OnboardingStrings.exchangeDescription(isRussian)
                )
            }
            OnboardingPageType.Organizer -> {
                FeatureItem(
                    visible = visible,
                    delay = 200,
                    icon = AppIcons.Contacts,
                    iconModifier = if (animationsEnabled) Modifier.scale(pulse) else Modifier,
                    color = Color(0xFF4FC3F7),
                    title = OnboardingStrings.contactsTitle(isRussian),
                    description = OnboardingStrings.contactsDescription(isRussian)
                )
                Spacer(modifier = Modifier.height(12.dp))
                FeatureItem(
                    visible = visible,
                    delay = 300,
                    icon = AppIcons.CalendarMonth,
                    iconModifier = if (animationsEnabled) Modifier.scale(pulse) else Modifier,
                    color = Color(0xFF42A5F5),
                    title = OnboardingStrings.calendarTitle(isRussian),
                    description = OnboardingStrings.calendarDescription(isRussian)
                )
                Spacer(modifier = Modifier.height(12.dp))
                FeatureItem(
                    visible = visible,
                    delay = 400,
                    icon = AppIcons.Task,
                    iconModifier = if (animationsEnabled) Modifier.scale(pulse) else Modifier,
                    color = Color(0xFFAB47BC),
                    title = OnboardingStrings.tasksTitle(isRussian),
                    description = OnboardingStrings.tasksDescription(isRussian)
                )
                Spacer(modifier = Modifier.height(12.dp))
                FeatureItem(
                    visible = visible,
                    delay = 500,
                    icon = AppIcons.StickyNote,
                    iconModifier = if (animationsEnabled) Modifier.scale(pulse) else Modifier,
                    color = Color(0xFF81C784),
                    title = OnboardingStrings.notesTitle(isRussian),
                    description = OnboardingStrings.notesDescription(isRussian)
                )
            }
            OnboardingPageType.Settings -> {
                FeatureItem(
                    visible = visible,
                    delay = 200,
                    icon = AppIcons.Settings,
                    iconModifier = if (animationsEnabled) Modifier.graphicsLayer { rotationZ = rotation } else Modifier,
                    color = Color(0xFF78909C),
                    title = OnboardingStrings.personalizationTitle(isRussian),
                    description = OnboardingStrings.personalizationDescription(isRussian)
                )
                Spacer(modifier = Modifier.height(16.dp))
                FeatureItem(
                    visible = visible,
                    delay = 350,
                    icon = AppIcons.Update,
                    iconModifier = if (animationsEnabled) Modifier.graphicsLayer { rotationZ = rotation } else Modifier,
                    color = Color(0xFF26A69A),
                    title = OnboardingStrings.updatesTitle(isRussian),
                    description = OnboardingStrings.updatesDescription(isRussian)
                )
            }
        }
        }
        
        ScrollColumnScrollbar(scrollState)
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
    title: String,
    description: String
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
