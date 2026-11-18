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
import androidx.compose.material3.LargeTopAppBar
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.components.LazyColumnScrollbar
import com.dedovmosol.iwomail.ui.components.ScrollColumnScrollbar
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
import kotlinx.coroutines.launch

/**
 * Настройки в стиле HyperOS (SET-24, вариант А): крупный заголовок на градиенте темы сворачивается при прокрутке
 * в обычную панель; светло-серая страница и белые карточки без разделителей (в тёмной теме — чёрная страница и
 * тёмно-серые карточки); значение справа со стрелкой; короткий выбор — выпадающим списком у строки, длинный —
 * нижним листом; иконки одной формы ([SettingsIconTile]). Всё на ролях Material 3 и цветах [LocalColorTheme].
 */
internal object SettingsTokens {
    val CardShape = RoundedCornerShape(20.dp)
    val TileShape = RoundedCornerShape(12.dp)
    val TileSize = 40.dp
    val HorizontalPadding = 16.dp
    val RowMinHeight = 64.dp
    val HubRowMinHeight = 72.dp
    /** До стольких вариантов — выпадающий список у строки, больше — нижний лист. */
    const val DROPDOWN_MAX_OPTIONS = 5

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

/** Маленькая градиентная панель — для экранов вне настроек (обновления). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTopBar(title: String, onBackClick: () -> Unit) {
    TopAppBar(
        title = { Text(title, color = Color.White) },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(AppIcons.ArrowBack, Strings.back, tint = Color.White)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        modifier = Modifier.background(themeGradient())
    )
}

@Composable
private fun themeGradient(): Brush = Brush.horizontalGradient(
    colors = listOf(LocalColorTheme.current.gradientStart, LocalColorTheme.current.gradientEnd)
)

/** Крупный заголовок на градиенте темы; при прокрутке сворачивается в панель обычной высоты. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsLargeTopBar(title: String, onBackClick: () -> Unit, scrollBehavior: TopAppBarScrollBehavior) {
    LargeTopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(AppIcons.ArrowBack, Strings.back)
            }
        },
        colors = TopAppBarDefaults.largeTopAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
            navigationIconContentColor = Color.White,
            titleContentColor = Color.White,
            actionIconContentColor = Color.White
        ),
        scrollBehavior = scrollBehavior,
        modifier = Modifier
            .background(themeGradient())
            .testTag(SETTINGS_TOP_BAR_TAG)
    )
}

internal const val SETTINGS_TOP_BAR_TAG = "settings_top_bar"

/** Общий каркас экрана настроек (корень и разделы): крупный заголовок, список, скроллбар. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPageScaffold(
    title: String,
    onBackClick: () -> Unit,
    content: LazyListScope.() -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { SettingsLargeTopBar(title, onBackClick, scrollBehavior) },
        containerColor = SettingsTokens.pageColor
    ) { padding ->
        val listState = rememberLazyListState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                content = content
            )
            LazyColumnScrollbar(listState = listState)
        }
    }
}

/**
 * Тот же заголовок и скролл, что у разделов настроек.
 * [footer] рисуется внизу экрана (пасхалка в «О приложении»): weight внутри
 * verticalScroll не заполняет высоту, у скролла maxHeight = Infinity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScrollScaffold(
    title: String,
    onBackClick: () -> Unit,
    footer: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { SettingsLargeTopBar(title, onBackClick, scrollBehavior) },
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(top = 8.dp, bottom = with(density) { footerPx.toDp() })
            ) {
                content()
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
        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 16.dp, bottom = 4.dp)
    )
}

/** Пояснение под карточкой группы. */
@Composable
internal fun SettingsGroupHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 2.dp, bottom = 6.dp)
    )
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
        Column(modifier = Modifier.padding(vertical = 4.dp), content = content)
    }
}

/**
 * Плитка иконки — одна форма и один размер во всех настройках. [accent]: на корне — градиент темы и белый глиф,
 * в разделах — тональная плитка.
 */
@Composable
internal fun SettingsIconTile(accent: Boolean = false, content: @Composable () -> Unit) {
    val background = if (accent) {
        Modifier.background(themeGradient())
    } else {
        Modifier.background(MaterialTheme.colorScheme.primaryContainer)
    }
    Box(
        modifier = Modifier
            .size(SettingsTokens.TileSize)
            .clip(SettingsTokens.TileShape)
            .then(background),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (accent) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
            content = content
        )
    }
}

@Composable
internal fun SettingsIconTile(icon: ImageVector, accent: Boolean = false) {
    SettingsIconTile(accent) { Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp)) }
}

/** Строка корня настроек: крупнее заголовок, градиентная плитка, высота под палец. */
@Composable
internal fun SettingsHubRow(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = if (subtitle.isNullOrBlank()) null else ({
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }),
        leadingContent = { SettingsIconTile(icon, accent = true) },
        trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .heightIn(min = SettingsTokens.HubRowMinHeight)
            .clickable(role = Role.Button, onClickLabel = title, onClick = onClick)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberSettingsHighlight(id: String, highlight: String): Pair<Modifier, ListItemColors> {
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
        leadingContent = icon?.let { { SettingsIconTile(content = it) } },
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
                { SettingsIconTile(icon) }
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
 * Выбор одного варианта (вместо диалога с радиокнопками): значение справа; до
 * [SettingsTokens.DROPDOWN_MAX_OPTIONS] вариантов — выпадающий список у строки, больше — нижний лист.
 * [optionLeading] — образец у варианта (цвет темы), [description] — пояснение в нижнем листе.
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
    val dropdown = options.size <= SettingsTokens.DROPDOWN_MAX_OPTIONS
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
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
            )
            content(close)
            Spacer(Modifier.height(8.dp))
        }
    }
}
