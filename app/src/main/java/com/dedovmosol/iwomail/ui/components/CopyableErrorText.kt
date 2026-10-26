package com.dedovmosol.iwomail.ui.components

import android.os.Build
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.ui.isRussian
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.util.SafeToast

/**
 * Кнопка «Копировать» для текста ошибки (DRY: карточка ошибки входа, статус соединения аккаунта).
 * Копируется [copyText] целиком — с техническими подробностями, которые нужны администратору.
 * Android 13+ сам подтверждает копирование системной плашкой; на старых версиях — тост.
 */
@Composable
fun CopyErrorButton(copyText: String, tint: Color, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val isRu = isRussian()
    IconButton(
        onClick = {
            clipboard.setText(AnnotatedString(copyText))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                SafeToast.short(context, if (isRu) "Текст ошибки скопирован" else "Error text copied")
            }
        },
        modifier = modifier
    ) {
        Icon(
            AppIcons.ContentCopy,
            contentDescription = if (isRu) "Копировать текст ошибки" else "Copy error text",
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Текст ошибки, который можно выделить (долгое нажатие) и скопировать кнопкой целиком.
 * [copyText] — что попадёт в буфер (по умолчанию показанный текст).
 */
@Composable
fun CopyableErrorText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    copyText: String = text,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    maxLines: Int = Int.MAX_VALUE
) {
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        SelectionContainer(modifier = Modifier.weight(1f).padding(top = 12.dp)) {
            Text(text = text, color = color, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
        CopyErrorButton(copyText = copyText, tint = color)
    }
}

/** Карточка ошибки на цвете errorContainer с выделением и копированием текста. */
@Composable
fun CopyableErrorCard(text: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = modifier
    ) {
        CopyableErrorText(
            text = text,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, bottom = 12.dp)
        )
    }
}
