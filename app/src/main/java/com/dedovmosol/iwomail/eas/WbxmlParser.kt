package com.dedovmosol.iwomail.eas

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
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
        
        // Токены (WAP-192 WBXML Spec)
        private const val TOKEN_SWITCH_PAGE = 0x00
        private const val TOKEN_END = 0x01
        private const val TOKEN_ENTITY = 0x02  // Character entity (mb_u_int32)
        private const val TOKEN_STR_I = 0x03   // Inline string
        private const val TOKEN_LITERAL = 0x04       // Tag from string table, no content/attrs
        private const val TOKEN_LITERAL_C = 0x44     // Tag from string table, with content
        private const val TOKEN_LITERAL_A = 0x84     // Tag from string table, with attrs
        private const val TOKEN_LITERAL_AC = 0xC4    // Tag from string table, content + attrs
        private const val TOKEN_OPAQUE = 0xC3
        
        // Флаги тегов
        private const val TAG_HAS_CONTENT = 0x40
        private const val TAG_HAS_ATTRS = 0x80
        
        private const val MAX_OPAQUE_SIZE = 16 * 1024 * 1024 // 16MB
        private const val MAX_STRING_SIZE = 16 * 1024 * 1024 // 16MB
        
        private val BINARY_OPAQUE_TAGS = setOf(
            "Timezone", "CompressedRTF", "MIMEData",
            "StartTimeZone", "EndTimeZone",
            "ConversationId", "ConversationIndex"
        )
    }
    
    /**
     * Парсит WBXML в XML строку.
     * Thread-safe: всё состояние локально внутри метода.
     */
    fun parse(data: ByteArray): String {
        val input = ByteArrayInputStream(data)
        val result = StringBuilder()
        
        val version = input.read()
        val publicId = readMultiByteInt(input)
        val charset = readMultiByteInt(input)
        val stringTableLength = readMultiByteInt(input)
        
        if (stringTableLength > 0) {
            input.skip(stringTableLength.toLong())
        }
        
        result.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        
        parseBody(input, result)
        
        return result.toString()
    }
    
    private fun parseBody(input: InputStream, result: StringBuilder) {
        val tagStack = mutableListOf<String>()
        var codePage = 0
        
        while (true) {
            val token = input.read()
            if (token == -1) break
            
            when (token) {
                TOKEN_SWITCH_PAGE -> {
                    codePage = input.read()
                }
                TOKEN_END -> {
                    if (tagStack.isNotEmpty()) {
                        val tag = tagStack.removeAt(tagStack.lastIndex)
                        result.append("</$tag>")
                    }
                }
                TOKEN_ENTITY -> {
                    val charCode = readMultiByteInt(input)
                    result.append("&#$charCode;")
                }
                TOKEN_STR_I -> {
                    result.append(escapeXmlContent(readStringRaw(input)))
                }
                TOKEN_OPAQUE -> {
                    val length = readMultiByteInt(input)
                    if (length > MAX_OPAQUE_SIZE) throw IOException("WBXML opaque data too large: $length bytes (max $MAX_OPAQUE_SIZE)")
                    val bytes = ByteArray(length)
                    DataInputStream(input).readFully(bytes)
                    val currentTag = tagStack.lastOrNull() ?: ""
                    if (isBinaryOpaqueTag(currentTag)) {
                        result.append(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                    } else {
                        result.append(escapeXmlContent(String(bytes, Charsets.UTF_8)))
                    }
                }
                TOKEN_LITERAL, TOKEN_LITERAL_C, TOKEN_LITERAL_A, TOKEN_LITERAL_AC -> {
                    val tableIndex = readMultiByteInt(input)
                    val tagName = "Literal_$tableIndex"
                    val hasContent = (token == TOKEN_LITERAL_C || token == TOKEN_LITERAL_AC)
                    val hasAttrs = (token == TOKEN_LITERAL_A || token == TOKEN_LITERAL_AC)
                    result.append("<$tagName")
                    if (hasAttrs) {
                        while (true) {
                            val attrToken = input.read()
                            if (attrToken == -1) throw IOException("Unexpected EOF in WBXML LITERAL attribute")
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
                else -> {
                    val tagId = token and 0x3F
                    val hasContent = (token and TAG_HAS_CONTENT) != 0
                    val hasAttrs = (token and TAG_HAS_ATTRS) != 0
                    
                    val tagName = getTagName(codePage, tagId)
                    result.append("<$tagName")
                    
                    if (hasAttrs) {
                        while (true) {
                            val attrToken = input.read()
                            if (attrToken == -1) throw IOException("Unexpected EOF in WBXML attribute")
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

    
    private fun isBinaryOpaqueTag(tag: String): Boolean = tag in BINARY_OPAQUE_TAGS

    private fun escapeXmlContent(text: String): String {
        if (text.indexOf('&') < 0 && text.indexOf('<') < 0 && text.indexOf('>') < 0) return text
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    private fun readStringRaw(input: InputStream): String {
        val buffer = ByteArrayOutputStream(256)
        while (true) {
            val b = input.read()
            if (b == 0 || b == -1) break
            if (buffer.size() >= MAX_STRING_SIZE) throw IOException("WBXML string too large (max $MAX_STRING_SIZE bytes)")
            buffer.write(b)
        }
        return buffer.toString(Charsets.UTF_8.name())
    }
    
    private fun readMultiByteInt(input: InputStream): Int {
        var result = 0
        var bytesRead = 0
        while (true) {
            val b = input.read()
            if (b == -1) throw IOException("Unexpected EOF in WBXML multi-byte int")
            result = (result shl 7) or (b and 0x7F)
            bytesRead++
            if ((b and 0x80) == 0) break
            if (bytesRead >= 5) throw IOException("WBXML multi-byte int too large (>5 bytes)")
        }
        if (result < 0) throw IOException("WBXML multi-byte int overflow")
        return result
    }
    
    /**
     * Генерирует WBXML из XML строки.
     * Thread-safe: всё состояние локально внутри метода.
     */
    fun generate(xml: String): ByteArray {
        val output = ByteArrayOutputStream()
        
        output.write(WBXML_VERSION)
        output.write(WBXML_UNKNOWN_PI)
        output.write(WBXML_CHARSET_UTF8)
        output.write(0x00)
        
        generateBody(xml, output)
        
        return output.toByteArray()
    }
    
    private fun generateBody(xml: String, output: ByteArrayOutputStream) {
        val parser = SimpleXmlParser(xml)
        val events = parser.parse()
        
        val namespaceStack = mutableListOf<String?>()
        var currentNamespace: String? = null
        var codePage = -1
        
        for (event in events) {
            when (event) {
                is XmlEvent.StartTag -> {
                    val effectiveNamespace = event.namespace ?: currentNamespace
                    
                    namespaceStack.add(event.namespace)
                    if (event.namespace != null) {
                        currentNamespace = event.namespace
                    }
                    
                    val (targetPage, tagId) = getTagIdWithContext(event.name, effectiveNamespace, codePage)
                    
                    if (targetPage != codePage) {
                        output.write(TOKEN_SWITCH_PAGE)
                        output.write(targetPage)
                        codePage = targetPage
                    }
                    
                    val token = tagId or (if (event.hasContent) TAG_HAS_CONTENT else 0)
                    output.write(token)
                }
                is XmlEvent.EndTag -> {
                    output.write(TOKEN_END)
                    
                    if (namespaceStack.isNotEmpty()) {
                        val poppedNs = namespaceStack.removeAt(namespaceStack.lastIndex)
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
    private val namespaceToPage = mapOf(
        "AirSync" to 0,
        "Contacts" to 1,
        "Email" to 2,
        "Calendar" to 4,
        "Move" to 5,
        "FolderHierarchy" to 7,
        "MeetingResponse" to 8,
        "Tasks" to 9,
        "ResolveRecipients" to 10,
        "ValidateCert" to 11,
        "Contacts2" to 12,
        "Ping" to 13,
        "Provision" to 14,
        "Search" to 15,
        "Gal" to 16,
        "AirSyncBase" to 17,
        "Settings" to 18,
        "DocumentLibrary" to 19,
        "ItemOperations" to 20,
        "ComposeMail" to 21,
        "Email2" to 22,
        "Notes" to 23
    )
    
    private fun getTagIdWithContext(tagName: String, namespace: String?, currentPage: Int): Pair<Int, Int> {
        val preferredPage = namespace?.let { namespaceToPage[it] }
        
        if (preferredPage != null) {
            val result = EasCodePages.getTagIdOnPage(tagName, preferredPage)
            if (result != null) return result
        }
        
        return EasCodePages.getTagId(tagName, currentPage)
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
                    events.add(XmlEvent.Text(XmlUtils.unescape(text)))
                }
            }
            
            // CDATA: <![CDATA[...]]>
            if (xml.startsWith("<![CDATA[", nextTag)) {
                val cdataEnd = xml.indexOf("]]>", nextTag + 9)
                if (cdataEnd == -1) break
                val text = xml.substring(nextTag + 9, cdataEnd)
                if (text.isNotEmpty()) events.add(XmlEvent.Text(text))
                pos = cdataEnd + 3
                continue
            }
            // Comment: <!-- ... -->
            if (xml.startsWith("<!--", nextTag)) {
                val commentEnd = xml.indexOf("-->", nextTag + 4)
                pos = if (commentEnd == -1) xml.length else commentEnd + 3
                continue
            }
            // Processing Instruction: <? ... ?>
            if (xml.startsWith("<?", nextTag)) {
                val piEnd = xml.indexOf("?>", nextTag + 2)
                pos = if (piEnd == -1) xml.length else piEnd + 2
                continue
            }
            
            val tagEnd = findTagEnd(xml, nextTag + 1)
            if (tagEnd == -1) break
            
            val tagContent = xml.substring(nextTag + 1, tagEnd)
            
            when {
                tagContent.startsWith("/") -> {
                    val fullName = tagContent.substring(1).trim()
                    val tagName = fullName.split(" ")[0].split(":").last()
                    events.add(XmlEvent.EndTag(tagName))
                }
                tagContent.trimEnd().endsWith("/") -> {
                    val fullContent = tagContent.trimEnd().dropLast(1).trim()
                    val nameWithPrefix = fullContent.split(" ")[0]
                    val (prefix, tagName) = splitPrefix(nameWithPrefix)
                    val ns = extractNamespace(fullContent.split(" "), prefix)
                    events.add(XmlEvent.StartTag(tagName, false, ns))
                }
                else -> {
                    val parts = tagContent.split(" ")
                    val nameWithPrefix = parts[0]
                    val (prefix, tagName) = splitPrefix(nameWithPrefix)
                    val ns = extractNamespace(parts, prefix)
                    events.add(XmlEvent.StartTag(tagName, true, ns))
                }
            }
            
            pos = tagEnd + 1
        }
        
        return events
    }

    private val prefixToNamespace = mapOf(
        "airsyncbase" to "AirSyncBase",
        "airsync" to "AirSync",
        "email" to "Email",
        "email2" to "Email2",
        "calendar" to "Calendar",
        "contacts" to "Contacts",
        "contacts2" to "Contacts2",
        "tasks" to "Tasks",
        "notes" to "Notes",
        "folderhierarchy" to "FolderHierarchy",
        "move" to "Move",
        "gal" to "Gal",
        "provision" to "Provision",
        "settings" to "Settings",
        "search" to "Search",
        "itemoperations" to "ItemOperations",
        "composemail" to "ComposeMail",
        "documentlibrary" to "DocumentLibrary",
        "ping" to "Ping"
    )
    
    private fun splitPrefix(nameWithPrefix: String): Pair<String?, String> {
        val colonIdx = nameWithPrefix.indexOf(':')
        return if (colonIdx >= 0) {
            nameWithPrefix.substring(0, colonIdx) to nameWithPrefix.substring(colonIdx + 1)
        } else {
            null to nameWithPrefix
        }
    }
    
    private fun extractNamespace(parts: List<String>, prefix: String?): String? {
        for (part in parts) {
            if (part.startsWith("xmlns=")) {
                return part.substringAfter("xmlns=\"").substringBefore("\"")
            }
        }
        return prefix?.let { prefixToNamespace[it.lowercase()] }
    }
    
    private fun findTagEnd(xml: String, start: Int): Int {
        var inQuote = false
        var quoteChar = ' '
        var i = start
        while (i < xml.length) {
            val c = xml[i]
            if (inQuote) {
                if (c == quoteChar) inQuote = false
            } else {
                if (c == '"' || c == '\'') { inQuote = true; quoteChar = c }
                else if (c == '>') return i
            }
            i++
        }
        return -1
    }
}

