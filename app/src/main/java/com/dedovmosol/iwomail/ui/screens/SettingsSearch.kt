package com.dedovmosol.iwomail.ui.screens

import androidx.compose.runtime.Composable
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.data.database.SyncMode
import com.dedovmosol.iwomail.data.repository.ServerCertificateEndpoint
import com.dedovmosol.iwomail.ui.Strings

/** Куда ведёт найденный пункт — корневые разделы настроек. */
enum class SettingsSearchDest { Accounts, Mail, Alerts, Appearance, Security, About }

/** Экран одного аккаунта: настройки аккаунта или его «Синхронизация и очистка» ([sync]). */
data class SettingsAccountTarget(val accountId: Long, val sync: Boolean = false)

/**
 * Пункт поиска. [focus] — id строки для подсветки на экране раздела; [account] — пункт настроек аккаунта (раздел
 * [dest] тогда — Аккаунты), [section] у него — адрес аккаунта: видно, чей это пункт, и находится по адресу.
 */
data class SettingsSearchEntry(
    val title: String,
    val section: String,
    val dest: SettingsSearchDest,
    val focus: String = "",
    val account: SettingsAccountTarget? = null,
    val contextTerms: String = ""
)

/**
 * То, от чего зависит, какие строки сейчас есть на экранах настроек: поиск находит только их. Скрытое (смена пароля
 * без блокировки, темы по дням без включённых тем, режим черновиков у IMAP) не предлагается.
 */
data class SettingsSearchState(
    val appLockEnabled: Boolean = false,
    val dailyThemesEnabled: Boolean = false,
    val accounts: List<AccountEntity> = emptyList(),
    val trustedSenderCount: Int = 0,
    val quietHoursEnabled: Boolean = false,
    val storageDegraded: Boolean = false
)

internal fun usesFallbackStorage(context: android.content.Context): Boolean =
    com.dedovmosol.iwomail.data.security.PasswordStorage.getInstance(context).isUsingInsecureStorage() ||
        com.dedovmosol.iwomail.data.security.AppLockManager.isUsingInsecureStorage()

/** Как в системных настройках: подстрока в названии пункта или разделе, без учёта регистра. */
fun filterSettingsEntries(query: String, catalog: List<SettingsSearchEntry>): List<SettingsSearchEntry> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return catalog.filter { e ->
        e.title.contains(q, ignoreCase = true) || e.section.contains(q, ignoreCase = true) || e.contextTerms.contains(q, ignoreCase = true)
    }
}

/** id строки уведомлений аккаунта в разделе «Уведомления о почте» (подсветка из поиска). */
internal fun accountNotificationsFocus(accountId: Long) = "accountNotifications_$accountId"

@Composable
fun rememberSettingsCatalog(state: SettingsSearchState): List<SettingsSearchEntry> {
    val accounts = Strings.accounts
    val mail = Strings.mailSection
    val alerts = Strings.mailAlertsSection
    val appearance = Strings.appearance
    val security = Strings.appLockSection
    val about = Strings.aboutApp
    val catalog = mutableListOf(
        SettingsSearchEntry(accounts, accounts, SettingsSearchDest.Accounts),
        SettingsSearchEntry(Strings.addAccount, accounts, SettingsSearchDest.Accounts, "addAccount"),
        SettingsSearchEntry(mail, mail, SettingsSearchDest.Mail),
        SettingsSearchEntry(Strings.remoteImagesSetting, mail, SettingsSearchDest.Mail, "remoteImages"),
        SettingsSearchEntry(Strings.galPhotosSetting, mail, SettingsSearchDest.Mail, "galPhotos"),
        SettingsSearchEntry(Strings.conversationViewSetting, mail, SettingsSearchDest.Mail, "conversations"),
        SettingsSearchEntry(Strings.undoSendSetting, mail, SettingsSearchDest.Mail, "undoSend"),
        SettingsSearchEntry(Strings.swipeStart, mail, SettingsSearchDest.Mail, "swipeStart"),
        SettingsSearchEntry(Strings.swipeEnd, mail, SettingsSearchDest.Mail, "swipeEnd"),
        SettingsSearchEntry(alerts, alerts, SettingsSearchDest.Alerts),
        SettingsSearchEntry(Strings.quietHours, alerts, SettingsSearchDest.Alerts, "quietHours"),
        SettingsSearchEntry(Strings.hapticsSetting, alerts, SettingsSearchDest.Alerts, "haptics", contextTerms = Strings.hapticsSettingDesc),
        SettingsSearchEntry(appearance, appearance, SettingsSearchDest.Appearance, contextTerms = Strings.appearanceSearchTerms),
        SettingsSearchEntry(Strings.language, appearance, SettingsSearchDest.Appearance, "language"),
        SettingsSearchEntry(Strings.fontSize, appearance, SettingsSearchDest.Appearance, "fontSize"),
        SettingsSearchEntry(Strings.colorTheme, appearance, SettingsSearchDest.Appearance, "colorTheme"),
        SettingsSearchEntry(Strings.themeMode, appearance, SettingsSearchDest.Appearance, "themeMode"),
        SettingsSearchEntry(Strings.dailyThemes, appearance, SettingsSearchDest.Appearance, "dailyThemes"),
        SettingsSearchEntry(Strings.scrollbarColor, appearance, SettingsSearchDest.Appearance, "scrollbarColor"),
        SettingsSearchEntry(Strings.animations, appearance, SettingsSearchDest.Appearance, "animations"),
        SettingsSearchEntry(security, security, SettingsSearchDest.Security),
        SettingsSearchEntry(Strings.appLockTitle, security, SettingsSearchDest.Security, "appLock"),
        SettingsSearchEntry(about, about, SettingsSearchDest.About),
        SettingsSearchEntry(Strings.privacyPolicy, about, SettingsSearchDest.About, "privacy"),
        SettingsSearchEntry(Strings.supportedProtocols, about, SettingsSearchDest.About, "protocols"),
        SettingsSearchEntry(Strings.developer, about, SettingsSearchDest.About, "developer"),
    )
    catalog += SettingsSearchEntry(Strings.appOverviewLabel, about, SettingsSearchDest.About, "overview")
    catalog += SettingsSearchEntry(Strings.softwareUpdateLabel, about, SettingsSearchDest.About, "updates")
    catalog += SettingsSearchEntry(Strings.autoUpdateCheck, Strings.softwareUpdateLabel, SettingsSearchDest.About, "autoUpdateCheck")
    if (state.trustedSenderCount > 0) catalog += SettingsSearchEntry(Strings.trustedImageSenders(state.trustedSenderCount), mail, SettingsSearchDest.Mail, "trustedSenders")
    if (state.quietHoursEnabled) {
        catalog += SettingsSearchEntry(Strings.quietStartLabel, alerts, SettingsSearchDest.Alerts, "quietStart")
        catalog += SettingsSearchEntry(Strings.quietEndLabel, alerts, SettingsSearchDest.Alerts, "quietEnd")
    }
    if (state.storageDegraded) catalog += SettingsSearchEntry(Strings.failClosedTitle, security, SettingsSearchDest.Security, "failClosed")
    // Строки, которые есть на экране только при включённой настройке
    if (state.dailyThemesEnabled) {
        catalog += SettingsSearchEntry(Strings.configureDailyThemes, appearance, SettingsSearchDest.Appearance, "configureDailyThemes")
        for ((day, name) in weekDays()) {
            catalog += SettingsSearchEntry("$name · ${Strings.colorTheme}", appearance, SettingsSearchDest.Appearance, "dayTheme_$day")
            catalog += SettingsSearchEntry("$name · ${Strings.scrollbarColor}", appearance, SettingsSearchDest.Appearance, "dayScroll_$day")
        }
    }
    if (state.appLockEnabled) {
        catalog += SettingsSearchEntry(Strings.appLockChangePassword, security, SettingsSearchDest.Security, "changePassword")
        catalog += SettingsSearchEntry(Strings.appLockFingerprintTitle, security, SettingsSearchDest.Security, "fingerprint")
    }
    state.accounts.forEachIndexed { index, account ->
        catalog += accountEntries(account, appFiles = index == 0)
        catalog += SettingsSearchEntry(
            Strings.accountNotificationSettings, account.email, SettingsSearchDest.Alerts,
            accountNotificationsFocus(account.id), contextTerms = "$alerts ${account.displayName}"
        )
    }
    return catalog
}

/**
 * Пункты экранов аккаунта — те же условия, что у AccountSettingsScreen и SyncCleanupScreen. [appFiles] — строки
 * очистки файлов приложения: они общие, показываются на экране каждого аккаунта, в поиске — один раз.
 */
@Composable
private fun accountEntries(account: AccountEntity, appFiles: Boolean): List<SettingsSearchEntry> {
    val exchange = AccountType.fromStored(account.accountType) == AccountType.EXCHANGE
    val scheduled = runCatching { SyncMode.valueOf(account.syncMode) }.getOrDefault(SyncMode.PUSH) == SyncMode.SCHEDULED
    val hasServerCert = !account.certificatePath.isNullOrBlank()
    val section = account.email
    val settings = SettingsAccountTarget(account.id)
    val sync = SettingsAccountTarget(account.id, sync = true)
    val accountTerms = "${Strings.accountSettings} ${account.displayName}"
    val cleanupTerms = "$accountTerms ${Strings.autoCleanup}"
    fun entry(title: String, focus: String, target: SettingsAccountTarget) =
        SettingsSearchEntry(title, section, SettingsSearchDest.Accounts, focus, target,
            if (focus.startsWith("autoCleanup")) cleanupTerms else accountTerms)
    return buildList {
        add(entry(Strings.changeCredentials, "credentials", settings))
        add(entry(Strings.signature, "signature", settings))
        if (exchange) add(entry(Strings.draftMode, "draftMode", settings))
        add(entry(Strings.syncAndCleanup, "syncAndCleanup", settings))
        if (hasServerCert) add(entry(Strings.serverCertificate, "serverCertificate", settings))
        if (!account.clientCertificatePath.isNullOrBlank()) add(entry(Strings.clientCertificate, "clientCertificate", settings))
        if (hasServerCert || ServerCertificateEndpoint.of(account) != null) {
            add(entry(Strings.certProtectionTitle, "certProtection", settings))
        }
        add(entry(Strings.deleteAccountAction, "deleteAccount", settings))
        if (exchange) add(entry(Strings.syncMode, "syncMode", sync))
        if (!exchange || scheduled) add(entry(Strings.syncInterval, "syncInterval", sync))
        add(entry(Strings.nightMode, "nightMode", sync))
        add(entry(Strings.ignoreBatterySaver, "ignoreBatterySaver", sync))
        if (exchange) {
            add(entry(Strings.contactsSync, "contactsSync", sync))
            add(entry(Strings.notesSync, "notesSync", sync))
            add(entry(Strings.calendarSync, "calendarSync", sync))
            add(entry(Strings.tasksSync, "tasksSync", sync))
        }
        add(entry(Strings.autoCleanupTrash, "autoCleanupTrash", sync))
        add(entry(Strings.autoCleanupDrafts, "autoCleanupDrafts", sync))
        add(entry(Strings.autoCleanupSpam, "autoCleanupSpam", sync))
        if (appFiles) {
            add(SettingsSearchEntry(Strings.autoCleanupDownloads, Strings.autoCleanupAppFiles, SettingsSearchDest.Accounts, "autoCleanupDownloads", sync))
            add(SettingsSearchEntry(Strings.autoCleanupRollback, Strings.autoCleanupAppFiles, SettingsSearchDest.Accounts, "autoCleanupRollback", sync))
        }
    }
}
