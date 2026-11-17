package com.dedovmosol.iwomail.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import com.dedovmosol.iwomail.ui.utils.TimeInput

/**
 * Поле времени «ЧЧ:ММ» (события, задачи): ввод цифрами, невозможное время не вводится ([TimeInput.sanitize]).
 *
 * Курсор всегда в конце. Поле со строковым значением сохраняет позицию курсора, а маска вставляет символы
 * («14» + «3» → «14:3», «9» → «09»), и следующая цифра попадала бы не в конец («14:3» + «0» → «14:03»).
 */
@Composable
fun TimeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: @Composable (() -> Unit)? = null,
    textStyle: TextStyle = LocalTextStyle.current,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
) {
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // Значение сменили снаружи (очистка, выбор в пикере) — показать его, курсор в конец
    val shown = if (field.text == value) field else TextFieldValue(value, TextRange(value.length))
    OutlinedTextField(
        value = shown,
        onValueChange = { new ->
            val clean = TimeInput.sanitize(new.text)
            field = TextFieldValue(clean, TextRange(clean.length))
            if (clean != value) onValueChange(clean)
        },
        modifier = modifier,
        placeholder = placeholder,
        textStyle = textStyle,
        singleLine = true,
        trailingIcon = trailingIcon,
        keyboardOptions = keyboardOptions
    )
}
