package com.dedovmosol.iwomail.ui.screens

import com.dedovmosol.iwomail.ui.utils.findActivity
import com.dedovmosol.iwomail.util.SafeToast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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
import com.dedovmosol.iwomail.ui.components.isEasterEggHintRevealed
import com.dedovmosol.iwomail.ui.components.setEasterEggHintRevealed
import com.dedovmosol.iwomail.ui.isRussian
import com.dedovmosol.iwomail.ui.theme.AppIcons

@OptIn(ExperimentalFoundationApi::class)
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
    val persistedHintRevealed = remember { isEasterEggHintRevealed(context) }
    var showEasterEgg by rememberSaveable { mutableStateOf(false) }
    var protocolTapCount by rememberSaveable { mutableIntStateOf(0) }
    var base64Revealed by rememberSaveable { mutableStateOf(persistedHintRevealed) }

    SettingsScrollScaffold(title = Strings.aboutApp, onBackClick = onBackClick) {
        SettingsGroup {
            SettingsNavRow(
                title = if (isRu) "Обзор" else "Overview",
                subtitle = if (isRu) "Краткий обзор приложения" else "Quick app overview",
                icon = AppIcons.Lightbulb,
                onClick = onNavigateToOnboarding
            )
            SettingsNavRow(
                title = if (isRu) "Обновление ПО" else "Software update",
                subtitle = "${Strings.version} ${BuildConfig.VERSION_NAME}",
                icon = AppIcons.Update,
                onClick = onNavigateToUpdates
            )
            SettingsNavRow(
                title = Strings.supportedProtocols,
                subtitle = "Exchange (EAS), IMAP, POP3",
                icon = AppIcons.Business,
                trailingIcon = null,
                onClick = {
                    if (!easterFound && !base64Revealed) {
                        protocolTapCount++
                        val remaining = 7 - protocolTapCount
                        if (remaining in 1..3) {
                            SafeToast.short(context, if (isRu) "iwo — подсказка близко" else "iwo — hint is near")
                        }
                        if (protocolTapCount >= 7) {
                            base64Revealed = true
                            setEasterEggHintRevealed(context, true)
                        }
                    }
                }
            )
        }
        SettingsGroup {
            SettingsNavRow(
                title = Strings.developer,
                subtitle = "DedovMosol",
                icon = AppIcons.Person,
                trailingIcon = AppIcons.OpenInNew,
                onClick = { uriHandler.openUri("https://github.com/DedovMosol/") }
            )
            SettingsNavRow(
                title = Strings.privacyPolicy,
                icon = AppIcons.Policy,
                trailingIcon = AppIcons.OpenInNew,
                onClick = { uriHandler.openUri(com.dedovmosol.iwomail.ui.PrivacyPolicy.url(isRu)) }
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val guitarRotation = com.dedovmosol.iwomail.ui.utils.rememberWobble(
                enabled = easterFound, amplitude = 8f, durationMs = 600
            )
            if (easterFound) {
                Text(
                    text = "🎸",
                    fontSize = 36.sp,
                    modifier = Modifier
                        .rotate(guitarRotation)
                        .clickable { showEasterEgg = true }
                )
            } else if (base64Revealed) {
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
                            SafeToast.short(context, if (isRu) "Скопировано" else "Copied")
                        }
                    )
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val activity = context.findActivity()
            val isChanging = activity?.isChangingConfigurations == true
            val isRotation = isChanging || activity == null || EasterEggPlayer.isRecentConfigChange()
            if (isChanging) EasterEggPlayer.markConfigChanged()
            if (!isRotation && EasterEggPlayer.isPlaying) {
                EasterEggPlayer.stop()
            }
        }
    }

    EasterEggOverlay(
        visible = showEasterEgg || EasterEggPlayer.isPlaying,
        onDismiss = { showEasterEgg = false }
    )
}
