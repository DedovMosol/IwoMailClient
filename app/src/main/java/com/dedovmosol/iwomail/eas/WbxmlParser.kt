package com.dedovmosol.iwomail.eas

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * WBXML Parser/Generator для Exchange ActiveSync
 * Поддерживает EAS 2.5, 12.0, 12.1 (Exchange 2007)
 */
class WbxmlParser {
    
    companion object {
        // WBXML константы
        private const val WBXML_VERSION = 0x03
        private const val WBXML_UNKNOWN_PI = 0x01
        private const val WBXML_CHARSET_UTF8 = 0x6A
        
        // Токены
        private const val TOKEN_SWITCH_PAGE = 0x00
        private const val TOKEN_END = 0x01
        private const val TOKEN_STR_I = 0x03  // Inline string
        private const val TOKEN_OPAQUE = 0xC3
        
        // Флаги тегов
        private const val TAG_HAS_CONTENT = 0x40
        private const val TAG_HAS_ATTRS = 0x80
    }
    
    private var currentCodePage = 0
    
    /**
     * Парсит WBXML в XML строку
     */
    fun parse(data: ByteArray): String {
        val input = ByteArrayInputStream(data)
        val result = StringBuilder()
        
        // Читаем заголовок
        val version = input.read()
        val publicId = readMultiByteInt(input)
        val charset = readMultiByteInt(input)
        val stringTableLength = readMultiByteInt(input)
        
        // Пропускаем string table
        if (stringTableLength > 0) {
            input.skip(stringTableLength.toLong())
        }
        
        currentCodePage = 0
        result.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        
        parseBody(input, result)
        
        return result.toString()
    }
    
    private fun parseBody(input: InputStream, result: StringBuilder) {
        val tagStack = mutableListOf<String>()
        
        while (input.available() > 0) {
            val token = input.read()
            if (token == -1) break
            
            when (token) {
                TOKEN_SWITCH_PAGE -> {
                    currentCodePage = input.read()
                }
                TOKEN_END -> {
                    if (tagStack.isNotEmpty()) {
                        val tag = tagStack.removeAt(tagStack.lastIndex)
                        result.append("</$tag>")
                    }
                }
                TOKEN_STR_I -> {
                    result.append(readString(input))
                }
                TOKEN_OPAQUE -> {
                    val length = readMultiByteInt(input)
                    val bytes = ByteArray(length)
                    input.read(bytes)
                    result.append(String(bytes, Charsets.UTF_8))
                }
                else -> {
                    val tagId = token and 0x3F
                    val hasContent = (token and TAG_HAS_CONTENT) != 0
                    val hasAttrs = (token and TAG_HAS_ATTRS) != 0
                    
                    val tagName = getTagName(currentCodePage, tagId)
                    result.append("<$tagName")
                    
                    if (hasAttrs) {
                        // Пропускаем атрибуты (редко используются в EAS)
                        while (true) {
                            val attrToken = input.read()
                            if (attrToken == TOKEN_END) break
                        }
                    }
                    
                    if (hasContent) {
                        result.append(">")
                        tagStack.add(tagName)
                    } else {
                        result.append("/>")
                    }
                }
            }
        }
    }

    
    private fun readString(input: InputStream): String {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val b = input.read()
            if (b == 0 || b == -1) break
            bytes.add(b.toByte())
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
    
    private fun readMultiByteInt(input: InputStream): Int {
        var result = 0
        while (true) {
            val b = input.read()
            result = (result shl 7) or (b and 0x7F)
            if ((b and 0x80) == 0) break
        }
        return result
    }
    
    /**
     * Генерирует WBXML из XML строки
     */
    fun generate(xml: String): ByteArray {
        val output = ByteArrayOutputStream()
        
        // Заголовок WBXML
        output.write(WBXML_VERSION)  // 0x03
        output.write(WBXML_UNKNOWN_PI)  // 0x01
        output.write(WBXML_CHARSET_UTF8)  // 0x6A
        output.write(0x00) // String table length
        
        // ВАЖНО: Начинаем с неопределённой страницы (-1)
        // чтобы первый тег обязательно записал SWITCH_PAGE
        currentCodePage = -1
        generateBody(xml, output)
        
        val result = output.toByteArray()
        return result
    }
    
    private fun generateBody(xml: String, output: ByteArrayOutputStream) {
        val parser = SimpleXmlParser(xml)
        val events = parser.parse()
        
        // Стек namespace для отслеживания контекста
        val namespaceStack = mutableListOf<String?>()
        var currentNamespace: String? = null
        
        for (event in events) {
            when (event) {
                is XmlEvent.StartTag -> {
                    // Если у тега есть свой namespace, используем его
                    // Иначе используем текущий из стека
                    val effectiveNamespace = event.namespace ?: currentNamespace
                    
                    // Запоминаем namespace для этого уровня
                    namespaceStack.add(event.namespace)
                    if (event.namespace != null) {
                        currentNamespace = event.namespace
                    }
                    
                    // Получаем tagId с учётом namespace
                    val (codePage, tagId) = getTagIdWithContext(event.name, effectiveNamespace)
                    
                    if (codePage != currentCodePage) {
                        output.write(TOKEN_SWITCH_PAGE)
                        output.write(codePage)
                        currentCodePage = codePage
                    }
                    
                    val token = tagId or (if (event.hasContent) TAG_HAS_CONTENT else 0)
                    output.write(token)
                }
                is XmlEvent.EndTag -> {
                    output.write(TOKEN_END)
                    
                    // Восстанавливаем namespace из стека
                    if (namespaceStack.isNotEmpty()) {
                        val poppedNs = namespaceStack.removeAt(namespaceStack.lastIndex)
                        // Если закрыли тег с namespace, возвращаемся к предыдущему
                        if (poppedNs != null && namespaceStack.isNotEmpty()) {
                            currentNamespace = namespaceStack.lastOrNull { it != null }
                        }
                    }
                }
                is XmlEvent.Text -> {
                    output.write(TOKEN_STR_I)
                    output.write(event.text.toByteArray(Charsets.UTF_8))
                    output.write(0x00)
                }
            }
        }
    }
    
    /**
     * Получает tagId с учётом контекста namespace
     * Это важно для тегов с одинаковыми именами на разных code pages (например Status)
     */
    private fun getTagIdWithContext(tagName: String, namespace: String?): Pair<Int, Int> {
        // Маппинг namespace -> code page
        val namespaceToPage = mapOf(
            "Provision" to 14,
            "FolderHierarchy" to 7,
            "AirSync" to 0,
            "Settings" to 18,
            "Email" to 2,
            "Calendar" to 4,
            "Contacts" to 1,
            "Search" to 15,
            "GAL" to 16,
            "AirSyncBase" to 17,
            "ComposeMail" to 21,
            "ItemOperations" to 20,
            "Move" to 5
        )
        
        val preferredPage = namespace?.let { namespaceToPage[it] }
        
        // Если есть предпочтительная страница, сначала ищем там
        if (preferredPage != null) {
            val result = EasCodePages.getTagIdOnPage(tagName, preferredPage)
            if (result != null) {
                return result
            }
        }
        
        // Fallback на обычный поиск
        return EasCodePages.getTagId(tagName)
    }
    
    /**
     * Генерирует WBXML для SendMail с opaque MIME данными
     * ComposeMail namespace (code page 21)
     * Теги согласно AOSP Tags.java:
     * - SendMail = 0x05
     * Согласно MS-ASWBXML Code Page 21 (ComposeMail):
     * - SendMail = 0x05
     * - ClientId = 0x11
     * - SaveInSentItems = 0x08
     * - Mime = 0x10
     */
    fun generateSendMail(clientId: String, mimeData: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        
        // Заголовок WBXML
        output.write(WBXML_VERSION)  // 0x03
        output.write(WBXML_UNKNOWN_PI)  // 0x01
        output.write(WBXML_CHARSET_UTF8)  // 0x6A
        output.write(0x00) // String table length
        
        // ComposeMail code page = 21 (0x15)
        output.write(TOKEN_SWITCH_PAGE)
        output.write(21)
        
        // <SendMail> tag = 0x05 with content
        output.write(0x05 or TAG_HAS_CONTENT)
        
        // <ClientId> tag = 0x11 with content
        output.write(0x11 or TAG_HAS_CONTENT)
        output.write(TOKEN_STR_I)
        output.write(clientId.toByteArray(Charsets.UTF_8))
        output.write(0x00)
        output.write(TOKEN_END) // </ClientId>
        
        // <SaveInSentItems/> tag = 0x08 without content
        output.write(0x08)
        
        // <Mime> tag = 0x10 with opaque content
        output.write(0x10 or TAG_HAS_CONTENT)
        output.write(TOKEN_OPAQUE)
        writeMultiByteInt(output, mimeData.size)
        output.write(mimeData)
        output.write(TOKEN_END) // </Mime>
        
        output.write(TOKEN_END) // </SendMail>
        
        val result = output.toByteArray()
        return result
    }
    
    private fun writeMultiByteInt(output: ByteArrayOutputStream, value: Int) {
        if (value == 0) {
            output.write(0)
            return
        }
        
        if (value == 0) {
            output.write(0)
            return
        }
        
        val bytes = mutableListOf<Int>()
        var v = value
        while (v > 0) {
            bytes.add(0, v and 0x7F)
            v = v shr 7
        }
        
        for (i in 0 until bytes.size - 1) {
            output.write(bytes[i] or 0x80)
        }
        output.write(bytes.last())
    }
    
    // EAS Code Pages и теги
    private fun getTagName(codePage: Int, tagId: Int): String {
        return EasCodePages.getTagName(codePage, tagId)
    }
    
    private fun getTagId(tagName: String): Pair<Int, Int> {
        return EasCodePages.getTagId(tagName)
    }
}

sealed class XmlEvent {
    data class StartTag(val name: String, val hasContent: Boolean, val namespace: String? = null) : XmlEvent()
    data class EndTag(val name: String) : XmlEvent()
    data class Text(val text: String) : XmlEvent()
}

class SimpleXmlParser(private val xml: String) {
    fun parse(): List<XmlEvent> {
        val events = mutableListOf<XmlEvent>()
        var pos = 0
        
        // Пропускаем XML declaration
        if (xml.startsWith("<?xml")) {
            pos = xml.indexOf("?>") + 2
        }
        
        while (pos < xml.length) {
            val nextTag = xml.indexOf('<', pos)
            if (nextTag == -1) break
            
            // Текст перед тегом
            if (nextTag > pos) {
                val text = xml.substring(pos, nextTag).trim()
                if (text.isNotEmpty()) {
                    events.add(XmlEvent.Text(text))
                }
            }
            
            val tagEnd = xml.indexOf('>', nextTag)
            if (tagEnd == -1) break
            
            val tagContent = xml.substring(nextTag + 1, tagEnd)
            
            when {
                tagContent.startsWith("/") -> {
                    // Закрывающий тег - берём только имя без namespace
                    val fullName = tagContent.substring(1).trim()
                    val tagName = fullName.split(" ")[0].split(":").last()
                    events.add(XmlEvent.EndTag(tagName))
                }
                tagContent.endsWith("/") -> {
                    // Самозакрывающийся тег - берём только имя без атрибутов и namespace
                    val fullContent = tagContent.dropLast(1).trim()
                    val tagName = fullContent.split(" ")[0].split(":").last()
                    events.add(XmlEvent.StartTag(tagName, false))
                }
                else -> {
                    // Открывающий тег - берём имя и namespace
                    val parts = tagContent.split(" ")
                    val tagName = parts[0].split(":").last()
                    
                    // Извлекаем namespace из xmlns="..."
                    var namespace: String? = null
                    for (part in parts) {
                        if (part.startsWith("xmlns=")) {
                            namespace = part.substringAfter("xmlns=\"").substringBefore("\"")
                            break
                        }
                    }
                    
                    events.add(XmlEvent.StartTag(tagName, true, namespace))
                }
            }
            
            pos = tagEnd + 1
        }
        
        return events
    }
}

