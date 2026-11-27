package com.dedovmosol.iwomail.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.components.LazyColumnScrollbar
import com.dedovmosol.iwomail.ui.components.ScrollColumnScrollbar
import com.dedovmosol.iwomail.ui.components.ScrollbarColor
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
import kotlinx.coroutines.launch

/**
 * Настройки в стиле HyperOS (SET-24): крупное название на градиенте темы сразу под стрелкой «Назад», при прокрутке
 * оно уезжает в обычную панель; светло-серая страница и почти во всю ширину белые карточки без разделителей
 * (в тёмной теме — чёрная страница и тёмно-серые карточки); значение справа со стрелкой; выбор значения — нижним
 * листом (внутри листа — списком у строки); иконки — в плитках одной формы статичных цветов ([SettingsIconTile]).
 */
internal object SettingsTokens {
    val CardShape = RoundedCornerShape(24.dp)
    val TileShape = RoundedCornerShape(12.dp)
    val TileSize = 40.dp
    /** Плитка корня настроек: крупнее и с заливкой. */
    val HubTileShape = RoundedCornerShape(15.dp)
    val HubTileSize = 48.dp
    /** Поля карточки от края экрана: карточки почти во всю ширину. */
    val HorizontalPadding = 8.dp
    /** Начало текста подписей над и под карточками — на линии содержимого карточки (поле карточки + 16 dp ListItem). */
    val TextInset = 24.dp
    val RowMinHeight = 64.dp
    val HubRowMinHeight = 84.dp
    /** Панель со стрелкой «Назад» (без блока крупного названия). */
    val TopBarHeight = 56.dp

    val isDark: Boolean @Composable get() = MaterialTheme.colorScheme.background.luminance() < 0.5f

    /** Фон страницы: светло-серый, в тёмной теме — фон приложения. */
    val pageColor: Color
        @Composable get() = if (isDark) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceContainer

    /** Карточка группы: белая, в тёмной теме — светлее страницы. */
    val cardColor: Color
        @Composable get() = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest

    /** Статус «всё хорошо»: у Material 3 нет такой роли. Контраст с карточкой проверяет AppColorSchemeContrastTest. */
    val successColor: Color @Composable get() = if (isDark) SUCCESS_DARK else SUCCESS_LIGHT
    val warningColor: Color @Composable get() = if (isDark) WARNING_DARK else WARNING_LIGHT

    val SUCCESS_LIGHT = Color(0xFF1B6E33)
    val SUCCESS_DARK = Color(0xFF81C995)
    val WARNING_LIGHT = Color(0xFF8A5100)
    val WARNING_DARK = Color(0xFFFFB960)
}

/**
 * Цвет плиток иконок раздела — из палитры цветов скроллбара ([ScrollbarColor]): статичный, не зависит от цветовой
 * темы и обоев (dynamic color). Задаёт его каркас раздела ([SettingsPageScaffold]), у корня — каждая строка своим.
 */
internal val LocalSettingsHue = staticCompositionLocalOf { ScrollbarColor.GRAY }

/**
 * Цвета плиток — чистые функции, контраст глифа с плиткой проверяет SettingsTileColorsTest (не ниже 3:1, WCAG 1.4.11
 * для значимой графики). Тональная плитка смешивается с постоянной основой, а не с цветом карточки: у карточки
 * с dynamic color свой оттенок.
 */
internal object SettingsTileColors {
    private val TONAL_BASE_DARK = Color(0xFF262628)

    /** Залитая плитка корня: цвет палитры, к нижнему правому углу темнее; глиф белый. */
    fun filled(hue: Color): List<Color> = listOf(hue, lerp(hue, Color.Black, 0.22f))

    fun tonalBackground(hue: Color, dark: Boolean): Color =
        if (dark) lerp(TONAL_BASE_DARK, hue, 0.32f) else lerp(Color.White, hue, 0.14f)

    fun tonalGlyph(hue: Color, dark: Boolean): Color =
        if (dark) lerp(hue, Color.White, 0.5f) else lerp(hue, Color.Black, 0.2f)
}

@Composable
private fun themeGradient(): Brush {
    val theme = LocalColorTheme.current
    return remember(theme) { Brush.horizontalGradient(listOf(theme.gradientStart, theme.gradientEnd)) }
}

/**
 * Заголовок раздела на градиенте темы: стрелка «Назад» и название в одной строке, с первого кадра. Прежний крупный
 * сворачиваемый заголовок принимал вид панели только после прокрутки вниз.
 */
@Composable
internal fun SettingsHeaderBar(title: String, onBackClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(themeGradient())
            .windowInsetsPadding(TopAppBarDefaults.windowInsets)
            .testTag(SETTINGS_TOP_BAR_TAG)
            .height(SettingsTokens.TopBarHeight)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBackClick) {
            Icon(AppIcons.ArrowBack, Strings.back, tint = Color.White)
        }
        Text(
            title,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, end = 12.dp)
                .semantics { heading() }
        )
    }
}

internal const val SETTINGS_TOP_BAR_TAG = "settings_top_bar"

/**
 * Общий каркас экрана настроек (корень и разделы): заголовок в строку со стрелкой, список, скроллбар. [hue] — цвет
 * плиток иконок раздела ([LocalSettingsHue]).
 */
@Composable
internal fun SettingsPageScaffold(
    title: String,
    onBackClick: () -> Unit,
    hue: ScrollbarColor = ScrollbarColor.GRAY,
    content: LazyListScope.() -> Unit
) {
    Scaffold(
        topBar = { SettingsHeaderBar(title, onBackClick) },
        containerColor = SettingsTokens.pageColor
    ) { padding ->
        val listState = rememberLazyListState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            CompositionLocalProvider(LocalSettingsHue provides hue) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                    content = content
                )
            }
            LazyColumnScrollbar(listState = listState)
        }
    }
}

/**
 * Тот же заголовок и скролл, что у разделов настроек.
 * [footer] рисуется внизу экрана (пасхалка в «О приложении»): weight внутри
 * verticalScroll не заполняет высоту, у скролла maxHeight = Infinity.
 */
@Composable
internal fun SettingsScrollScaffold(
    title: String,
    onBackClick: () -> Unit,
    hue: ScrollbarColor = ScrollbarColor.GRAY,
    footer: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        topBar = { SettingsHeaderBar(title, onBackClick) },
        containerColor = SettingsTokens.pageColor
    ) { padding ->
        val scrollState = rememberScrollState()
        val density = LocalDensity.current
        var footerPx by remember { mutableIntStateOf(0) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            CompositionLocalProvider(LocalSettingsHue provides hue) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(top = 8.dp, bottom = with(density) { footerPx.toDp() })
                ) {
                    content()
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { footerPx = it.height }
            ) {
                footer()
            }
            ScrollColumnScrollbar(scrollState)
        }
    }
}

/** Мелкий серый заголовок над карточкой группы. */
@Composable
internal fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = SettingsTokens.TextInset, end = SettingsTokens.TextInset, top = 16.dp, bottom = 4.dp)
    )
}

/** Пояснение под карточкой группы. */
@Composable
internal fun SettingsGroupHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = SettingsTokens.TextInset, end = SettingsTokens.TextInset, top = 2.dp, bottom = 6.dp)
    )
}

/** Заметка внизу страницы: значок «i» и пояснение, как устроены настройки (крупнее и заметнее [SettingsGroupHint]). */
@Composable
internal fun SettingsInfoNote(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SettingsTokens.TextInset, end = SettingsTokens.TextInset, top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            AppIcons.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Группа строк: карточка без разделителей. */
@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsTokens.HorizontalPadding, vertical = 6.dp),
        shape = SettingsTokens.CardShape,
        colors = CardDefaults.cardColors(containerColor = SettingsTokens.cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp), content = content)
    }
}

/**
 * Плитка иконки — одна форма во всех настройках, цвет из палитры ([LocalSettingsHue]), не из темы. [filled] — плитка
 * корня: крупнее, заливка цветом с переходом и белый глиф; иначе — тональная плитка с глифом того же цвета.
 */
@Composable
internal fun SettingsIconTile(
    hue: ScrollbarColor = LocalSettingsHue.current,
    filled: Boolean = false,
    /** Крупная плитка корня настроек; строки разделов — та же заливка, плитка меньше. */
    hub: Boolean = filled,
    content: @Composable () -> Unit
) {
    val dark = SettingsTokens.isDark
    val background = if (filled) {
        val brush = remember(hue) { Brush.linearGradient(SettingsTileColors.filled(hue.color)) }
        Modifier.background(brush)
    } else {
        Modifier.background(SettingsTileColors.tonalBackground(hue.color, dark))
    }
    Box(
        modifier = Modifier
            .size(if (hub) SettingsTokens.HubTileSize else SettingsTokens.TileSize)
            .clip(if (hub) SettingsTokens.HubTileShape else SettingsTokens.TileShape)
            .then(background),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (filled) Color.White else SettingsTileColors.tonalGlyph(hue.color, dark),
            content = content
        )
    }
}

@Composable
internal fun SettingsIconTile(
    icon: ImageVector,
    hue: ScrollbarColor = LocalSettingsHue.current,
    filled: Boolean = false,
    hub: Boolean = filled
) {
    SettingsIconTile(hue, filled, hub) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(if (hub) 28.dp else 22.dp))
    }
}

/** Строка корня настроек: крупная залитая плитка цвета [hue], заголовок и пояснение, высота под палец. */
@Composable
internal fun SettingsHubRow(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    hue: ScrollbarColor,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = if (subtitle.isNullOrBlank()) null else ({
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }),
        leadingContent = { SettingsIconTile(icon, hue = hue, filled = true) },
        trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .heightIn(min = SettingsTokens.HubRowMinHeight)
            .clickable(role = Role.Button, onClickLabel = title, onClick = onClick)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberSettingsHighlight(id: String, highlight: String): Pair<Modifier, ListItemColors> {
    val on = highlight.isNotEmpty() && highlight == id
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(on, id) {
        if (on) requester.bringIntoView()
    }
    val modifier = if (on) Modifier.bringIntoViewRequester(requester) else Modifier
    val colors = ListItemDefaults.colors(
        containerColor = if (on) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            Color.Transparent
        }
    )
    return modifier to colors
}

/** Stable semantic accents for child rows; destructive/status icons keep their own colours. */
@Composable
private fun rowHue(id: String): ScrollbarColor = when (id) {
    "signature", "compose", "overview", "haptics" -> ScrollbarColor.ORANGE
    "remoteImages", "trustedSenders", "galPhotos", "colorTheme" -> ScrollbarColor.GREEN
    "quietHours", "quietStart", "quietEnd", "nightMode", "undoSend" -> ScrollbarColor.PURPLE
    "syncMode", "syncInterval", "autoUpdateCheck", "updates", "credentials" -> ScrollbarColor.BLUE
    "fontSize", "animations", "conversations" -> ScrollbarColor.PINK
    "failClosed", "changePassword", "fingerprint" -> ScrollbarColor.GREEN
    else -> LocalSettingsHue.current
}

/** Переключатель на всю строку (M3 Settings); свитч справа без своего onClick — иначе двойное срабатывание. */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    description: String? = null,
    icon: @Composable (() -> Unit)? = null,
    checked: Boolean,
    enabled: Boolean = true,
    highlightId: String = "",
    highlight: String = "",
    onChange: (Boolean) -> Unit
) {
    val (focusMod, rowColors) = rememberSettingsHighlight(highlightId, highlight)
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        supportingContent = if (description.isNullOrBlank()) null else ({
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }),
        leadingContent = icon?.let { { SettingsIconTile(hue = rowHue(highlightId), filled = true, hub = false, content = it) } },
        trailingContent = {
            Switch(checked = checked, enabled = enabled, onCheckedChange = null)
        },
        colors = rowColors,
        modifier = Modifier
            .heightIn(min = SettingsTokens.RowMinHeight)
            .then(focusMod)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onChange
            )
    )
}

/** Текущее значение справа (серым, в одну строку) — как в HyperOS. */
@Composable
private fun SettingsValue(value: String, color: Color) {
    Text(
        value,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 160.dp)
    )
}

/**
 * Строка списка настроек: переход, внешняя ссылка или действие справа. [value] — текущее значение справа
 * перед стрелкой.
 */
@Composable
internal fun SettingsNavRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    leading: @Composable (() -> Unit)? = null,
    value: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    trailingIcon: ImageVector? = AppIcons.ChevronRight,
    enabled: Boolean = true,
    titleColor: Color = Color.Unspecified,
    highlightId: String = "",
    highlight: String = "",
    onClick: (() -> Unit)? = null
) {
    val (focusMod, rowColors) = rememberSettingsHighlight(highlightId, highlight)
    val baseTitle = if (titleColor != Color.Unspecified) titleColor else MaterialTheme.colorScheme.onSurface
    val textColor = if (enabled) baseTitle else baseTitle.copy(alpha = 0.38f)
    val subColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall, color = textColor) },
        supportingContent = if (subtitle.isNullOrBlank()) null else ({
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = subColor)
        }),
        leadingContent = when {
            icon != null -> {
                // Как у разделов в корне: залитая плитка цвета строки и белый глиф, плитка поменьше
                { SettingsIconTile(icon, rowHue(highlightId), filled = true, hub = false) }
            }
            leading != null -> leading
            else -> null
        },
        trailingContent = when {
            trailing != null -> trailing
            value != null || (trailingIcon != null && enabled) -> {
                {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (value != null) SettingsValue(value, subColor)
                        if (trailingIcon != null && enabled) {
                            Icon(trailingIcon, contentDescription = null, tint = subColor)
                        }
                    }
                }
            }
            else -> null
        },
        colors = rowColors,
        modifier = Modifier
            .heightIn(min = SettingsTokens.RowMinHeight)
            .then(focusMod)
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(role = Role.Button, onClickLabel = title, onClick = onClick)
                } else {
                    Modifier
                }
            )
    )
}

/**
 * Выбор одного варианта: значение справа, варианты — в нижнем листе с образцом, названием и радиокнопкой (как цвет
 * скроллбара); внутри другого листа — списком у строки. [optionLeading] — образец у варианта (цвет темы, иконка),
 * [description] — пояснение в листе.
 */
@Composable
internal fun <T> SettingsChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    leading: @Composable (() -> Unit)? = null,
    optionLeading: @Composable ((T) -> Unit)? = null,
    description: String? = null,
    enabled: Boolean = true,
    highlightId: String = "",
    highlight: String = ""
) {
    var open by rememberSaveable { mutableStateOf(false) }
    // Внутри нижнего листа — список у строки: лист поверх листа даёт два затемнения и двойное «Назад»
    val dropdown = LocalInSettingsSheet.current
    Box(modifier = Modifier.fillMaxWidth()) {
        SettingsNavRow(
            title = title,
            subtitle = subtitle,
            icon = icon,
            leading = leading,
            value = label(selected),
            trailingIcon = if (dropdown) AppIcons.ExpandMore else AppIcons.ChevronRight,
            enabled = enabled,
            highlightId = highlightId,
            highlight = highlight,
            onClick = { open = true }
        )
        if (dropdown) {
            // Якорь у правого края строки: список открывается под значением, как в HyperOS
            Box(modifier = Modifier.align(Alignment.BottomEnd).padding(end = SettingsTokens.HorizontalPadding)) {
                DropdownMenu(
                    expanded = open && enabled,
                    onDismissRequest = { open = false }
                ) {
                    Column(modifier = Modifier.selectableGroup()) {
                        for (option in options) {
                            val isSelected = option == selected
                            DropdownMenuItem(
                                // Текст — onSurface: primary жёлтой темы на фоне меню не даёт 4,5:1; выбор отмечает галочка
                                text = { Text(label(option), color = MaterialTheme.colorScheme.onSurface) },
                                leadingIcon = optionLeading?.let { { it(option) } },
                                trailingIcon = if (isSelected) ({
                                    Icon(AppIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }) else null,
                                onClick = {
                                    open = false
                                    if (!isSelected) onSelect(option)
                                },
                                modifier = Modifier.semantics {
                                    this.selected = isSelected
                                    role = Role.RadioButton
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    if (!dropdown && open && enabled) {
        SettingsSheet(title = title, onDismiss = { open = false }) { close ->
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
            }
            Column(modifier = Modifier.selectableGroup()) {
                for (option in options) {
                    val isSelected = option == selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = {
                                    if (!isSelected) onSelect(option)
                                    close()
                                }
                            )
                            .padding(horizontal = 24.dp)
                    ) {
                        if (optionLeading != null) {
                            optionLeading(option)
                            Spacer(Modifier.size(16.dp))
                        }
                        Text(label(option), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        RadioButton(selected = isSelected, onClick = null)
                    }
                }
            }
        }
    }
}

/** true внутри [SettingsSheet]: выбор там открывается списком у строки, не вторым листом. */
internal val LocalInSettingsSheet = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Нижний лист настроек: заголовок и содержимое; [content] получает close — закрыть с анимацией. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = com.dedovmosol.iwomail.ui.components.rememberSafeScope()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SettingsTokens.cardColor,
        // Без тонального оттенка: лист того же цвета, что карточки
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
                    .semantics { heading() }
            )
            CompositionLocalProvider(LocalInSettingsSheet provides true) { content(close) }
            Spacer(Modifier.height(8.dp))
        }
    }
}
