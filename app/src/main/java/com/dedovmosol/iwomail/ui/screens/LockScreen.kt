package com.dedovmosol.iwomail.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.dedovmosol.iwomail.data.security.AppLockManager
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
import com.dedovmosol.iwomail.ui.theme.ThemeButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Экран разблокировки приложения (цель релиза «пароль + дактилоскопия»).
 *
 * Показывается гейтом в MainActivity, пока [AppLockManager.locked] == true,
 * полностью замещая навигацию — обойти его кнопкой «назад» нельзя
 * (см. [BackHandler] ниже).
 *
 * Производительность: проверка пароля — 600k итераций PBKDF2 (~0.5–1 с),
 * поэтому выполняется на [Dispatchers.IO], а не на главном потоке
 * (защита от джанка и пропуска кадров). Кнопка блокируется на время проверки.
 *
 * Строки читаются в композабельный контекст один раз и захватываются
 * замыканиями — @Composable-геттеры нельзя вызывать из несоставных лямбд.
 */
@Composable
fun LockScreen(
    biometricAvailable: Boolean,
    biometricEnabled: Boolean,
    onBiometricRequest: () -> Unit
) {
    val colorTheme = LocalColorTheme.current
    val scope = com.dedovmosol.iwomail.ui.components.rememberSafeScope()

    // Строки — захватываем в замыкания из композабельного контекста (единая точка чтения).
    val titleLabel = Strings.appLockTitle
    val descLabel = Strings.appLockDesc
    val passwordLabel = Strings.appLockEnterPassword
    val unlockLabel = Strings.appLockUnlock
    val wrongPasswordMsg = Strings.appLockWrongPassword
    val fingerprintLabel = Strings.appLockFingerprintTitle

    // Блокируем выход из приложения кнопкой «назад» — единственный путь наружу
    // это разблокировка (защита от обхода гейта).
    BackHandler(enabled = true) { /* намеренно пусто */ }

    var password by rememberSaveable { mutableStateOf("") }
    var showWrongPassword by remember { mutableStateOf(false) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Автофокус на поле пароля после первого появления экрана.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    /** Общая точка проверки пароля (кнопка + IME-действие). Тяжёлый PBKDF2 — на IO. */
    fun tryUnlock() {
        if (isChecking || password.isEmpty()) return
        isChecking = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                AppLockManager.verifyPassword(password)
            }
            isChecking = false
            if (ok) {
                // Разблокировано: гейт в MainActivity реактивно уберёт экран.
                password = ""
            } else {
                showWrongPassword = true
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Градиентный круг с иконкой замка — визуальный якорь безопасности.
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(colorTheme.gradientStart, colorTheme.gradientEnd)
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    AppIcons.Lock,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = titleLabel,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = descLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(28.dp))

            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    if (showWrongPassword) showWrongPassword = false
                },
                label = { Text(passwordLabel) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                isError = showWrongPassword,
                enabled = !isChecking,
                visualTransformation = if (passwordVisible)
                    VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                keyboardActions = KeyboardActions(onDone = { tryUnlock() }),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible)
                                AppIcons.VisibilityOff else AppIcons.Visibility,
                            contentDescription = null
                        )
                    }
                }
            )

            // Ошибка «неверный пароль» — только после неудачной попытки.
            if (showWrongPassword) {
                Text(
                    text = wrongPasswordMsg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            ThemeButton(
                onClick = { tryUnlock() },
                enabled = password.isNotEmpty(),
                isLoading = isChecking
            ) {
                Text(unlockLabel, color = Color.White)
            }

            // Кнопка отпечатка: только если пользователь её разрешил И устройство
            // поддерживает биометрию (оба условия проверяются до показа).
            if (biometricEnabled && biometricAvailable) {
                Spacer(modifier = Modifier.height(24.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(onClick = onBiometricRequest, enabled = !isChecking) {
                        Icon(
                            AppIcons.Fingerprint,
                            contentDescription = fingerprintLabel,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    Text(
                        text = fingerprintLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
