package com.dedovmosol.iwomail.data.repository

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Репозиторий для хранения настроек приложения
 * Singleton для избежания создания множества экземпляров
 */
class SettingsRepository private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "SettingsRepository"
        
        @Volatile
        private var INSTANCE: SettingsRepository? = null
        
        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Scope для фоновых операций кэширования
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Кэшированные значения для часто используемых настроек UI (thread-safe)
    private val cachedFontSize = AtomicReference<FontSize?>(null)
    private val cachedColorTheme = AtomicReference<String?>(null)
    private val cachedDailyThemesEnabled = AtomicReference<Boolean?>(null)
    private val cachedAnimationsEnabled = AtomicReference<Boolean?>(null)
    private val cachedLanguage = AtomicReference<String?>(null)
    
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
        // Инициализация кэша при создании (один раз)
        cacheScope.launch {
            try {
                val prefs = context.dataStore.data.first()
                // UI настройки
                cachedFontSize.set(FontSize.fromName(prefs[Keys.FONT_SIZE] ?: FontSize.MEDIUM.name))
                cachedColorTheme.set(prefs[Keys.COLOR_THEME] ?: "purple")
                cachedDailyThemesEnabled.set(prefs[Keys.DAILY_THEMES_ENABLED] ?: false)
                cachedAnimationsEnabled.set(prefs[Keys.ANIMATIONS_ENABLED] ?: true)
                cachedLanguage.set(prefs[Keys.LANGUAGE] ?: "ru")
                
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
                
                // Темы по дням недели
                cachedDayThemes[java.util.Calendar.MONDAY] = prefs[Keys.THEME_MONDAY] ?: "purple"
                cachedDayThemes[java.util.Calendar.TUESDAY] = prefs[Keys.THEME_TUESDAY] ?: "purple"
                cachedDayThemes[java.util.Calendar.WEDNESDAY] = prefs[Keys.THEME_WEDNESDAY] ?: "purple"
                cachedDayThemes[java.util.Calendar.THURSDAY] = prefs[Keys.THEME_THURSDAY] ?: "purple"
                cachedDayThemes[java.util.Calendar.FRIDAY] = prefs[Keys.THEME_FRIDAY] ?: "purple"
                cachedDayThemes[java.util.Calendar.SATURDAY] = prefs[Keys.THEME_SATURDAY] ?: "purple"
                cachedDayThemes[java.util.Calendar.SUNDAY] = prefs[Keys.THEME_SUNDAY] ?: "purple"
                
                // Цвета скроллбара по дням недели
                cachedDayScrollbarColors[java.util.Calendar.MONDAY] = prefs[Keys.SCROLLBAR_MONDAY] ?: "blue"
                cachedDayScrollbarColors[java.util.Calendar.TUESDAY] = prefs[Keys.SCROLLBAR_TUESDAY] ?: "blue"
                cachedDayScrollbarColors[java.util.Calendar.WEDNESDAY] = prefs[Keys.SCROLLBAR_WEDNESDAY] ?: "blue"
                cachedDayScrollbarColors[java.util.Calendar.THURSDAY] = prefs[Keys.SCROLLBAR_THURSDAY] ?: "blue"
                cachedDayScrollbarColors[java.util.Calendar.FRIDAY] = prefs[Keys.SCROLLBAR_FRIDAY] ?: "blue"
                cachedDayScrollbarColors[java.util.Calendar.SATURDAY] = prefs[Keys.SCROLLBAR_SATURDAY] ?: "blue"
                cachedDayScrollbarColors[java.util.Calendar.SUNDAY] = prefs[Keys.SCROLLBAR_SUNDAY] ?: "blue"
                
                // Per-account настройки НЕ загружаем заранее (lazy load при первом обращении)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to init settings cache", e)
            }
        }
        
        // Подписка на изменения для обновления кэша
        cacheScope.launch {
            try {
                context.dataStore.data.collect { prefs ->
                    // UI настройки
                    cachedFontSize.set(FontSize.fromName(prefs[Keys.FONT_SIZE] ?: FontSize.MEDIUM.name))
                    cachedColorTheme.set(prefs[Keys.COLOR_THEME] ?: "purple")
                    cachedDailyThemesEnabled.set(prefs[Keys.DAILY_THEMES_ENABLED] ?: false)
                    cachedAnimationsEnabled.set(prefs[Keys.ANIMATIONS_ENABLED] ?: true)
                    cachedLanguage.set(prefs[Keys.LANGUAGE] ?: "ru")
                    
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
                    
                    // Темы по дням недели
                    cachedDayThemes[java.util.Calendar.MONDAY] = prefs[Keys.THEME_MONDAY] ?: "purple"
                    cachedDayThemes[java.util.Calendar.TUESDAY] = prefs[Keys.THEME_TUESDAY] ?: "purple"
                    cachedDayThemes[java.util.Calendar.WEDNESDAY] = prefs[Keys.THEME_WEDNESDAY] ?: "purple"
                    cachedDayThemes[java.util.Calendar.THURSDAY] = prefs[Keys.THEME_THURSDAY] ?: "purple"
                    cachedDayThemes[java.util.Calendar.FRIDAY] = prefs[Keys.THEME_FRIDAY] ?: "purple"
                    cachedDayThemes[java.util.Calendar.SATURDAY] = prefs[Keys.THEME_SATURDAY] ?: "purple"
                    cachedDayThemes[java.util.Calendar.SUNDAY] = prefs[Keys.THEME_SUNDAY] ?: "purple"
                    
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
    
    val notificationsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.NOTIFICATIONS_ENABLED] ?: true
    }
    
    val syncOnWifiOnly: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.SYNC_ON_WIFI_ONLY] ?: false
    }
    
    val showPreview: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.SHOW_PREVIEW] ?: true
    }
    
    val confirmDelete: Flow<Boolean> = context.dataStore.data.map { prefs ->
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
    
    val fontSize: Flow<FontSize> = context.dataStore.data.map { prefs ->
        FontSize.fromName(prefs[Keys.FONT_SIZE] ?: FontSize.MEDIUM.name)
    }
    
    suspend fun setFontSize(size: FontSize) {
        context.dataStore.edit { prefs ->
            prefs[Keys.FONT_SIZE] = size.name
        }
    }
    
    fun getFontSizeSync(): FontSize {
        // Используем кэш (всегда доступен после инициализации)
        return cachedFontSize.get() ?: FontSize.MEDIUM
    }
    
    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NOTIFICATIONS_ENABLED] = enabled
        }
    }
    
    suspend fun setSyncOnWifiOnly(wifiOnly: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SYNC_ON_WIFI_ONLY] = wifiOnly
        }
    }
    
    suspend fun setShowPreview(show: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SHOW_PREVIEW] = show
        }
    }
    
    suspend fun setConfirmDelete(confirm: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CONFIRM_DELETE] = confirm
        }
    }
    
    // Язык приложения
    val language: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.LANGUAGE] ?: "ru"
    }
    
    suspend fun setLanguage(languageCode: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LANGUAGE] = languageCode
        }
    }
    
    fun getLanguageSync(): String {
        // Используем кэш (всегда доступен после инициализации)
        return cachedLanguage.get() ?: "ru"
    }
    
    // Время последней синхронизации
    val lastSyncTime: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[Keys.LAST_SYNC_TIME] ?: 0L
    }
    
    suspend fun setLastSyncTime(timeMillis: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_SYNC_TIME] = timeMillis
        }
    }
    
    fun getLastSyncTimeSync(): Long {
        return cachedLastSyncTime.get() ?: 0L
    }
    
    // Флаг завершения первой синхронизации для аккаунта
    suspend fun isInitialSyncCompleted(accountId: Long): Boolean {
        return context.dataStore.data.first()[Keys.initialSyncCompleted(accountId)] ?: false
    }
    
    suspend fun setInitialSyncCompleted(accountId: Long, completed: Boolean = true) {
        context.dataStore.edit { prefs ->
            prefs[Keys.initialSyncCompleted(accountId)] = completed
        }
    }
    
    suspend fun resetInitialSyncFlag(accountId: Long) {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.initialSyncCompleted(accountId))
        }
    }
    
    // Время последней проверки уведомлений (для определения новых писем)
    val lastNotificationCheckTime: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[Keys.LAST_NOTIFICATION_CHECK_TIME] ?: 0L
    }
    
    suspend fun setLastNotificationCheckTime(timeMillis: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_NOTIFICATION_CHECK_TIME] = timeMillis
        }
    }
    
    fun getLastNotificationCheckTimeSync(): Long {
        return cachedLastNotificationCheckTime.get() ?: 0L
    }

    suspend fun setLastNotificationCheckTime(accountId: Long, timeMillis: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.accountNotificationCheckTime(accountId)] = timeMillis
        }
        cachedAccountNotificationCheckTimes[accountId] = timeMillis
    }

    suspend fun getLastNotificationCheckTime(accountId: Long): Long {
        val value = context.dataStore.data.first()[Keys.accountNotificationCheckTime(accountId)] ?: 0L
        cachedAccountNotificationCheckTimes[accountId] = value
        return value
    }

    fun getLastNotificationCheckTimeSync(accountId: Long): Long {
        return cachedAccountNotificationCheckTimes.computeIfAbsent(accountId) { 0L }
    }

    suspend fun resetLastNotificationCheckTime(accountId: Long) {
        context.dataStore.edit { prefs ->
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
    val colorTheme: Flow<String> = context.dataStore.data.map { prefs ->
        migrateThemeCode(prefs[Keys.COLOR_THEME] ?: "purple")
    }
    
    suspend fun setColorTheme(themeCode: String) {
        cachedColorTheme.set(themeCode) // Обновляем кэш сразу (виджет читает кэш)
        context.dataStore.edit { prefs ->
            prefs[Keys.COLOR_THEME] = themeCode
        }
    }
    
    fun getColorThemeSync(): String {
        // Используем кэш (всегда доступен после инициализации)
        return migrateThemeCode(cachedColorTheme.get() ?: "purple")
    }
    
    // Темы по дням недели
    val dailyThemesEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.DAILY_THEMES_ENABLED] ?: false
    }
    
    suspend fun setDailyThemesEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DAILY_THEMES_ENABLED] = enabled
        }
    }
    
    fun getDailyThemesEnabledSync(): Boolean {
        // Используем кэш (всегда доступен после инициализации)
        return cachedDailyThemesEnabled.get() ?: false
    }
    
    // Анимации интерфейса
    val animationsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.ANIMATIONS_ENABLED] ?: true
    }
    
    suspend fun setAnimationsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ANIMATIONS_ENABLED] = enabled
        }
    }
    
    fun getAnimationsEnabledSync(): Boolean {
        // Используем кэш (всегда доступен после инициализации)
        return cachedAnimationsEnabled.get() ?: true
    }
    
    // Звуки приложения
    val soundEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.SOUND_ENABLED] ?: true
    }
    
    suspend fun setSoundEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SOUND_ENABLED] = enabled
        }
    }
    
    fun getSoundEnabledSync(): Boolean {
        return cachedSoundEnabled.get() ?: true
    }
    
    // Цвет скроллбара
    val scrollbarColor: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.SCROLLBAR_COLOR] ?: "blue"
    }
    
    suspend fun setScrollbarColor(colorCode: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SCROLLBAR_COLOR] = colorCode
        }
    }
    
    fun getScrollbarColorSync(): String {
        return cachedScrollbarColor.get() ?: "blue"
    }
    
    // Цвет скроллбара по дням недели
    fun getDayScrollbarColor(dayOfWeek: Int): Flow<String> = context.dataStore.data.map { prefs ->
        prefs[getDayScrollbarKey(dayOfWeek)] ?: "blue"
    }
    
    suspend fun setDayScrollbarColor(dayOfWeek: Int, colorCode: String) {
        context.dataStore.edit { prefs ->
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
    val lastTrashCleanupTime: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[Keys.LAST_TRASH_CLEANUP_TIME] ?: 0L
    }
    
    suspend fun setLastTrashCleanupTime(timeMillis: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_TRASH_CLEANUP_TIME] = timeMillis
        }
    }
    
    fun getLastTrashCleanupTimeSync(): Long {
        return cachedLastTrashCleanupTime.get() ?: 0L
    }

    // Время последней автоочистки корзины (для каждого аккаунта отдельно)
    private fun getAutoCleanupTrashKey(accountId: Long) = longPreferencesKey("last_auto_cleanup_trash_$accountId")

    suspend fun setLastAutoCleanupTrashTime(accountId: Long, timeMillis: Long) {
        context.dataStore.edit { prefs ->
            prefs[getAutoCleanupTrashKey(accountId)] = timeMillis
        }
        cachedAutoCleanupTrashTimes[accountId] = timeMillis
    }

    fun getLastAutoCleanupTrashTimeSync(accountId: Long): Long {
        return cachedAutoCleanupTrashTimes.computeIfAbsent(accountId) { 0L }
    }

    suspend fun getLastAutoCleanupTrashTime(accountId: Long): Long {
        val value = context.dataStore.data.first()[getAutoCleanupTrashKey(accountId)] ?: 0L
        cachedAutoCleanupTrashTimes[accountId] = value
        return value
    }

    // Время последней автоочистки локальных черновиков (для каждого аккаунта отдельно)
    private fun getAutoCleanupDraftsKey(accountId: Long) = longPreferencesKey("last_auto_cleanup_drafts_$accountId")

    suspend fun setLastAutoCleanupDraftsTime(accountId: Long, timeMillis: Long) {
        context.dataStore.edit { prefs ->
            prefs[getAutoCleanupDraftsKey(accountId)] = timeMillis
        }
        cachedAutoCleanupDraftsTimes[accountId] = timeMillis
    }

    fun getLastAutoCleanupDraftsTimeSync(accountId: Long): Long {
        return cachedAutoCleanupDraftsTimes.computeIfAbsent(accountId) { 0L }
    }

    suspend fun getLastAutoCleanupDraftsTime(accountId: Long): Long {
        val value = context.dataStore.data.first()[getAutoCleanupDraftsKey(accountId)] ?: 0L
        cachedAutoCleanupDraftsTimes[accountId] = value
        return value
    }

    // Время последней автоочистки спама (для каждого аккаунта отдельно)
    private fun getAutoCleanupSpamKey(accountId: Long) = longPreferencesKey("last_auto_cleanup_spam_$accountId")

    suspend fun setLastAutoCleanupSpamTime(accountId: Long, timeMillis: Long) {
        context.dataStore.edit { prefs ->
            prefs[getAutoCleanupSpamKey(accountId)] = timeMillis
        }
        cachedAutoCleanupSpamTimes[accountId] = timeMillis
    }

    fun getLastAutoCleanupSpamTimeSync(accountId: Long): Long {
        return cachedAutoCleanupSpamTimes.computeIfAbsent(accountId) { 0L }
    }

    suspend fun getLastAutoCleanupSpamTime(accountId: Long): Long {
        val value = context.dataStore.data.first()[getAutoCleanupSpamKey(accountId)] ?: 0L
        cachedAutoCleanupSpamTimes[accountId] = value
        return value
    }
    
    // --- Очистка файлов приложения (глобальные, не per-account) ---

    val autoCleanupDownloadsDays: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.AUTO_CLEANUP_DOWNLOADS_DAYS] ?: 0
    }

    suspend fun getAutoCleanupDownloadsDays(): Int =
        context.dataStore.data.first()[Keys.AUTO_CLEANUP_DOWNLOADS_DAYS] ?: 0

    suspend fun setAutoCleanupDownloadsDays(days: Int) {
        context.dataStore.edit { prefs -> prefs[Keys.AUTO_CLEANUP_DOWNLOADS_DAYS] = days }
    }

    val autoCleanupRollbackDays: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.AUTO_CLEANUP_ROLLBACK_DAYS] ?: 0
    }

    suspend fun getAutoCleanupRollbackDays(): Int =
        context.dataStore.data.first()[Keys.AUTO_CLEANUP_ROLLBACK_DAYS] ?: 0

    suspend fun setAutoCleanupRollbackDays(days: Int) {
        context.dataStore.edit { prefs -> prefs[Keys.AUTO_CLEANUP_ROLLBACK_DAYS] = days }
    }

    suspend fun getLastAutoCleanupDownloadsTime(): Long =
        context.dataStore.data.first()[Keys.LAST_AUTO_CLEANUP_DOWNLOADS] ?: 0L

    suspend fun setLastAutoCleanupDownloadsTime(timeMillis: Long) {
        context.dataStore.edit { prefs -> prefs[Keys.LAST_AUTO_CLEANUP_DOWNLOADS] = timeMillis }
    }

    suspend fun getLastAutoCleanupRollbackTime(): Long =
        context.dataStore.data.first()[Keys.LAST_AUTO_CLEANUP_ROLLBACK] ?: 0L

    suspend fun setLastAutoCleanupRollbackTime(timeMillis: Long) {
        context.dataStore.edit { prefs -> prefs[Keys.LAST_AUTO_CLEANUP_ROLLBACK] = timeMillis }
    }

    // Время последней синхронизации контактов (для каждого аккаунта отдельно)
    private fun getContactsSyncKey(accountId: Long) = longPreferencesKey("last_contacts_sync_$accountId")
    
    suspend fun setLastContactsSyncTime(accountId: Long, timeMillis: Long) {
        context.dataStore.edit { prefs ->
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
        context.dataStore.edit { prefs ->
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
        context.dataStore.edit { prefs ->
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
        context.dataStore.edit { prefs ->
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
    fun getDayTheme(dayOfWeek: Int): Flow<String> = context.dataStore.data.map { prefs ->
        val key = getDayKey(dayOfWeek)
        migrateThemeCode(prefs[key] ?: "purple")
    }
    
    suspend fun setDayTheme(dayOfWeek: Int, themeCode: String) {
        cachedDayThemes[dayOfWeek] = themeCode // Обновляем кэш сразу (виджет читает кэш)
        context.dataStore.edit { prefs ->
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
    val onboardingShown: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.ONBOARDING_SHOWN] ?: false
    }
    
    suspend fun setOnboardingShown(shown: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ONBOARDING_SHOWN] = shown
        }
    }
    
    fun getOnboardingShownSync(): Boolean {
        return cachedOnboardingShown.get() ?: false
    }
    
    // Режим черновиков по умолчанию (для новых аккаунтов)
    suspend fun setDefaultDraftMode(mode: String) {
        context.dataStore.edit { prefs ->
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
    
    val updateCheckInterval: Flow<UpdateCheckInterval> = context.dataStore.data.map { prefs ->
        UpdateCheckInterval.fromName(prefs[Keys.UPDATE_CHECK_INTERVAL] ?: UpdateCheckInterval.DAILY.name)
    }
    
    suspend fun setUpdateCheckInterval(interval: UpdateCheckInterval) {
        context.dataStore.edit { prefs ->
            prefs[Keys.UPDATE_CHECK_INTERVAL] = interval.name
        }
    }
    
    fun getUpdateCheckIntervalSync(): UpdateCheckInterval {
        return cachedUpdateCheckInterval.get() ?: UpdateCheckInterval.DAILY
    }
    
    // Время последней проверки обновлений
    val lastUpdateCheckTime: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[Keys.LAST_UPDATE_CHECK_TIME] ?: 0L
    }
    
    suspend fun setLastUpdateCheckTime(time: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_UPDATE_CHECK_TIME] = time
        }
    }
    
    fun getLastUpdateCheckTimeSync(): Long {
        return cachedLastUpdateCheckTime.get() ?: 0L
    }
    
    // Версия, которую пользователь отложил (нажал "Позже")
    val updateDismissedVersion: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.UPDATE_DISMISSED_VERSION] ?: 0
    }
    
    suspend fun setUpdateDismissedVersion(versionCode: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.UPDATE_DISMISSED_VERSION] = versionCode
        }
    }
    
    fun getUpdateDismissedVersionSync(): Int {
        return cachedUpdateDismissedVersion.get() ?: 0
    }
    
    // Последняя запущенная версия приложения
    suspend fun getLastAppVersion(): Int {
        return context.dataStore.data.first()[Keys.LAST_APP_VERSION] ?: 0
    }
    
    suspend fun setLastAppVersion(versionCode: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_APP_VERSION] = versionCode
        }
    }
    
    suspend fun getLastInstallTime(): Long {
        return context.dataStore.data.first()[Keys.LAST_INSTALL_TIME] ?: 0L
    }
    
    suspend fun setLastInstallTime(time: Long) {
        context.dataStore.edit { prefs ->
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
     * Отменяет coroutine scope для предотвращения memory leak
     */
    fun cleanup() {
        cacheScope.cancel()
        synchronized(Companion) {
            INSTANCE = null
        }
    }
}

