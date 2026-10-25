package com.dedovmosol.iwomail.ui.screens.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.dedovmosol.iwomail.data.repository.CategoryColorRepository
import com.dedovmosol.iwomail.data.repository.CategoryColorSource
import com.dedovmosol.iwomail.data.repository.CategoryColors
import com.dedovmosol.iwomail.data.repository.CategoryRefreshResult
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.util.CalendarCategories
import com.dedovmosol.iwomail.util.OutlookCategoryPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Цвет палитры [MS-OXOCFG] для UI; null — «без цвета». */
internal fun categoryUiColor(color: Int): Color? = OutlookCategoryPalette.argb(color)?.let { Color(it) }

/** Цвет первой окрашенной категории события — для подсветки карточки и точки в месяце. */
internal fun primaryCategoryColor(categories: List<String>, colors: CategoryColors): Color? =
    categories.firstNotNullOfOrNull { categoryUiColor(colors.resolve(it).color) }

/** Читаемый текст на фоне цвета категории (палитра содержит и светлые, и тёмные цвета). */
private fun contentOn(background: Color): Color = if (background.luminance() > 0.5f) Color.Black else Color.White

/** Плашка категории: фон — цвет категории; без цвета — контур (как у Outlook). */
@Composable
internal fun CategoryChip(name: String, color: Color?, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color ?: Color.Transparent,
        border = if (color == null) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        modifier = modifier
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = color?.let { contentOn(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CategoryChips(categories: List<String>, colors: CategoryColors, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        categories.forEach { name -> CategoryChip(name, categoryUiColor(colors.resolve(name).color)) }
    }
}

/** Строка категорий в карточке события: «● Проект  ● Важно», одной строкой с многоточием. */
@Composable
internal fun CategoryInlineSummary(categories: List<String>, colors: CategoryColors, textColor: Color) {
    val noColor = MaterialTheme.colorScheme.outline
    val text = remember(categories, colors, noColor) {
        buildAnnotatedString {
            categories.forEachIndexed { index, name ->
                if (index > 0) append("   ")
                val color = categoryUiColor(colors.resolve(name).color)
                withStyle(SpanStyle(color = color ?: noColor)) { append(if (color != null) "\u25CF " else "\u25CB ") }
                append(name)
            }
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = textColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** Кружок цвета палитры; «без цвета» — контур с диагональю. */
@Composable
private fun ColorSwatch(color: Int, size: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    val fill = categoryUiColor(color)
    val outline = MaterialTheme.colorScheme.outline
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (fill != null) Modifier.background(fill)
                else Modifier
                    .border(1.dp, outline, CircleShape)
                    .drawBehind {
                        drawLine(
                            color = outline,
                            start = Offset(this.size.width * 0.25f, this.size.height * 0.75f),
                            end = Offset(this.size.width * 0.75f, this.size.height * 0.25f),
                            strokeWidth = 1.5.dp.toPx()
                        )
                    }
            )
    ) { content() }
}

@Composable
private fun sourceLabel(source: CategoryColorSource): String = when (source) {
    CategoryColorSource.USER -> Strings.categorySourceUser
    CategoryColorSource.SERVER -> Strings.categorySourceServer
    CategoryColorSource.NOT_IN_SERVER_LIST -> Strings.categorySourceNotInList
    CategoryColorSource.PRESET -> Strings.categorySourcePreset
    CategoryColorSource.AUTO -> Strings.categorySourceAuto
}

/**
 * Экран «Цвета категорий»: категории аккаунта (из списка Outlook и из событий) с цветом и его
 * источником; нажатие — выбор своего цвета. При открытии цвета обновляются с сервера (Exchange 2010+).
 */
@Composable
internal fun CategoryColorsDialog(
    accountId: Long,
    usedCategories: List<String>,
    colors: CategoryColors,
    repository: CategoryColorRepository,
    onDismiss: () -> Unit
) {
    val scope = com.dedovmosol.iwomail.ui.components.rememberSafeScope()
    var refreshGeneration by rememberSaveable { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(true) }
    var refreshResult by remember { mutableStateOf<CategoryRefreshResult?>(null) }
    var editingName by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(accountId, refreshGeneration) {
        refreshing = true
        refreshResult = try {
            repository.refresh(accountId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            CategoryRefreshResult.FAILED
        }
        refreshing = false
    }

    val names = remember(colors, usedCategories) { colors.knownCategories(usedCategories) }

    com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
        onDismissRequest = onDismiss,
        scrollable = false,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .padding(vertical = 8.dp),
        icon = { Icon(AppIcons.Palette, null) },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Strings.categoryColors, modifier = Modifier.weight(1f))
                if (refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { refreshGeneration++ }) {
                        Icon(AppIcons.Refresh, Strings.refresh)
                    }
                }
            }
        },
        text = {
            Column {
                when (refreshResult) {
                    CategoryRefreshResult.NOT_SUPPORTED -> Text(
                        Strings.categoryColorsNoServerList,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    CategoryRefreshResult.FAILED -> Text(
                        Strings.categoryColorsRefreshFailed,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    else -> Unit
                }
                if (names.isEmpty()) {
                    if (!refreshing) {
                        Text(
                            Strings.categoryColorsEmpty,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(names, key = { CalendarCategories.key(it) }) { name ->
                            val resolved = colors.resolve(name)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(role = Role.Button) { editingName = name }
                                    .padding(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                ColorSwatch(resolved.color, 28.dp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        sourceLabel(resolved.source),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    AppIcons.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(onClick = onDismiss, text = Strings.close)
        }
    )

    editingName?.let { name ->
        CategoryColorPickerDialog(
            name = name,
            current = colors.resolve(name).color,
            canReset = colors.hasUserColor(name),
            onPick = { color ->
                editingName = null
                scope.launch { repository.setUserColor(accountId, name, color) }
            },
            onReset = {
                editingName = null
                scope.launch { repository.clearUserColor(accountId, name) }
            },
            onDismiss = { editingName = null }
        )
    }
}

/** Выбор цвета категории: палитра Outlook из 25 цветов и «без цвета»; выбор применяется сразу. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryColorPickerDialog(
    name: String,
    current: Int,
    canReset: Boolean,
    onPick: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val colorNames = Strings.categoryColorNames
    com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(Strings.chooseCategoryColor, style = MaterialTheme.typography.titleMedium)
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlookCategoryPalette.ALL.forEach { color ->
                    val isCurrent = color == current
                    val label = colorNames[color + 1]
                    ColorSwatch(
                        color = color,
                        size = 40.dp,
                        modifier = Modifier
                            .then(
                                if (isCurrent) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                else Modifier
                            )
                            .semantics {
                                contentDescription = label
                                selected = isCurrent
                            }
                            .clickable(role = Role.RadioButton) { onPick(color) }
                    ) {
                        if (isCurrent) {
                            Icon(
                                AppIcons.Check,
                                contentDescription = null,
                                tint = categoryUiColor(color)?.let { contentOn(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(onClick = onDismiss, text = Strings.cancel)
        },
        dismissButton = if (canReset) {
            { com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(onClick = onReset, text = Strings.resetToServerColor) }
        } else null
    )
}
