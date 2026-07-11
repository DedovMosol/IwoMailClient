package com.dedovmosol.iwomail.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.theme.DeleteButton
import com.dedovmosol.iwomail.ui.theme.StyledAlertDialog
import com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton

/**
 * Единый диалог подтверждения очистки корзины (задачи/заметки/календарь).
 *
 * DRY: три экрана делили идентичный каркас `StyledAlertDialog` с иконкой `DeleteForever`,
 * кнопками «Да» (DeleteButton) / «Нет» (ThemeOutlinedButton) — различались только
 * заголовком, текстом и действием подтверждения. Специфика (deletionController vs
 * прямой repo-вызов, прогресс, тосты) остаётся в [onConfirm] у каждого экрана.
 *
 * @param title заголовок (уже разрешённая строка, напр. `Strings.emptyTasksTrash`)
 * @param text текст-подтверждение (уже разрешённая строка)
 * @param onConfirm действие при нажатии «Да» (экран сам закрывает диалог внутри)
 * @param onDismiss закрытие диалога («Нет», клик вне, системная кнопка)
 */
@Composable
fun EmptyTrashConfirmDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    StyledAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.DeleteForever, null) },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            DeleteButton(onClick = onConfirm, text = Strings.yes)
        },
        dismissButton = {
            ThemeOutlinedButton(onClick = onDismiss, text = Strings.no)
        }
    )
}
