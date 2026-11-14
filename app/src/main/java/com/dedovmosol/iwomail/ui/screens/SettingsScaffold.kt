package com.dedovmosol.iwomail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.components.LazyColumnScrollbar
import com.dedovmosol.iwomail.ui.components.ScrollColumnScrollbar
import com.dedovmosol.iwomail.ui.components.ScrollColumnScrollbar
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme

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

/** Общий каркас экрана настроек (корень и разделы): градиентный app bar, список, скроллбар. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPageScaffold(
    title: String,
    onBackClick: () -> Unit,
    content: LazyListScope.() -> Unit
) {
    Scaffold(
        topBar = { SettingsTopBar(title, onBackClick) }
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
                contentPadding = PaddingValues(vertical = 12.dp),
                content = content
            )
            LazyColumnScrollbar(listState = listState)
        }
    }
}

/**
 * Тот же app bar, но Column+скролл: нужен экрану «О приложении»,
 * где подвал (пасхалка) прижат вниз через [Modifier.weight].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScrollScaffold(
    title: String,
    onBackClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        topBar = { SettingsTopBar(title, onBackClick) }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val minH = maxHeight
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = minH)
                    .verticalScroll(scrollState)
                    .padding(vertical = 12.dp),
                content = content
            )
            ScrollColumnScrollbar(scrollState)
        }
    }
}

/** Группа строк на корне настроек: карточка, не одна простыня. */
@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsLeadingIcon(icon: ImageVector, boxSize: Dp = 48.dp) {
    Box(
        modifier = Modifier
            .size(boxSize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/** Строка корня настроек: крупнее заголовок, иконка в круге, высота под палец. */
@Composable
internal fun SettingsHubRow(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    onClick: () -> Unit,
    showDivider: Boolean = false
) {
    Column {
        ListItem(
            headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
            supportingContent = if (subtitle.isNullOrBlank()) null else ({
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            }),
            leadingContent = { SettingsLeadingIcon(icon) },
            trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .heightIn(min = 72.dp)
                .clickable(role = Role.Button, onClickLabel = title, onClick = onClick)
        )
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 80.dp, end = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

/** Переключатель на всю строку (M3 Settings); свитч справа без своего onClick — иначе двойное срабатывание. */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    description: String? = null,
    icon: @Composable (() -> Unit)? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        supportingContent = if (description.isNullOrBlank()) null else ({
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }),
        leadingContent = icon?.let {
            {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) { it() }
            }
        },
        trailingContent = {
            Switch(checked = checked, enabled = enabled, onCheckedChange = null)
        },
        modifier = Modifier
            .heightIn(min = 64.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onChange
            )
    )
}

/** Строка списка настроек: переход, внешняя ссылка или действие справа. */
@Composable
internal fun SettingsNavRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    trailingIcon: ImageVector? = AppIcons.ChevronRight,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val textColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
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
                { SettingsLeadingIcon(icon, boxSize = 40.dp) }
            }
            leading != null -> leading
            else -> null
        },
        trailingContent = when {
            trailing != null -> trailing
            trailingIcon != null -> {
                { Icon(trailingIcon, contentDescription = null, tint = textColor) }
            }
            else -> null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .heightIn(min = 64.dp)
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(role = Role.Button, onClickLabel = title, onClick = onClick)
                } else {
                    Modifier
                }
            )
    )
}
