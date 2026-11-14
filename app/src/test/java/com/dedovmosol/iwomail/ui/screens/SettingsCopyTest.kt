package com.dedovmosol.iwomail.ui.screens

import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.ui.NotificationStrings
import com.dedovmosol.iwomail.ui.Strings
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsCopyTest {

    @Test
    fun `empty accounts offer to add one`() {
        assertThat(settingsAccountsSubtitle(emptyList(), "Add account", "2 accounts"))
            .isEqualTo("Add account")
    }

    @Test
    fun `one account shows its address`() {
        assertThat(settingsAccountsSubtitle(listOf("a@corp.ru"), "Add account", "2 accounts"))
            .isEqualTo("a@corp.ru")
    }

    @Test
    fun `several accounts show the count not the first address`() {
        assertThat(
            settingsAccountsSubtitle(
                listOf("a@corp.ru", "b@corp.ru"),
                "Add account",
                "2 accounts"
            )
        ).isEqualTo("2 accounts")
    }

    @Test
    fun `russian account count uses the right plural`() {
        assertThat(Strings.accountsCountLabel(1, russian = true)).isEqualTo("1 аккаунт")
        assertThat(Strings.accountsCountLabel(2, russian = true)).isEqualTo("2 аккаунта")
        assertThat(Strings.accountsCountLabel(5, russian = true)).isEqualTo("5 аккаунтов")
        assertThat(Strings.accountsCountLabel(11, russian = true)).isEqualTo("11 аккаунтов")
        assertThat(Strings.accountsCountLabel(21, russian = true)).isEqualTo("21 аккаунт")
        assertThat(Strings.accountsCountLabel(2, russian = false)).isEqualTo("2 accounts")
    }

    @Test
    fun `dark avatar gets a white glyph, pale avatar a black one`() {
        assertThat(contrastingContentArgb(0xFF1976D2.toInt())).isEqualTo(0xFFFFFFFF.toInt())
        assertThat(contrastingContentArgb(0xFFFFF59D.toInt())).isEqualTo(0xFF000000.toInt())
    }

    @Test
    fun `stored account type falls back to Exchange`() {
        assertThat(AccountType.fromStored("EXCHANGE")).isEqualTo(AccountType.EXCHANGE)
        assertThat(AccountType.fromStored("IMAP")).isEqualTo(AccountType.IMAP)
        assertThat(AccountType.fromStored("POP3")).isEqualTo(AccountType.POP3)
        assertThat(AccountType.fromStored("not-a-type")).isEqualTo(AccountType.EXCHANGE)
        assertThat(AccountType.fromStored("")).isEqualTo(AccountType.EXCHANGE)
    }

    @Test
    fun `contacts sync interval labels`() {
        assertThat(getContactsSyncIntervalText(0, true)).isEqualTo(NotificationStrings.getNever(true))
        assertThat(getContactsSyncIntervalText(1, false)).isEqualTo(NotificationStrings.getDaily(false))
        assertThat(getContactsSyncIntervalText(7, true)).isEqualTo(NotificationStrings.getWeekly(true))
        assertThat(getContactsSyncIntervalText(14, false)).isEqualTo(NotificationStrings.getEveryTwoWeeks(false))
        assertThat(getContactsSyncIntervalText(30, true)).isEqualTo(NotificationStrings.getMonthly(true))
        assertThat(getContactsSyncIntervalText(3, false)).isEqualTo(NotificationStrings.getEveryNDays(3, false))
    }
}
