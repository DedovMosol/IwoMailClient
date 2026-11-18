package com.dedovmosol.iwomail.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.domain.SwipeAction
import com.dedovmosol.iwomail.domain.UndoSend
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.AppIcons
import kotlinx.coroutines.launch

/** Настройки почты: картинки из интернета, фото из адресной книги, цепочки, отмена отправки, свайпы в списке писем. */
@Composable
internal fun MailSettingsSection(settingsRepo: SettingsRepository, highlight: String = "") {
    val scope = com.dedovmosol.iwomail.ui.components.rememberSafeScope()
    val images by settingsRepo.remoteImagesPrefs.collectAsStateWithLifecycle(initialValue = null)
    val undoSeconds by settingsRepo.undoSendSeconds.collectAsStateWithLifecycle(initialValue = UndoSend.DEFAULT_SECONDS)
    val swipes by settingsRepo.swipeActions.collectAsStateWithLifecycle(
        initialValue = SwipeAction.DEFAULT_START to SwipeAction.DEFAULT_END
    )
    val galPhotos by settingsRepo.galPhotosEnabled.collectAsStateWithLifecycle(initialValue = null)
    val conversations by settingsRepo.conversationView.collectAsStateWithLifecycle(initialValue = null)

    Column {
        SettingsSwitchRow(
            title = Strings.remoteImagesSetting,
            description = Strings.remoteImagesSettingDesc,
            icon = { Icon(AppIcons.Image, null) },
            checked = images?.alwaysLoad == true,
            enabled = images != null,
            highlightId = "remoteImages",
            highlight = highlight,
            onChange = { always -> scope.launch { settingsRepo.setRemoteImagesAlways(always) } }
        )
        SettingsSwitchRow(
            title = Strings.galPhotosSetting,
            description = Strings.galPhotosSettingDesc,
            icon = { Icon(AppIcons.Person, null) },
            checked = galPhotos == true,
            enabled = galPhotos != null,
            highlightId = "galPhotos",
            highlight = highlight,
            onChange = { on -> scope.launch { settingsRepo.setGalPhotosEnabled(on) } }
        )
        SettingsSwitchRow(
            title = Strings.conversationViewSetting,
            description = Strings.conversationViewSettingDesc,
            icon = { Icon(AppIcons.Reply, null) },
            checked = conversations == true,
            enabled = conversations != null,
            highlightId = "conversations",
            highlight = highlight,
            onChange = { on -> scope.launch { settingsRepo.setConversationView(on) } }
        )

        val trusted = images?.trustedSenders.orEmpty()
        if (trusted.isNotEmpty()) {
            SettingsNavRow(
                title = Strings.trustedImageSenders(trusted.size),
                trailingIcon = null,
                trailing = {
                    TextButton(onClick = { scope.launch { settingsRepo.clearTrustedImageSenders() } }) {
                        Text(Strings.reset)
                    }
                }
            )
        }

        SettingsChoiceRow(
            title = Strings.undoSendSetting,
            options = UndoSend.OPTIONS,
            selected = undoSeconds,
            label = { Strings.secondsOption(it) },
            onSelect = { seconds -> scope.launch { settingsRepo.setUndoSendSeconds(seconds) } },
            icon = AppIcons.Send,
            highlightId = "undoSend",
            highlight = highlight
        )
        SettingsChoiceRow(
            title = Strings.swipeStart,
            options = SwipeAction.entries,
            selected = swipes.first,
            label = { Strings.swipeActionName(it) },
            onSelect = { action -> scope.launch { settingsRepo.setSwipeAction(true, action) } },
            highlightId = "swipeStart",
            highlight = highlight
        )
        SettingsChoiceRow(
            title = Strings.swipeEnd,
            options = SwipeAction.entries,
            selected = swipes.second,
            label = { Strings.swipeActionName(it) },
            onSelect = { action -> scope.launch { settingsRepo.setSwipeAction(false, action) } },
            highlightId = "swipeEnd",
            highlight = highlight
        )
    }
}
