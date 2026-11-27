package com.dedovmosol.iwomail.domain

import com.dedovmosol.iwomail.ui.screens.getContactsSyncIntervalText
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Интервал синхронизации контактов, календаря, заметок и задач, включая «Вместе с основной». */
class PimSyncIntervalTest {

    private val day = PimSyncInterval.DAY_MS
    private val now = 100 * day

    @Test
    fun `with mail is due on every mail sync, right after the previous one too`() {
        assertThat(PimSyncInterval.isDue(PimSyncInterval.WITH_MAIL, lastSyncAt = now - 1, now = now)).isTrue()
        assertThat(PimSyncInterval.isDue(PimSyncInterval.WITH_MAIL, lastSyncAt = 0, now = now)).isTrue()
    }

    @Test
    fun `never is never due`() {
        assertThat(PimSyncInterval.isDue(PimSyncInterval.NEVER, lastSyncAt = 0, now = now)).isFalse()
    }

    @Test
    fun `day intervals keep their previous meaning`() {
        assertThat(PimSyncInterval.isDue(1, lastSyncAt = now - day + 1, now = now)).isFalse()
        assertThat(PimSyncInterval.isDue(1, lastSyncAt = now - day, now = now)).isTrue()
        assertThat(PimSyncInterval.isDue(7, lastSyncAt = now - 6 * day, now = now)).isFalse()
        assertThat(PimSyncInterval.isDue(7, lastSyncAt = 0, now = now)).isTrue()
    }

    @Test
    fun `a clock moved back does not postpone sync until the old date`() {
        assertThat(PimSyncInterval.isDue(7, lastSyncAt = now + 3 * day, now = now)).isTrue()
    }

    @Test
    fun `GAL is a full download, so with mail it runs at most daily while personal contacts run every time`() {
        assertThat(PimSyncInterval.isGalDue(PimSyncInterval.WITH_MAIL, lastSyncAt = now - day / 2, now = now)).isFalse()
        assertThat(PimSyncInterval.isGalDue(PimSyncInterval.WITH_MAIL, lastSyncAt = now - day, now = now)).isTrue()
        assertThat(PimSyncInterval.isGalDue(7, lastSyncAt = now - 7 * day, now = now)).isTrue()
        assertThat(PimSyncInterval.isGalDue(PimSyncInterval.NEVER, lastSyncAt = 0, now = now)).isFalse()
    }

    @Test
    fun `settings offer with mail first and every option has a label`() {
        assertThat(PimSyncInterval.OPTIONS.first()).isEqualTo(PimSyncInterval.WITH_MAIL)
        assertThat(PimSyncInterval.OPTIONS).containsAtLeast(0, 1, 7, 14, 30)
        assertThat(getContactsSyncIntervalText(PimSyncInterval.WITH_MAIL, true)).isEqualTo("Вместе с основной")
        assertThat(getContactsSyncIntervalText(PimSyncInterval.WITH_MAIL, false)).isEqualTo("With mail sync")
        assertThat(PimSyncInterval.OPTIONS.map { getContactsSyncIntervalText(it, true) }.toSet()).hasSize(PimSyncInterval.OPTIONS.size)
    }
}
