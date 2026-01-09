package com.iwo.mailclient.data.repository

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
        return runBlocking {
            context.dataStore.data.first()[Keys.ONBOARDING_SHOWN] ?: false
        }
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
        return runBlocking {
            UpdateCheckInterval.fromName(context.dataStore.data.first()[Keys.UPDATE_CHECK_INTERVAL] ?: UpdateCheckInterval.DAILY.name)
        }
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
        return runBlocking {
            context.dataStore.data.first()[Keys.LAST_UPDATE_CHECK_TIME] ?: 0L
        }
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
        return runBlocking {
            context.dataStore.data.first()[Keys.UPDATE_DISMISSED_VERSION] ?: 0
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
}

