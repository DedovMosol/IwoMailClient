package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.eas.FakeExchange.Reply
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * Фото из глобальной адресной книги — EAS ResolveRecipients с Picture ([MS-ASCMD] 2.2.1.15): версия протокола, запрос,
 * разбор статусов и путь от HTTP и настоящего WBXML до байтов фото. Exchange 2007 (12.1) и 2010 без SP1 (14.0) не
 * получают ни одного запроса.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContactPicturesTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46)
    private val jpegBase64 = Base64.getEncoder().encodeToString(jpeg)

    private fun response(status: Int = 1, vararg responses: String) =
        """<?xml version="1.0" encoding="utf-8"?><ResolveRecipients xmlns="ResolveRecipients"><Status>$status</Status>""" +
            responses.joinToString("") + "</ResolveRecipients>"

    /** Response одного адреса; [recipients] — Recipient-блоки. */
    private fun answer(to: String, status: Int, vararg recipients: String) =
        "<Response><To>$to</To><Status>$status</Status><RecipientCount>${recipients.size}</RecipientCount>" +
            recipients.joinToString("") + "</Response>"

    private fun recipient(address: String, picture: String? = null) =
        "<Recipient><Type>1</Type><DisplayName>Коллега</DisplayName><EmailAddress>$address</EmailAddress>" +
            (picture ?: "") + "</Recipient>"

    private fun picture(status: Int, data: String? = null) =
        "<Picture><Status>$status</Status>" + (data?.let { "<Data>$it</Data>" } ?: "") + "</Picture>"

    // === Версия протокола ===

    @Test
    fun `pictures need protocol 14_1 or later`() {
        for (version in listOf("2.5", "12.0", "12.1", "14.0")) assertThat(ContactPictures.supported(version)).isFalse()
        for (version in listOf("14.1", "16.0", "16.1")) assertThat(ContactPictures.supported(version)).isTrue()
        assertThat(ContactPictures.supported("")).isFalse()
        assertThat(ContactPictures.supported("abc")).isFalse()
    }

    // === Запрос ===

    @Test
    fun `request asks one exact match and one picture per address`() {
        val xml = ContactPictures.request(listOf("a@corp.example", "b&c@corp.example"), XmlUtils::escape)

        assertThat(xml).contains("<To>a@corp.example</To><To>b&amp;c@corp.example</To>")
        assertThat(xml).contains("<MaxAmbiguousRecipients>1</MaxAmbiguousRecipients>")
        assertThat(xml).contains("<Picture><MaxSize>102400</MaxSize><MaxPictures>1</MaxPictures></Picture>")
        // Порядок элементов Options по схеме: сначала MaxAmbiguousRecipients, затем Picture
        assertThat(xml.indexOf("<MaxAmbiguousRecipients>")).isLessThan(xml.indexOf("<Picture>"))
    }

    // === Разбор ответа ===

    @Test
    fun `found photo is returned by the asked address in lower case`() {
        val photos = ContactPictures.parse(response(1, answer("Boss@Corp.Example", 1, recipient("boss@corp.example", picture(1, jpegBase64)))))

        assertThat(photos!!.keys).containsExactly("boss@corp.example")
        assertThat(photos.getValue("boss@corp.example")).isEqualTo(jpeg)
    }

    @Test
    fun `no photo, too large photo and unknown address are remembered as no photo`() {
        val photos = ContactPictures.parse(
            response(
                1,
                answer("a@corp.example", 1, recipient("a@corp.example", picture(173))),
                answer("b@corp.example", 1, recipient("b@corp.example", picture(174))),
                answer("external@mail.example", 4)
            )
        )!!

        assertThat(photos.keys).containsExactly("a@corp.example", "b@corp.example", "external@mail.example")
        assertThat(photos.values.all { it == null }).isTrue()
    }

    @Test
    fun `picture limit leaves the address unknown to ask again`() {
        val photos = ContactPictures.parse(response(1, answer("a@corp.example", 1, recipient("a@corp.example", picture(175)))))!!

        assertThat(photos).isEmpty()
    }

    @Test
    fun `single recipient with another address is the asked one`() {
        // Отправитель писал с дополнительного адреса ящика: сервер нашёл ящик, а EmailAddress — основной адрес
        val photos = ContactPictures.parse(
            response(1, answer("ivan.alias@corp.example", 1, recipient("ivan@corp.example", picture(1, jpegBase64))))
        )!!

        assertThat(photos.getValue("ivan.alias@corp.example")).isEqualTo(jpeg)
    }

    @Test
    fun `ambiguous match is not taken for the asked address`() {
        val photos = ContactPictures.parse(
            response(1, answer("ivan@corp.example", 2, recipient("ivan.petrov@corp.example", picture(1, jpegBase64))))
        )!!

        assertThat(photos.keys).containsExactly("ivan@corp.example")
        assertThat(photos.getValue("ivan@corp.example")).isNull()
    }

    @Test
    fun `broken or oversized data is no photo`() {
        val oversized = Base64.getEncoder().encodeToString(ByteArray(ContactPictures.MAX_SIZE_BYTES + 1))
        val photos = ContactPictures.parse(
            response(
                1,
                answer("a@corp.example", 1, recipient("a@corp.example", picture(1, "@@not base64@@"))),
                answer("b@corp.example", 1, recipient("b@corp.example", picture(1, oversized))),
                answer("c@corp.example", 1, recipient("c@corp.example", picture(1)))
            )
        )!!

        assertThat(photos).containsExactly("a@corp.example", null, "b@corp.example", null, "c@corp.example", null)
    }

    @Test
    fun `failed command gives no answer at all`() {
        assertThat(ContactPictures.parse(response(5))).isNull()
        assertThat(ContactPictures.parse(response(6))).isNull()
        assertThat(ContactPictures.parse("<ResolveRecipients/>")).isNull()
    }

    // === Запрос фото: версия, ошибки ===

    @Test
    fun `old protocol is unsupported without a request`() = runBlocking<Unit> {
        for (version in listOf("12.1", "14.0")) {
            var requests = 0
            val result = ContactPictures.lookup(listOf("a@corp.example"), { EasResult.Success(version) }) {
                requests++
                EasResult.Success(emptyMap())
            }
            assertThat(result).isEqualTo(ContactPictures.Lookup.Unsupported)
            assertThat(requests).isEqualTo(0)
        }
    }

    @Test
    fun `failed version detection and failed command are temporary failures`() = runBlocking<Unit> {
        val noVersion = ContactPictures.lookup(listOf("a@corp.example"), { EasResult.Error("offline") }) { error("not sent") }
        assertThat(noVersion).isEqualTo(ContactPictures.Lookup.Failed("offline"))

        val httpError = ContactPictures.lookup(listOf("a@corp.example"), { EasResult.Success("14.1") }) { EasResult.Error("HTTP 503") }
        assertThat(httpError).isEqualTo(ContactPictures.Lookup.Failed("HTTP 503"))

        val badStatus = ContactPictures.lookup(listOf("a@corp.example"), { EasResult.Success("14.1") }) { EasResult.Success(null) }
        assertThat(badStatus).isInstanceOf(ContactPictures.Lookup.Failed::class.java)
    }

    @Test
    fun `one request carries at most a batch of addresses`() = runBlocking<Unit> {
        var request = ""
        val addresses = (1..30).map { "user$it@corp.example" }
        ContactPictures.lookup(addresses, { EasResult.Success("14.1") }) {
            request = it
            EasResult.Success(emptyMap())
        }

        assertThat(Regex("<To>").findAll(request).count()).isEqualTo(ContactPictures.BATCH_SIZE)
    }

    @Test
    fun `nothing to ask sends nothing`() = runBlocking<Unit> {
        val result = ContactPictures.lookup(emptyList(), { error("version not needed") }) { error("not sent") }

        assertThat(result).isEqualTo(ContactPictures.Lookup.Found(emptyMap()))
    }

    // === От HTTP и WBXML до фото ===

    private fun FakeExchange.lookup(version: String, addresses: List<String>): ContactPictures.Lookup = runBlocking {
        val transport = transport(version)
        ContactPictures.lookup(addresses, { EasResult.Success(transport.easVersion) }) { request ->
            transport.executeEasCommand("ResolveRecipients", request) { ContactPictures.parse(it) }
        }
    }

    @Test
    fun `Exchange 2010 SP1 returns photos through real WBXML`() {
        val exchange = FakeExchange { call ->
            if (call.command != "ResolveRecipients") Reply.Http(500)
            else Reply.Wbxml(
                response(
                    1,
                    answer("boss@corp.example", 1, recipient("boss@corp.example", picture(1, jpegBase64))),
                    answer("new@corp.example", 1, recipient("new@corp.example", picture(173)))
                )
            )
        }

        val result = exchange.lookup("14.1", listOf("boss@corp.example", "new@corp.example"))

        val photos = (result as ContactPictures.Lookup.Found).photos
        assertThat(photos.getValue("boss@corp.example")).isEqualTo(jpeg)
        assertThat(photos.getValue("new@corp.example")).isNull()
        // Запрос дошёл до сервера целым: кодовая страница ResolveRecipients, все элементы Options
        val sent = exchange.calls.single().xml
        assertThat(sent).contains("<To>boss@corp.example</To><To>new@corp.example</To>")
        assertThat(sent).contains("<MaxAmbiguousRecipients>1</MaxAmbiguousRecipients>")
        assertThat(sent).contains("<MaxSize>102400</MaxSize>")
        assertThat(sent).contains("<MaxPictures>1</MaxPictures>")
    }

    @Test
    fun `Exchange 2007 gets no request at all`() {
        val exchange = FakeExchange { Reply.Http(500) }

        assertThat(exchange.lookup("12.1", listOf("boss@corp.example"))).isEqualTo(ContactPictures.Lookup.Unsupported)
        assertThat(exchange.calls).isEmpty()
    }

    @Test
    fun `server error is a temporary failure`() {
        val exchange = FakeExchange { Reply.Http(503) }

        assertThat(exchange.lookup("14.1", listOf("boss@corp.example"))).isInstanceOf(ContactPictures.Lookup.Failed::class.java)
    }

    // === OPAQUE Data в WBXML ===

    /** Ответ, собранный по токенам вручную: Data фото — OPAQUE (массив байтов, как его описывает [MS-ASCMD]). */
    @Test
    fun `opaque picture data is decoded as bytes, opaque body data stays text`() {
        val out = java.io.ByteArrayOutputStream()
        fun bytes(vararg values: Int) = values.forEach { out.write(it) }
        fun text(tag: Int, value: String) {
            bytes(tag or 0x40, 0x03)
            out.write(value.toByteArray(Charsets.UTF_8))
            bytes(0x00, 0x01)
        }
        bytes(0x03, 0x01, 0x6A, 0x00) // WBXML 1.3, UTF-8, без таблицы строк
        bytes(0x00, 0x0A) // SWITCH_PAGE ResolveRecipients
        bytes(0x45) // ResolveRecipients
        text(0x07, "1") // Status
        bytes(0x46) // Response
        text(0x10, "boss@corp.example") // To
        text(0x07, "1")
        bytes(0x49) // Recipient
        text(0x0B, "boss@corp.example") // EmailAddress
        bytes(0x5A) // Picture
        text(0x07, "1")
        bytes(0x5C, 0xC3, jpeg.size) // Data, OPAQUE, длина
        out.write(jpeg)
        bytes(0x01, 0x01, 0x01, 0x01) // Data, Picture, Recipient, Response
        bytes(0x00, 0x11, 0x4B, 0xC3, 5) // SWITCH_PAGE AirSyncBase, Data, OPAQUE «hello»
        out.write("hello".toByteArray())
        bytes(0x01, 0x01) // Data, ResolveRecipients

        val xml = WbxmlParser().parse(out.toByteArray())

        assertThat(ContactPictures.parse(xml)!!.getValue("boss@corp.example")).isEqualTo(jpeg)
        assertThat(xml).contains("<Data>hello</Data>")
    }
}
