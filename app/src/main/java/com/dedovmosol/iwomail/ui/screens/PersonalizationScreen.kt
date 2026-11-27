package com.dedovmosol.iwomail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.components.ScrollbarColor
import com.dedovmosol.iwomail.ui.isRussian
import com.dedovmosol.iwomail.ui.theme.AppColorTheme
import com.dedovmosol.iwomail.ui.theme.AppIcons
import kotlinx.coroutines.launch
import java.util.Calendar

/** Локализованное название цветовой темы. */
@Composable
private fun getThemeDisplayName(theme: AppColorTheme): String = when (theme) {
    AppColorTheme.PURPLE -> Strings.themePurple
    AppColorTheme.BLUE -> Strings.themeBlue
    AppColorTheme.YELLOW -> Strings.themeYellow
    AppColorTheme.GREEN -> Strings.themeGreen
}

/** Образец цвета: в строке (40 dp, как плитка иконки) и в списке вариантов (20 dp). */
@Composable
private fun ColorSwatch(color: Color, size: Dp = 20.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}

/** Дни недели в порядке недели (с понедельника) и их подписи. */
@Composable
internal fun weekDays(): List<Pair<Int, String>> = listOf(
    Calendar.MONDAY to Strings.monday,
    Calendar.TUESDAY to Strings.tuesday,
    Calendar.WEDNESDAY to Strings.wednesday,
    Calendar.THURSDAY to Strings.thursday,
    Calendar.FRIDAY to Strings.friday,
    Calendar.SATURDAY to Strings.saturday,
    Calendar.SUNDAY to Strings.sunday
)

/** Значения по умолчанию — те же, что были у экрана до переделки. */
private val DEFAULT_DAY_THEMES = mapOf(
    Calendar.MONDAY to "purple", Calendar.TUESDAY to "blue", Calendar.WEDNESDAY to "green",
    Calendar.THURSDAY to "yellow", Calendar.FRIDAY to "purple", Calendar.SATURDAY to "blue",
    Calendar.SUNDAY to "yellow"
)

/** Темы и цвета скроллбара по дням недели — нижний лист вместо диалога с 14 выпадающими списками. */
@Composable
private fun DailyThemesSheet(settingsRepo: SettingsRepository, highlight: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = com.dedovmosol.iwomail.ui.components.rememberSafeScope()
    val isRu = isRussian()
    val days = weekDays()
    SettingsSheet(title = Strings.configureDailyThemes, onDismiss = onDismiss) {
        SettingsSectionTitle(Strings.colorTheme)
        for ((day, name) in days) {
            val code by settingsRepo.getDayTheme(day)
                .collectAsStateWithLifecycle(initialValue = DEFAULT_DAY_THEMES.getValue(day))
            val current = AppColorTheme.fromCode(code)
            SettingsChoiceRow(
                title = name,
                options = AppColorTheme.entries,
                highlightId = "dayTheme_$day", highlight = highlight,
                selected = current,
                label = { getThemeDisplayName(it) },
                optionLeading = { ColorSwatch(it.gradientStart) },
                leading = { ColorSwatch(current.gradientStart) },
                onSelect = { theme ->
                    scope.launch {
                        settingsRepo.setDayTheme(day, theme.code)
                        com.dedovmosol.iwomail.widget.updateMailWidget(context)
                    }
                }
            )
        }
        SettingsSectionTitle(Strings.scrollbarColor)
        for ((day, name) in days) {
            val code by settingsRepo.getDayScrollbarColor(day).collectAsStateWithLifecycle(initialValue = "blue")
            val current = ScrollbarColor.fromCode(code)
            SettingsChoiceRow(
                title = name,
                options = ScrollbarColor.entries,
                highlightId = "dayScroll_$day", highlight = highlight,
                selected = current,
                label = { it.getDisplayName(isRu) },
                optionLeading = { ColorSwatch(it.color) },
                leading = { ColorSwatch(current.color) },
                onSelect = { color -> scope.launch { settingsRepo.setDayScrollbarColor(day, color.code) } }
            )
        }
    }
}

@Composable
fun PersonalizationScreen(
    onBackClick: () -> Unit,
    highlight: String = ""
) {
    val context = LocalContext.current
    val scope = com.dedovmosol.iwomail.ui.components.rememberSafeScope()
    val settingsRepo = remember { SettingsRepository.getInstance(context) }
    val isRu = isRussian()

    val currentLanguage = LocalLanguage.current
    val fontSize by settingsRepo.fontSize.collectAsStateWithLifecycle(initialValue = SettingsRepository.FontSize.MEDIUM)
    val colorThemeCode by settingsRepo.colorTheme.collectAsStateWithLifecycle(initialValue = "purple")
    val dailyThemesEnabled by settingsRepo.dailyThemesEnabled.collectAsStateWithLifecycle(initialValue = false)
    val animationsEnabled by settingsRepo.animationsEnabled.collectAsStateWithLifecycle(initialValue = true)
    val themeMode by settingsRepo.themeMode.collectAsStateWithLifecycle(initialValue = settingsRepo.getThemeModeSync())
    val scrollbarColorCode by settingsRepo.scrollbarColor.collectAsStateWithLifecycle(initialValue = "blue")
    var showDailyThemes by rememberSaveable(highlight) {
        mutableStateOf(highlight.startsWith("dayTheme_") || highlight.startsWith("dayScroll_"))
    }

    if (showDailyThemes && dailyThemesEnabled) {
        DailyThemesSheet(settingsRepo, highlight, onDismiss = { showDailyThemes = false })
    }

    SettingsPageScaffold(
        title = Strings.appearance,
        onBackClick = onBackClick,
        hue = SettingsSearchDest.Appearance.hue
    ) {
        // Одним элементом списка: строку из поиска есть кому подсветить и при крупном шрифте (элемент LazyColumn за
        // экраном не создаётся)
        item {
            SettingsGroup {
                SettingsChoiceRow(
                    title = Strings.language,
                    options = AppLanguage.entries,
                    selected = currentLanguage,
                    label = { it.displayName },
                    onSelect = { lang -> scope.launch { settingsRepo.setLanguage(lang.code) } },
                    icon = AppIcons.Language,
                    highlightId = "language",
                    highlight = highlight
                )
                SettingsChoiceRow(
                    title = Strings.fontSize,
                    options = SettingsRepository.FontSize.entries,
                    selected = fontSize,
                    label = { it.getDisplayName(isRu) },
                    onSelect = { size -> scope.launch { settingsRepo.setFontSize(size) } },
                    icon = AppIcons.TextFields,
                    highlightId = "fontSize",
                    highlight = highlight
                )
            }
            val currentTheme = AppColorTheme.fromCode(colorThemeCode)
            val themeEnabled = !dailyThemesEnabled
            val currentSbColor = ScrollbarColor.fromCode(scrollbarColorCode)
            SettingsGroup {
                SettingsChoiceRow(
                    title = Strings.colorTheme,
                    subtitle = if (dailyThemesEnabled) Strings.dailyThemesActive else null,
                    options = AppColorTheme.entries,
                    selected = currentTheme,
                    label = { getThemeDisplayName(it) },
                    optionLeading = { ColorSwatch(it.gradientStart) },
                    leading = {
                        ColorSwatch(
                            if (themeEnabled) currentTheme.gradientStart else currentTheme.gradientStart.copy(alpha = 0.38f),
                            size = SettingsTokens.TileSize
                        )
                    },
                    enabled = themeEnabled,
                    highlightId = "colorTheme",
                    highlight = highlight,
                    onSelect = { theme ->
                        scope.launch {
                            settingsRepo.setColorTheme(theme.code)
                            com.dedovmosol.iwomail.widget.updateMailWidget(context)
                        }
                    }
                )
                SettingsChoiceRow(
                    title = Strings.themeMode,
                    options = SettingsRepository.ThemeMode.entries,
                    selected = themeMode,
                    label = { it.getDisplayName(isRu) },
                    optionLeading = { Icon(themeModeIcon(it), contentDescription = null, modifier = Modifier.size(20.dp)) },
                    icon = themeModeIcon(themeMode),
                    highlightId = "themeMode",
                    highlight = highlight,
                    onSelect = { mode -> scope.launch { settingsRepo.setThemeMode(mode) } }
                )
                SettingsChoiceRow(
                    title = Strings.scrollbarColor,
                    options = ScrollbarColor.entries,
                    selected = currentSbColor,
                    label = { it.getDisplayName(isRu) },
                    optionLeading = { ColorSwatch(it.color) },
                    leading = { ColorSwatch(currentSbColor.color, size = SettingsTokens.TileSize) },
                    enabled = themeEnabled,
                    highlightId = "scrollbarColor",
                    highlight = highlight,
                    onSelect = { color -> scope.launch { settingsRepo.setScrollbarColor(color.code) } }
                )
                SettingsSwitchRow(
                    title = Strings.dailyThemes,
                    description = Strings.dailyThemesDesc,
                    icon = { Icon(AppIcons.CalendarMonth, null) },
                    highlightId = "dailyThemes",
                    highlight = highlight,
                    checked = dailyThemesEnabled,
                    onChange = { enabled ->
                        scope.launch { settingsRepo.setDailyThemesEnabled(enabled) }
                    }
                )
                if (dailyThemesEnabled) {
                    SettingsNavRow(
                        title = Strings.configureDailyThemes,
                        icon = AppIcons.Settings,
                        highlightId = "configureDailyThemes",
                        highlight = highlight,
                        onClick = { showDailyThemes = true }
                    )
                }
            }
            SettingsGroup {
                SettingsSwitchRow(
                    title = Strings.animations,
                    description = Strings.animationsDesc,
                    icon = { Icon(AppIcons.Animation, null) },
                    highlightId = "animations",
                    highlight = highlight,
                    checked = animationsEnabled,
                    onChange = { enabled ->
                        scope.launch { settingsRepo.setAnimationsEnabled(enabled) }
                    }
                )
            }
        }
    }
}

/**
 * Иконка режима темы (единая точка выбора — список вариантов и строка).
 * DRY: маппинг режима на иконку определён один раз.
 */
@Composable
private fun themeModeIcon(mode: SettingsRepository.ThemeMode): ImageVector = when (mode) {
    SettingsRepository.ThemeMode.SYSTEM -> AppIcons.Contrast
    SettingsRepository.ThemeMode.LIGHT -> AppIcons.LightMode
    SettingsRepository.ThemeMode.DARK -> AppIcons.NightsStay
}
