package com.dedovmosol.iwomail.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Юнит-тесты Kotlin-санитайзера редактора (L-3).
 *
 * После DRY-рефактора `stripDangerousTags` делегирует в `sanitizeEmailHtml`
 * (script/iframe/object/embed/applet + **inline-обработчики `on*=`** + `javascript:`/`data:text/html`)
 * и дополнительно вырезает `<base>`/`<link>`. Комплексное покрытие самого `sanitizeEmailHtml` —
 * в `HtmlUtilsTest`; здесь проверяем именно интеграцию в редакторе (раньше обработчики НЕ резались).
 *
 * `RichTextEditorController` — обычный держатель Compose-состояния, конструируется в JVM-тесте.
 */
class RichTextEditorSanitizeTest {

    private val controller = RichTextEditorController()

    @Test
    fun `strips inline event handlers (regression for L-3)`() {
        val out = controller.stripDangerousTags("<img src=\"x\" onerror=\"steal()\">")
        assertThat(out).doesNotContain("onerror")
    }

    @Test
    fun `strips javascript and data-html URIs`() {
        assertThat(controller.stripDangerousTags("<a href=\"javascript:alert(1)\">L</a>"))
            .doesNotContain("javascript:")
        assertThat(controller.stripDangerousTags("<a href=\"data:text/html,x\">L</a>"))
            .doesNotContain("data:text/html")
    }

    @Test
    fun `strips script iframe base and link`() {
        val out = controller.stripDangerousTags(
            "<script>x</script><iframe src=\"e\"></iframe><base href=\"http://e/\"><link rel=\"stylesheet\">"
        )
        assertThat(out).doesNotContain("<script")
        assertThat(out).doesNotContain("<iframe")
        assertThat(out).doesNotContain("<base")
        assertThat(out).doesNotContain("<link")
    }

    @Test
    fun `preserves safe formatting`() {
        val safe = "<b>bold</b><table><tr><td>cell</td></tr></table><blockquote>quote</blockquote>"
        assertThat(controller.stripDangerousTags(safe)).isEqualTo(safe)
    }
}
