package com.dedovmosol.iwomail.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.runtime.Composable
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.AppIcons

/**
 * Кнопка «Показать пароль» в поле пароля — одна для всех полей (DRY, SET-13). Переключатель (IconToggleButton):
 * TalkBack называет действие и состояние — «Показать пароль, включено/выключено», а не безымянную кнопку.
 */
@Composable
fun PasswordVisibilityToggle(visible: Boolean, onVisibleChange: (Boolean) -> Unit) {
    IconToggleButton(checked = visible, onCheckedChange = onVisibleChange) {
        Icon(
            imageVector = if (visible) AppIcons.VisibilityOff else AppIcons.Visibility,
            contentDescription = Strings.showPassword
        )
    }
}
