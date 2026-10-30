package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Записи RSS-каналов Outlook ([MS-OXORSS]): класс элемента и ссылка на статью из тела. */
class RssItemsTest {

    /** Тело записи — как в примере [MS-OXORSS] 3.1 (PidTagHtml), с описанием, в котором тоже есть ссылка. */
    private val outlookBody = """
        <html><body><table><tr><td>Instant Search can help, see <a href="https://contoso.example/tips">tips</a>.</td></tr></table>
        <p><BR/><A HREF="https://www.contoso.example/article?id=7&amp;src=rss">View article...</A></p></body></html>
    """.trimIndent()

    @Test
    fun `RSS message class is recognised in any case and with subclasses`() {
        assertThat(RssItems.isRss("IPM.Post.RSS")).isTrue()
        assertThat(RssItems.isRss("ipm.post.rss")).isTrue()
        assertThat(RssItems.isRss("IPM.Post.Rss.Custom")).isTrue()
        assertThat(RssItems.isRss("IPM.Post")).isFalse()
        assertThat(RssItems.isRss("IPM.Post.RSSX")).isFalse()
        assertThat(RssItems.isRss("IPM.Note")).isFalse()
        assertThat(RssItems.isRss("")).isFalse()
        assertThat(RssItems.isRss(null)).isFalse()
    }

    @Test
    fun `article link is the last link Outlook appends, entities decoded`() {
        assertThat(RssItems.articleLink(outlookBody)).isEqualTo("https://www.contoso.example/article?id=7&src=rss")
    }

    @Test
    fun `non-web links are skipped`() {
        val body = """<p><a href="https://feed.example/a">A</a></p><p><a href='javascript:alert(1)'>x</a> <a href="mailto:a@b.example">m</a></p>"""

        assertThat(RssItems.articleLink(body)).isEqualTo("https://feed.example/a")
    }

    @Test
    fun `links with credentials or without host are not offered`() {
        assertThat(RssItems.articleLink("""<a href="https://user:pw@feed.example/a">x</a>""")).isNull()
        assertThat(RssItems.articleLink("""<a href="https:///a">x</a>""")).isNull()
    }

    @Test
    fun `plain text body takes the last web address without trailing punctuation`() {
        val text = "Read more at http://news.example/a, or the full story: https://news.example/story/42."

        assertThat(RssItems.articleLink(text)).isEqualTo("https://news.example/story/42")
    }

    @Test
    fun `body without a link gives nothing`() {
        assertThat(RssItems.articleLink("<p>No link here</p>")).isNull()
        // HTML со ссылками, но без веб-ссылок, — не ищем адреса в тексте атрибутов
        assertThat(RssItems.articleLink("""<a href="mailto:a@b.example">https://x.example</a>""")).isNull()
        assertThat(RssItems.articleLink("")).isNull()
    }
}
