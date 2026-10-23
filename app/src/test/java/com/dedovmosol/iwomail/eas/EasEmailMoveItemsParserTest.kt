package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Характеризация [EasEmailService.parseMoveItemsResponse] ПЕРЕД миграцией fragile regex
 * `<Response>(.*?)</Response>` → [EasXmlParser].
 *
 * MoveItems-ответ (MS-ASCMD 2.2.1.9) — голые теги (WBXML Move code page 5), МАЛЫЙ (только
 * SrcMsgId/DstMsgId/Status, без тел писем), поэтому EasXmlParser здесь уместен и дёшев.
 * Контракт: только Status=3 (успех) с обоими Id попадает в map src→dst.
 *
 * NB: горячий разбор Sync (parseSyncResponse/parseEmailFromXml) НЕ мигрируется и остаётся на
 * XmlUtils (глубинный разбор + без материализации крупного тела на каждом поле — вопрос
 * производительности на критическом пути). Здесь только холодный MoveItems.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EasEmailMoveItemsParserTest {

    private fun service(): EasEmailService {
        val deps = EasEmailService.EmailServiceDependencies(
            executeEasCommand = object : EasEmailService.EasCommandExecutor {
                override suspend fun <T> invoke(command: String, xml: String, parser: (String) -> T): EasResult<T> =
                    EasResult.Error("unused in parse tests")
            },
            executeRequest = { throw UnsupportedOperationException("unused") },
            buildUrl = { it },
            getAuthHeader = { "" },
            getPolicyKey = { null },
            getEasVersion = { "14.1" },
            isVersionDetected = { true },
            detectEasVersion = { EasResult.Error("unused") },
            provision = { EasResult.Error("unused") },
            extractValue = { xml, tag -> XmlUtils.extractTagValue(xml, tag) },
            escapeXml = { it },
            wbxmlParser = WbxmlParser(),
            getFromEmail = { "" },
            getDeviceId = { "" },
            performNtlmHandshake = { null },
            executeNtlmRequest = { _, _ -> null },
            getDeletedItemsFolderId = { null },
            isExchange2007 = { false },
            buildEwsSoapRequest = { it },
            extractEwsError = { "" }
        )
        return EasEmailService(deps)
    }

    @Test
    fun `parseMoveItemsResponse keeps only Status 3 with both ids, maps src to dst`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<MoveItems>
<Response><SrcMsgId>5:1</SrcMsgId><DstMsgId>9:1</DstMsgId><Status>3</Status></Response>
<Response><SrcMsgId>5:2</SrcMsgId><DstMsgId>9:2</DstMsgId><Status>3</Status></Response>
<Response><SrcMsgId>5:3</SrcMsgId><Status>1</Status></Response>
<Response><SrcMsgId>5:4</SrcMsgId><DstMsgId>9:4</DstMsgId><Status>2</Status></Response>
<Response><DstMsgId>9:5</DstMsgId><Status>3</Status></Response>
</MoveItems>""".trimIndent()

        val result = service().parseMoveItemsResponse(xml)

        // 5:1 и 5:2 — Status=3 + оба Id. 5:3 (нет Dst, Status=1), 5:4 (Status=2), без Src — отброшены.
        assertThat(result).containsExactly("5:1", "9:1", "5:2", "9:2")
    }

    @Test
    fun `parseMoveItemsResponse returns empty when no successful responses`() {
        val xml = """<MoveItems>
<Response><SrcMsgId>5:1</SrcMsgId><DstMsgId>9:1</DstMsgId><Status>2</Status></Response>
<Response><Status>3</Status></Response>
</MoveItems>""".trimIndent()

        assertThat(service().parseMoveItemsResponse(xml)).isEmpty()
    }

    @Test
    fun `parseMoveItemsResponse returns empty for response without Response blocks`() {
        assertThat(service().parseMoveItemsResponse("<MoveItems><Status>1</Status></MoveItems>")).isEmpty()
    }
}
