package com.dedovmosol.iwomail.ui.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsSearchTest {

    private val catalog = listOf(
        SettingsSearchEntry("Почта", "Почта", SettingsSearchDest.Mail),
        SettingsSearchEntry("Цепочки писем", "Почта", SettingsSearchDest.Mail),
        SettingsSearchEntry("Блокировка", "Безопасность", SettingsSearchDest.Security),
        SettingsSearchEntry("Accounts", "Accounts", SettingsSearchDest.Accounts)
    )

    @Test
    fun blankQueryShowsNoHitsSoHubStays() {
        assertThat(filterSettingsEntries("", catalog)).isEmpty()
        assertThat(filterSettingsEntries("  ", catalog)).isEmpty()
    }

    @Test
    fun matchesTitleAndSectionIgnoringCase() {
        assertThat(filterSettingsEntries("цепоч", catalog).map { it.title })
            .containsExactly("Цепочки писем")
        assertThat(filterSettingsEntries("почта", catalog).map { it.title })
            .containsExactly("Почта", "Цепочки писем").inOrder()
        assertThat(filterSettingsEntries("ACCOUNTS", catalog)).hasSize(1)
    }

    @Test
    fun noMatchIsEmpty() {
        assertThat(filterSettingsEntries("xyz", catalog)).isEmpty()
    }
}
