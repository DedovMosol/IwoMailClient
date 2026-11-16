package com.dedovmosol.iwomail.ui.screens

import androidx.compose.runtime.Composable
import com.dedovmosol.iwomail.ui.Strings

/** Куда ведёт найденный пункт — корневые разделы настроек. */
enum class SettingsSearchDest { Accounts, Mail, Alerts, Appearance, Security, About }

data class SettingsSearchEntry(
    val title: String,
    val section: String,
    val dest: SettingsSearchDest,
    val focus: String = ""
)

/** Как в системных настройках: подстрока в названии пункта или разделе, без учёта регистра. */
fun filterSettingsEntries(query: String, catalog: List<SettingsSearchEntry>): List<SettingsSearchEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return catalog.filter { e ->
        e.title.lowercase().contains(q) || e.section.lowercase().contains(q)
    }
}

@Composable
fun rememberSettingsCatalog(): List<SettingsSearchEntry> {
    val accounts = Strings.accounts
    val mail = Strings.mailSection
    val alerts = Strings.mailAlertsSection
    val appearance = Strings.appearance
    val security = Strings.appLockSection
    val about = Strings.aboutApp
    return listOf(
        SettingsSearchEntry(accounts, accounts, SettingsSearchDest.Accounts),
        SettingsSearchEntry(Strings.addAccount, accounts, SettingsSearchDest.Accounts),
        SettingsSearchEntry(mail, mail, SettingsSearchDest.Mail),
        SettingsSearchEntry(Strings.remoteImagesSetting, mail, SettingsSearchDest.Mail, "remoteImages"),
        SettingsSearchEntry(Strings.galPhotosSetting, mail, SettingsSearchDest.Mail, "galPhotos"),
        SettingsSearchEntry(Strings.conversationViewSetting, mail, SettingsSearchDest.Mail, "conversations"),
        SettingsSearchEntry(Strings.undoSendSetting, mail, SettingsSearchDest.Mail, "undoSend"),
        SettingsSearchEntry(Strings.swipeStart, mail, SettingsSearchDest.Mail, "swipeStart"),
        SettingsSearchEntry(Strings.swipeEnd, mail, SettingsSearchDest.Mail, "swipeEnd"),
        SettingsSearchEntry(alerts, alerts, SettingsSearchDest.Alerts),
        SettingsSearchEntry(Strings.quietHours, alerts, SettingsSearchDest.Alerts, "quietHours"),
        SettingsSearchEntry(Strings.vipSenders, alerts, SettingsSearchDest.Alerts, "vipSenders"),
        SettingsSearchEntry(appearance, appearance, SettingsSearchDest.Appearance),
        SettingsSearchEntry(Strings.language, appearance, SettingsSearchDest.Appearance, "language"),
        SettingsSearchEntry(Strings.fontSize, appearance, SettingsSearchDest.Appearance, "fontSize"),
        SettingsSearchEntry(Strings.colorTheme, appearance, SettingsSearchDest.Appearance, "colorTheme"),
        SettingsSearchEntry(Strings.themeMode, appearance, SettingsSearchDest.Appearance, "themeMode"),
        SettingsSearchEntry(Strings.dailyThemes, appearance, SettingsSearchDest.Appearance, "dailyThemes"),
        SettingsSearchEntry(security, security, SettingsSearchDest.Security),
        SettingsSearchEntry(Strings.appLockTitle, security, SettingsSearchDest.Security, "appLock"),
        SettingsSearchEntry(Strings.appLockFingerprintTitle, security, SettingsSearchDest.Security, "fingerprint"),
        SettingsSearchEntry(about, about, SettingsSearchDest.About),
        SettingsSearchEntry(Strings.privacyPolicy, about, SettingsSearchDest.About, "privacy"),
        SettingsSearchEntry(Strings.supportedProtocols, about, SettingsSearchDest.About, "protocols"),
        SettingsSearchEntry(Strings.developer, about, SettingsSearchDest.About, "developer"),
    )
}
