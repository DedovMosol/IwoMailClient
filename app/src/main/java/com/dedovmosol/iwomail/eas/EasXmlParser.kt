package com.dedovmosol.iwomail.eas

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Утилита для парсинга XML на базе XmlPullParser.
 * Заменяет Regex-подход (EasPatterns, XmlValueExtractor, extractValue) потоковым парсером.
 * 
 * Поддерживает:
 * - EAS XML (WBXML→XML): namespace prefixes airsyncbase:, calendar:, tasks:, notes:,
 *   contacts:, gal:, email:, email2:, composemail:, airsync:
 * - EWS SOAP XML (Exchange 2007 SP1+): namespace prefixes t:, m:, soap:
 * - Оба режима: namespace-aware и namespace-ignorant
 * 
 * Использование:
 * 
 * 1. Drop-in замена extractValue:
 *    val status = EasXmlParser.extractValue(xml, "Status")
 *    val subject = EasXmlParser.extractValue(xml, "Subject", listOf("t", "tasks"))
 * 
 * 2. Извлечение атрибута:
 *    val itemId = EasXmlParser.extractAttribute(xml, "ItemId", "Id", listOf("t"))
 * 
 * 3. Извлечение всех совпадений:
 *    val serverIds = EasXmlParser.extractAll(xml, "ServerId")
 * 
 * 4. Обход элементов:
 *    EasXmlParser.forEachElement(xml, "Add") { addXml ->
 *        val serverId = EasXmlParser.extractValue(addXml, "ServerId")
 *        // ...
 *    }
 * 
 * 5. Структурированный парсинг (DSL):
 *    val emails = EasXmlParser.parse(xml) {
 *        forEachChild("Add") {
 *            val serverId = childText("ServerId")
 *            val subject = childText("Subject")
 *            // ...
 *        }
 *    }
 * 
 * Миграция (v1.7.0):
 * Поэтапно заменяем regex-вызовы (EasPatterns/XmlValueExtractor) потоковым парсером:
 * EasProvisioning → EasContactsService → EasAttachmentService → EasNotesService →
 * EasTasksService → EasDraftsService → EasEmailService → EasCalendarService → EasClient
 *
 * После завершения миграции EasPatterns и XmlValueExtractor удаляются.
 */
object EasXmlParser {

    private const val TAG = "EasXmlParser"

    // ═══════════════════════════════════════════════════════════════
    // Drop-in замены для extractValue / XmlValueExtractor
    // ═══════════════════════════════════════════════════════════════

    /**
     * Извлекает текстовое содержимое первого найденного тега.
     * Эквивалент: deps.extractValue(xml, "Status")
     * 
     * @param xml XML-строка
     * @param tag имя тега без namespace (например "Status", "SyncKey")
     * @param nsPrefixes опциональные namespace prefixes для поиска (например listOf("t", "m"))
     *                   Поиск идёт: сначала с prefix, затем без
     * @return текстовое содержимое тега или null
     */
    fun extractValue(xml: String, tag: String, nsPrefixes: List<String> = emptyList()): String? {
        return try {
            val parser = createParser(xml)
            findTagValue(parser, tag, nsPrefixes)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "extractValue($tag) failed: ${e.message}")
            null
        }
    }

    /**
     * Извлекает атрибут элемента.
     * Эквивалент: XmlValueExtractor.extractAttribute(xml, "ItemId", "Id")
     * 
     * @param xml XML-строка
     * @param element имя элемента (например "ItemId")
     * @param attribute имя атрибута (например "Id")
     * @param nsPrefixes namespace prefixes (например listOf("t"))
     * @return значение атрибута или null
     */
    fun extractAttribute(
        xml: String,
        element: String,
        attribute: String,
        nsPrefixes: List<String> = emptyList()
    ): String? {
        return try {
            val parser = createParser(xml)
            findAttribute(parser, element, attribute, nsPrefixes)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "extractAttribute($element.$attribute) failed: ${e.message}")
            null
        }
    }

    /**
     * Извлекает ВСЕ значения тега (не только первое).
     * Эквивалент: regex.findAll(xml).map { ... }
     * 
     * @return список всех найденных значений
     */
    fun extractAll(xml: String, tag: String, nsPrefixes: List<String> = emptyList()): List<String> {
        return try {
            val parser = createParser(xml)
            findAllTagValues(parser, tag, nsPrefixes)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "extractAll($tag) failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Извлекает ВСЕ значения атрибута.
     * Например: все ItemId Id= из ответа FindItem
     */
    fun extractAllAttributes(
        xml: String,
        element: String,
        attribute: String,
        nsPrefixes: List<String> = emptyList()
    ): List<String> {
        return try {
            val parser = createParser(xml)
            findAllAttributes(parser, element, attribute, nsPrefixes)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "extractAllAttributes($element.$attribute) failed: ${e.message}")
            emptyList()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Обход элементов (замена regex findAll + forEach)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Вызывает [block] для каждого элемента [tag], передавая XML-содержимое элемента.
     * 
     * Эквивалент:
     *   val pattern = "<Add>(.*?)</Add>".toRegex(DOT_MATCHES_ALL)
     *   pattern.findAll(xml).forEach { block(it.groupValues[1]) }
     * 
     * ВНИМАНИЕ: [block] получает inner XML как строку (для совместимости при миграции).
     * В будущем перейдём на DSL-подход с XmlScope.
     */
    fun forEachElement(
        xml: String,
        tag: String,
        nsPrefixes: List<String> = emptyList(),
        block: (innerXml: String) -> Unit
    ) {
        try {
            val parser = createParser(xml)
            iterateElements(parser, tag, nsPrefixes, block)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "forEachElement($tag) failed: ${e.message}")
        }
    }

    /**
     * Собирает все inner XML блоки элемента [tag] в список.
     */
    fun collectElements(
        xml: String,
        tag: String,
        nsPrefixes: List<String> = emptyList()
    ): List<String> {
        val result = mutableListOf<String>()
        forEachElement(xml, tag, nsPrefixes) { result.add(it) }
        return result
    }

    // ═══════════════════════════════════════════════════════════════
    // DSL для структурированного парсинга
    // ═══════════════════════════════════════════════════════════════

    /**
     * Парсит XML через DSL-подход.
     * 
     * Использование:
     *   EasXmlParser.parse(soapXml) {
     *       onElement("Envelope") {
     *           onElement("Body") {
     *               onElement("FindItemResponse") {
     *                   val status = childText("ResponseCode")
     *               }
     *           }
     *       }
     *   }
     */
    fun parse(xml: String, block: XmlScope.() -> Unit) {
        try {
            val parser = createParser(xml)
            val scope = XmlScope(parser)
            while (parser.eventType != XmlPullParser.START_TAG) {
                parser.next()
            }
            scope.block()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "parse() failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Специализированные методы (для удобства, как в XmlValueExtractor)
    // ═══════════════════════════════════════════════════════════════

    /** EAS Notes: namespace notes: */
    fun note(xml: String, tag: String): String? = extractValue(xml, tag, listOf("notes"))

    /** EAS Calendar: namespace calendar: */
    fun calendar(xml: String, tag: String): String? = extractValue(xml, tag, listOf("calendar"))

    /** EAS Tasks: namespace tasks: */
    fun task(xml: String, tag: String): String? = extractValue(xml, tag, listOf("tasks"))

    /** EAS Contacts: namespace contacts: */
    fun contact(xml: String, tag: String): String? = extractValue(xml, tag, listOf("contacts"))

    /** EAS GAL: namespace gal: */
    fun gal(xml: String, tag: String): String? = extractValue(xml, tag, listOf("gal"))

    /** EWS: namespaces t:, m: */
    fun ews(xml: String, tag: String): String? = extractValue(xml, tag, listOf("t", "m"))

    /** EAS AirSyncBase: namespace airsyncbase: */
    fun airsync(xml: String, tag: String): String? = extractValue(xml, tag, listOf("airsyncbase"))

    /** EAS Email: namespace email: */
    fun email(xml: String, tag: String): String? = extractValue(xml, tag, listOf("email"))

    /** EAS Email2: namespace email2: */
    fun email2(xml: String, tag: String): String? = extractValue(xml, tag, listOf("email2"))

    /** EAS ComposeMail: namespace composemail: */
    fun composeMail(xml: String, tag: String): String? = extractValue(xml, tag, listOf("composemail"))

    /** SOAP Envelope: namespace soap: */
    fun soap(xml: String, tag: String): String? = extractValue(xml, tag, listOf("soap"))

    // ═══════════════════════════════════════════════════════════════
    // Внутренние методы
    // ═══════════════════════════════════════════════════════════════

    private fun createParser(xml: String): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        try { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) } catch (_: Exception) {}
        try { parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
        parser.setInput(StringReader(xml))
        return parser
    }

    /**
     * Проверяет совпадение имени тега с учётом namespace prefixes.
     * "t:Subject" совпадает с tag="Subject", nsPrefixes=["t"]
     * "Subject" совпадает с tag="Subject", nsPrefixes=[]
     */
    internal fun matchesTag(parserName: String, tag: String, nsPrefixes: List<String>): Boolean {
        // Точное совпадение (без prefix)
        if (parserName == tag) return true
        // С prefix (например "t:Subject")
        for (ns in nsPrefixes) {
            if (parserName == "$ns:$tag") return true
        }
        return false
    }

    private fun findTagValue(
        parser: XmlPullParser,
        tag: String,
        nsPrefixes: List<String>
    ): String? {
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && matchesTag(parser.name, tag, nsPrefixes)) {
                return readText(parser)
            }
            eventType = parser.next()
        }
        return null
    }

    private fun findAllTagValues(
        parser: XmlPullParser,
        tag: String,
        nsPrefixes: List<String>
    ): List<String> {
        val results = mutableListOf<String>()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && matchesTag(parser.name, tag, nsPrefixes)) {
                readText(parser)?.let { results.add(it) }
            }
            eventType = parser.next()
        }
        return results
    }

    private fun findAttribute(
        parser: XmlPullParser,
        element: String,
        attribute: String,
        nsPrefixes: List<String>
    ): String? {
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && matchesTag(parser.name, element, nsPrefixes)) {
                // Ищем атрибут по имени (без namespace — т.к. FEATURE_PROCESS_NAMESPACES=false)
                for (i in 0 until parser.attributeCount) {
                    if (parser.getAttributeName(i) == attribute) {
                        return parser.getAttributeValue(i)
                    }
                }
            }
            eventType = parser.next()
        }
        return null
    }

    private fun findAllAttributes(
        parser: XmlPullParser,
        element: String,
        attribute: String,
        nsPrefixes: List<String>
    ): List<String> {
        val results = mutableListOf<String>()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && matchesTag(parser.name, element, nsPrefixes)) {
                for (i in 0 until parser.attributeCount) {
                    if (parser.getAttributeName(i) == attribute) {
                        results.add(parser.getAttributeValue(i) ?: "")
                        break
                    }
                }
            }
            eventType = parser.next()
        }
        return results
    }

    /**
     * Читает текстовое содержимое текущего элемента.
     * Обрабатывает как простой текст, так и mixed content (собирает весь текст до END_TAG).
     */
    internal fun readText(parser: XmlPullParser): String? {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.TEXT -> if (depth == 1) sb.append(parser.text)
                XmlPullParser.CDSECT -> if (depth == 1) sb.append(parser.text)
                XmlPullParser.ENTITY_REF -> if (depth == 1) sb.append(parser.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        val result = sb.toString().trim()
        return result.ifEmpty { null }
    }

    /**
     * Обходит элементы [tag] и вызывает [block] с inner XML каждого.
     * Inner XML извлекается как сырой текст между START_TAG и END_TAG.
     * 
     * Для совместимости при поэтапной миграции — передаёт inner XML как String,
     * чтобы существующий код парсинга внутри мог работать без изменений.
     */
    private fun iterateElements(
        parser: XmlPullParser,
        tag: String,
        nsPrefixes: List<String>,
        block: (String) -> Unit
    ) {
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && matchesTag(parser.name, tag, nsPrefixes)) {
                val innerXml = readInnerXml(parser)
                block(innerXml)
            }
            eventType = parser.next()
        }
    }

    /**
     * Читает всё содержимое текущего элемента как сырой XML-текст.
     * Используется для forEachElement — чтобы передать inner XML в block.
     * 
     * ВАЖНО: XmlPullParser не даёт доступа к сырому XML.
     * Поэтому реконструируем XML из событий парсера.
     */
    private fun readInnerXml(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1

        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    val isEmpty = parser.isEmptyElementTag
                    sb.append("<${parser.name}")
                    for (i in 0 until parser.attributeCount) {
                        sb.append(" ${parser.getAttributeName(i)}=\"${escapeXmlAttr(parser.getAttributeValue(i) ?: "")}\"")
                    }
                    if (isEmpty) {
                        sb.append("/>")
                        // Android KXmlParser всё равно генерирует END_TAG для empty elements,
                        // поэтому пропускаем его чтобы не дублировать: <foo/></foo>
                        parser.next() // skip END_TAG
                    } else {
                        sb.append(">")
                        depth++
                    }
                }
                XmlPullParser.END_TAG -> {
                    depth--
                    if (depth > 0) {
                        sb.append("</${parser.name}>")
                    }
                }
                XmlPullParser.TEXT -> {
                    sb.append(escapeXmlText(parser.text))
                }
                XmlPullParser.ENTITY_REF -> {
                    sb.append("&${parser.name};")
                }
                XmlPullParser.CDSECT -> {
                    sb.append("<![CDATA[${parser.text}]]>")
                }
                XmlPullParser.END_DOCUMENT -> break
            }
        }

        return sb.toString()
    }

    private fun escapeXmlAttr(value: String): String {
        return value.replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun escapeXmlText(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    // ═══════════════════════════════════════════════════════════════
    // Вспомогательные функции
    // ═══════════════════════════════════════════════════════════════

    /**
     * Проверяет, содержит ли XML указанный элемент (любой вложенности).
     * Быстрее чем xml.contains("<Tag") — корректно обрабатывает namespaces.
     */
    fun hasElement(xml: String, tag: String, nsPrefixes: List<String> = emptyList()): Boolean {
        return try {
            val parser = createParser(xml)
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && matchesTag(parser.name, tag, nsPrefixes)) {
                    return true
                }
                eventType = parser.next()
            }
            false
        } catch (e: Exception) {
            android.util.Log.w(TAG, "hasElement($tag) failed: ${e.message}")
            false
        }
    }

    /**
     * Пропускает текущий элемент и все его дочерние (как в Android docs).
     */
    fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) {
            throw IllegalStateException("Expected START_TAG, got ${parser.eventType}")
        }
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }
}

/**
 * DSL-scope для структурированного парсинга XML.
 * Используется через EasXmlParser.parse(xml) { ... }
 */
class XmlScope(private val parser: XmlPullParser) {

    /**
     * Получает текстовое содержимое дочернего элемента по имени.
     * Ищет только среди прямых дочерних элементов текущего уровня.
     * 
     * ВНИМАНИЕ: после вызова парсер перемещается вперёд.
     * Вызывать childText для разных тегов нужно в порядке их появления в XML.
     */
    fun childText(tag: String, nsPrefixes: List<String> = emptyList()): String? {
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType == XmlPullParser.END_DOCUMENT) return null
            if (parser.eventType != XmlPullParser.START_TAG) continue

            if (EasXmlParser.matchesTag(parser.name, tag, nsPrefixes)) {
                return EasXmlParser.readText(parser)
            } else {
                EasXmlParser.skip(parser)
            }
        }
        return null
    }

    /**
     * Обрабатывает каждый дочерний элемент с указанным именем.
     */
    fun forEachChild(tag: String, nsPrefixes: List<String> = emptyList(), block: XmlScope.() -> Unit) {
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType == XmlPullParser.END_DOCUMENT) return
            if (parser.eventType != XmlPullParser.START_TAG) continue

            if (EasXmlParser.matchesTag(parser.name, tag, nsPrefixes)) {
                val childScope = XmlScope(parser)
                childScope.block()
                // Убедимся что дочерний scope дочитал до END_TAG
                skipToEndTag()
            } else {
                EasXmlParser.skip(parser)
            }
        }
    }

    /**
     * Обрабатывает каждый START_TAG дочерний элемент (независимо от имени).
     * Имя доступно через [currentTag].
     */
    fun forEachChild(block: XmlScope.(tagName: String) -> Unit) {
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType == XmlPullParser.END_DOCUMENT) return
            if (parser.eventType != XmlPullParser.START_TAG) continue

            val tagName = parser.name
            val childScope = XmlScope(parser)
            childScope.block(tagName)
            skipToEndTag()
        }
    }

    /**
     * Заходит в дочерний элемент с указанным именем и выполняет block внутри.
     */
    fun onElement(tag: String, nsPrefixes: List<String> = emptyList(), block: XmlScope.() -> Unit) {
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType == XmlPullParser.END_DOCUMENT) return
            if (parser.eventType != XmlPullParser.START_TAG) continue

            if (EasXmlParser.matchesTag(parser.name, tag, nsPrefixes)) {
                val childScope = XmlScope(parser)
                childScope.block()
                skipToEndTag()
                return
            } else {
                EasXmlParser.skip(parser)
            }
        }
    }

    /**
     * Получает значение атрибута текущего элемента.
     */
    fun attr(name: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == name) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    /**
     * Читает текст текущего элемента.
     */
    fun text(): String? = EasXmlParser.readText(parser)

    /**
     * Имя текущего тега (без namespace prefix).
     */
    val currentTag: String get() = parser.name?.substringAfter(":") ?: ""

    /**
     * Полное имя текущего тега (с namespace prefix).
     */
    val currentTagRaw: String get() = parser.name ?: ""

    private fun skipToEndTag() {
        if (parser.eventType == XmlPullParser.END_TAG) return
        var depth = if (parser.eventType == XmlPullParser.START_TAG) 1 else 0
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }
}
