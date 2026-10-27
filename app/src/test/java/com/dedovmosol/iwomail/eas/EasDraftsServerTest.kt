package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.eas.FakeExchange.Reply
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Черновики на сервере — от HTTP до разбора ответа ([FakeExchange], реальный транспорт EAS/EWS).
 * Exchange 2007 SP1 (EAS 12.1, EWS Exchange2007_SP1) — минимальная поддерживаемая версия.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EasDraftsServerTest {

    private fun FakeExchange.draftsService(easVersion: String = "12.1"): EasDraftsService {
        val transport = transport(easVersion)
        return EasDraftsService(EasDraftsService.DraftsServiceDependencies(
            executeEasCommand = object : EasDraftsService.EasCommandExecutor {
                override suspend fun <T> invoke(command: String, xml: String, parser: (String) -> T): EasResult<T> =
                    transport.executeEasCommand(command, xml, parser = parser)
            },
            folderSync = { EasResult.Success(FolderSyncResponse("1", emptyList())) },
            refreshSyncKey = { _, key -> EasResult.Success(key) },
            extractValue = { xml, tag -> XmlUtils.extractTagValue(xml, tag) },
            escapeXml = { XmlUtils.escape(it) },
            getEasVersion = { transport.easVersion },
            isVersionDetected = { true },
            detectEasVersion = { EasResult.Success(transport.easVersion) },
            performNtlmHandshake = { transport.performNtlmHandshake() },
            executeNtlmRequest = { request, action -> transport.executeNtlmRequest(request, action) },
            tryBasicAuthEws = { request, action -> transport.executeEwsWithAuth(request, action) },
            getDraftsFolderId = { "5" }
        ))
    }

    private fun message(id: String) = """<t:Message><t:ItemId Id="$id" ChangeKey="CK-$id"/></t:Message>"""
    private fun FakeExchange.ews(command: String) = calls.filter { it.isEws && it.command == command }

    @Test
    fun `update clears an emptied Cc and does not touch Bcc it does not know`() = runBlocking<Unit> {
        val exchange = FakeExchange { call ->
            when (call.command) {
                "GetItem" -> Reply.Text(EwsXml.getItem(listOf(message("AAMkD1="))))
                "UpdateItem" -> Reply.Text(EwsXml.success("UpdateItem", message("AAMkD1=")))
                else -> Reply.Http(500)
            }
        }

        val result = exchange.draftsService().updateDraft(
            "AAMkD1=", to = "a@corp.example", cc = "", bcc = null, subject = "S", body = "<p>B</p>", importance = 2
        )

        assertThat(result).isEqualTo(EasResult.Success(true))
        val update = exchange.ews("UpdateItem").single().xml
        assertThat(update).contains("""ChangeKey="CK-AAMkD1="""")
        assertThat(update).contains("""<t:DeleteItemField><t:FieldURI FieldURI="message:CcRecipients"/></t:DeleteItemField>""")
        assertThat(update).doesNotContain("BccRecipients")
        assertThat(update).contains("<t:Importance>High</t:Importance>")
    }

    @Test
    fun `update of a draft that is gone fails without UpdateItem`() = runBlocking<Unit> {
        val exchange = FakeExchange { call ->
            if (call.command == "GetItem") Reply.Text(EwsXml.getItem(listOf(null))) else Reply.Http(500)
        }

        val result = exchange.draftsService().updateDraft("AAMkGONE=", "a@corp.example", "", "", "S", "B")

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        // Текст ошибки EWS (MessageText) — его покажет экран письма.
        assertThat((result as EasResult.Error).message).contains("was not found in the store")
        assertThat(exchange.ews("UpdateItem")).isEmpty()
    }

    @Test
    fun `failed attachment removes the half-made draft and never borrows another draft by subject`() = runBlocking<Unit> {
        val exchange = FakeExchange { call ->
            when {
                call.command == "CreateItem" && call.xml.contains("MimeContent") ->
                    Reply.Text(EwsXml.error("CreateItem", "ErrorMimeContentConversionFailed"))
                call.command == "CreateItem" -> Reply.Text(EwsXml.success("CreateItem", message("AAMkNEW=")))
                call.command == "CreateAttachment" -> Reply.Text(EwsXml.error("CreateAttachment", "ErrorAttachmentSizeLimitExceeded"))
                call.command == "DeleteItem" -> Reply.Text(EwsXml.success("DeleteItem"))
                // Старая версия пересохраняемого черновика с той же темой — её id нельзя выдавать за новый.
                call.command == "FindItem" -> Reply.Text(EwsXml.findItem(message("AAMkOLD="), total = 1, includesLast = true))
                else -> Reply.Http(500)
            }
        }
        val attachment = DraftAttachmentData("report.txt", "text/plain", "data".toByteArray())

        val result = exchange.draftsService().createDraft(
            "a@corp.example", "", "", "Отчёт", "<p>B</p>", listOf(attachment)
        )

        // Не сохранён на сервере — локальный черновик; вызывающий не удалит прежний.
        assertThat((result as EasResult.Success).data).startsWith(DraftIds.LOCAL_PREFIX)
        assertThat(exchange.ews("FindItem")).isEmpty()
        val cleanup = exchange.ews("DeleteItem").single().xml
        assertThat(cleanup).contains("""DeleteType="HardDelete"""")
        assertThat(cleanup).contains("""Id="AAMkNEW="""")
    }

    @Test
    fun `bcc and importance of a MIME draft are set explicitly after CreateItem`() = runBlocking<Unit> {
        val exchange = FakeExchange { call ->
            when (call.command) {
                "CreateItem" -> Reply.Text(EwsXml.success("CreateItem", message("AAMkMIME=")))
                "UpdateItem" -> Reply.Text(EwsXml.success("UpdateItem", message("AAMkMIME=")))
                else -> Reply.Http(500)
            }
        }
        val inline = DraftAttachmentData("image_1.png", "image/png", byteArrayOf(1, 2, 3), isInline = true, contentId = "img1_1")

        val result = exchange.draftsService().createDraft(
            "a@corp.example", "", "boss@corp.example", "S", """<img src="cid:img1_1">""", listOf(inline), importance = 2
        )

        assertThat(result).isEqualTo(EasResult.Success("AAMkMIME="))
        assertThat(exchange.ews("CreateItem").single().xml).contains("<t:MimeContent")
        val extras = exchange.ews("UpdateItem").single().xml
        assertThat(extras).contains("<t:EmailAddress>boss@corp.example</t:EmailAddress>")
        assertThat(extras).contains("<t:Importance>High</t:Importance>")
        // Тело и тема MIME-черновика этим запросом не перезаписываются.
        assertThat(extras).doesNotContain("item:Body")
    }

    @Test
    fun `plain create sends importance and recipients in schema order`() = runBlocking<Unit> {
        val exchange = FakeExchange { call ->
            if (call.command == "CreateItem") Reply.Text(EwsXml.success("CreateItem", message("AAMkP="))) else Reply.Http(500)
        }

        val result = exchange.draftsService("14.1").createDraft("a@corp.example", "c@corp.example", "", "S", "<p>B</p>", importance = 0)

        assertThat(result).isEqualTo(EasResult.Success("AAMkP="))
        val create = exchange.ews("CreateItem").single().xml
        assertThat(create).contains("""<t:RequestServerVersion Version="Exchange2010_SP1"/>""")
        assertThat(create.indexOf("<t:Importance>Low</t:Importance>")).isLessThan(create.indexOf("<t:ToRecipients>"))
        assertThat(create).doesNotContain("BccRecipients")
    }
}
