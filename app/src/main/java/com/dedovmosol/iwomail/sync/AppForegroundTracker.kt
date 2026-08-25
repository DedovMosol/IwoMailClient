package com.dedovmosol.iwomail.sync

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Единый источник истины о том, находится ли приложение (хотя бы одна Activity) в foreground.
 *
 * База: официальный паттерн детекции видимости всего процесса —
 * [ProcessLifecycleOwner] + [DefaultLifecycleObserver] (androidx.lifecycle:lifecycle-process).
 * `ON_START`/`ON_STOP` диспатчатся один раз на весь процесс (не на каждую Activity), причём
 * `ON_STOP` — с задержкой ~700 мс (спека библиотеки), что исключает ложные «фон» при смене
 * Activity и делает трекер идеальным для:
 *  1. Подавления системных уведомлений о письмах, которые пользователь уже видит в открытом
 *     клиенте (письма появляются мгновенно через реактивный Room Flow — дубль уведомления шум).
 *  2. Немедленной синхронизации при возврате из фона (push уже отработал в фоне — список актуален).
 *
 * DRY: все потребители (NotificationHelper, PushService, будущие фичи) читают одно состояние
 * из одного места вместо разрозненных `ActivityManager.getRunningAppProcesses()` эвристик.
 *
 * Потокобезопасность: [MutableStateFlow] — lock-free, атомарные update/read с любого потока
 * (вызывается из main-потока через lifecycle-колбэки, читается из IO-диспатчера сервисов).
 *
 * Тестируемость: чистая логика «нужно ли подавлять уведомление» вынесена в
 * [shouldSuppressNewMailNotification] (companion) — юнит-тестируется без Android-фреймворка.
 */
object AppForegroundTracker : DefaultLifecycleObserver {

    private val _inForeground = MutableStateFlow(false)

    /** true, пока хотя бы одна Activity приложения видима (процесс в состоянии >= STARTED). */
    val inForeground: StateFlow<Boolean> = _inForeground.asStateFlow()

    /** Синхронный срез для немедленных решений на любом потоке. */
    fun isInForeground(): Boolean = _inForeground.value

    /**
     * Инициализация из [android.app.Application.onCreate] — ровно один раз на процесс.
     * Повторные вызовы безопасны (идемпотентность): повторная подписка на один и тот же
     * [LifecycleOwner] не создаёт дублей (библиотека игнорирует re-add).
     * Контекст не требуется: [ProcessLifecycleOwner.get] — глобальный синглтон.
     */
    @Volatile
    private var initialized = false

    fun init() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
            initialized = true
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        _inForeground.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        _inForeground.value = false
    }

    /**
     * Чистая логика решения «показывать ли сейчас системное уведомление о новых письмах»
     * (DRY + тестируемость без Android-фреймворка). Показываем только если уведомления
     * разрешены И приложение НЕ в foreground: в открытом клиенте письма и так появляются
     * мгновенно в реактивном списке (Room Flow), дубль в шторке — шум (практика Gmail/Outlook).
     */
    fun shouldShowNewMailNotification(notificationsEnabled: Boolean, appInForeground: Boolean): Boolean =
        notificationsEnabled && !appInForeground
}
