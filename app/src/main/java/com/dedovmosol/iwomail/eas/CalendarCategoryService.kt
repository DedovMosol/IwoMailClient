package com.dedovmosol.iwomail.eas

import android.util.Xml
import com.dedovmosol.iwomail.util.CalendarCategories
import com.dedovmosol.iwomail.util.OutlookCategoryPalette
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** Категория из Master Category List Outlook: имя и индекс цвета [MS-OXOCFG] (-1..24). */
data class OutlookCategory(val name: String, val color: Int)

/**
 * Master Category List ящика через EWS GetUserConfiguration — реальные цвета категорий Outlook.
 *
 * [MS-OXOCFG] 2.2.5.2.2: список хранится в FAI-сообщении `IPM.Configuration.CategoryList` папки
 * «Календарь», XML-документ — в PidTagRoamingXmlStream. EWS отдаёт его как `t:XmlData` (base64Binary)
 * по имени конфигурации "CategoryList".
 *
 * Версии: объекты пользовательской конфигурации и операция GetUserConfiguration есть с Exchange 2010
 * (RequestServerVersion Exchange2010). На Exchange 2007 (EAS 12.x) запрос не отправляется вовсе —
 * там цвет задают стандартные имена и ручные назначения.
 */
internal class CalendarCategoryService(
    private val ewsRequest: suspend (soapBody: String, operation: String) -> EasResult<String>,
    private val easMajorVersion: suspend () -> Int
) {
    /**
     * Success(null) — сервер не поддерживает список (Exchange 2007); Success(list) — актуальный
     * список (пустой, если у ящика его нет: ErrorItemNotFound); Error — сбой, прежние данные
     * вызывающий сохраняет.
     */
    suspend fun fetchCategoryList(): EasResult<List<OutlookCategory>?> {
        if (easMajorVersion() < MIN_EAS_MAJOR) return EasResult.Success(null)
        return when (val response = ewsRequest(REQUEST, OPERATION)) {
            is EasResult.Success -> parseResponse(response.data)
            is EasResult.Error -> response
        }
    }

    companion object {
        /** EAS 14.0 — Exchange 2010: первая версия с GetUserConfiguration. */
        const val MIN_EAS_MAJOR = 14
        private const val OPERATION = "GetUserConfiguration"

        /** Сервер SHOULD ограничивать документ 512 КБ ([MS-OXOCFG] Reading Category List); берём вдвое. */
        internal const val MAX_XML_BYTES = 1024 * 1024

        internal val REQUEST = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header>
        <t:RequestServerVersion Version="Exchange2010"/>
    </soap:Header>
    <soap:Body>
        <m:GetUserConfiguration>
            <m:UserConfigurationName Name="CategoryList">
                <t:DistinguishedFolderId Id="calendar"/>
            </m:UserConfigurationName>
            <m:UserConfigurationProperties>XmlData</m:UserConfigurationProperties>
        </m:GetUserConfiguration>
    </soap:Body>
</soap:Envelope>"""

        private val RESPONSE_CODE = Regex("<(?:m:)?ResponseCode>\\s*([^<\\s]+)\\s*</(?:m:)?ResponseCode>")
        private val XML_DATA = Regex("<(?:t:)?XmlData>([^<]*)</(?:t:)?XmlData>")

        internal fun parseResponse(xml: String): EasResult<List<OutlookCategory>?> {
            val code = RESPONSE_CODE.find(xml)?.groupValues?.get(1)
                ?: return EasResult.Error("$OPERATION: no ResponseCode")
            if (code == "ErrorItemNotFound") return EasResult.Success(emptyList())
            if (code != "NoError") return EasResult.Error("$OPERATION: $code")
            val data = XML_DATA.find(xml)?.groupValues?.get(1)?.trim()
            if (data.isNullOrEmpty()) return EasResult.Success(emptyList())
            return try {
                EasResult.Success(parseXmlData(data))
            } catch (e: Exception) {
                EasResult.Error("$OPERATION: invalid CategoryList (${e.message})")
            }
        }

        /** base64 из `t:XmlData` → категории. Документ — UTF-8 ([MS-OXOCFG] XML Streams), возможно с BOM. */
        internal fun parseXmlData(base64: String): List<OutlookCategory> {
            require(base64.length <= MAX_XML_BYTES / 3 * 4 + 4) { "CategoryList larger than $MAX_XML_BYTES bytes" }
            val bytes = java.util.Base64.getMimeDecoder().decode(base64)
            return parseCategoryListXml(String(bytes, Charsets.UTF_8))
        }

        /**
         * `<categories xmlns="CategoryList.xsd"><category name=".." color=".." .../></categories>`.
         * Имена уникальны без учёта регистра — повтор отбрасывается; пустое имя — запись пропускается.
         * DOCTYPE не обрабатывается (без внешних сущностей).
         */
        internal fun parseCategoryListXml(xml: String): List<OutlookCategory> {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            try { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) } catch (_: Exception) {}
            parser.setInput(StringReader(xml.removePrefix("\uFEFF")))
            val byKey = LinkedHashMap<String, OutlookCategory>()
            while (true) {
                val event = parser.next()
                if (event == XmlPullParser.END_DOCUMENT) break
                if (event != XmlPullParser.START_TAG || parser.name.substringAfter(':') != "category") continue
                val name = parser.getAttributeValue(null, "name")?.trim().orEmpty()
                if (name.isEmpty()) continue
                val color = OutlookCategoryPalette.normalize(parser.getAttributeValue(null, "color")?.trim()?.toIntOrNull())
                byKey.putIfAbsent(CalendarCategories.key(name), OutlookCategory(name, color))
            }
            return byKey.values.toList()
        }
    }
}
