package com.iwo.mailclient.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Репозиторий для хранения настроек приложения
 * Singleton для избежания создания множества экземпляров
 */
class SettingsRepository private constructor(private val context: Context) {
    
    companion object {
        @Volatile
        private var INSTANCE: SettingsRepository? = null
        
        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
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
        val NIGHT_MODE_ENABLED = booleanPreferencesKey("night_mode_enabled")
        val IGNORE_BATTERY_SAVER = booleanPreferencesKey("ignore_battery_saver")
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
    
    // Ночной режим экономии батареи (23:00-7:00, синхронизация каждые 60 мин)
    val nightModeEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.NIGHT_MODE_ENABLED] ?: false
    }
    
    suspend fun setNightModeEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NIGHT_MODE_ENABLED] = enabled
        }
    }
    
    fun getNightModeEnabledSync(): Boolean {
        return runBlocking {
            context.dataStore.data.first()[Keys.NIGHT_MODE_ENABLED] ?: false
        }
    }
    
    /**
     * Проверяет, является ли текущее время ночным (23:00-7:00)
     */
    fun isNightTime(): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return hour >= 23 || hour < 7
    }
    
    // Игнорировать режим экономии батареи (для тех кому важна почта)
    val ignoreBatterySaver: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.IGNORE_BATTERY_SAVER] ?: false
    }
    
    suspend fun setIgnoreBatterySaver(ignore: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.IGNORE_BATTERY_SAVER] = ignore
        }
    }
    
    fun getIgnoreBatterySaverSync(): Boolean {
        return runBlocking {
            context.dataStore.data.first()[Keys.IGNORE_BATTERY_SAVER] ?: false
        }
    }
    
    /**
     * Проверяет, активен ли режим экономии батареи Android
     */
    fun isBatterySaverActive(): Boolean {
        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        return powerManager?.isPowerSaveMode == true
    }
    
    /**
     * Проверяет, нужно ли применять ограничения Battery Saver
     * Возвращает true если Battery Saver активен И пользователь НЕ игнорирует его
     */
    fun shouldApplyBatterySaverRestrictions(): Boolean {
        return isBatterySaverActive() && !getIgnoreBatterySaverSync()
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
        return runBlocking {
            FontSize.fromName(context.dataStore.data.first()[Keys.FONT_SIZE] ?: FontSize.MEDIUM.name)
        }
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
        return runBlocking {
            context.dataStore.data.first()[Keys.LANGUAGE] ?: "ru"
        }
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
        return runBlocking {
            context.dataStore.data.first()[Keys.LAST_SYNC_TIME] ?: 0L
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
        return runBlocking {
            context.dataStore.data.first()[Keys.LAST_NOTIFICATION_CHECK_TIME] ?: 0L
        }
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
        return runBlocking {
            context.dataStore.data.first()[Keys.COLOR_THEME] ?: "purple"
        }
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
        return runBlocking {
            context.dataStore.data.first()[Keys.DAILY_THEMES_ENABLED] ?: false
        }
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
        return runBlocking {
            context.dataStore.data.first()[Keys.ANIMATIONS_ENABLED] ?: true
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
        return runBlocking {
            context.dataStore.data.first()[Keys.LAST_TRASH_CLEANUP_TIME] ?: 0L
        }
    }
    
    // Время последней синхронизации контактов (для каждого аккаунта отдельно)
    private fun getContactsSyncKey(accountId: Long) = longPreferencesKey("last_contacts_sync_$accountId")
    
    suspend fun setLastContactsSyncTime(accountId: Long, timeMillis: Long) {
        context.dataStore.edit { prefs ->
            prefs[getContactsSyncKey(accountId)] = timeMillis
        }
    }
    
    fun getLastContactsSyncTimeSync(accountId: Long): Long {
        return runBlocking {
            context.dataStore.data.first()[getContactsSyncKey(accountId)] ?: 0L
        }
    }
    
    // Время последней синхронизации заметок (для каждого аккаунта отдельно)
    private fun getNotesSyncKey(accountId: Long) = longPreferencesKey("last_notes_sync_$accountId")
    
    suspend fun setLastNotesSyncTime(accountId: Long, timeMillis: Long) {
        context.dataStore.edit { prefs ->
            prefs[getNotesSyncKey(accountId)] = timeMillis
        }
    }
    
    fun getLastNotesSyncTimeSync(accountId: Long): Long {
        return runBlocking {
            context.dataStore.data.first()[getNotesSyncKey(accountId)] ?: 0L
        }
    }
    
    // Время последней синхронизации календаря (для каждого аккаунта отдельно)
    private fun getCalendarSyncKey(accountId: Long) = longPreferencesKey("last_calendar_sync_$accountId")
    
    suspend fun setLastCalendarSyncTime(accountId: Long, timeMillis: Long) {
        context.dataStore.edit { prefs ->
            prefs[getCalendarSyncKey(accountId)] = timeMillis
        }
    }
    
    fun getLastCalendarSyncTimeSync(accountId: Long): Long {
        return runBlocking {
            context.dataStore.data.first()[getCalendarSyncKey(accountId)] ?: 0L
        }
    }
    
    // Время последней синхронизации задач (для каждого аккаунта отдельно)
    private fun getTasksSyncKey(accountId: Long) = longPreferencesKey("last_tasks_sync_$accountId")
    
    suspend fun setLastTasksSyncTime(accountId: Long, timeMillis: Long) {
        context.dataStore.edit { prefs ->
            prefs[getTasksSyncKey(accountId)] = timeMillis
        }
    }
    
    fun getLastTasksSyncTimeSync(accountId: Long): Long {
        return runBlocking {
            context.dataStore.data.first()[getTasksSyncKey(accountId)] ?: 0L
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
        return runBlocking {
            context.dataStore.data.first()[getDayKey(dayOfWeek)] ?: "purple"
        }
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
}

