package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.eas.FakeExchange.Reply
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * email2:ConversationId в ответе Sync ([MS-ASEMAIL] 2.2.2.20, EAS 14.0+) — от HTTP и настоящего WBXML до письма.
 * ConversationIndex рядом не путается с ConversationId; у письма Exchange 2007 переписки нет.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EasConversationIdSyncTest {

    private fun FakeExchange.emailService(version: String): EasEmailService {
        val transport = transport(version)
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
            isExchange2007 = { version.startsWith("12") },
            buildEwsSoapRequest = { it },
            extractEwsError = { it }
        ))
    }

    private fun page(adds: String) =
        """<?xml version="1.0" encoding="utf-8"?><Sync xmlns="AirSync" xmlns:email="Email" xmlns:email2="Email2">""" +
            "<Collections><Collection><SyncKey>K2</SyncKey><CollectionId>2</CollectionId><Status>1</Status>" +
            "<Commands>$adds</Commands></Collection></Collections></Sync>"

    private fun add(serverId: String, subject: String, conversation: String) =
        "<Add><ServerId>$serverId</ServerId><ApplicationData><email:Subject>$subject</email:Subject>" +
            "<email:DateReceived>2026-10-01T10:00:00.000Z</email:DateReceived><email:Read>0</email:Read>" +
            "<email:MessageClass>IPM.Note</email:MessageClass>$conversation</ApplicationData></Add>"

    @Test
    fun `Exchange 2010 email carries its conversation id`() = runBlocking<Unit> {
        val exchange = FakeExchange {
            Reply.Wbxml(
                page(
                    add(
                        "2:1", "RE: Смета",
                        "<email2:ConversationIndex>AdcA1B2C3D4=</email2:ConversationIndex>" +
                            "<email2:ConversationId>AAQkADAwATM0MDAAMS1iNWM=</email2:ConversationId>"
                    )
                )
            )
        }

        val email = (exchange.emailService("14.1").sync("2", "K1") as EasResult.Success).data.emails.single()

        assertThat(email.conversationId).isEqualTo("AAQkADAwATM0MDAAMS1iNWM=")
    }

    @Test
    fun `Exchange 2007 email has no conversation`() = runBlocking<Unit> {
        val exchange = FakeExchange { Reply.Wbxml(page(add("2:1", "RE: Смета", ""))) }

        val email = (exchange.emailService("12.1").sync("2", "K1") as EasResult.Success).data.emails.single()

        assertThat(email.conversationId).isNull()
        assertThat(email.subject).isEqualTo("RE: Смета")
    }
}
