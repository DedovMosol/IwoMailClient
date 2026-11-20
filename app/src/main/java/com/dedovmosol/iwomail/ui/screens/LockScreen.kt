package com.dedovmosol.iwomail.ui.screens

import com.dedovmosol.iwomail.ui.utils.tryRequestFocus
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import com.dedovmosol.iwomail.data.security.AppLockManager
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
import com.dedovmosol.iwomail.ui.theme.ThemeButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Экран разблокировки приложения (цель релиза «пароль + дактилоскопия»).
 *
 * Показывается поверх навигации в MainActivity, пока [AppLockManager.locked] == true.
 * NavHost не уничтожается — после пароля/отпечатка тот же экран. «Назад» перехвачен
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
    val scope = com.dedovmosol.iwomail.ui.components.rememberSafeScope()

    // Строки — захватываем в замыкания из композабельного контекста (единая точка чтения).
    val titleLabel = Strings.appLockTitle
    val descLabel = Strings.appLockDesc
    val passwordLabel = Strings.appLockEnterPassword
    val unlockLabel = Strings.appLockUnlock
    val fingerprintLabel = Strings.appLockFingerprintTitle

    // Блокируем выход из приложения кнопкой «назад» — единственный путь наружу
    // это разблокировка (защита от обхода гейта).
    BackHandler(enabled = true) { /* намеренно пусто */ }

    // БЕЗОПАСНОСТЬ: введённый пароль хранится только в памяти композабля (remember),
    // а НЕ в rememberSaveable — сохранение секрета в instance-state bundle означало бы
    // его утечку в сериализованное состояние при смерти процесса/снимке системы.
    var password by remember { mutableStateOf("") }
    var showWrongPassword by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val fingerprintOffered = biometricEnabled && biometricAvailable
    // Запрос отпечатка уже показан в этой блокировке. rememberSaveable — чтобы поворот экрана не открыл второй
    // системный диалог (BiometricPrompt сам переживает смену конфигурации); с разблокировкой экран уходит из
    // композиции, и следующая блокировка начинает заново.
    var biometricAutoRequested by rememberSaveable { mutableStateOf(false) }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle

    // Отпечаток разрешён — сразу системный запрос «приложите палец» (как в банковских приложениях), один раз за
    // блокировку: отменил — пароль или кнопка отпечатка. Флаг из настроек может прийти позже первого кадра —
    // эффект ждёт его. Диалог показывается только у Activity на экране (RESUMED).
    LaunchedEffect(fingerprintOffered) {
        if (!fingerprintOffered || biometricAutoRequested) return@LaunchedEffect
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        biometricAutoRequested = true
        onBiometricRequest()
    }

    // Автофокус на поле пароля — когда отпечатка нет: иначе клавиатура открылась бы под системным диалогом.
    LaunchedEffect(Unit) {
        if (!fingerprintOffered) focusRequester.tryRequestFocus()
    }

    // NAV-01: пауза после серии неверных попыток (переживает перезапуск и смерть процесса — хранится в AppLockManager)
    var lockoutRemainingMs by rememberLockoutRemainingMs {
        // Пауза кончилась: старое «неверный пароль» убрать, поле снова доступно — вернуть в него фокус
        showWrongPassword = false
        if (!fingerprintOffered) focusRequester.tryRequestFocus()
    }
    var attemptsBeforeLockout by remember { mutableIntStateOf(AppLockManager.FREE_ATTEMPTS) }
    val lockedOut = lockoutRemainingMs > 0L
    val attemptMessage = Strings.appLockAttemptState(attemptsBeforeLockout, lockoutRemainingMs)

    /** Общая точка проверки пароля (кнопка + IME-действие). Тяжёлый PBKDF2 — на IO. */
    fun tryUnlock() {
        if (isChecking || password.isEmpty() || lockedOut) return
        isChecking = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                AppLockManager.attempt(password, unlockOnSuccess = true)
            }
            isChecking = false
            when (result) {
                // Разблокировано: гейт в MainActivity реактивно уберёт экран.
                AppLockManager.Attempt.Success -> password = ""
                is AppLockManager.Attempt.Wrong -> {
                    attemptsBeforeLockout = result.attemptsBeforeLockout
                    lockoutRemainingMs = result.lockoutMs
                    showWrongPassword = true
                    // Во время паузы поле очищено: набранный вслепую пароль не отправится по её окончании
                    if (result.lockoutMs > 0L) password = ""
                }
                is AppLockManager.Attempt.LockedOut -> {
                    lockoutRemainingMs = result.remainingMs
                    showWrongPassword = true
                    password = ""
                }
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
            LockHeader(title = titleLabel, description = descLabel)

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
                isError = showWrongPassword || lockedOut,
                enabled = !isChecking && !lockedOut,
                visualTransformation = if (passwordVisible)
                    VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                keyboardActions = KeyboardActions(onDone = { tryUnlock() }),
                trailingIcon = {
                    com.dedovmosol.iwomail.ui.components.PasswordVisibilityToggle(passwordVisible) { passwordVisible = it }
                }
            )

            // Ошибка «неверный пароль» — после неудачной попытки; во время паузы — обратный отсчёт.
            // liveRegion: TalkBack объявляет ошибку и окончание паузы без перехода фокуса.
            if (showWrongPassword || lockedOut) {
                Text(
                    text = attemptMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            ThemeButton(
                onClick = { tryUnlock() },
                enabled = password.isNotEmpty() && !lockedOut,
                isLoading = isChecking
            ) {
                Text(unlockLabel, color = Color.White)
            }

            // Кнопка отпечатка: только если пользователь её разрешил И устройство
            // поддерживает биометрию (оба условия проверяются до показа).
            if (fingerprintOffered) {
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

/** Шапка экранов блокировки: градиентный круг с замком, заголовок, пояснение (DRY: пароль и сброс). */
@Composable
private fun LockHeader(title: String, description: String) {
    val colorTheme = LocalColorTheme.current
    // Градиентный круг с иконкой замка — визуальный якорь безопасности.
    Box(
        modifier = Modifier
            .size(88.dp)
            .background(
                brush = Brush.linearGradient(colors = listOf(colorTheme.gradientStart, colorTheme.gradientEnd)),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(AppIcons.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
    }
    Spacer(modifier = Modifier.height(24.dp))
    Text(text = title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(28.dp))
}

/**
 * NAV-01: оставшаяся пауза между попытками (хранится в [AppLockManager], переживает перезапуск), отсчёт раз в
 * секунду; [onEnd] — пауза кончилась. Один отсчёт для экрана пароля и экрана сброса (DRY).
 */
@Composable
private fun rememberLockoutRemainingMs(onEnd: () -> Unit): MutableLongState {
    val remaining = remember { mutableLongStateOf(0L) }
    val currentOnEnd by rememberUpdatedState(onEnd)
    LaunchedEffect(Unit) {
        remaining.longValue = withContext(Dispatchers.IO) { AppLockManager.lockoutRemainingMs() }
    }
    val lockedOut = remaining.longValue > 0L
    LaunchedEffect(lockedOut) {
        if (!lockedOut) return@LaunchedEffect
        while (remaining.longValue > 0L) {
            kotlinx.coroutines.delay(minOf(1_000L, remaining.longValue))
            remaining.longValue = withContext(Dispatchers.IO) { AppLockManager.lockoutRemainingMs() }
        }
        currentOnEnd()
    }
    return remaining
}

/**
 * NAV-04: блокировка включена, а её пароля на устройстве нет (хранилище ключей Android сброшено, хеш повреждён).
 * Раньше приложение в этом случае открывалось без пароля. Теперь войти можно только паролем учётной записи
 * (активной): он сверяется с сохранённым или, если сохранённого нет, входом на сервер Exchange. После проверки
 * блокировка выключается — пароль блокировки задаётся заново в настройках. Попытки считаются и ограничиваются
 * паузами так же, как пароль блокировки. Учётных записей нет — защищать нечего, блокировка выключается сразу.
 */
@Composable
fun LockRecoveryScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = com.dedovmosol.iwomail.ui.components.rememberSafeScope()
    val accountRepo = remember { com.dedovmosol.iwomail.data.repository.RepositoryProvider.getAccountRepository(context) }
    val settingsRepo = remember { com.dedovmosol.iwomail.data.repository.RepositoryProvider.getSettingsRepository(context) }
    val unavailableLabel = Strings.appLockRecoveryUnavailable
    val doneLabel = Strings.appLockRecoveryDone

    // Выйти можно только сбросом (как с экрана пароля)
    BackHandler(enabled = true) { /* намеренно пусто */ }

    var account by remember { mutableStateOf<com.dedovmosol.iwomail.data.database.AccountEntity?>(null) }
    // Пароль — только в памяти (remember), не в сохранённом состоянии
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }
    var showWrongPassword by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    var attemptsBeforeLockout by remember { mutableIntStateOf(AppLockManager.FREE_ATTEMPTS) }
    var lockoutRemainingMs by rememberLockoutRemainingMs { showWrongPassword = false }
    val lockedOut = lockoutRemainingMs > 0L
    val attemptMessage = Strings.appLockAttemptState(attemptsBeforeLockout, lockoutRemainingMs)

    /** Сброс: сначала флаг (гейт сразу открывается), затем хеш; виджет снова показывает содержимое. */
    suspend fun turnOffLock() {
        // The successful reset removes this screen. Finish the authenticated state transition
        // even if its composition scope is cancelled when DataStore emits the new flag.
        withContext(kotlinx.coroutines.NonCancellable) {
            settingsRepo.setAppLockBiometricEnabled(false)
            settingsRepo.setAppLockEnabled(false)
            AppLockManager.removePassword()
            com.dedovmosol.iwomail.widget.updateMailWidget(context.applicationContext)
        }
        com.dedovmosol.iwomail.util.SafeToast.long(context, doneLabel)
    }

    LaunchedEffect(Unit) {
        val found = withContext(Dispatchers.IO) {
            accountRepo.getActiveAccountSync() ?: accountRepo.accounts.first().firstOrNull()
        }
        if (found == null) turnOffLock() else account = found
    }

    fun tryReset() {
        val target = account ?: return
        if (isChecking || password.isEmpty() || lockedOut) return
        isChecking = true
        unavailable = false
        val candidate = password
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    AppLockManager.recoveryAttempt { accountRepo.verifyAccountPassword(target.id, candidate) }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null // Storage or transport failure is not a wrong password.
            } finally {
                isChecking = false
            }
            when (result) {
                null -> unavailable = true
                AppLockManager.Attempt.Success -> {
                    password = ""
                    turnOffLock()
                }
                is AppLockManager.Attempt.Wrong -> {
                    attemptsBeforeLockout = result.attemptsBeforeLockout
                    lockoutRemainingMs = result.lockoutMs
                    showWrongPassword = true
                    if (result.lockoutMs > 0L) password = ""
                }
                is AppLockManager.Attempt.LockedOut -> {
                    lockoutRemainingMs = result.remainingMs
                    showWrongPassword = true
                    password = ""
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Текст длиннее, чем на экране пароля: с клавиатурой на маленьком экране — прокрутка, иначе — по центру
        BoxWithConstraints(modifier = Modifier.fillMaxSize().imePadding()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val target = account
            if (target == null) {
                CircularProgressIndicator()
                return@Column
            }
            LockHeader(title = Strings.appLockRecoveryTitle, description = Strings.appLockRecoveryDesc(target.email))

            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    showWrongPassword = false
                    unavailable = false
                },
                label = { Text(Strings.appLockRecoveryPassword) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = showWrongPassword || lockedOut,
                enabled = !isChecking && !lockedOut,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                keyboardActions = KeyboardActions(onDone = { tryReset() }),
                trailingIcon = {
                    com.dedovmosol.iwomail.ui.components.PasswordVisibilityToggle(passwordVisible) { passwordVisible = it }
                }
            )

            val message = when {
                showWrongPassword || lockedOut -> attemptMessage
                unavailable -> unavailableLabel
                else -> null
            }
            if (message != null) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            ThemeButton(
                onClick = { tryReset() },
                enabled = password.isNotEmpty() && !lockedOut,
                isLoading = isChecking
            ) {
                Text(Strings.appLockRecoveryAction, color = Color.White)
            }
        }
        }
    }
}
