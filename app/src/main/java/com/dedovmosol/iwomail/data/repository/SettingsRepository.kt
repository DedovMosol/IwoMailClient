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
    
    // Кэшированные значения для per-account настроек (thread-safe Map)
    private val cachedContactsSyncTimes = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val cachedNotesSyncTimes = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val cachedCalendarSyncTimes = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val cachedTasksSyncTimes = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    
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
                cachedUpdateCheckInterval.set(UpdateCheckInterval.fromName(prefs[Keys.UPDATE_CHECK_INTERVAL] ?: UpdateCheckInterval.DAILY.name))
                cachedLastUpdateCheckTime.set(prefs[Keys.LAST_UPDATE_CHECK_TIME] ?: 0L)
                cachedUpdateDismissedVersion.set(prefs[Keys.UPDATE_DISMISSED_VERSION] ?: 0)
                
                // Темы по дням недели
                cachedDayThemes[java.util.Calendar.MONDAY] = prefs[Keys.THEME_MONDAY] ?: "purple"
                cachedDayThemes[java.util.Calendar.TUESDAY] = prefs[Keys.THEME_TUESDAY] ?: "purple"
                cachedDayThemes[java.util.Calendar.WEDNESDAY] = prefs[Keys.THEME_WEDNESDAY] ?: "purple"
                cachedDayThemes[java.util.Calendar.THURSDAY] = prefs[Keys.THEME_THURSDAY] ?: "purple"
                cachedDayThemes[java.util.Calendar.FRIDAY] = prefs[Keys.THEME_FRIDAY] ?: "purple"
                cachedDayThemes[java.util.Calendar.SATURDAY] = prefs[Keys.THEME_SATURDAY] ?: "purple"
                cachedDayThemes[java.util.Calendar.SUNDAY] = prefs[Keys.THEME_SUNDAY] ?: "purple"
                
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
                    cachedUpdateCheckInterval.set(UpdateCheckInterval.fromName(prefs[Keys.UPDATE_CHECK_INTERVAL] ?: UpdateCheckInterval.DAILY.name))
                    cachedLastUpdateCheckTime.set(prefs[Keys.LAST_UPDATE_CHECK_TIME] ?: 0L)
                    cachedUpdateDismissedVersion.set(prefs[Keys.UPDATE_DISMISSED_VERSION] ?: 0)
                    
                    // Темы по дням недели
                    cachedDayThemes[java.util.Calendar.MONDAY] = prefs[Keys.THEME_MONDAY] ?: "purple"
                    cachedDayThemes[java.util.Calendar.TUESDAY] = prefs[Keys.THEME_TUESDAY] ?: "purple"
                    cachedDayThemes[java.util.Calendar.WEDNESDAY] = prefs[Keys.THEME_WEDNESDAY] ?: "purple"
                    cachedDayThemes[java.util.Calendar.THURSDAY] = prefs[Keys.THEME_THURSDAY] ?: "purple"
                    cachedDayThemes[java.util.Calendar.FRIDAY] = prefs[Keys.THEME_FRIDAY] ?: "purple"
                    cachedDayThemes[java.util.Calendar.SATURDAY] = prefs[Keys.THEME_SATURDAY] ?: "purple"
                    cachedDayThemes[java.util.Calendar.SUNDAY] = prefs[Keys.THEME_SUNDAY] ?: "purple"
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
        
        // Динамические ключи для аккаунтов
        fun initialSyncCompleted(accountId: Long) = booleanPreferencesKey("initial_sync_completed_$accountId")
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
    
    // Цветовая тема
    val colorTheme: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.COLOR_THEME] ?: "purple"
    }
    
    suspend fun setColorTheme(themeCode: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.COLOR_THEME] = themeCode
        }
    }
    
    fun getColorThemeSync(): String {
        // Используем кэш (всегда доступен после инициализации)
        return cachedColorTheme.get() ?: "purple"
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
        prefs[key] ?: "purple"
    }
    
    suspend fun setDayTheme(dayOfWeek: Int, themeCode: String) {
        context.dataStore.edit { prefs ->
            prefs[getDayKey(dayOfWeek)] = themeCode
        }
    }
    
    fun getDayThemeSync(dayOfWeek: Int): String {
        return cachedDayThemes[dayOfWeek] ?: "purple"
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

