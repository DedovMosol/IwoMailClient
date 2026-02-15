package com.dedovmosol.iwomail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape

import com.dedovmosol.iwomail.ui.theme.AppIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.isRussian
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
import com.dedovmosol.iwomail.ui.theme.AppColorTheme
import kotlinx.coroutines.launch

/**
 * Получить локализованное название темы
 */
@Composable
private fun getThemeDisplayName(theme: AppColorTheme, isRu: Boolean): String {
    return when (theme) {
        AppColorTheme.PURPLE -> Strings.themePurple
        AppColorTheme.BLUE -> Strings.themeBlue
        AppColorTheme.YELLOW -> Strings.themeYellow
        AppColorTheme.GREEN -> Strings.themeGreen
    }
}

/**
 * Строка выбора темы для дня недели
 */
@Composable
private fun DayThemeRow(
    dayName: String,
    currentThemeCode: String,
    isRu: Boolean,
    onThemeSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentTheme = AppColorTheme.fromCode(currentThemeCode)
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = dayName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(110.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier
                .wrapContentSize(Alignment.TopStart)
                .clickable { expanded = true }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(currentTheme.gradientStart)
                )

                Text(
                    text = getThemeDisplayName(currentTheme, isRu),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .width(100.dp)
                        .padding(start = 8.dp)
                )

                Icon(
                    AppIcons.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                offset = DpOffset(0.dp, 6.dp)
            ) {
                AppColorTheme.entries.forEach { theme ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(theme.gradientStart)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(getThemeDisplayName(theme, isRu))
                            }
                        },
                        onClick = {
                            onThemeSelected(theme.code)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * Строка выбора цвета скроллбара для дня недели
 */
@Composable
private fun DayScrollbarColorRow(
    dayName: String,
    currentColorCode: String,
    isRu: Boolean,
    onColorSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentColor = com.dedovmosol.iwomail.ui.components.ScrollbarColor.fromCode(currentColorCode)
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = dayName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(110.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier
                .wrapContentSize(Alignment.TopStart)
                .clickable { expanded = true }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(currentColor.color)
                )

                Text(
                    text = currentColor.getDisplayName(isRu),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .width(100.dp)
                        .padding(start = 8.dp)
                )

                Icon(
                    AppIcons.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                offset = DpOffset(0.dp, 6.dp)
            ) {
                com.dedovmosol.iwomail.ui.components.ScrollbarColor.entries.forEach { sbColor ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(sbColor.color)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(sbColor.getDisplayName(isRu))
                            }
                        },
                        onClick = {
                            onColorSelected(sbColor.code)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalizationScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { SettingsRepository.getInstance(context) }
    val isRu = isRussian()
    
    // Настройки языка
    val currentLanguage = LocalLanguage.current
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    
    // Настройки размера шрифта
    val fontSize by settingsRepo.fontSize.collectAsState(initial = SettingsRepository.FontSize.MEDIUM)
    var showFontSizeDialog by rememberSaveable { mutableStateOf(false) }
    
    // Настройки цветовой темы
    val colorThemeCode by settingsRepo.colorTheme.collectAsState(initial = "purple")
    val dailyThemesEnabled by settingsRepo.dailyThemesEnabled.collectAsState(initial = false)
    val animationsEnabled by settingsRepo.animationsEnabled.collectAsState(initial = true)
    var showColorThemeDialog by rememberSaveable { mutableStateOf(false) }
    var showScrollbarColorDialog by rememberSaveable { mutableStateOf(false) }
    var showDailyThemesDialog by rememberSaveable { mutableStateOf(false) }

    // Темы по дням недели
    val mondayTheme by settingsRepo.getDayTheme(java.util.Calendar.MONDAY).collectAsState(initial = "purple")
    val tuesdayTheme by settingsRepo.getDayTheme(java.util.Calendar.TUESDAY).collectAsState(initial = "blue")
    val wednesdayTheme by settingsRepo.getDayTheme(java.util.Calendar.WEDNESDAY).collectAsState(initial = "green")
    val thursdayTheme by settingsRepo.getDayTheme(java.util.Calendar.THURSDAY).collectAsState(initial = "yellow")
    val fridayTheme by settingsRepo.getDayTheme(java.util.Calendar.FRIDAY).collectAsState(initial = "purple")
    val saturdayTheme by settingsRepo.getDayTheme(java.util.Calendar.SATURDAY).collectAsState(initial = "blue")
    val sundayTheme by settingsRepo.getDayTheme(java.util.Calendar.SUNDAY).collectAsState(initial = "yellow")
    
    // Цвет скроллбара
    val scrollbarColorCode by settingsRepo.scrollbarColor.collectAsState(initial = "blue")
    
    // Цвета скроллбара по дням недели
    val monScrollbar by settingsRepo.getDayScrollbarColor(java.util.Calendar.MONDAY).collectAsState(initial = "blue")
    val tueScrollbar by settingsRepo.getDayScrollbarColor(java.util.Calendar.TUESDAY).collectAsState(initial = "blue")
    val wedScrollbar by settingsRepo.getDayScrollbarColor(java.util.Calendar.WEDNESDAY).collectAsState(initial = "blue")
    val thuScrollbar by settingsRepo.getDayScrollbarColor(java.util.Calendar.THURSDAY).collectAsState(initial = "blue")
    val friScrollbar by settingsRepo.getDayScrollbarColor(java.util.Calendar.FRIDAY).collectAsState(initial = "blue")
    val satScrollbar by settingsRepo.getDayScrollbarColor(java.util.Calendar.SATURDAY).collectAsState(initial = "blue")
    val sunScrollbar by settingsRepo.getDayScrollbarColor(java.util.Calendar.SUNDAY).collectAsState(initial = "blue")
    
    // Диалог выбора размера шрифта
    if (showFontSizeDialog) {
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showFontSizeDialog = false },
            title = { Text(Strings.selectFontSize) },
            text = {
                Column {
                    SettingsRepository.FontSize.entries.forEach { size ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { settingsRepo.setFontSize(size) }
                                    showFontSizeDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = fontSize == size, onClick = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(size.getDisplayName(isRu))
                        }
                    }
                }
            },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { showFontSizeDialog = false },
                    text = Strings.cancel
                )
            }
        )
    }

    // Диалог выбора цветовой темы
    if (showColorThemeDialog) {
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showColorThemeDialog = false },
            title = { Text(Strings.selectColorTheme) },
            text = {
                Column {
                    AppColorTheme.entries.forEach { theme ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        settingsRepo.setColorTheme(theme.code)
                                        com.dedovmosol.iwomail.widget.updateMailWidget(context)
                                    }
                                    showColorThemeDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = colorThemeCode == theme.code, onClick = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(theme.gradientStart)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(getThemeDisplayName(theme, isRu))
                        }
                    }
                }
            },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { showColorThemeDialog = false },
                    text = Strings.cancel
                )
            }
        )
    }

    // Диалог выбора цвета скроллбара
    if (showScrollbarColorDialog) {
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showScrollbarColorDialog = false },
            title = { Text(if (isRu) "Цвет скроллбара" else "Scrollbar color") },
            text = {
                Column {
                    com.dedovmosol.iwomail.ui.components.ScrollbarColor.entries.forEach { sbColor ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { settingsRepo.setScrollbarColor(sbColor.code) }
                                    showScrollbarColorDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = scrollbarColorCode == sbColor.code, onClick = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(sbColor.color)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(sbColor.getDisplayName(isRu))
                        }
                    }
                }
            },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { showScrollbarColorDialog = false },
                    text = Strings.cancel
                )
            }
        )
    }

    // Диалог настройки тем по дням недели — два раздельных блока
    if (showDailyThemesDialog) {
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showDailyThemesDialog = false },
            title = { Text(Strings.configureDailyThemes) },
            text = {
                Column {
                    // === Блок 1: Цветовая тема по дням ===
                    Text(
                        text = if (isRu) "Цветовая тема" else "Color theme",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    DayThemeRow(Strings.monday, mondayTheme, isRu) { theme ->
                        scope.launch { settingsRepo.setDayTheme(java.util.Calendar.MONDAY, theme); com.dedovmosol.iwomail.widget.updateMailWidget(context) }
                    }
                    DayThemeRow(Strings.tuesday, tuesdayTheme, isRu) { theme ->
                        scope.launch { settingsRepo.setDayTheme(java.util.Calendar.TUESDAY, theme); com.dedovmosol.iwomail.widget.updateMailWidget(context) }
                    }
                    DayThemeRow(Strings.wednesday, wednesdayTheme, isRu) { theme ->
                        scope.launch { settingsRepo.setDayTheme(java.util.Calendar.WEDNESDAY, theme); com.dedovmosol.iwomail.widget.updateMailWidget(context) }
                    }
                    DayThemeRow(Strings.thursday, thursdayTheme, isRu) { theme ->
                        scope.launch { settingsRepo.setDayTheme(java.util.Calendar.THURSDAY, theme); com.dedovmosol.iwomail.widget.updateMailWidget(context) }
                    }
                    DayThemeRow(Strings.friday, fridayTheme, isRu) { theme ->
                        scope.launch { settingsRepo.setDayTheme(java.util.Calendar.FRIDAY, theme); com.dedovmosol.iwomail.widget.updateMailWidget(context) }
                    }
                    DayThemeRow(Strings.saturday, saturdayTheme, isRu) { theme ->
                        scope.launch { settingsRepo.setDayTheme(java.util.Calendar.SATURDAY, theme); com.dedovmosol.iwomail.widget.updateMailWidget(context) }
                    }
                    DayThemeRow(Strings.sunday, sundayTheme, isRu) { theme ->
                        scope.launch { settingsRepo.setDayTheme(java.util.Calendar.SUNDAY, theme); com.dedovmosol.iwomail.widget.updateMailWidget(context) }
                    }
                    
                    // === Разделитель ===
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    
                    // === Блок 2: Цвет скроллбара по дням ===
                    Text(
                        text = if (isRu) "Цвет скроллбара" else "Scrollbar color",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    DayScrollbarColorRow(Strings.monday, monScrollbar, isRu) { color ->
                        scope.launch { settingsRepo.setDayScrollbarColor(java.util.Calendar.MONDAY, color) }
                    }
                    DayScrollbarColorRow(Strings.tuesday, tueScrollbar, isRu) { color ->
                        scope.launch { settingsRepo.setDayScrollbarColor(java.util.Calendar.TUESDAY, color) }
                    }
                    DayScrollbarColorRow(Strings.wednesday, wedScrollbar, isRu) { color ->
                        scope.launch { settingsRepo.setDayScrollbarColor(java.util.Calendar.WEDNESDAY, color) }
                    }
                    DayScrollbarColorRow(Strings.thursday, thuScrollbar, isRu) { color ->
                        scope.launch { settingsRepo.setDayScrollbarColor(java.util.Calendar.THURSDAY, color) }
                    }
                    DayScrollbarColorRow(Strings.friday, friScrollbar, isRu) { color ->
                        scope.launch { settingsRepo.setDayScrollbarColor(java.util.Calendar.FRIDAY, color) }
                    }
                    DayScrollbarColorRow(Strings.saturday, satScrollbar, isRu) { color ->
                        scope.launch { settingsRepo.setDayScrollbarColor(java.util.Calendar.SATURDAY, color) }
                    }
                    DayScrollbarColorRow(Strings.sunday, sunScrollbar, isRu) { color ->
                        scope.launch { settingsRepo.setDayScrollbarColor(java.util.Calendar.SUNDAY, color) }
                    }
                }
            },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { showDailyThemesDialog = false },
                    text = Strings.done
                )
            }
        )
    }

    // Диалог выбора языка
    if (showLanguageDialog) {
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(Strings.selectLanguage) },
            text = {
                Column {
                    AppLanguage.entries.forEach { lang ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { settingsRepo.setLanguage(lang.code) }
                                    showLanguageDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = currentLanguage == lang, onClick = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(lang.displayName)
                        }
                    }
                }
            },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { showLanguageDialog = false },
                    text = Strings.cancel
                )
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Strings.interfacePersonalization, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(AppIcons.ArrowBack, Strings.back, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                modifier = Modifier.background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            LocalColorTheme.current.gradientStart,
                            LocalColorTheme.current.gradientEnd
                        )
                    )
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Выбор языка
            item {
                ListItem(
                    headlineContent = { Text(Strings.language) },
                    supportingContent = { Text(currentLanguage.displayName) },
                    leadingContent = { Icon(AppIcons.Language, null) },
                    modifier = Modifier.clickable { showLanguageDialog = true }
                )
            }
            
            // Выбор размера шрифта
            item {
                ListItem(
                    headlineContent = { Text(Strings.fontSize) },
                    supportingContent = { Text(fontSize.getDisplayName(isRu)) },
                    leadingContent = { Icon(AppIcons.TextFields, null) },
                    modifier = Modifier.clickable { showFontSizeDialog = true }
                )
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            
            // Выбор цветовой темы (неактивно если включены темы по дням)
            item {
                val currentTheme = AppColorTheme.fromCode(colorThemeCode)
                val isEnabled = !dailyThemesEnabled
                ListItem(
                    headlineContent = { 
                        Text(
                            Strings.colorTheme,
                            color = if (isEnabled) MaterialTheme.colorScheme.onSurface 
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        ) 
                    },
                    supportingContent = { 
                        Text(
                            if (dailyThemesEnabled) Strings.dailyThemesActive else getThemeDisplayName(currentTheme, isRu),
                            color = if (isEnabled) MaterialTheme.colorScheme.onSurfaceVariant 
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        ) 
                    },
                    leadingContent = { 
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isEnabled) currentTheme.gradientStart 
                                    else currentTheme.gradientStart.copy(alpha = 0.38f)
                                )
                        )
                    },
                    modifier = if (isEnabled) Modifier.clickable { showColorThemeDialog = true } else Modifier
                )
            }
            
            // Цвет скроллбара (неактивно если включены темы по дням)
            item {
                val currentSbColor = com.dedovmosol.iwomail.ui.components.ScrollbarColor.fromCode(scrollbarColorCode)
                val isEnabled = !dailyThemesEnabled
                ListItem(
                    headlineContent = { Text(if (isRu) "Цвет скроллбара" else "Scrollbar color") },
                    supportingContent = { Text(currentSbColor.getDisplayName(isRu)) },
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(currentSbColor.color)
                        )
                    },
                    modifier = if (isEnabled) Modifier.clickable { showScrollbarColorDialog = true } else Modifier
                )
            }
            
            // Темы по дням недели
            item {
                ListItem(
                    headlineContent = { Text(Strings.dailyThemes) },
                    supportingContent = { Text(Strings.dailyThemesDesc) },
                    leadingContent = { Icon(AppIcons.CalendarMonth, null) },
                    trailingContent = {
                        Switch(
                            checked = dailyThemesEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch { settingsRepo.setDailyThemesEnabled(enabled) }
                            }
                        )
                    }
                )
            }

            // Настройка тем по дням (показывается только если включено)
            if (dailyThemesEnabled) {
                item {
                    ListItem(
                        headlineContent = { Text(Strings.configureDailyThemes) },
                        leadingContent = { Icon(AppIcons.Settings, null) },
                        modifier = Modifier.clickable { showDailyThemesDialog = true }
                    )
                }
            }
            
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            
            // Анимации интерфейса
            item {
                ListItem(
                    headlineContent = { Text(Strings.animations) },
                    supportingContent = { Text(Strings.animationsDesc) },
                    leadingContent = { Icon(AppIcons.Animation, null) },
                    trailingContent = {
                        Switch(
                            checked = animationsEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch { settingsRepo.setAnimationsEnabled(enabled) }
                            }
                        )
                    }
                )
            }
        }
    }
}
