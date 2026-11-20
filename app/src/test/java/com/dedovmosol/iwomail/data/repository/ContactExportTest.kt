package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.eas.GalContact
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PIM-14: экспорт контактов из GAL (чужие данные) не встраивает в файл формулу таблицы (CSV-инъекция) и не
 * начинает новое свойство или карточку vCard переводом строки.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContactExportTest {

    private lateinit var repo: ContactRepository

    @Before fun setUp() {
        MailDatabase.resetForTesting()
        repo = ContactRepository(ApplicationProvider.getApplicationContext<Context>())
    }

    @After fun tearDown() = MailDatabase.resetForTesting()

    private fun csvRow(contact: GalContact): String = repo.exportGalToCSV(listOf(contact)).split("\r\n")[1]

    @Test fun `formula-like values start with an apostrophe`() {
        val row = csvRow(
            GalContact(
                displayName = "=HYPERLINK(\"https://evil.example\",\"Click\")",
                email = "@SUM(1+1)",
                firstName = "+cmd|' /C calc'!A0",
                lastName = "-2+3",
                company = "\tTab",
                department = "\rCR"
            )
        )
        assertThat(row).startsWith("\"'=HYPERLINK(\"\"https://evil.example\"\",\"\"Click\"\")\"")
        assertThat(row).contains(",'@SUM(1+1),")
        assertThat(row).contains(",'+cmd|' /C calc'!A0,")
        assertThat(row).contains(",'-2+3,")
        assertThat(row).contains(",'\tTab,")
        // CR — в кавычках, иначе строка CSV разорвалась бы
        assertThat(row).contains(",\"'\rCR\",")
    }

    @Test fun `phone numbers and plain text stay as they are`() {
        val row = csvRow(
            GalContact(displayName = "Ivan Petrov", email = "ivan@corp.example", phone = "+7 (999) 123-45-67", mobilePhone = "-5")
        )
        assertThat(row).isEqualTo("Ivan Petrov,,,ivan@corp.example,+7 (999) 123-45-67,-5,,,")
    }

    @Test fun `vcard values cannot start a new property or card`() {
        val vcard = repo.exportGalToVCard(
            listOf(
                GalContact(
                    displayName = "Evil\r\nEND:VCARD\r\nBEGIN:VCARD\r\nFN:Injected",
                    email = "a@corp.example\r\nNOTE:x",
                    phone = "123\rTEL:999",
                    company = "Corp\rX"
                )
            )
        )
        val lines = vcard.split("\r\n")
        assertThat(lines.count { it.startsWith("BEGIN:VCARD") }).isEqualTo(1)
        assertThat(lines.count { it.startsWith("END:VCARD") }).isEqualTo(1)
        assertThat(lines).contains("FN:Evil\\nEND:VCARD\\nBEGIN:VCARD\\nFN:Injected")
        assertThat(lines).contains("EMAIL:a@corp.example\\nNOTE:x")
        assertThat(lines).contains("TEL;TYPE=WORK:123\\nTEL:999")
        assertThat(lines).contains("ORG:Corp\\nX")
        assertThat(lines.none { it.startsWith("NOTE:") }).isTrue()
    }
}
