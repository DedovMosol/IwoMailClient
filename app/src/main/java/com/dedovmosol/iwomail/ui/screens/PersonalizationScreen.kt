package com.dedovmosol.iwomail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape

import com.dedovmosol.iwomail.ui.theme.AppIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
        AppColorTheme.RED -> Strings.themeRed
        AppColorTheme.YELLOW -> Strings.themeYellow
        AppColorTheme.ORANGE -> Strings.themeOrange
        AppColorTheme.GREEN -> Strings.themeGreen
        AppColorTheme.PINK -> Strings.themePink
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
    var showLanguageDialog by remember { mutableStateOf(false) }
    
    // Настройки размера шрифта
    val fontSize by settingsRepo.fontSize.collectAsState(initial = SettingsRepository.FontSize.MEDIUM)
    var showFontSizeDialog by remember { mutableStateOf(false) }
    
    // Настройки цветовой темы
    val colorThemeCode by settingsRepo.colorTheme.collectAsState(initial = "purple")
    val dailyThemesEnabled by settingsRepo.dailyThemesEnabled.collectAsState(initial = false)
    val animationsEnabled by settingsRepo.animationsEnabled.collectAsState(initial = true)
    var showColorThemeDialog by remember { mutableStateOf(false) }
    var showDailyThemesDialog by remember { mutableStateOf(false) }

    // Темы по дням недели
    val mondayTheme by settingsRepo.getDayTheme(java.util.Calendar.MONDAY).collectAsState(initial = "purple")
    val tuesdayTheme by settingsRepo.getDayTheme(java.util.Calendar.TUESDAY).collectAsState(initial = "blue")
    val wednesdayTheme by settingsRepo.getDayTheme(java.util.Calendar.WEDNESDAY).collectAsState(initial = "green")
    val thursdayTheme by settingsRepo.getDayTheme(java.util.Calendar.THURSDAY).collectAsState(initial = "orange")
    val fridayTheme by settingsRepo.getDayTheme(java.util.Calendar.FRIDAY).collectAsState(initial = "red")
    val saturdayTheme by settingsRepo.getDayTheme(java.util.Calendar.SATURDAY).collectAsState(initial = "pink")
    val sundayTheme by settingsRepo.getDayTheme(java.util.Calendar.SUNDAY).collectAsState(initial = "yellow")
    
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
                TextButton(onClick = { showFontSizeDialog = false }) {
                    Text(Strings.cancel)
                }
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
                                    scope.launch { settingsRepo.setColorTheme(theme.code) }
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
                TextButton(onClick = { showColorThemeDialog = false }) {
                    Text(Strings.cancel)
                }
            }
        )
    }

    // Диалог настройки тем по дням недели
    if (showDailyThemesDialog) {
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showDailyThemesDialog = false },
            title = { Text(Strings.configureDailyThemes) },
            text = {
                Column {
                    DayThemeRow(Strings.monday, mondayTheme, isRu) { theme ->
                        scope.launch { settingsRepo.setDayTheme(java.util.Calendar.MONDAY, theme) }
                    }
                    DayThemeRow(Strings.tuesday, tuesdayTheme, isRu) { theme ->
                        scope.launch { settingsRepo.setDayTheme(java.util.Calendar.TUESDAY, theme) }
                    }
                    DayThemeRow(Strings.wednesday, wednesdayTheme, isRu) { theme ->
                        scope.launch { settingsRepo.setDayTheme(java.util.Calendar.WEDNESDAY, theme) }
                    }
                    DayThemeRow(Strings.thursday, thursdayTheme, isRu) { theme ->
                        scope.launch { settingsRepo.setDayTheme(java.util.Calendar.THURSDAY, theme) }
                    }
                    DayThemeRow(Strings.friday, fridayTheme, isRu) { theme ->
                        scope.launch { settingsRepo.setDayTheme(java.util.Calendar.FRIDAY, theme) }
                    }
                    DayThemeRow(Strings.saturday, saturdayTheme, isRu) { theme ->
                        scope.launch { settingsRepo.setDayTheme(java.util.Calendar.SATURDAY, theme) }
                    }
                    DayThemeRow(Strings.sunday, sundayTheme, isRu) { theme ->
                        scope.launch { settingsRepo.setDayTheme(java.util.Calendar.SUNDAY, theme) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDailyThemesDialog = false }) {
                    Text(Strings.done)
                }
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
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(Strings.cancel)
                }
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
