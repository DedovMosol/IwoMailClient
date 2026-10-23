package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Декодер WBXML не выдаёт оборванный документ за целый.
 *
 * До фикса `parse()` на EOF просто останавливался и отдавал незакрытый XML. Sync-разбор видел в нём
 * Status=1 и не видел MoreAvailable — и неполная страница становилась «последней», а неполный снимок
 * — полным (по нему репозитории удаляли данные). WAP-192: каждый элемент с контентом закрывается END.
 * AOSP Exchange в этом случае бросает EodException.
 *
 * Документы — настоящие: кодирует их реальный [WbxmlParser.generate]/[WbxmlParser.generateSendMail].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WbxmlParserTruncationTest {

    private val wbxml = WbxmlParser()

    private val syncResponse = """<?xml version="1.0" encoding="utf-8"?>
<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:tasks="Tasks"><Collections><Collection>
<SyncKey>K2</SyncKey><CollectionId>12</CollectionId><Status>1</Status><MoreAvailable/><Commands>
<Add><ServerId>12:1</ServerId><ApplicationData><tasks:Subject>Отчёт &amp; план</tasks:Subject>
<airsyncbase:Body><airsyncbase:Type>1</airsyncbase:Type><airsyncbase:Data>Текст задачи</airsyncbase:Data></airsyncbase:Body>
<tasks:Importance>2</tasks:Importance></ApplicationData></Add>
</Commands></Collection></Collections></Sync>"""

    /** Байт, с которого начинается корневой элемент: заголовок (4) + SWITCH_PAGE (2). */
    private val rootStart = 6

    @Test
    fun `complete document still decodes`() {
        val xml = wbxml.parse(wbxml.generate(syncResponse))

        assertThat(xml).contains("<SyncKey>K2</SyncKey>")
        assertThat(xml).contains("<MoreAvailable/>")
        assertThat(xml).contains("<Subject>Отчёт &amp; план</Subject>")
        assertThat(xml).endsWith("</Collection></Collections></Sync>")
    }

    @Test
    fun `every cut after the root start tag is rejected`() {
        val full = wbxml.generate(syncResponse)
        assertThat(full[rootStart].toInt() and 0x3F).isEqualTo(0x05) // Sync, code page 0

        for (cut in (rootStart + 1) until full.size) {
            val error = assertThrows("cut=$cut of ${full.size}", IOException::class.java) {
                wbxml.parse(full.copyOf(cut))
            }
            assertThat(error).hasMessageThat().isNotEmpty()
        }
    }

    @Test
    fun `cut that drops only the closing tokens names the unclosed elements`() {
        val full = wbxml.generate(syncResponse)
        // Хвост документа — 6 токенов END: ApplicationData, Add, Commands, Collection, Collections, Sync.
        val error = assertThrows(IOException::class.java) { wbxml.parse(full.copyOf(full.size - 6)) }

        assertThat(error).hasMessageThat().contains("6 unclosed")
        assertThat(error).hasMessageThat().contains("<ApplicationData>")
    }

    @Test
    fun `cut inside opaque MIME data of SendMail is rejected`() {
        val mime = ("From: a@corp.local\r\nTo: b@corp.local\r\nSubject: hi\r\n\r\n" + "x".repeat(200)).toByteArray()
        val full = wbxml.generateSendMail("cid-1", mime)

        // Целый документ разбирается, оборванный посреди OPAQUE — нет.
        assertThat(wbxml.parse(full)).contains("<SendMail>")
        assertThrows(IOException::class.java) { wbxml.parse(full.copyOf(full.size - 50)) }
    }

    @Test
    fun `extra END tokens after a complete document are still tolerated`() {
        // Поведение до фикса для «лишних» END сохранено: они игнорируются.
        val full = wbxml.generate(syncResponse)
        val padded = full + byteArrayOf(0x01, 0x01)

        assertThat(wbxml.parse(padded)).isEqualTo(wbxml.parse(full))
    }
}
