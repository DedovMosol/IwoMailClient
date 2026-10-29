package com.dedovmosol.iwomail.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dedovmosol.iwomail.sync.BackgroundWork
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.AppIcons

/**
 * Показать диалог «Фоновая работа» (MainActivity: он над всеми экранами). null — вне MainActivity (превью, тесты):
 * баннер тогда не показывается — открыть нечего.
 */
val LocalRequestBackgroundWork = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * Разрешена ли работа в фоне — заново при каждом возвращении на экран (ON_RESUME): пользователь разрешает её в
 * системных настройках, вне приложения. Проверка — один вызов PowerManager, без опроса.
 */
@Composable
fun rememberBackgroundWorkAllowed(): Boolean {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(BackgroundWork.isAllowed(context)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed = BackgroundWork.isAllowed(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return allowed
}

/** Карточка на главной: фоновая работа не разрешена — новые письма приходят с опозданием. Нажатие — тот же диалог. */
@Composable
fun BackgroundWorkBanner(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = Strings.continueAction, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)),
        shape = MaterialTheme.shapes.large
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                AppIcons.BatteryChargingFull,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = Strings.backgroundWorkBannerTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = Strings.backgroundWorkBannerText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}
