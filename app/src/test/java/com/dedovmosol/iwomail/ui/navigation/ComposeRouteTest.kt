package com.dedovmosol.iwomail.ui.navigation

import android.net.Uri
import com.dedovmosol.iwomail.util.MailtoLink
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Маршрут экрана письма переносит все поля ссылки mailto: (NAV-13): копия и скрытая копия тоже. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeRouteTest {

    private fun args(route: String): Map<String, String?> {
        val uri = Uri.parse("app://$route")
        return Screen.Compose.argNames.associateWith { Screen.Compose.decodeId(uri.getQueryParameter(it)) }
    }

    @Test
    fun `a mailto link travels to the compose screen whole`() {
        val link = MailtoLink(
            to = listOf("a@corp.example", "b@corp.example"),
            cc = listOf("c@corp.example"),
            bcc = listOf("d@corp.example"),
            subject = "Q&A? 50/50 #1",
            body = "Line 1\nLine 2 & more"
        )

        val args = args(Screen.Compose.createRoute(link))

        assertThat(args["toEmail"]).isEqualTo("a@corp.example, b@corp.example")
        assertThat(args["cc"]).isEqualTo("c@corp.example")
        assertThat(args["bcc"]).isEqualTo("d@corp.example")
        assertThat(args["subject"]).isEqualTo("Q&A? 50/50 #1")
        assertThat(args["body"]).isEqualTo("Line 1\nLine 2 & more")
        assertThat(args["replyTo"]).isNull()
        assertThat(args["draftId"]).isNull()
    }

    @Test
    fun `every argument of the route is declared`() {
        for (name in Screen.Compose.argNames) {
            assertThat(Screen.Compose.route).contains("$name={$name}")
        }
        assertThat(Screen.Compose.createRoute()).isEqualTo("compose")
    }
}
