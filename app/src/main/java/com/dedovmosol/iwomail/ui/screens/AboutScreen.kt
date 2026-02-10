package com.dedovmosol.iwomail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.BuildConfig
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.isRussian
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBackClick: () -> Unit,
    onNavigateToUpdates: () -> Unit,
    onNavigateToOnboarding: () -> Unit
) {
    val isRu = isRussian()
    val uriHandler = LocalUriHandler.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Strings.aboutApp, color = Color.White) },
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
            // Название и версия
            item {
                ListItem(
                    headlineContent = { Text("iwo Mail Client") },
                    supportingContent = { Text("${Strings.version} ${BuildConfig.VERSION_NAME}") },
                    leadingContent = { Icon(AppIcons.Info, null) }
                )
            }
            
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            
            // Обновления
            item {
                ListItem(
                    headlineContent = { Text(if (isRu) "Обновления" else "Updates") },
                    supportingContent = { Text(if (isRu) "Проверить наличие обновлений" else "Check for updates") },
                    leadingContent = { Icon(AppIcons.Update, null) },
                    trailingContent = { Icon(AppIcons.ChevronRight, null) },
                    modifier = Modifier.clickable { onNavigateToUpdates() }
                )
            }
            
            // Обзор (онбординг)
            item {
                ListItem(
                    headlineContent = { Text(if (isRu) "Обзор" else "Overview") },
                    supportingContent = { Text(if (isRu) "Краткий обзор приложения" else "Quick app overview") },
                    leadingContent = { Icon(AppIcons.Lightbulb, null) },
                    modifier = Modifier.clickable { onNavigateToOnboarding() }
                )
            }
            
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            
            // Разработчик
            item {
                ListItem(
                    headlineContent = { Text(Strings.developer) },
                    supportingContent = { Text("DedovMosol") },
                    leadingContent = { Icon(AppIcons.Person, null) },
                    trailingContent = { Icon(AppIcons.OpenInNew, null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://github.com/DedovMosol/")
                    }
                )
            }
            
            // Поддерживаемые протоколы
            item {
                ListItem(
                    headlineContent = { Text(Strings.supportedProtocols) },
                    supportingContent = { Text("Exchange (EAS), IMAP, POP3") },
                    leadingContent = { Icon(AppIcons.Business, null) }
                )
            }
            
            // Политика конфиденциальности
            item {
                ListItem(
                    headlineContent = { Text(Strings.privacyPolicy) },
                    leadingContent = { Icon(AppIcons.Policy, null) },
                    trailingContent = { Icon(AppIcons.OpenInNew, null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://github.com/DedovMosol/IwoMailClient/blob/main/PRIVACY_POLICY.md")
                    }
                )
            }
        }
    }
}
