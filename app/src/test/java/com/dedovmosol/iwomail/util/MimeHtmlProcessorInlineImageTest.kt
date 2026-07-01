package com.dedovmosol.iwomail.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Юнит-тесты единого извлечения inline-картинок (N-5).
 *
 * После унификации `EasEmailService.extractInlineImagesFromMime` делегирует сюда — тест фиксирует
 * поведение единого источника: CID→data:URL, вложенные multipart, а также guard глубины рекурсии
 * (часть-преамбула содержит `boundary=`/`multipart/` без реального разделителя — без guard'а это
 * приводило бы к бесконечной рекурсии; guard добавлен при унификации).
 *
 * Вход — СЫРОЙ MIME (содержит "Content-Type:", ":" и т.п.), поэтому `decodeMimeWrapper` его не
 * трогает (не проходит BASE64_DETECT) → `android.util.Base64` не вызывается → чистый JVM-тест.
 */
class MimeHtmlProcessorInlineImageTest {

    private fun mime(vararg lines: String) = lines.joinToString("\r\n")

    @Test
    fun `extracts CID to data URL from multipart related`() {
        val data = mime(
            "Content-Type: multipart/related; boundary=\"BOUND\"",
            "",
            "--BOUND",
            "Content-Type: text/html; charset=UTF-8",
            "",
            "<html><body><img src=\"cid:img1@example\"></body></html>",
            "--BOUND",
            "Content-Type: image/png",
            "Content-ID: <img1@example>",
            "Content-Transfer-Encoding: base64",
            "",
            "iVBORw0KGgoAAAANSUhEUg",
            "--BOUND--"
        )
        val images = MimeHtmlProcessor.extractInlineImagesFromMime(data)
        assertThat(images).containsEntry("img1@example", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg")
    }

    @Test
    fun `finds image inside nested multipart`() {
        val data = mime(
            "Content-Type: multipart/mixed; boundary=\"OUTER\"",
            "",
            "--OUTER",
            "Content-Type: multipart/related; boundary=\"INNER\"",
            "",
            "--INNER",
            "Content-Type: image/jpeg",
            "Content-ID: <nested@y>",
            "Content-Transfer-Encoding: base64",
            "",
            "/9j/4AAQSkZ",
            "--INNER--",
            "--OUTER",
            "Content-Type: application/pdf; name=\"doc.pdf\"",
            "Content-Disposition: attachment",
            "",
            "JVBERi0xLjQ",
            "--OUTER--"
        )
        val images = MimeHtmlProcessor.extractInlineImagesFromMime(data)
        assertThat(images).containsEntry("nested@y", "data:image/jpeg;base64,/9j/4AAQSkZ")
    }

    @Test
    fun `non-mime input yields empty map`() {
        assertThat(MimeHtmlProcessor.extractInlineImagesFromMime("just plain text, no headers")).isEmpty()
    }
}
