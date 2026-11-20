package com.dedovmosol.iwomail.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/**
 * MAIL-03: линейный санитайзер даёт тот же результат, что эталон на регулярных выражениях ([LegacySanitizer]: прежний
 * плюс шаг MAIL-09), и не зависает на «письме-яде». Эталон медленный только на ядовитых входах, поэтому сверка идёт на
 * коротких случайных строках из «опасных» фрагментов.
 */
class HtmlUtilsSanitizePerformanceTest {

    private fun legacySanitize(html: String) = LegacySanitizer.sanitize(html)

    private val fragments = listOf(
        "<script>", "</script>", "<SCRIPT a=1>", "</ScRiPt>", "<scripts>", "<script", "</scr", "ipt>",
        "<iframe>", "</iframe>", "<IfRaMe src=x>", "<object>", "</object>", "<applet>", "</applet>",
        "<embed>", "<embed src=x/>", "<embedx>", "<EMBED", "<meta http-equiv=refresh content=0>", "<meta charset=utf-8>",
        "<META HTTP-EQUIV='Refresh'>", "<metax http-equiv=refresh>", " onclick=\"x\"", " onload='y'", " onerror=z", "  on",
        " one", "\tonx = \"", "'", "\"", " href=\"javascript:a\"", " src='javascript:b'", " href=\"data:text/html,c\"",
        " formaction='data:text/html;x'", "javascript:", "x", "Привет", ">", "<", "</", "\n", " ", "  ", "<b>", "</b>",
        "<p style=\"color:red\">", "=", "İ", "ſcript", "_",
        // MAIL-09
        "<form action=x>", "</FORM>", "<fo", "rm>", "<input type=password>", "<inputs>", "<INPUT", "<button>", "</button>",
        "<textarea>", "<select>", "</select>", "<base href=//e/>", "<basefont>", "<link rel=dns-prefetch href=//e>",
        "<linked>", "<use href=//e#x>", "<user>", "<use-x>", "<frame src=x>", "<frameset>", "</frameset>", "<form-x>", "-"
    )

    @Test fun `same result as the legacy regex sanitizer on 200 000 random fragments`() {
        val r = Random(20261117)
        var diff = 0
        val examples = mutableListOf<String>()
        repeat(200_000) {
            val s = buildString { repeat(1 + r.nextInt(24)) { append(fragments[r.nextInt(fragments.size)]) } }
            if (sanitizeEmailHtml(s) != legacySanitize(s)) {
                diff++
                if (examples.size < 5) examples += s
            }
        }
        assertThat(examples).isEmpty()
        assertThat(diff).isEqualTo(0)
    }

    @Test fun `known payloads are sanitized exactly as before`() {
        val cases = listOf(
            "<scr<script>ipt>alert(1)</scr</script>ipt>",
            "<p>a</p><script>x</script><p>b</p>",
            "<SCRIPT>x</script>tail",
            "<iframe src=//e>x</IFRAME><iframe>",
            "<div onclick=\"x\"><script>e</script><a href=\"javascript:y\">L</a></div>",
            "<img/onerror=alert(1) src=x>", "<svg/onload=alert(1)>",
            "<meta http-equiv=\"refresh\" content=\"0;url=evil\"><p>ok</p>",
            "<embed src=x><embedded>keep</embedded>",
            "<form action=\"https://evil.example\"><input type=password name=p><button>Sign in</button></form>",
            "<fo<input>rm action=x>", "<base href=\"https://evil.example/\"><a href=\"login\">L</a>"
        )
        for (c in cases) assertThat(sanitizeEmailHtml(c)).isEqualTo(legacySanitize(c))
    }

    /** Каждый вход ниже прежний санитайзер обрабатывал секунды-минуты (квадратичный рост). */
    @Test fun `poison emails are sanitized in linear time`() {
        val n = 200_000
        val poisons = listOf(
            "<iframe>".repeat(n),
            "<script>".repeat(n),
            "<object>".repeat(n),
            "<applet>".repeat(n),
            "<script".repeat(n),            // нет '>'
            "</iframe".repeat(n),
            "<embed ".repeat(n),
            "<meta ".repeat(n) + ">",       // нет http-equiv, один '>' в самом конце
            " ".repeat(n * 4) + "x",        // длинный пробельный отрезок
            " o".repeat(n),
            " onx=\"".repeat(n / 2),
            "<input".repeat(n),             // нет '>'
            "<use ".repeat(n) + ">",
            "<u".repeat(n),                 // начало имени без совпадения
            "</form>".repeat(n),
            "<p>" + "a".repeat(n * 4) + "</p>"
        )
        for (p in poisons) {
            val t = System.nanoTime()
            sanitizeEmailHtml(p)
            val ms = (System.nanoTime() - t) / 1_000_000
            assertThat(ms).isLessThan(1_500L)
        }
    }

    @Test fun `ordinary 2 MB email stays fast and unchanged`() {
        val normal = "<table><tr><td style=\"padding:4px\">Отчёт <b>за ноябрь</b> <a href=\"https://corp.example/r\">ссылка</a></td></tr></table>"
            .repeat(20_000)
        val t = System.nanoTime()
        val out = sanitizeEmailHtml(normal)
        assertThat((System.nanoTime() - t) / 1_000_000).isLessThan(1_500L)
        assertThat(out).isEqualTo(normal)
    }
}
