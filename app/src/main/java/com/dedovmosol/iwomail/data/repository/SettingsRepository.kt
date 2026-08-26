package com.dedovmosol.iwomail.data.repository

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Репозиторий для хранения настроек приложения
 * Singleton для избежания создания множества экземпляров.
 *
 * DataStore инжектируется через конструктор (Dependency Inversion):
 * в проде — общий делегат [settingsDataStore], в тестах — изолированный
 * экземпляр через [PreferenceDataStoreFactory] (официальный паттерн
 * тестирования DataStore, устраняет гонки статического делегата).
 */
class SettingsRepository private constructor(
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val startCacheCollector: Boolean = true
) {
    
    companion object {
        private const val TAG = "SettingsRepository"
        
        @Volatile
        private var INSTANCE: SettingsRepository? = null
        
        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(
                    context.applicationContext,
                    context.applicationContext.settingsDataStore
                ).also { INSTANCE = it }
            }
        }

        /**
         * Только для тестов: экземпляр с изолированным DataStore.
         * Не трогает прод-синглтон [INSTANCE].
         *
         * [startCacheCollector] = false даёт детерминированный «холодный кэш»
         * (контракт fail-closed синк-геттеров): с коллектором кэш согревается
         * асинхронно и гонка с ассертом делает тест недетерминированным.
         */
        @androidx.annotation.VisibleForTesting
        fun createForTesting(
            context: Context,
            dataStore: DataStore<Preferences>,
            startCacheCollector: Boolean = true
        ): SettingsRepository {
            return SettingsRepository(context.applicationContext, dataStore, startCacheCollector)
        }
    }
    
    // Scope для фоновых операций кэширования
    private val cacheScope = com.dedovmosol.iwomail.util.supervisedScope(Dispatchers.IO)

    // Флаги «сеттер записал ключ в рамках жизни этого экземпляра»: после записи
    // сеттера коллектор НЕ должен перезаписывать соответствующий кэш устаревшими
    // эмиссиями — эхо собственных записей в DataStore приходит асинхронно и может
    // откатить кэш к промежуточному значению (гонка «сеттер → устаревшая эмиссия»).
    // Корректно в силу инварианта единственного писателя: файл настроек пишет
    // только этот репозиторий (официальное ограничение DataStore — single writer).
    private val themeModeWrittenBySetter = AtomicBoolean(false)
    private val colorThemeWrittenBySetter = AtomicBoolean(false)
    private val dayThemesWrittenBySetter = AtomicBoolean(false)
    private val appLockEnabledWrittenBySetter = AtomicBoolean(false)
    private val appLockBiometricWrittenBySetter = AtomicBoolean(false)
    
    // Кэшированные значения для часто используемых настроек UI (thread-safe)
    private val cachedFontSize = AtomicReference<FontSize?>(null)
    private val cachedColorTheme = AtomicReference<String?>(null)
    private val cachedDailyThemesEnabled = AtomicReference<Boolean?>(null)
    private val cachedAnimationsEnabled = AtomicReference<Boolean?>(null)
    private val cachedLanguage = AtomicReference<String?>(null)
    private val cachedThemeMode = AtomicReference<ThemeMode?>(null)
    
    // Кэшированные значения для простых настроек
    private val cachedLastSyncTime = AtomicReference<Long?>(null)
    private val cachedLastNotificationCheckTime = AtomicReference<Long?>(null)
    private val cachedLastTrashCleanupTime = AtomicReference<Long?>(null)
    private val cachedOnboardingShown = AtomicReference<Boolean?>(null)
    private val cachedUpdateCheckInterval = AtomicReference<UpdateCheckInterval?>(null)
    private val cachedLastUpdateCheckTime = AtomicReference<Long?>(null)
    private val cachedUpdateDismissedVersion = AtomicReference<Int?>(null)
    private val cachedSoundEnabled = AtomicReference<Boolean?>(null)
    private val cachedScrollbarColor = AtomicReference<String?>(null)
    private val cachedDefaultDraftMode = AtomicReference<String?>(null)
    
    // Кэшированные флаги блокировки приложения (синк-чтение для гейта в окне/сервисах)
    private val cachedAppLockEnabled = AtomicReference<Boolean?>(null)
    private val cachedAppLockBiometricEnabled = AtomicReference<Boolean?>(null)
    
    // Кэшированные цвета скроллбара по дням недели
    private val cachedDayScrollbarColors = java.util.concurrent.ConcurrentHashMap<Int, String>()
    
    // Кэшированные значения для per-account настроек (thread-safe Map)
    private val cachedContactsSyncTimes = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val cachedNotesSyncTimes = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val cachedCalendarSyncTimes = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val cachedTasksSyncTimes = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val cachedAccountNotificationCheckTimes = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val cachedAutoCleanupTrashTimes = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val cachedAutoCleanupDraftsTimes = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val cachedAutoCleanupSpamTimes = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    
    // Кэшированные темы по дням недели (7 дней)
    private val cachedDayThemes = java.util.concurrent.ConcurrentHashMap<Int, String>()
    
    init {
        // Подписка на изменения для обновления кэша.
        //
        // ВАЖНО (гонка): единственный источник правды для кэша — этот поток.
        // `dataStore.data` эмитит ТЕКУЩЕЕ значение сразу при подписке, поэтому
        // отдельный первичный «сид» не нужен. Дублирующий сид создавал гонку:
        // поздний запуск мог перезаписать кэш значением из файла ПОСЛЕ того,
        // как сеттер уже обновил его (тест «sync cache reflects value
        // immediately after set»). DRY + корректность.
        //
        // Гвард [startCacheCollector] — тестовый шов для детерминированного
        // «холодного кэша» (контракт fail-closed синк-геттеров): коллектор
        // согревает кэш асинхронно на Dispatchers.IO, что в тестах создаёт
        // гонку с ассертом. Прод всегда проходит с коллектором (значение по умолчанию).
        if (startCacheCollector) cacheScope.launch {
            try {
                dataStore.data.collect { prefs ->
                    // UI настройки
                    cachedFontSize.set(FontSize.fromName(prefs[Keys.FONT_SIZE] ?: FontSize.MEDIUM.name))
                    if (!colorThemeWrittenBySetter.get()) {
                        cachedColorTheme.set(prefs[Keys.COLOR_THEME] ?: "purple")
                    }
                    cachedDailyThemesEnabled.set(prefs[Keys.DAILY_THEMES_ENABLED] ?: false)
                    cachedAnimationsEnabled.set(prefs[Keys.ANIMATIONS_ENABLED] ?: true)
                    cachedLanguage.set(prefs[Keys.LANGUAGE] ?: "ru")
                    if (!themeModeWrittenBySetter.get()) {
                        cachedThemeMode.set(ThemeMode.fromName(prefs[Keys.THEME_MODE]))
                    }                    
                    // Простые настройки
                    cachedLastSyncTime.set(prefs[Keys.LAST_SYNC_TIME] ?: 0L)
                    cachedLastNotificationCheckTime.set(prefs[Keys.LAST_NOTIFICATION_CHECK_TIME] ?: 0L)
                    cachedLastTrashCleanupTime.set(prefs[Keys.LAST_TRASH_CLEANUP_TIME] ?: 0L)
                    cachedOnboardingShown.set(prefs[Keys.ONBOARDING_SHOWN] ?: false)
                    cachedDefaultDraftMode.set(prefs[Keys.DEFAULT_DRAFT_MODE] ?: "SERVER")
                    cachedUpdateCheckInterval.set(UpdateCheckInterval.fromName(prefs[Keys.UPDATE_CHECK_INTERVAL] ?: UpdateCheckInterval.DAILY.name))
                    cachedLastUpdateCheckTime.set(prefs[Keys.LAST_UPDATE_CHECK_TIME] ?: 0L)
                    cachedUpdateDismissedVersion.set(prefs[Keys.UPDATE_DISMISSED_VERSION] ?: 0)
                    cachedSoundEnabled.set(prefs[Keys.SOUND_ENABLED] ?: true)
                    cachedScrollbarColor.set(prefs[Keys.SCROLLBAR_COLOR] ?: "blue")
                    // Флаги блокировки: гвард «сеттер → устаревшая эмиссия» (паттерн
                    // themeMode) — после записи сеттера коллектор не откатывает кэш
                    // асинхронным эхом с промежуточным значением.
                    if (!appLockEnabledWrittenBySetter.get()) {
                        cachedAppLockEnabled.set(prefs[Keys.APP_LOCK_ENABLED] ?: false)
                    }
                    if (!appLockBiometricWrittenBySetter.get()) {
                        cachedAppLockBiometricEnabled.set(prefs[Keys.APP_LOCK_BIOMETRIC_ENABLED] ?: false)
                    }
                    
                    // Темы по дням недели
                    if (!dayThemesWrittenBySetter.get()) {
                        cachedDayThemes[java.util.Calendar.MONDAY] = prefs[Keys.THEME_MONDAY] ?: "purple"
                        cachedDayThemes[java.util.Calendar.TUESDAY] = prefs[Keys.THEME_TUESDAY] ?: "purple"
                        cachedDayThemes[java.util.Calendar.WEDNESDAY] = prefs[Keys.THEME_WEDNESDAY] ?: "purple"
                        cachedDayThemes[java.util.Calendar.THURSDAY] = prefs[Keys.THEME_THURSDAY] ?: "purple"
                        cachedDayThemes[java.util.Calendar.FRIDAY] = prefs[Keys.THEME_FRIDAY] ?: "purple"
                        cachedDayThemes[java.util.Calendar.SATURDAY] = prefs[Keys.THEME_SATURDAY] ?: "purple"
                        cachedDayThemes[java.util.Calendar.SUNDAY] = prefs[Keys.THEME_SUNDAY] ?: "purple"
                    }
                    
                    // Цвета скроллбара по дням недели
                    cachedDayScrollbarColors[java.util.Calendar.MONDAY] = prefs[Keys.SCROLLBAR_MONDAY] ?: "blue"
                    cachedDayScrollbarColors[java.util.Calendar.TUESDAY] = prefs[Keys.SCROLLBAR_TUESDAY] ?: "blue"
                    cachedDayScrollbarColors[java.util.Calendar.WEDNESDAY] = prefs[Keys.SCROLLBAR_WEDNESDAY] ?: "blue"
                    cachedDayScrollbarColors[java.util.Calendar.THURSDAY] = prefs[Keys.SCROLLBAR_THURSDAY] ?: "blue"
                    cachedDayScrollbarColors[java.util.Calendar.FRIDAY] = prefs[Keys.SCROLLBAR_FRIDAY] ?: "blue"
                    cachedDayScrollbarColors[java.util.Calendar.SATURDAY] = prefs[Keys.SCROLLBAR_SATURDAY] ?: "blue"
                    cachedDayScrollbarColors[java.util.Calendar.SUNDAY] = prefs[Keys.SCROLLBAR_SUNDAY] ?: "blue"
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to update settings cache", e)
            }
        }
    }
    
    private object Keys {
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val SYNC_ON_WIFI_ONLY = booleanPreferencesKey("sync_wifi_only")
        val SHOW_PREVIEW = booleanPreferencesKey("show_preview")
        val CONFIRM_DELETE = booleanPreferencesKey("confirm_delete")
        val LANGUAGE = stringPreferencesKey("app_language")
        val FONT_SIZE = stringPreferencesKey("font_size")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LAST_SYNC_TIME = longPreferencesKey("last_sync_time")
        val LAST_NOTIFICATION_CHECK_TIME = longPreferencesKey("last_notification_check_time")
        val COLOR_THEME = stringPreferencesKey("color_theme")
        val DAILY_THEMES_ENABLED = booleanPreferencesKey("daily_themes_enabled")
        val ANIMATIONS_ENABLED = booleanPreferencesKey("animations_enabled")
        val LAST_TRASH_CLEANUP_TIME = longPreferencesKey("last_trash_cleanup_time")
        val THEME_MONDAY = stringPreferencesKey("theme_monday")
        val THEME_TUESDAY = stringPreferencesKey("theme_tuesday")
        val THEME_WEDNESDAY = stringPreferencesKey("theme_wednesday")
        val THEME_THURSDAY = stringPreferencesKey("theme_thursday")
        val THEME_FRIDAY = stringPreferencesKey("theme_friday")
        val THEME_SATURDAY = stringPreferencesKey("theme_saturday")
        val THEME_SUNDAY = stringPreferencesKey("theme_sunday")
        val ONBOARDING_SHOWN = booleanPreferencesKey("onboarding_shown")
        val UPDATE_CHECK_INTERVAL = stringPreferencesKey("update_check_interval")
        val LAST_UPDATE_CHECK_TIME = longPreferencesKey("last_update_check_time")
        val UPDATE_DISMISSED_VERSION = intPreferencesKey("update_dismissed_version")
        val LAST_APP_VERSION = intPreferencesKey("last_app_version")
        val LAST_INSTALL_TIME = longPreferencesKey("last_install_time")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val SCROLLBAR_COLOR = stringPreferencesKey("scrollbar_color")
        val SCROLLBAR_MONDAY = stringPreferencesKey("scrollbar_monday")
        val SCROLLBAR_TUESDAY = stringPreferencesKey("scrollbar_tuesday")
        val SCROLLBAR_WEDNESDAY = stringPreferencesKey("scrollbar_wednesday")
        val SCROLLBAR_THURSDAY = stringPreferencesKey("scrollbar_thursday")
        val SCROLLBAR_FRIDAY = stringPreferencesKey("scrollbar_friday")
        val SCROLLBAR_SATURDAY = stringPreferencesKey("scrollbar_saturday")
        val SCROLLBAR_SUNDAY = stringPreferencesKey("scrollbar_sunday")
        
        val DEFAULT_DRAFT_MODE = stringPreferencesKey("default_draft_mode")

        // Блокировка приложения паролем + отпечатком пальца (цель релиза).
        // Флаги в DataStore; сам секрет (хеш пароля) — в шифрованном хранилище
        // AppLockManager, НЕ в DataStore (SOC: секреты отдельно от настроек).
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val APP_LOCK_BIOMETRIC_ENABLED = booleanPreferencesKey("app_lock_biometric_enabled")

        val AUTO_CLEANUP_DOWNLOADS_DAYS = intPreferencesKey("auto_cleanup_downloads_days")
        val AUTO_CLEANUP_ROLLBACK_DAYS = intPreferencesKey("auto_cleanup_rollback_days")
        val LAST_AUTO_CLEANUP_DOWNLOADS = longPreferencesKey("last_auto_cleanup_downloads")
        val LAST_AUTO_CLEANUP_ROLLBACK = longPreferencesKey("last_auto_cleanup_rollback")

        // Динамические ключи для аккаунтов
        fun initialSyncCompleted(accountId: Long) = booleanPreferencesKey("initial_sync_completed_$accountId")
        fun accountNotificationCheckTime(accountId: Long) =
            longPreferencesKey("last_notification_check_time_$accountId")
    }
    
    // Размеры шрифта
    enum class FontSize(val scale: Float, val displayNameRu: String, val displayNameEn: String) {
        SMALL(0.85f, "Маленький", "Small"),
        MEDIUM(1.0f, "Средний", "Medium");
        
        fun getDisplayName(isRussian: Boolean): String = if (isRussian) displayNameRu else displayNameEn
        
        companion object {
            fun fromName(name: String): FontSize = entries.find { it.name == name } ?: MEDIUM
        }
    }
    
    // Режим темы: системный / светлая / тёмная
    enum class ThemeMode(val displayNameRu: String, val displayNameEn: String) {
        SYSTEM("Как в системе", "Follow system"),
        LIGHT("Светлая", "Light"),
        DARK("Тёмная", "Dark");
        
        fun getDisplayName(isRussian: Boolean): String = if (isRussian) displayNameRu else displayNameEn
        
        companion object {
            fun fromName(name: String?): ThemeMode = entries.find { it.name == name } ?: SYSTEM
        }
    }
    
    val notificationsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.NOTIFICATIONS_ENABLED] ?: true
    }
    
    val syncOnWifiOnly: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SYNC_ON_WIFI_ONLY] ?: false
    }
    
    val showPreview: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SHOW_PREVIEW] ?: true
    }
    
    val confirmDelete: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.CONFIRM_DELETE] ?: true
    }
    
    /**
     * Проверяет, активен ли режим экономии батареи Android
     */
    fun isBatterySaverActive(): Boolean {
        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        return powerManager?.isPowerSaveMode == true
    }
    
    /**
     * Flow для отслеживания состояния Battery Saver через BroadcastReceiver
     * Мгновенно реагирует на изменения без polling
     */
    val batterySaverState: Flow<Boolean> = callbackFlow {
        // Отправляем начальное состояние
        trySend(isBatterySaverActive())
        
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                    trySend(isBatterySaverActive())
                }
            }
        }
        
        context.registerReceiver(
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        )
        
        awaitClose {
            context.unregisterReceiver(receiver)
        }
    }
    
    val fontSize: Flow<FontSize> = dataStore.data.map { prefs ->
        FontSize.fromName(prefs[Keys.FONT_SIZE] ?: FontSize.MEDIUM.name)
    }
    
    suspend fun setFontSize(size: FontSize) {
        dataStore.edit { prefs ->
            prefs[Keys.FONT_SIZE] = size.name
        }
    }
    
    fun getFontSizeSync(): FontSize {
        // Используем кэш (всегда доступен после инициализации)
        return cachedFontSize.get() ?: FontSize.MEDIUM
    }
    
    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.NOTIFICATIONS_ENABLED] = enabled
        }
    }
    
    suspend fun setSyncOnWifiOnly(wifiOnly: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.SYNC_ON_WIFI_ONLY] = wifiOnly
        }
    }
    
    suspend fun setShowPreview(show: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.SHOW_PREVIEW] = show
        }
    }
    
    suspend fun setConfirmDelete(confirm: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.CONFIRM_DELETE] = confirm
        }
    }
    
    // Язык приложения
    val language: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.LANGUAGE] ?: "ru"
    }
    
    suspend fun setLanguage(languageCode: String) {
        dataStore.edit { prefs ->
            prefs[Keys.LANGUAGE] = languageCode
        }
    }
    
    fun getLanguageSync(): String {
        // Используем кэш (всегда доступен после инициализации)
        return cachedLanguage.get() ?: "ru"
    }
    
    // Время последней синхронизации
    val lastSyncTime: Flow<Long> = dataStore.data.map { prefs ->
        prefs[Keys.LAST_SYNC_TIME] ?: 0L
    }
    
    suspend fun setLastSyncTime(timeMillis: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.LAST_SYNC_TIME] = timeMillis
        }
    }
    
    fun getLastSyncTimeSync(): Long {
        return cachedLastSyncTime.get() ?: 0L
    }
    
    // Флаг завершения первой синхронизации для аккаунта
    suspend fun isInitialSyncCompleted(accountId: Long): Boolean {
        return dataStore.data.first()[Keys.initialSyncCompleted(accountId)] ?: false
    }
    
    suspend fun setInitialSyncCompleted(accountId: Long, completed: Boolean = true) {
        dataStore.edit { prefs ->
            prefs[Keys.initialSyncCompleted(accountId)] = completed
        }
    }
    
    suspend fun resetInitialSyncFlag(accountId: Long) {
        dataStore.edit { prefs ->
            prefs.remove(Keys.initialSyncCompleted(accountId))
        }
    }
    
    // Время последней проверки уведомлений (для определения новых писем)
    val lastNotificationCheckTime: Flow<Long> = dataStore.data.map { prefs ->
        prefs[Keys.LAST_NOTIFICATION_CHECK_TIME] ?: 0L
    }
    
    suspend fun setLastNotificationCheckTime(timeMillis: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.LAST_NOTIFICATION_CHECK_TIME] = timeMillis
        }
    }
    
    fun getLastNotificationCheckTimeSync(): Long {
        return cachedLastNotificationCheckTime.get() ?: 0L
    }

    suspend fun setLastNotificationCheckTime(accountId: Long, timeMillis: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.accountNotificationCheckTime(accountId)] = timeMillis
        }
        cachedAccountNotificationCheckTimes[accountId] = timeMillis
    }

    suspend fun getLastNotificationCheckTime(accountId: Long): Long {
        val value = dataStore.data.first()[Keys.accountNotificationCheckTime(accountId)] ?: 0L
        cachedAccountNotificationCheckTimes[accountId] = value
        return value
    }

    fun getLastNotificationCheckTimeSync(accountId: Long): Long {
        return cachedAccountNotificationCheckTimes.computeIfAbsent(accountId) { 0L }
    }

    suspend fun resetLastNotificationCheckTime(accountId: Long) {
        dataStore.edit { prefs ->
            prefs.remove(Keys.accountNotificationCheckTime(accountId))
        }
        cachedAccountNotificationCheckTimes.remove(accountId)
    }
    
    // Миграция удалённых тем (red, orange, pink) → purple
    private fun migrateThemeCode(code: String): String {
        return when (code) {
            "red", "orange", "pink" -> "purple"
            else -> code
        }
    }

    // Цветовая тема
    val colorTheme: Flow<String> = dataStore.data.map { prefs ->
        migrateThemeCode(prefs[Keys.COLOR_THEME] ?: "purple")
    }
    
    suspend fun setColorTheme(themeCode: String) {
        colorThemeWrittenBySetter.set(true) // кэш авторитетен от сеттера (см. поля флагов)
        cachedColorTheme.set(themeCode) // Обновляем кэш сразу (виджет читает кэш)
        dataStore.edit { prefs ->
            prefs[Keys.COLOR_THEME] = themeCode
        }
    }
    
    fun getColorThemeSync(): String {
        // Используем кэш (всегда доступен после инициализации)
        return migrateThemeCode(cachedColorTheme.get() ?: "purple")
    }
    
    // Режим темы (системный / светлая / тёмная)
    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        ThemeMode.fromName(prefs[Keys.THEME_MODE])
    }
    
    suspend fun setThemeMode(mode: ThemeMode) {
        // После записи сеттера коллектор не трогает кэш этого ключа (см. поля
        // флагов) — устраняет гонку «сеттер → устаревшая эмиссия».
        themeModeWrittenBySetter.set(true)
        cachedThemeMode.set(mode) // Обновляем кэш сразу (синк-читатели)
        dataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode.name
        }
    }
    
    fun getThemeModeSync(): ThemeMode {
        // Как и остальные Sync-геттеры файла (getFontSizeSync и др.): читаем
        // только кэш, без runBlocking — блокировка главного потока на первом
        // чтении DataStore запрещена официальными гайдлайнами (риск ANR/джанка).
        // Холодный кэш возможен лишь в узком окне после создания репозитория,
        // пока init-коллектор не успел эмитить; до эмитта UI всё равно не знает
        // сохранённый режим, поэтому детерминированный дефолт SYSTEM корректен:
        // первый же эмит применит сохранённый режим реактивно через themeMode.
        return cachedThemeMode.get() ?: ThemeMode.SYSTEM
    }
    
    // Темы по дням недели
    val dailyThemesEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.DAILY_THEMES_ENABLED] ?: false
    }
    
    suspend fun setDailyThemesEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.DAILY_THEMES_ENABLED] = enabled
        }
    }
    
    fun getDailyThemesEnabledSync(): Boolean {
        // Используем кэш (всегда доступен после инициализации)
        return cachedDailyThemesEnabled.get() ?: false
    }
    
    // Анимации интерфейса
    val animationsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.ANIMATIONS_ENABLED] ?: true
    }
    
    suspend fun setAnimationsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.ANIMATIONS_ENABLED] = enabled
        }
    }
    
    fun getAnimationsEnabledSync(): Boolean {
        // Используем кэш (всегда доступен после инициализации)
        return cachedAnimationsEnabled.get() ?: true
    }
    
    // Звуки приложения
    val soundEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SOUND_ENABLED] ?: true
    }
    
    suspend fun setSoundEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.SOUND_ENABLED] = enabled
        }
    }
    
    fun getSoundEnabledSync(): Boolean {
        return cachedSoundEnabled.get() ?: true
    }
    
    // Цвет скроллбара
    val scrollbarColor: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.SCROLLBAR_COLOR] ?: "blue"
    }
    
    suspend fun setScrollbarColor(colorCode: String) {
        dataStore.edit { prefs ->
            prefs[Keys.SCROLLBAR_COLOR] = colorCode
        }
    }
    
    // ─── Блокировка приложения паролем + отпечатком пальца (цель релиза) ───
    // Флаги настроек; секрет (хеш пароля) хранит AppLockManager в шифрованном
    // хранилище — сюда секреты не попадают (SOC).
    
    /** Включена ли блокировка приложения при входе. */
    val appLockEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.APP_LOCK_ENABLED] ?: false
    }
    
    suspend fun setAppLockEnabled(enabled: Boolean) {
        // Паттерн themeMode: флаг «сеттер записал» + кэш обновляется сразу —
        // синк-геттеры (решение гейта/окна) видят новое значение мгновенно,
        // а коллектор не откатывает кэш асинхронным эхом собственных записей.
        appLockEnabledWrittenBySetter.set(true)
        cachedAppLockEnabled.set(enabled)
        dataStore.edit { prefs ->
            prefs[Keys.APP_LOCK_ENABLED] = enabled
        }
    }
    
    /**
     * Синк-срез для несоставных контекстов (решение окна/сервисов без корутин).
     *
     * Fail-closed: до первой эмиссии DataStore (холодный старт, кэш пуст) возвращаем
     * true — если пароль задан, гейт показывает экран блокировки сразу, без «вспышки»
     * главного экрана на ~100 мс инициализации DataStore. Для пользователей БЕЗ пароля
     * флаг безвреден: сессия разблокирована (evaluateInitialState), гейт закрыт.
     */
    fun getAppLockEnabledSync(): Boolean = cachedAppLockEnabled.get() ?: true
    
    /** Разрешён ли вход по отпечатку пальца (действует только при включённой блокировке). */
    val appLockBiometricEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.APP_LOCK_BIOMETRIC_ENABLED] ?: false
    }
    
    suspend fun setAppLockBiometricEnabled(enabled: Boolean) {
        appLockBiometricWrittenBySetter.set(true)
        cachedAppLockBiometricEnabled.set(enabled)
        dataStore.edit { prefs ->
            prefs[Keys.APP_LOCK_BIOMETRIC_ENABLED] = enabled
        }
    }
    
    /**
     * Синк-срез флага биометрии.
     *
     * Fail-OPEN (false): биометрическая разблокировка не разрешается, пока флаг
     * явно не подтверждён чтением из DataStore — в отличие от флага блокировки,
     * здесь «лишний запрет» безопаснее «лишнего разрешения».
     */
    fun getAppLockBiometricEnabledSync(): Boolean = cachedAppLockBiometricEnabled.get() ?: false
    
    fun getScrollbarColorSync(): String {
        return cachedScrollbarColor.get() ?: "blue"
    }
    
    // Цвет скроллбара по дням недели
    fun getDayScrollbarColor(dayOfWeek: Int): Flow<String> = dataStore.data.map { prefs ->
        prefs[getDayScrollbarKey(dayOfWeek)] ?: "blue"
    }
    
    suspend fun setDayScrollbarColor(dayOfWeek: Int, colorCode: String) {
        dataStore.edit { prefs ->
            prefs[getDayScrollbarKey(dayOfWeek)] = colorCode
        }
    }
    
    fun getDayScrollbarColorSync(dayOfWeek: Int): String {
        return cachedDayScrollbarColors[dayOfWeek] ?: "blue"
    }
    
    private fun getDayScrollbarKey(dayOfWeek: Int): Preferences.Key<String> {
        return when (dayOfWeek) {
            java.util.Calendar.MONDAY -> Keys.SCROLLBAR_MONDAY
            java.util.Calendar.TUESDAY -> Keys.SCROLLBAR_TUESDAY
            java.util.Calendar.WEDNESDAY -> Keys.SCROLLBAR_WEDNESDAY
            java.util.Calendar.THURSDAY -> Keys.SCROLLBAR_THURSDAY
            java.util.Calendar.FRIDAY -> Keys.SCROLLBAR_FRIDAY
            java.util.Calendar.SATURDAY -> Keys.SCROLLBAR_SATURDAY
            java.util.Calendar.SUNDAY -> Keys.SCROLLBAR_SUNDAY
            else -> Keys.SCROLLBAR_MONDAY
        }
    }
    
    /**
     * Получить текущий цвет скроллбара с учётом расписания по дням
     */
    fun getCurrentScrollbarColorSync(): String {
        val dailyEnabled = getDailyThemesEnabledSync()
        return if (dailyEnabled) {
            val dayOfWeek = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
            getDayScrollbarColorSync(dayOfWeek)
        } else {
            getScrollbarColorSync()
        }
    }
    
    // Автоочистка папок (настройки хранятся в AccountEntity)
    enum class AutoCleanupDays(val days: Int, val displayNameRu: String, val displayNameEn: String) {
        DISABLED(0, "Никогда", "Never"),
        DAYS_3(3, "Раз в 3 дня", "Every 3 days"),
        DAYS_7(7, "Раз в 7 дней", "Every 7 days"),
        DAYS_14(14, "Раз в 14 дней", "Every 14 days"),
        DAYS_30(30, "Раз в 30 дней", "Every 30 days"),
        DAYS_60(60, "Раз в 60 дней", "Every 60 days");
        
        fun getDisplayName(isRussian: Boolean): String = if (isRussian) displayNameRu else displayNameEn
        
        companion object {
            fun fromDays(days: Int): AutoCleanupDays = entries.find { it.days == days } ?: DAYS_60
        }
    }
    
    // Время последней очистки (глобальное, проверяем раз в день)
    val lastTrashCleanupTime: Flow<Long> = dataStore.data.map { prefs ->
        prefs[Keys.LAST_TRASH_CLEANUP_TIME] ?: 0L
    }
    
    suspend fun setLastTrashCleanupTime(timeMillis: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.LAST_TRASH_CLEANUP_TIME] = timeMillis
        }
    }
    
    fun getLastTrashCleanupTimeSync(): Long {
        return cachedLastTrashCleanupTime.get() ?: 0L
    }

    // Время последней автоочистки корзины (для каждого аккаунта отдельно)
    private fun getAutoCleanupTrashKey(accountId: Long) = longPreferencesKey("last_auto_cleanup_trash_$accountId")

    suspend fun setLastAutoCleanupTrashTime(accountId: Long, timeMillis: Long) {
        dataStore.edit { prefs ->
            prefs[getAutoCleanupTrashKey(accountId)] = timeMillis
        }
        cachedAutoCleanupTrashTimes[accountId] = timeMillis
    }

    fun getLastAutoCleanupTrashTimeSync(accountId: Long): Long {
        return cachedAutoCleanupTrashTimes.computeIfAbsent(accountId) { 0L }
    }

    suspend fun getLastAutoCleanupTrashTime(accountId: Long): Long {
        val value = dataStore.data.first()[getAutoCleanupTrashKey(accountId)] ?: 0L
        cachedAutoCleanupTrashTimes[accountId] = value
        return value
    }

    // Время последней автоочистки локальных черновиков (для каждого аккаунта отдельно)
    private fun getAutoCleanupDraftsKey(accountId: Long) = longPreferencesKey("last_auto_cleanup_drafts_$accountId")

    suspend fun setLastAutoCleanupDraftsTime(accountId: Long, timeMillis: Long) {
        dataStore.edit { prefs ->
            prefs[getAutoCleanupDraftsKey(accountId)] = timeMillis
        }
        cachedAutoCleanupDraftsTimes[accountId] = timeMillis
    }

    fun getLastAutoCleanupDraftsTimeSync(accountId: Long): Long {
        return cachedAutoCleanupDraftsTimes.computeIfAbsent(accountId) { 0L }
    }

    suspend fun getLastAutoCleanupDraftsTime(accountId: Long): Long {
        val value = dataStore.data.first()[getAutoCleanupDraftsKey(accountId)] ?: 0L
        cachedAutoCleanupDraftsTimes[accountId] = value
        return value
    }

    // Время последней автоочистки спама (для каждого аккаунта отдельно)
    private fun getAutoCleanupSpamKey(accountId: Long) = longPreferencesKey("last_auto_cleanup_spam_$accountId")

    suspend fun setLastAutoCleanupSpamTime(accountId: Long, timeMillis: Long) {
        dataStore.edit { prefs ->
            prefs[getAutoCleanupSpamKey(accountId)] = timeMillis
        }
        cachedAutoCleanupSpamTimes[accountId] = timeMillis
    }

    fun getLastAutoCleanupSpamTimeSync(accountId: Long): Long {
        return cachedAutoCleanupSpamTimes.computeIfAbsent(accountId) { 0L }
    }

    suspend fun getLastAutoCleanupSpamTime(accountId: Long): Long {
        val value = dataStore.data.first()[getAutoCleanupSpamKey(accountId)] ?: 0L
        cachedAutoCleanupSpamTimes[accountId] = value
        return value
    }
    
    // --- Очистка файлов приложения (глобальные, не per-account) ---

    val autoCleanupDownloadsDays: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.AUTO_CLEANUP_DOWNLOADS_DAYS] ?: 0
    }

    suspend fun getAutoCleanupDownloadsDays(): Int =
        dataStore.data.first()[Keys.AUTO_CLEANUP_DOWNLOADS_DAYS] ?: 0

    suspend fun setAutoCleanupDownloadsDays(days: Int) {
        dataStore.edit { prefs -> prefs[Keys.AUTO_CLEANUP_DOWNLOADS_DAYS] = days }
    }

    val autoCleanupRollbackDays: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.AUTO_CLEANUP_ROLLBACK_DAYS] ?: 0
    }

    suspend fun getAutoCleanupRollbackDays(): Int =
        dataStore.data.first()[Keys.AUTO_CLEANUP_ROLLBACK_DAYS] ?: 0

    suspend fun setAutoCleanupRollbackDays(days: Int) {
        dataStore.edit { prefs -> prefs[Keys.AUTO_CLEANUP_ROLLBACK_DAYS] = days }
    }

    suspend fun getLastAutoCleanupDownloadsTime(): Long =
        dataStore.data.first()[Keys.LAST_AUTO_CLEANUP_DOWNLOADS] ?: 0L

    suspend fun setLastAutoCleanupDownloadsTime(timeMillis: Long) {
        dataStore.edit { prefs -> prefs[Keys.LAST_AUTO_CLEANUP_DOWNLOADS] = timeMillis }
    }

    suspend fun getLastAutoCleanupRollbackTime(): Long =
        dataStore.data.first()[Keys.LAST_AUTO_CLEANUP_ROLLBACK] ?: 0L

    suspend fun setLastAutoCleanupRollbackTime(timeMillis: Long) {
        dataStore.edit { prefs -> prefs[Keys.LAST_AUTO_CLEANUP_ROLLBACK] = timeMillis }
    }

    // Время последней синхронизации контактов (для каждого аккаунта отдельно)
    private fun getContactsSyncKey(accountId: Long) = longPreferencesKey("last_contacts_sync_$accountId")
    
    suspend fun setLastContactsSyncTime(accountId: Long, timeMillis: Long) {
        dataStore.edit { prefs ->
            prefs[getContactsSyncKey(accountId)] = timeMillis
        }
        cachedContactsSyncTimes[accountId] = timeMillis  // Явное обновление кэша
    }
    
    fun getLastContactsSyncTimeSync(accountId: Long): Long {
        // Используем computeIfAbsent для thread-safe lazy load без runBlocking
        return cachedContactsSyncTimes.computeIfAbsent(accountId) {
            // Если значение не в кэше, возвращаем 0L (будет загружено при следующей синхронизации)
            0L
        }
    }
    
    // Время последней синхронизации заметок (для каждого аккаунта отдельно)
    private fun getNotesSyncKey(accountId: Long) = longPreferencesKey("last_notes_sync_$accountId")
    
    suspend fun setLastNotesSyncTime(accountId: Long, timeMillis: Long) {
        dataStore.edit { prefs ->
            prefs[getNotesSyncKey(accountId)] = timeMillis
        }
        cachedNotesSyncTimes[accountId] = timeMillis  // Явное обновление кэша
    }
    
    fun getLastNotesSyncTimeSync(accountId: Long): Long {
        // Используем computeIfAbsent для thread-safe lazy load без runBlocking
        return cachedNotesSyncTimes.computeIfAbsent(accountId) {
            // Если значение не в кэше, возвращаем 0L (будет загружено при следующей синхронизации)
            0L
        }
    }
    
    // Время последней синхронизации календаря (для каждого аккаунта отдельно)
    private fun getCalendarSyncKey(accountId: Long) = longPreferencesKey("last_calendar_sync_$accountId")
    
    suspend fun setLastCalendarSyncTime(accountId: Long, timeMillis: Long) {
        dataStore.edit { prefs ->
            prefs[getCalendarSyncKey(accountId)] = timeMillis
        }
        cachedCalendarSyncTimes[accountId] = timeMillis  // Явное обновление кэша
    }
    
    fun getLastCalendarSyncTimeSync(accountId: Long): Long {
        // Используем computeIfAbsent для thread-safe lazy load без runBlocking
        return cachedCalendarSyncTimes.computeIfAbsent(accountId) {
            // Если значение не в кэше, возвращаем 0L (будет загружено при следующей синхронизации)
            0L
        }
    }
    
    // Время последней синхронизации задач (для каждого аккаунта отдельно)
    private fun getTasksSyncKey(accountId: Long) = longPreferencesKey("last_tasks_sync_$accountId")
    
    suspend fun setLastTasksSyncTime(accountId: Long, timeMillis: Long) {
        dataStore.edit { prefs ->
            prefs[getTasksSyncKey(accountId)] = timeMillis
        }
        cachedTasksSyncTimes[accountId] = timeMillis  // Явное обновление кэша
    }
    
    fun getLastTasksSyncTimeSync(accountId: Long): Long {
        // Используем computeIfAbsent для thread-safe lazy load без runBlocking
        return cachedTasksSyncTimes.computeIfAbsent(accountId) {
            // Если значение не в кэше, возвращаем 0L (будет загружено при следующей синхронизации)
            0L
        }
    }

    // Получить тему для конкретного дня (1=Воскресенье, 2=Понедельник, ..., 7=Суббота)
    fun getDayTheme(dayOfWeek: Int): Flow<String> = dataStore.data.map { prefs ->
        val key = getDayKey(dayOfWeek)
        migrateThemeCode(prefs[key] ?: "purple")
    }
    
    suspend fun setDayTheme(dayOfWeek: Int, themeCode: String) {
        dayThemesWrittenBySetter.set(true) // кэш авторитетен от сеттера (см. поля флагов)
        cachedDayThemes[dayOfWeek] = themeCode // Обновляем кэш сразу (виджет читает кэш)
        dataStore.edit { prefs ->
            prefs[getDayKey(dayOfWeek)] = themeCode
        }
    }
    
    fun getDayThemeSync(dayOfWeek: Int): String {
        return migrateThemeCode(cachedDayThemes[dayOfWeek] ?: "purple")
    }
    
    private fun getDayKey(dayOfWeek: Int): Preferences.Key<String> {
        return when (dayOfWeek) {
            java.util.Calendar.MONDAY -> Keys.THEME_MONDAY
            java.util.Calendar.TUESDAY -> Keys.THEME_TUESDAY
            java.util.Calendar.WEDNESDAY -> Keys.THEME_WEDNESDAY
            java.util.Calendar.THURSDAY -> Keys.THEME_THURSDAY
            java.util.Calendar.FRIDAY -> Keys.THEME_FRIDAY
            java.util.Calendar.SATURDAY -> Keys.THEME_SATURDAY
            java.util.Calendar.SUNDAY -> Keys.THEME_SUNDAY
            else -> Keys.THEME_MONDAY
        }
    }
    
    /**
     * Получить текущую тему с учётом расписания по дням
     */
    fun getCurrentThemeSync(): String {
        val dailyEnabled = getDailyThemesEnabledSync()
        return if (dailyEnabled) {
            val dayOfWeek = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
            getDayThemeSync(dayOfWeek)
        } else {
            getColorThemeSync()
        }
    }
    
    // Onboarding показан
    val onboardingShown: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.ONBOARDING_SHOWN] ?: false
    }
    
    suspend fun setOnboardingShown(shown: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.ONBOARDING_SHOWN] = shown
        }
    }
    
    fun getOnboardingShownSync(): Boolean {
        return cachedOnboardingShown.get() ?: false
    }
    
    // Режим черновиков по умолчанию (для новых аккаунтов)
    suspend fun setDefaultDraftMode(mode: String) {
        dataStore.edit { prefs ->
            prefs[Keys.DEFAULT_DRAFT_MODE] = mode
        }
    }
    
    fun getDefaultDraftModeSync(): String {
        return cachedDefaultDraftMode.get() ?: "SERVER"
    }
    
    // Интервал проверки обновлений
    enum class UpdateCheckInterval(val days: Int, val displayNameRu: String, val displayNameEn: String) {
        DAILY(1, "Раз в день", "Daily"),
        EVERY_3_DAYS(3, "Раз в 3 дня", "Every 3 days"),
        WEEKLY(7, "Раз в неделю", "Weekly"),
        NEVER(0, "Никогда", "Never");
        
        fun getDisplayName(isRussian: Boolean): String = if (isRussian) displayNameRu else displayNameEn
        
        companion object {
            fun fromName(name: String): UpdateCheckInterval = entries.find { it.name == name } ?: DAILY
        }
    }
    
    val updateCheckInterval: Flow<UpdateCheckInterval> = dataStore.data.map { prefs ->
        UpdateCheckInterval.fromName(prefs[Keys.UPDATE_CHECK_INTERVAL] ?: UpdateCheckInterval.DAILY.name)
    }
    
    suspend fun setUpdateCheckInterval(interval: UpdateCheckInterval) {
        dataStore.edit { prefs ->
            prefs[Keys.UPDATE_CHECK_INTERVAL] = interval.name
        }
    }
    
    fun getUpdateCheckIntervalSync(): UpdateCheckInterval {
        return cachedUpdateCheckInterval.get() ?: UpdateCheckInterval.DAILY
    }
    
    // Время последней проверки обновлений
    val lastUpdateCheckTime: Flow<Long> = dataStore.data.map { prefs ->
        prefs[Keys.LAST_UPDATE_CHECK_TIME] ?: 0L
    }
    
    suspend fun setLastUpdateCheckTime(time: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.LAST_UPDATE_CHECK_TIME] = time
        }
    }
    
    fun getLastUpdateCheckTimeSync(): Long {
        return cachedLastUpdateCheckTime.get() ?: 0L
    }
    
    // Версия, которую пользователь отложил (нажал "Позже")
    val updateDismissedVersion: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.UPDATE_DISMISSED_VERSION] ?: 0
    }
    
    suspend fun setUpdateDismissedVersion(versionCode: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.UPDATE_DISMISSED_VERSION] = versionCode
        }
    }
    
    fun getUpdateDismissedVersionSync(): Int {
        return cachedUpdateDismissedVersion.get() ?: 0
    }
    
    // Последняя запущенная версия приложения
    suspend fun getLastAppVersion(): Int {
        return dataStore.data.first()[Keys.LAST_APP_VERSION] ?: 0
    }
    
    suspend fun setLastAppVersion(versionCode: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.LAST_APP_VERSION] = versionCode
        }
    }
    
    suspend fun getLastInstallTime(): Long {
        return dataStore.data.first()[Keys.LAST_INSTALL_TIME] ?: 0L
    }
    
    suspend fun setLastInstallTime(time: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.LAST_INSTALL_TIME] = time
        }
    }
    
    /**
     * Проверяет, нужно ли показывать диалог обновления
     * @param availableVersionCode код доступной версии
     * @return true если нужно показать диалог
     */
    fun shouldShowUpdateDialog(availableVersionCode: Int): Boolean {
        val interval = getUpdateCheckIntervalSync()
        if (interval == UpdateCheckInterval.NEVER) return false
        
        val lastCheck = getLastUpdateCheckTimeSync()
        val dismissedVersion = getUpdateDismissedVersionSync()
        val intervalMs = interval.days * 24 * 60 * 60 * 1000L
        
        // Если эту версию уже отложили — проверяем прошёл ли интервал
        if (dismissedVersion == availableVersionCode) {
            return System.currentTimeMillis() - lastCheck >= intervalMs
        }
        
        // Новая версия — показываем сразу
        return true
    }
    
    /**
     * Очистка ресурсов при завершении приложения
     * Отменяет coroutine scope для предотвращения memory leak.
     *
     * Глобальный синглтон сбрасывается только если ЭТОТ экземпляр им и
     * является — тестовые экземпляры из [createForTesting] не должны
     * трогать прод-синглтон (SOC).
     */
    fun cleanup() {
        cacheScope.cancel()
        synchronized(Companion) {
            if (INSTANCE === this) {
                INSTANCE = null
            }
        }
    }
}

