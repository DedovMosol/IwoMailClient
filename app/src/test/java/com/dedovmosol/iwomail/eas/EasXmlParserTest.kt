package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Поведенческие тесты потокового [EasXmlParser] (XmlPullParser, namespace-aware по префиксам).
 *
 * ЦЕЛЬ: зафиксировать ТОЧНОЕ поведение парсера ПЕРЕД поэтапной миграцией сервисов
 * (EasProvisioning → … → EasClient) с regex-пути (EasPatterns/XmlValueExtractor). Эти тесты —
 * контракт эквивалентности: на их основе будет проверяться, что переход не меняет разбор
 * реальных ответов Exchange 2007 SP2 … 2013 (EAS WBXML→XML и EWS SOAP).
 *
 * Robolectric обязателен: `EasXmlParser` использует `android.util.Xml.newPullParser()`, который
 * в чистом JUnit (isReturnDefaultValues=true) вернул бы null-заглушку.
 *
 * Ключевые свойства, которые ПИНуются ниже (расходятся с наивным regex — важно для миграции):
 *  - значение листового тега тримится и отдаётся как null, если пусто;
 *  - сущности (&amp; &lt; &gt;) РАЗворачиваются (в отличие от сырого regex-захвата);
 *  - для КОНТЕЙНЕРНОГО тега возвращается только текст глубины 1 (вложенная разметка НЕ попадает);
 *  - сопоставление тега учитывает namespace-префиксы строкой ("t:Subject" при nsPrefixes=["t"]);
 *  - forEachElement реконструирует inner-XML (с ре-экранированием) для совместимости миграции;
 *  - битый XML → null/пусто, без краша.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EasXmlParserTest {

    // ───────────────────────── extractValue: листовые теги ─────────────────────────

    @Test
    fun `extractValue returns leaf text`() {
        assertThat(EasXmlParser.extractValue("<Status>1</Status>", "Status")).isEqualTo("1")
    }

    @Test
    fun `extractValue trims surrounding whitespace`() {
        assertThat(EasXmlParser.extractValue("<a>  42  </a>", "a")).isEqualTo("42")
    }

    @Test
    fun `extractValue returns null for missing tag`() {
        assertThat(EasXmlParser.extractValue("<a>1</a>", "b")).isNull()
    }

    @Test
    fun `extractValue returns null for empty and whitespace-only element`() {
        assertThat(EasXmlParser.extractValue("<a></a>", "a")).isNull()
        assertThat(EasXmlParser.extractValue("<a>   </a>", "a")).isNull()
    }

    @Test
    fun `extractValue returns null for self-closing element`() {
        assertThat(EasXmlParser.extractValue("<Root><Foo/></Root>", "Foo")).isNull()
    }

    @Test
    fun `extractValue returns first match in document order`() {
        // Критично для EasProvisioning: Provision-level Status идёт ДО Policy-level в MS-ASPROV.
        val xml = "<Provision><Status>1</Status><Policies><Policy><Status>2</Status></Policy></Policies></Provision>"
        assertThat(EasXmlParser.extractValue(xml, "Status")).isEqualTo("1")
    }

    @Test
    fun `extractValue on container tag returns only depth-1 text (not nested markup)`() {
        // Policy содержит только вложенные элементы → текста глубины 1 нет → null.
        val xml = "<Policy><Status>1</Status><PolicyKey>99</PolicyKey></Policy>"
        assertThat(EasXmlParser.extractValue(xml, "Policy")).isNull()
    }

    @Test
    fun `extractValue on mixed content keeps only depth-1 text`() {
        assertThat(EasXmlParser.extractValue("<a>x<b>y</b>z</a>", "a")).isEqualTo("xz")
    }

    // ───────────────────────── сущности / CDATA ─────────────────────────

    @Test
    fun `extractValue unescapes standard entities`() {
        assertThat(EasXmlParser.extractValue("<a>Jack &amp; Jill</a>", "a")).isEqualTo("Jack & Jill")
        assertThat(EasXmlParser.extractValue("<a>&lt;tag&gt;</a>", "a")).isEqualTo("<tag>")
    }

    @Test
    fun `extractValue reads CDATA content verbatim`() {
        assertThat(EasXmlParser.extractValue("<a><![CDATA[<b>raw & co</b>]]></a>", "a"))
            .isEqualTo("<b>raw & co</b>")
    }

    // ───────────────────────── namespace-префиксы ─────────────────────────

    @Test
    fun `extractValue matches bare tag when prefix requested`() {
        assertThat(EasXmlParser.extractValue("<Subject>Hi</Subject>", "Subject", listOf("t"))).isEqualTo("Hi")
    }

    @Test
    fun `extractValue matches prefixed tag only when prefix listed`() {
        val xml = "<t:Subject>Hi</t:Subject>"
        assertThat(EasXmlParser.extractValue(xml, "Subject", listOf("t"))).isEqualTo("Hi")
        assertThat(EasXmlParser.extractValue(xml, "Subject", emptyList())).isNull()
    }

    // ───────────────────────── extractAll / attributes ─────────────────────────

    @Test
    fun `extractAll returns all values in order`() {
        val xml = "<r><ServerId>5:1</ServerId><ServerId>5:2</ServerId><ServerId>5:3</ServerId></r>"
        assertThat(EasXmlParser.extractAll(xml, "ServerId")).containsExactly("5:1", "5:2", "5:3").inOrder()
    }

    @Test
    fun `extractAttribute reads attribute with prefix`() {
        val xml = """<t:ItemId Id="AAA=" ChangeKey="CQ=="/>"""
        assertThat(EasXmlParser.extractAttribute(xml, "ItemId", "Id", listOf("t"))).isEqualTo("AAA=")
        assertThat(EasXmlParser.extractAttribute(xml, "ItemId", "ChangeKey", listOf("t"))).isEqualTo("CQ==")
    }

    @Test
    fun `extractAttribute returns null when attribute absent`() {
        assertThat(EasXmlParser.extractAttribute("""<t:ItemId Id="x"/>""", "ItemId", "ChangeKey", listOf("t")))
            .isNull()
    }

    // ───────────────────────── forEachElement / hasElement ─────────────────────────

    @Test
    fun `forEachElement iterates each element and inner XML is re-parseable`() {
        val xml = "<Commands>" +
            "<Add><ServerId>5:1</ServerId><Subject>A</Subject></Add>" +
            "<Add><ServerId>5:2</ServerId><Subject>B</Subject></Add>" +
            "</Commands>"
        val serverIds = mutableListOf<String>()
        val subjects = mutableListOf<String>()
        EasXmlParser.forEachElement(xml, "Add") { inner ->
            EasXmlParser.extractValue(inner, "ServerId")?.let { serverIds.add(it) }
            EasXmlParser.extractValue(inner, "Subject")?.let { subjects.add(it) }
        }
        assertThat(serverIds).containsExactly("5:1", "5:2").inOrder()
        assertThat(subjects).containsExactly("A", "B").inOrder()
    }

    @Test
    fun `forEachElement reconstructs self-closing child with attribute`() {
        val xml = "<Items><Message><t:ItemId Id=\"ID1\"/><t:Subject>Hello</t:Subject></Message></Items>"
        val ids = mutableListOf<String>()
        val subjects = mutableListOf<String>()
        EasXmlParser.forEachElement(xml, "Message") { inner ->
            EasXmlParser.extractAttribute(inner, "ItemId", "Id", listOf("t"))?.let { ids.add(it) }
            EasXmlParser.extractValue(inner, "Subject", listOf("t"))?.let { subjects.add(it) }
        }
        assertThat(ids).containsExactly("ID1")
        assertThat(subjects).containsExactly("Hello")
    }

    @Test
    fun `hasElement detects presence regardless of depth and prefix`() {
        assertThat(EasXmlParser.hasElement("<a><b><c/></b></a>", "c")).isTrue()
        assertThat(EasXmlParser.hasElement("<a><t:Flag/></a>", "Flag", listOf("t"))).isTrue()
        assertThat(EasXmlParser.hasElement("<a><b/></a>", "z")).isFalse()
    }

    // ───────────────────────── устойчивость ─────────────────────────

    @Test
    fun `lenient parser best-effort on unclosed tag, null on empty, no crash`() {
        // ФАКТ (пин реального поведения): парсер ЛЕНИВ — незакрытый тег отдаёт прочитанный
        // текст, НЕ бросает и НЕ возвращает null (расхождение с regex `<a>(.*?)</a>`, который
        // без закрывающего тега дал бы null). Важно при миграции: усечённый/оборванный ответ
        // Exchange даст ЧАСТИЧНЫЕ данные, а не пустоту — обрабатывающий код должен это учитывать.
        assertThat(EasXmlParser.extractValue("<a>unclosed", "a")).isEqualTo("unclosed")
        // Пустой вход → null, без краша.
        assertThat(EasXmlParser.extractValue("", "a")).isNull()
    }

    // ───────────────────────── реальные формы Exchange ─────────────────────────

    @Test
    fun `real EAS Provision response (default namespace) parses correctly`() {
        // MS-ASPROV: Provision-level Status (первый), затем Policies/Policy с собственным Status+PolicyKey.
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <Provision xmlns="Provision">
                <Status>1</Status>
                <Policies>
                    <Policy>
                        <PolicyType>MS-EAS-Provisioning-WBXML</PolicyType>
                        <Status>1</Status>
                        <PolicyKey>1294706673</PolicyKey>
                    </Policy>
                </Policies>
            </Provision>""".trimIndent()

        assertThat(EasXmlParser.extractValue(xml, "PolicyKey")).isEqualTo("1294706673")
        assertThat(EasXmlParser.extractValue(xml, "Status")).isEqualTo("1") // provision-level (первый)
        // Policy-блок доступен через forEachElement для раздельного Policy-level Status.
        val policyStatuses = mutableListOf<String>()
        EasXmlParser.forEachElement(xml, "Policy") { inner ->
            EasXmlParser.extractValue(inner, "Status")?.let { policyStatuses.add(it) }
        }
        assertThat(policyStatuses).containsExactly("1")
    }

    @Test
    fun `real EWS FindItem response (soap and t m prefixes) parses correctly`() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                           xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                           xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
              <soap:Body>
                <m:FindItemResponse>
                  <m:ResponseMessages>
                    <m:FindItemResponseMessage ResponseClass="Success">
                      <m:ResponseCode>NoError</m:ResponseCode>
                      <m:RootFolder>
                        <t:Items>
                          <t:Message>
                            <t:ItemId Id="AAAtAAA=" ChangeKey="CQAAAB=="/>
                            <t:Subject>Quarterly &amp; report</t:Subject>
                          </t:Message>
                        </t:Items>
                      </m:RootFolder>
                    </m:FindItemResponseMessage>
                  </m:ResponseMessages>
                </m:FindItemResponse>
              </soap:Body>
            </soap:Envelope>""".trimIndent()

        assertThat(EasXmlParser.extractValue(xml, "ResponseCode", listOf("m"))).isEqualTo("NoError")
        assertThat(EasXmlParser.extractAttribute(xml, "ItemId", "Id", listOf("t"))).isEqualTo("AAAtAAA=")
        assertThat(EasXmlParser.extractAttribute(xml, "ItemId", "ChangeKey", listOf("t"))).isEqualTo("CQAAAB==")
        assertThat(EasXmlParser.ews(xml, "Subject")).isEqualTo("Quarterly & report")
        assertThat(EasXmlParser.extractAttribute(xml, "FindItemResponseMessage", "ResponseClass", listOf("m")))
            .isEqualTo("Success")
    }

    @Test
    fun `DSL childText reads direct children in order`() {
        val xml = "<Policy><PolicyType>WBXML</PolicyType><Status>1</Status><PolicyKey>7</PolicyKey></Policy>"
        var type: String? = null
        var status: String? = null
        var key: String? = null
        // parse() позиционируется НА корневом START_TAG (Policy); блок читает ПРЯМЫХ детей
        // (onElement("Policy") искал бы Policy ВНУТРИ Policy — лишний уровень).
        EasXmlParser.parse(xml) {
            type = childText("PolicyType")
            status = childText("Status")
            key = childText("PolicyKey")
        }
        assertThat(type).isEqualTo("WBXML")
        assertThat(status).isEqualTo("1")
        assertThat(key).isEqualTo("7")
    }

    // Категории EAS (<Categories><Category>) читает EasSyncStream.readCategories — см. EasSyncStreamTest.
}
