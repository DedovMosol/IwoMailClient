package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.eas.FakeExchange.Reply
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Флаг письма через EAS Sync ([MS-ASEMAIL] Flag, EAS 12.1 — Exchange 2007 SP1) — от HTTP до разбора: запись Change
 * и проверка Status ответа, разбор изменений флага, сделанных в Outlook.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EasEmailFlagServerTest {

    private fun FakeExchange.emailService(): EasEmailService {
        val transport = transport("12.1")
        return EasEmailService(EasEmailService.EmailServiceDependencies(
            executeEasCommand = object : EasEmailService.EasCommandExecutor {
                override suspend fun <T> invoke(command: String, xml: String, parser: (String) -> T): EasResult<T> =
                    transport.executeEasCommand(command, xml, parser = parser)
            },
            executeRequest = { throw UnsupportedOperationException("unused") },
            buildUrl = { it },
            getAuthHeader = { "" },
            getPolicyKey = { null },
            getEasVersion = { transport.easVersion },
            isVersionDetected = { true },
            detectEasVersion = { EasResult.Success(transport.easVersion) },
            provision = { EasResult.Error("unused") },
            extractValue = { xml, tag -> XmlUtils.extractTagValue(xml, tag) },
            escapeXml = { XmlUtils.escape(it) },
            wbxmlParser = WbxmlParser(),
            getFromEmail = { "user@corp.example" },
            getDeviceId = { "androidtestdevice01" },
            performNtlmHandshake = { null },
            executeNtlmRequest = { _, _ -> null },
            getDeletedItemsFolderId = { null },
            isExchange2007 = { true },
            buildEwsSoapRequest = { it },
            extractEwsError = { it }
        ))
    }

    private val root = """<?xml version="1.0" encoding="utf-8"?><Sync xmlns="AirSync" xmlns:email="Email" xmlns:tasks="Tasks">"""

    private fun changeResponse(changeStatus: Int?) =
        "$root<Collections><Collection><SyncKey>K2</SyncKey><CollectionId>5</CollectionId><Status>1</Status>" +
            (changeStatus?.let { "<Responses><Change><ServerId>5:1</ServerId><Status>$it</Status></Change></Responses>" } ?: "") +
            "</Collection></Collections></Sync>"

    @Test
    fun `flag with a due date is written to the server and the new sync key is returned`() = runBlocking<Unit> {
        val exchange = FakeExchange { call -> if (call.command == "Sync") Reply.Wbxml(changeResponse(null)) else Reply.Http(500) }
        // Срок — локальные дни (полночь в поясе устройства), как их задаёт меню флага
        val today = com.dedovmosol.iwomail.domain.FlagPresets.startOfDay(System.currentTimeMillis(), java.util.TimeZone.getDefault())
        val friday = java.util.Calendar.getInstance().apply { timeInMillis = today; add(java.util.Calendar.DAY_OF_YEAR, 3) }.timeInMillis
        val flag = MailFlag.due(today, friday, friday + 9 * 3_600_000L)

        val result = exchange.emailService().setFlag("5", "5:1", "K1", flag)

        assertThat(result).isEqualTo(EasResult.Success("K2"))
        val request = exchange.calls.single { it.command == "Sync" }.xml
        assertThat(request).contains("<ServerId>5:1</ServerId>")
        assertThat(request).contains("<FlagStatus>2</FlagStatus>")
        assertThat(request).contains("<FlagType>for Follow Up</FlagType>")
        assertThat(MailFlags.parse(XmlUtils.extractTagValue(request, "Flag"))).isEqualTo(flag)
    }

    @Test
    fun `flag the server rejects is an error`() = runBlocking<Unit> {
        val exchange = FakeExchange { Reply.Wbxml(changeResponse(6)) }

        val result = exchange.emailService().setFlag("5", "5:1", "K1", MailFlag(MailFlag.STATUS_ACTIVE))

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        assertThat((result as EasResult.Error).message).contains("Change Status=6")
    }

    @Test
    fun `stale sync key is an error`() = runBlocking<Unit> {
        val exchange = FakeExchange {
            Reply.Wbxml("$root<Collections><Collection><SyncKey>0</SyncKey><CollectionId>5</CollectionId><Status>3</Status></Collection></Collections></Sync>")
        }

        val result = exchange.emailService().setFlag("5", "5:1", "K1", MailFlag.NONE)

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
    }

    @Test
    fun `flag changes made in Outlook are read in full`() = runBlocking<Unit> {
        val changes = listOf(
            // Срок и напоминание
            "<Change><ServerId>5:1</ServerId><ApplicationData><email:Flag><email:FlagStatus>2</email:FlagStatus>" +
                "<email:FlagType>Follow up</email:FlagType><tasks:StartDate>2026-10-30T00:00:00.000Z</tasks:StartDate>" +
                "<tasks:DueDate>2026-10-30T00:00:00.000Z</tasks:DueDate><tasks:UtcStartDate>2026-10-29T21:00:00.000Z</tasks:UtcStartDate>" +
                "<tasks:UtcDueDate>2026-10-29T21:00:00.000Z</tasks:UtcDueDate><tasks:ReminderSet>1</tasks:ReminderSet>" +
                "<tasks:ReminderTime>2026-10-30T06:00:00.000Z</tasks:ReminderTime></email:Flag></ApplicationData></Change>",
            // Выполнено
            "<Change><ServerId>5:2</ServerId><ApplicationData><email:Flag><email:FlagStatus>1</email:FlagStatus>" +
                "<email:CompleteTime>2026-10-29T12:00:00.000Z</email:CompleteTime></email:Flag></ApplicationData></Change>",
            // Снят (пустой Flag)
            "<Change><ServerId>5:3</ServerId><ApplicationData><email:Flag/></ApplicationData></Change>",
            // Флаг не менялся — только прочитано
            "<Change><ServerId>5:4</ServerId><ApplicationData><email:Read>1</email:Read></ApplicationData></Change>"
        )
        val exchange = FakeExchange { call ->
            if (call.command == "Sync") Reply.Wbxml(EasSyncXml.page("K2", "5", changes, more = false)) else Reply.Http(500)
        }

        val result = exchange.emailService().sync("5", "K1", windowSize = 50, fullBody = false)

        val byId = (result as EasResult.Success).data.changedEmails.associateBy { it.serverId }
        val dated = byId.getValue("5:1").flag!!
        assertThat(dated.active).isTrue()
        assertThat(dated.dueDate).isNotNull()
        assertThat(dated.reminderTime).isEqualTo(java.time.Instant.parse("2026-10-30T06:00:00Z").toEpochMilli())
        assertThat(byId.getValue("5:2").flag).isEqualTo(MailFlag(MailFlag.STATUS_COMPLETE))
        assertThat(byId.getValue("5:3").flag).isEqualTo(MailFlag.NONE)
        assertThat(byId.getValue("5:4").flag).isNull()
    }
}
