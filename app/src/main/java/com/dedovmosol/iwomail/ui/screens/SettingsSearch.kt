package com.dedovmosol.iwomail.ui.screens

import androidx.compose.runtime.Composable
import com.dedovmosol.iwomail.ui.Strings

/** Куда ведёт найденный пункт — корневые разделы настроек. */
enum class SettingsSearchDest { Accounts, Mail, Alerts, Appearance, Security, About }

data class SettingsSearchEntry(
    val title: String,
    val section: String,
    val dest: SettingsSearchDest
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
        SettingsSearchEntry(Strings.remoteImagesSetting, mail, SettingsSearchDest.Mail),
        SettingsSearchEntry(Strings.galPhotosSetting, mail, SettingsSearchDest.Mail),
        SettingsSearchEntry(Strings.conversationViewSetting, mail, SettingsSearchDest.Mail),
        SettingsSearchEntry(Strings.undoSendSetting, mail, SettingsSearchDest.Mail),
        SettingsSearchEntry(Strings.swipeStart, mail, SettingsSearchDest.Mail),
        SettingsSearchEntry(Strings.swipeEnd, mail, SettingsSearchDest.Mail),
        SettingsSearchEntry(alerts, alerts, SettingsSearchDest.Alerts),
        SettingsSearchEntry(Strings.quietHours, alerts, SettingsSearchDest.Alerts),
        SettingsSearchEntry(Strings.vipSenders, alerts, SettingsSearchDest.Alerts),
        SettingsSearchEntry(appearance, appearance, SettingsSearchDest.Appearance),
        SettingsSearchEntry(Strings.language, appearance, SettingsSearchDest.Appearance),
        SettingsSearchEntry(Strings.fontSize, appearance, SettingsSearchDest.Appearance),
        SettingsSearchEntry(Strings.colorTheme, appearance, SettingsSearchDest.Appearance),
        SettingsSearchEntry(Strings.themeMode, appearance, SettingsSearchDest.Appearance),
        SettingsSearchEntry(Strings.dailyThemes, appearance, SettingsSearchDest.Appearance),
        SettingsSearchEntry(security, security, SettingsSearchDest.Security),
        SettingsSearchEntry(Strings.appLockTitle, security, SettingsSearchDest.Security),
        SettingsSearchEntry(Strings.appLockFingerprintTitle, security, SettingsSearchDest.Security),
        SettingsSearchEntry(about, about, SettingsSearchDest.About),
        SettingsSearchEntry(Strings.privacyPolicy, about, SettingsSearchDest.About),
        SettingsSearchEntry(Strings.supportedProtocols, about, SettingsSearchDest.About),
        SettingsSearchEntry(Strings.developer, about, SettingsSearchDest.About),
    )
}
