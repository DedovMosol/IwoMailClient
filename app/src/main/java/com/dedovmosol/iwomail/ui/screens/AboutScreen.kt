package com.dedovmosol.iwomail.ui.screens

import android.app.Activity
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dedovmosol.iwomail.BuildConfig
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.components.EasterEggPlayer
import com.dedovmosol.iwomail.ui.components.EasterEggOverlay
import com.dedovmosol.iwomail.ui.components.isEasterEggFound
import com.dedovmosol.iwomail.ui.isRussian
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.components.ScrollColumnScrollbar
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme

private fun ContextWrapper.findActivity(): Activity? {
    var current: android.content.Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AboutScreen(
    onBackClick: () -> Unit,
    onNavigateToUpdates: () -> Unit,
    onNavigateToOnboarding: () -> Unit
) {
    val isRu = isRussian()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val easterFound = remember { isEasterEggFound(context) }
    var showEasterEgg by rememberSaveable { mutableStateOf(false) }

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
        },
    ) { padding ->
        val scrollState = rememberScrollState()
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val minH = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = minH)
                    .verticalScroll(scrollState)
            ) {
                // Обзор (онбординг)
                ListItem(
                    headlineContent = { Text(if (isRu) "Обзор" else "Overview") },
                    supportingContent = { Text(if (isRu) "Краткий обзор приложения" else "Quick app overview") },
                    leadingContent = { Icon(AppIcons.Lightbulb, null) },
                    modifier = Modifier.clickable { onNavigateToOnboarding() }
                )
                
                // Обновление ПО
                ListItem(
                    headlineContent = { Text(if (isRu) "Обновление ПО" else "Software update") },
                    supportingContent = { Text("${Strings.version} ${BuildConfig.VERSION_NAME}") },
                    leadingContent = { Icon(AppIcons.Update, null) },
                    trailingContent = { Icon(AppIcons.ChevronRight, null) },
                    modifier = Modifier.clickable { onNavigateToUpdates() }
                )
                
                // Поддерживаемые протоколы
                ListItem(
                    headlineContent = { Text(Strings.supportedProtocols) },
                    supportingContent = { Text("Exchange (EAS), IMAP, POP3") },
                    leadingContent = { Icon(AppIcons.Business, null) }
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                // Разработчик
                ListItem(
                    headlineContent = { Text(Strings.developer) },
                    supportingContent = { Text("DedovMosol") },
                    leadingContent = { Icon(AppIcons.Person, null) },
                    trailingContent = { Icon(AppIcons.OpenInNew, null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://github.com/DedovMosol/")
                    }
                )
                
                // Политика конфиденциальности
                ListItem(
                    headlineContent = { Text(Strings.privacyPolicy) },
                    leadingContent = { Icon(AppIcons.Policy, null) },
                    trailingContent = { Icon(AppIcons.OpenInNew, null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://github.com/DedovMosol/IwoMailClient/blob/main/docs/PRIVACY_POLICY.md")
                    }
                )
                
                Spacer(modifier = Modifier.weight(1f))
                
                // Footer: гитара (если нашёл) или Base64 подсказка
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (easterFound) {
                        // Анимация покачивания гитары
                        val infiniteTransition = rememberInfiniteTransition(label = "guitar_wobble")
                        val guitarRotation by infiniteTransition.animateFloat(
                            initialValue = -8f,
                            targetValue = 8f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(600, easing = EaseInOutSine),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "guitar_rotation"
                        )
                        // Кликабельная гитара с анимацией покачивания
                        Text(
                            text = "\uD83C\uDFB8",
                            fontSize = 36.sp,
                            modifier = Modifier
                                .rotate(guitarRotation)
                                .clickable { showEasterEgg = true }
                        )
                    } else {
                        // Base64 подсказка — копируемая
                        val base64Text = "SSB3YW50IG91dCwgdG8gbGl2ZSBteSBsaWZlIGFuZCB0byBiZSBmcmVl"
                        Text(
                            text = base64Text,
                            fontSize = 8.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    clipboardManager.setText(AnnotatedString(base64Text))
                                    Toast.makeText(
                                        context,
                                        if (isRu) "Скопировано" else "Copied",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        )
                    }
                }
            }
            ScrollColumnScrollbar(scrollState)
        }
    }
    
    // Останавливаем музыку при уходе с экрана (свайп назад / навигация)
    DisposableEffect(Unit) {
        onDispose {
            val activity = (context as? ContextWrapper)?.findActivity()
            val isChanging = activity?.isChangingConfigurations == true
            // Если activity недоступен — безопаснее считать это поворотом, чем ложно остановить музыку
            val isRotation = isChanging || activity == null || EasterEggPlayer.isRecentConfigChange()
            if (isChanging) EasterEggPlayer.markConfigChanged()
            if (!isRotation && EasterEggPlayer.isPlaying) {
                EasterEggPlayer.stop()
            }
        }
    }

    // Easter egg overlay
    EasterEggOverlay(
        visible = showEasterEgg || EasterEggPlayer.isPlaying,
        onDismiss = { showEasterEgg = false }
    )
}
