package com.dedovmosol.iwomail.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.domain.SwipeAction
import com.dedovmosol.iwomail.domain.UndoSend
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.AppIcons
import kotlinx.coroutines.launch

/** Настройки почты: картинки из интернета, фото из адресной книги, цепочки, отмена отправки, свайпы в списке писем. */
@Composable
internal fun MailSettingsSection(settingsRepo: SettingsRepository) {
    val scope = com.dedovmosol.iwomail.ui.components.rememberSafeScope()
    val images by settingsRepo.remoteImagesPrefs.collectAsStateWithLifecycle(initialValue = null)
    val undoSeconds by settingsRepo.undoSendSeconds.collectAsStateWithLifecycle(initialValue = UndoSend.DEFAULT_SECONDS)
    val swipes by settingsRepo.swipeActions.collectAsStateWithLifecycle(
        initialValue = SwipeAction.DEFAULT_START to SwipeAction.DEFAULT_END
    )
    val galPhotos by settingsRepo.galPhotosEnabled.collectAsStateWithLifecycle(initialValue = null)
    val conversations by settingsRepo.conversationView.collectAsStateWithLifecycle(initialValue = null)
    // Открытый выбор: "undo", "start", "end" (переживает поворот)
    var choice by rememberSaveable { mutableStateOf<String?>(null) }

    Column {
        Text(
            text = Strings.mailSection,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(16.dp)
        )

        ListItem(
            headlineContent = { Text(Strings.remoteImagesSetting) },
            supportingContent = { Text(Strings.remoteImagesSettingDesc) },
            leadingContent = { Icon(AppIcons.Image, null) },
            trailingContent = {
                Switch(
                    checked = images?.alwaysLoad == true,
                    enabled = images != null,
                    onCheckedChange = { always -> scope.launch { settingsRepo.setRemoteImagesAlways(always) } }
                )
            }
        )
        SwitchSetting(
            title = Strings.galPhotosSetting,
            description = Strings.galPhotosSettingDesc,
            icon = { Icon(AppIcons.Person, null) },
            checked = galPhotos,
            onChange = { on -> scope.launch { settingsRepo.setGalPhotosEnabled(on) } }
        )
        SwitchSetting(
            title = Strings.conversationViewSetting,
            description = Strings.conversationViewSettingDesc,
            icon = { Icon(AppIcons.Reply, null) },
            checked = conversations,
            onChange = { on -> scope.launch { settingsRepo.setConversationView(on) } }
        )

        val trusted = images?.trustedSenders.orEmpty()
        if (trusted.isNotEmpty()) {
            ListItem(
                headlineContent = { Text(Strings.trustedImageSenders(trusted.size)) },
                trailingContent = {
                    TextButton(onClick = { scope.launch { settingsRepo.clearTrustedImageSenders() } }) { Text(Strings.reset) }
                }
            )
        }

        ListItem(
            headlineContent = { Text(Strings.undoSendSetting) },
            supportingContent = { Text(Strings.undoSendDesc(undoSeconds)) },
            leadingContent = { Icon(AppIcons.Send, null) },
            modifier = Modifier.clickable { choice = "undo" }
        )

        ListItem(
            headlineContent = { Text(Strings.swipeSetting) },
            supportingContent = {
                Column {
                    Text("${Strings.swipeStart}: ${Strings.swipeActionName(swipes.first)}")
                    Text("${Strings.swipeEnd}: ${Strings.swipeActionName(swipes.second)}")
                }
            },
            leadingContent = { Icon(AppIcons.SwapHoriz, null) }
        )
        Row(modifier = Modifier.fillMaxWidth().padding(start = 56.dp)) {
            TextButton(onClick = { choice = "start" }) { Text(Strings.swipeStart) }
            TextButton(onClick = { choice = "end" }) { Text(Strings.swipeEnd) }
        }
    }

    when (choice) {
        "undo" -> ChoiceDialog(
            title = Strings.undoSendSetting,
            options = UndoSend.OPTIONS,
            selected = undoSeconds,
            label = { Strings.secondsOption(it) },
            onDismiss = { choice = null },
            onSelect = { seconds -> choice = null; scope.launch { settingsRepo.setUndoSendSeconds(seconds) } }
        )
        "start", "end" -> {
            val start = choice == "start"
            ChoiceDialog(
                title = if (start) Strings.swipeStart else Strings.swipeEnd,
                options = SwipeAction.entries,
                selected = if (start) swipes.first else swipes.second,
                label = { Strings.swipeActionName(it) },
                onDismiss = { choice = null },
                onSelect = { action -> choice = null; scope.launch { settingsRepo.setSwipeAction(start, action) } }
            )
        }
    }
}

/** Настройка-переключатель: нажатие на всю строку; [checked] null — значение ещё не прочитано. */
@Composable
private fun SwitchSetting(
    title: String,
    description: String,
    icon: @Composable () -> Unit,
    checked: Boolean?,
    onChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        leadingContent = icon,
        trailingContent = { Switch(checked = checked == true, enabled = checked != null, onCheckedChange = null) },
        modifier = Modifier.toggleable(
            value = checked == true,
            enabled = checked != null,
            role = Role.Switch,
            onValueChange = onChange
        )
    )
}

/** Выбор одного варианта из списка (радиокнопки). */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit
) {
    com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                for (option in options) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) })
                            .padding(vertical = 8.dp)
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(label(option), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(onClick = onDismiss, text = Strings.cancel)
        }
    )
}
