package com.dedovmosol.iwomail.ui.screens.emaillist

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** «Кому» в строке Отправленных и Черновиков: отправитель там — вы сами (у черновика из Outlook его нет вовсе). */
class EmailRowNamesTest {

    @Test
    fun `display name of the first recipient`() {
        assertThat(firstRecipientName("\"Andrey Igorevich Dodonov\" <Andrey.dodonov@minfin-samara.ru>"))
            .isEqualTo("Andrey Igorevich Dodonov")
        // Запятая внутри кавычек — часть имени, не разделитель
        assertThat(firstRecipientName("\"Петров, Иван\" <p@corp.ru>, \"Сидоров\" <s@corp.ru>")).isEqualTo("Петров, Иван")
    }

    @Test
    fun `address only gives its local part`() {
        assertThat(firstRecipientName("ivan@corp.ru")).isEqualTo("ivan")
        assertThat(firstRecipientName("<ivan@corp.ru>")).isEqualTo("ivan")
        assertThat(firstRecipientName("ivan@corp.ru, petr@corp.ru")).isEqualTo("ivan")
    }

    @Test
    fun `no recipients is empty`() {
        assertThat(firstRecipientName("")).isEmpty()
        assertThat(firstRecipientName("   ")).isEmpty()
    }

    @Test
    fun `empty To uses the first Cc`() {
        assertThat(firstRecipientName("", "\"Иван\" <ivan@corp.ru>, petr@corp.ru")).isEqualTo("Иван")
        assertThat(firstRecipientName("  ", "petr@corp.ru")).isEqualTo("petr")
        assertThat(firstRecipientName("anna@corp.ru", "ivan@corp.ru")).isEqualTo("anna")
    }

    @Test
    fun `empty To and Cc use Bcc`() {
        assertThat(firstRecipientName("", "", "\"Иван\" <ivan@corp.ru>")).isEqualTo("Иван")
        assertThat(firstRecipientName("  ", "", "hidden@corp.ru")).isEqualTo("hidden")
        assertThat(firstRecipientName("", "cc@corp.ru", "bcc@corp.ru")).isEqualTo("cc")
        assertThat(firstRecipientField("", "", null)).isEmpty()
    }

    @Test
    fun `sent and drafts show the recipient not From`() {
        assertThat(folderShowsRecipient(com.dedovmosol.iwomail.eas.FolderType.SENT_ITEMS)).isTrue()
        assertThat(folderShowsRecipient(com.dedovmosol.iwomail.eas.FolderType.DRAFTS)).isTrue()
        assertThat(folderShowsRecipient(com.dedovmosol.iwomail.eas.FolderType.INBOX)).isFalse()
    }
}
