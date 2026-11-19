package com.dedovmosol.iwomail.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.data.database.SyncMode
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Поиск настроек находит только строки, которые сейчас есть на экранах (смена пароля — при включённой блокировке),
 * и строки настроек каждого аккаунта — с переходом прямо на его экран.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class SettingsSearchCatalogTest {

    @get:Rule
    val rule = createComposeRule()

    /** Каталоги для нескольких состояний — одной композицией (setContent в тесте — один раз). */
    private fun catalogs(vararg states: SettingsSearchState): List<List<SettingsSearchEntry>> {
        val result = arrayOfNulls<List<SettingsSearchEntry>>(states.size)
        rule.setContent {
            CompositionLocalProvider(LocalLanguage provides AppLanguage.ENGLISH) {
                states.forEachIndexed { i, s -> result[i] = rememberSettingsCatalog(s) }
            }
        }
        rule.waitForIdle()
        return result.map { it!! }
    }

    private fun catalog(state: SettingsSearchState) = catalogs(state).single()

    private fun exchange(id: Long, email: String, syncMode: SyncMode = SyncMode.PUSH, cert: String? = null) = AccountEntity(
        id = id, email = email, displayName = email, serverUrl = "mail.corp.example", username = "me",
        accountType = AccountType.EXCHANGE.name, syncMode = syncMode.name, certificatePath = cert
    )

    private fun imap(id: Long, email: String) = AccountEntity(
        id = id, email = email, displayName = email, serverUrl = "imap.corp.example", username = "me",
        accountType = AccountType.IMAP.name, incomingPort = 993
    )

    private fun List<SettingsSearchEntry>.titles() = map { it.title }

    @Test
    fun `app lock rows are found only while the lock is on`() {
        val (off, on) = catalogs(SettingsSearchState(appLockEnabled = false), SettingsSearchState(appLockEnabled = true))
        val security = filterSettingsEntries("Security", off).titles()
        assertThat(security).isNotEmpty()
        assertThat(off.map { it.focus }).containsNoneOf("changePassword", "fingerprint")

        assertThat(on.filter { it.dest == SettingsSearchDest.Security }.map { it.focus })
            .containsAtLeast("appLock", "changePassword", "fingerprint")
    }

    @Test
    fun `daily themes setup is found only while daily themes are on`() {
        val (off, on) = catalogs(SettingsSearchState(), SettingsSearchState(dailyThemesEnabled = true))
        assertThat(off.map { it.focus }).doesNotContain("configureDailyThemes")
        assertThat(on.map { it.focus }).contains("configureDailyThemes")
    }

    @Test
    fun `account rows follow the account type and lead to that account`() {
        val entries = catalog(
            SettingsSearchState(accounts = listOf(exchange(1, "boss@corp.example"), imap(2, "home@mail.example")))
        )
        val boss = entries.filter { it.account?.accountId == 1L }
        val home = entries.filter { it.account?.accountId == 2L }

        // Exchange (Push): режим черновиков и синхронизации есть, интервала нет — он только у «По расписанию»
        assertThat(boss.map { it.focus }).containsAtLeast("credentials", "signature", "draftMode", "syncMode", "contactsSync", "deleteAccount")
        assertThat(boss.map { it.focus }).doesNotContain("syncInterval")
        assertThat(boss.map { it.focus }).doesNotContain("serverCertificate")
        // IMAP: ни режима черновиков, ни данных Exchange, интервал есть
        assertThat(home.map { it.focus }).containsAtLeast("credentials", "syncInterval", "autoCleanupTrash")
        assertThat(home.map { it.focus }).containsNoneOf("draftMode", "syncMode", "contactsSync", "notesSync")

        // Раздел пункта — адрес аккаунта; синхронизация — свой экран
        assertThat(boss.filter { it.focus != "autoCleanupDownloads" && it.focus != "autoCleanupRollback" }.map { it.section }.distinct())
            .containsExactly("boss@corp.example")
        assertThat(boss.single { it.focus == "syncMode" }.account!!.sync).isTrue()
        assertThat(boss.single { it.focus == "signature" }.account!!.sync).isFalse()
        // Файлы приложения общие — в поиске один раз
        assertThat(entries.count { it.focus == "autoCleanupDownloads" }).isEqualTo(1)
    }

    @Test
    fun `scheduled sync and an imported certificate add their rows`() {
        val entries = catalog(
            SettingsSearchState(accounts = listOf(exchange(1, "boss@corp.example", SyncMode.SCHEDULED, cert = "/data/cert.cer")))
        )
        assertThat(entries.map { it.focus }).containsAtLeast("syncInterval", "serverCertificate")
    }

    @Test
    fun `search by an address finds that account's settings, the notification sound row included`() {
        val entries = catalog(
            SettingsSearchState(accounts = listOf(exchange(1, "boss@corp.example"), imap(2, "home@mail.example")))
        )
        val hits = filterSettingsEntries("home@", entries)
        assertThat(hits).isNotEmpty()
        assertThat(hits.all { it.account?.accountId == 2L || it.focus == accountNotificationsFocus(2) }).isTrue()
        assertThat(hits.map { it.focus }).contains(accountNotificationsFocus(2))
    }

    @Test
    fun `without accounts there are no account rows`() {
        val entries = catalog(SettingsSearchState())
        assertThat(entries.none { it.account != null }).isTrue()
        assertThat(entries.titles()).contains("Accounts")
    }
}
