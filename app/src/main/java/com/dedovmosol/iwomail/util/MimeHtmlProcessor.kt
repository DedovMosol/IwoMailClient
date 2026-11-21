package com.dedovmosol.iwomail.util

import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * MIME/HTML processing utilities for email body handling.
 *
 * Supports Exchange 2007 SP1 MIME formats:
 * - Content-Transfer-Encoding: quoted-printable (RFC 2045 §6.7)
 * - Content-Transfer-Encoding: base64 (RFC 2045 §6.8)
 * - multipart/mixed, multipart/alternative, multipart/related (RFC 2046 §5.1)
 * - Content-ID (CID) inline image references
 */
object MimeHtmlProcessor {

    private val BASE64_DETECT = Regex("^[A-Za-z0-9+/=\\s]+$")
    private val CHARSET_HEADER = Regex("charset\\s*=\\s*\"?([^\";\\r\\n]+)\"?", RegexOption.IGNORE_CASE)
    private val TRANSFER_ENCODING = Regex("Content-Transfer-Encoding:\\s*([^\\s;\\r\\n]+)", RegexOption.IGNORE_CASE)

    // Fallback-регексы для MIME без boundary (одночастный/нестандартный).
    // Lookahead требует "--" в НАЧАЛЕ строки (RFC 2046: разделитель = CRLF + "--" + boundary):
    // голое (?=--|$) обрезало тело на первом "--" где угодно — на "<!--[if mso]>" Outlook,
    // "<style><!--" и "-----Original Message-----"
    private val HTML_PART = "(Content-Type:\\s*text/html[^\\r\\n]*(?:\\r?\\n[^\\r\\n]+)*)\\r?\\n\\r?\\n(.*?)(?=\\r?\\n--|\$)".toRegex(
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private val TEXT_PART = "(Content-Type:\\s*text/plain[^\\r\\n]*(?:\\r?\\n[^\\r\\n]+)*)\\r?\\n\\r?\\n(.*?)(?=\\r?\\n--|\$)".toRegex(
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private val BOUNDARY = "boundary=\"?([^\"\\r\\n]+)\"?".toRegex(RegexOption.IGNORE_CASE)
    private val CID_HEADER = "Content-ID:\\s*<([^>]+)>".toRegex(RegexOption.IGNORE_CASE)
    private val IMAGE_TYPE = "Content-Type:\\s*(image/[^;\\r\\n]+)".toRegex(RegexOption.IGNORE_CASE)

    /**
     * PB-2: порог размера MIME для извлечения inline-картинок. Больше — не строим `data:`-URL
     * (иначе base64-копии картинок держатся в памяти → риск `OutOfMemoryError`, который
     * `catch(Exception)` не ловит). ≈ лимиту 5 МБ суммарных вложений + overhead base64/MIME.
     */
    private const val MAX_INLINE_MIME_CHARS = 8_388_608 // 8 MiB

    /**
     * Extracts HTML from MIME data (bodyType=4).
     * Falls back to text/plain → HTML conversion if no HTML part found.
     *
     * Основной путь — честный RFC 2046-сплит по boundary (тело части = всё до следующего
     * разделителя, никакая обрезка по содержимому невозможна); regex-fallback остаётся
     * для одночастного MIME и нестандартных boundary.
     */
    fun extractHtmlFromMime(mimeData: String): String {
        // Large raw MIME can be scanned by ranges. An oversized base64 wrapper must instead be
        // decoded incrementally; decoding to a ByteArray/String would also copy every attachment.
        val large = mimeData.length > MAX_INLINE_MIME_CHARS
        if (large && mimeData.all { it.isBase64Digit() || it.isWhitespace() }) {
            return extractLargeWrappedBody(mimeData)
        }
        val decoded = if (large) mimeData else decodeMimeWrapper(mimeData)
        var plainText: String? = null
        var html: String? = null
        visitMimeParts(decoded, { html != null }) { headers, start, end ->
            if (end - start > MAX_INLINE_MIME_CHARS) return@visitMimeParts
            when {
                headers.hasContentType("text/html") -> html = decodeMimePartContent(headers, decoded.substring(start, end).trim())
                plainText == null && headers.hasContentType("text/plain") ->
                    plainText = decodeMimePartContent(headers, decoded.substring(start, end).trim())
            }
        }
        html?.let { return it }
        if (!large) findTextPart(decoded, "text/html")?.let { return it }
        plainText?.let { return it.replace("\n", "<br>") }
        if (large) return ""
        findTextPart(decoded, "text/plain")?.let { return it.replace("\n", "<br>") }
        return decoded
    }

    private fun Char.isBase64Digit() = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' ||
        this == '+' || this == '/' || this == '='

    private val multipartBoundary = Regex("(?:^|;)\\s*boundary\\s*=\\s*(?:\"([^\"]+)\"|([^;\\s]+))", RegexOption.IGNORE_CASE)
    private val foldedHeader = Regex("\\r?\\n[ \\t]+")

    /** Decode from the existing String, without creating a second encoded copy or a decoded MIME blob. */
    private fun extractLargeWrappedBody(wrapper: String): String {
        val source = object : InputStream() {
            private var offset = 0
            override fun read(): Int {
                while (offset < wrapper.length) {
                    val c = wrapper[offset++]
                    if (!c.isWhitespace()) return c.code
                }
                return -1
            }
        }
        var plain: String? = null
        return try {
            Base64.getDecoder().wrap(source).buffered().use { input ->
                val boundaries = ArrayList<String>()
                var headers: StringBuilder? = StringBuilder()
                var bodyHeaders = ""
                var body: StringBuilder? = null
                var isHtml = false
                fun finishPart(): String? {
                    val text = body ?: return null
                    body = null
                    val decoded = decodeMimePartContent(bodyHeaders, text.toString().trim(), byteView = true)
                    if (isHtml) return decoded
                    plain = decoded
                    return null
                }
                val headerLimit = 64 * 1024
                val line = StringBuilder()
                while (true) {
                    line.setLength(0)
                    val cap = when {
                        headers != null -> headerLimit
                        body != null -> MAX_INLINE_MIME_CHARS - (body?.length ?: 0)
                        else -> 256 // Only the boundary prefix may matter in an ignored attachment.
                    }.coerceAtLeast(256)
                    var overflow = false
                    var byte = input.read()
                    if (byte < 0) break
                    while (byte >= 0 && byte != '\n'.code) {
                        if (line.length < cap) line.append(byte.toChar()) else overflow = true
                        byte = input.read()
                    }

                    // RFC 2046 §5.1: an outer delimiter is valid even if a nested closing delimiter is missing.
                    if (!overflow && line.startsWith("--")) {
                        val delimiter = line.toString().trimEnd(' ', '\t', '\r')
                        val index = boundaries.indexOfLast { delimiter == it || delimiter == "$it--" }
                        if (index >= 0) {
                            finishPart()?.let { return it }
                            val closing = delimiter != boundaries[index]
                            while (boundaries.size > index + (if (closing) 0 else 1)) boundaries.removeAt(boundaries.lastIndex)
                            if (closing && boundaries.isEmpty()) break
                            headers = if (closing) null else StringBuilder()
                            continue
                        }
                    }

                    val currentHeaders = headers
                    val currentBody = body
                    if (currentHeaders != null) {
                        if (overflow || currentHeaders.length + line.length + 1 > headerLimit) {
                            headers = null
                        } else if (line.isEmpty() || line.length == 1 && line[0] == '\r') {
                            val block = foldedHeader.replace(currentHeaders, " ")
                            headers = null
                            val values = block.lineSequence().filter { ':' in it }
                                .associate { it.substringBefore(':').lowercase(java.util.Locale.ROOT) to it.substringAfter(':').trim() }
                            val contentType = values["content-type"].orEmpty()
                            val type = contentType.substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
                            val attachment = values["content-disposition"]?.substringBefore(';')?.trim()
                                .equals("attachment", ignoreCase = true)
                            if (!attachment && type.startsWith("multipart/")) {
                                val match = multipartBoundary.find(contentType)
                                val boundary = match?.groupValues?.let { it[1].ifEmpty { it[2] } }
                                if (boundaries.size < 10 && boundary != null && boundary.length in 1..70) {
                                    boundaries.add("--$boundary")
                                }
                            } else if (!attachment && (type == "text/html" || plain == null &&
                                    (type == "text/plain" || type.isEmpty() && values.isNotEmpty()))) {
                                body = StringBuilder()
                                bodyHeaders = block
                                isHtml = type == "text/html"
                            }
                        } else {
                            currentHeaders.append(line).append('\n')
                        }
                    } else if (currentBody != null) {
                        val newline = if (byte == '\n'.code) 1 else 0
                        if (overflow || currentBody.length + line.length + newline > MAX_INLINE_MIME_CHARS) {
                            body = null
                        } else {
                            currentBody.append(line)
                            if (newline != 0) currentBody.append('\n')
                        }
                    }
                }
                finishPart() ?: plain?.replace("\n", "<br>").orEmpty()
            }
        } catch (_: java.io.IOException) {
            plain?.replace("\n", "<br>").orEmpty()
        }
    }

    /**
     * Ищет часть указанного типа (text/html | text/plain), рекурсивно спускаясь
     * во вложенные multipart (multipart/mixed { multipart/alternative { plain + html } }).
     * Возвращает декодированное содержимое (CTE + charset) или null.
     */
    private fun findTextPart(section: String, type: String, boundary: String? = null, depth: Int = 0): String? {
        if (depth <= 10) {
            val effectiveBoundary = boundary ?: BOUNDARY.find(section)?.groupValues?.get(1)
            if (effectiveBoundary != null) {
                for (part in section.split("--$effectiveBoundary")) {
                    val (headers, body) = splitHeadersFromBody(part) ?: continue

                    val nestedBoundary = BOUNDARY.find(headers)?.groupValues?.get(1)
                    if (nestedBoundary != null && nestedBoundary != effectiveBoundary &&
                        headers.hasContentType("multipart/")
                    ) {
                        findTextPart(part, type, nestedBoundary, depth + 1)?.let { return it }
                        continue
                    }

                    if (headers.hasContentType(type)) {
                        return decodeMimePartContent(headers, body.trim())
                    }
                }
            }
        }

        // Fallback: одночастный MIME / boundary не сработал — прежний regex-путь
        val regex = if (type == "text/html") HTML_PART else TEXT_PART
        val match = regex.find(section) ?: return null
        return decodeMimePartContent(
            headers = match.groupValues[1],
            body = match.groupValues[2].trim()
        )
    }

    /** Есть ли в заголовках `Content-Type: <prefix>` (с пробелом после двоеточия или без). */
    private fun String.hasContentType(prefix: String): Boolean =
        contains("Content-Type: $prefix", ignoreCase = true) ||
            contains("Content-Type:$prefix", ignoreCase = true)

    /** Разделяет MIME-часть на заголовки и тело по первой пустой строке (CRLF или LF). */
    private fun splitHeadersFromBody(part: String): Pair<String, String>? {
        val crlfIdx = part.indexOf("\r\n\r\n")
        val lfIdx = part.indexOf("\n\n")
        val (idx, sepLen) = when {
            crlfIdx != -1 && (lfIdx == -1 || crlfIdx <= lfIdx) -> crlfIdx to 4
            lfIdx != -1 -> lfIdx to 2
            else -> return null
        }
        return part.substring(0, idx) to part.substring(idx + sepLen)
    }

    /**
     * Extracts inline images from MIME data as CID → data:URI map.
     * Recursively handles nested multipart structures:
     * multipart/mixed { multipart/related { html + image } + attachment }
     */
    fun extractInlineImagesFromMime(mimeData: String): Map<String, String> {
        val images = mutableMapOf<String, String>()

        // PB-2: гигантский MIME не обрабатываем — построение data:URL держит в памяти base64-копии
        // картинок (OutOfMemoryError, который catch(Exception) не ловит). Предотвращение важнее
        // ловли: очень большое письмо показывается БЕЗ inline-картинок (тело письма отображается).
        if (mimeData.length > MAX_INLINE_MIME_CHARS) return images

        val decoded = try {
            decodeMimeWrapper(mimeData)
        } catch (_: Exception) {
            return images
        }

        if (!decoded.contains("Content-Type:", ignoreCase = true)) {
            return images
        }

        extractImagesRecursive(decoded, images)
        return images
    }

    /** For message preview: decode only referenced image parts from the already cached, bounded MIME. */
    internal fun forEachReferencedInlineImage(
        mimeData: String, wanted: Set<String>, limit: () -> Long, consume: (String, ByteArray) -> Unit
    ) {
        if (wanted.isEmpty() || mimeData.length > MAX_INLINE_MIME_CHARS) return
        val mime = decodeMimeWrapper(mimeData)
        visitMimeParts(mime, { wanted.isEmpty() || limit() <= 0 }) { headers, dataStart, end ->
            if (!headers.hasContentType("image/")) return@visitMimeParts
            val cid = CID_HEADER.find(headers)?.groupValues?.get(1)?.let(::normalizeContentId)
                ?.lowercase(java.util.Locale.ROOT) ?: return@visitMimeParts
            if (cid !in wanted || !TRANSFER_ENCODING.find(headers)?.groupValues?.get(1).equals("base64", ignoreCase = true)) return@visitMimeParts
            val max = limit()
            // Size check before copying/compacting the payload or allocating decoded bytes.
            if ((end - dataStart).toLong() > max * 3 / 2 + 1024) return@visitMimeParts
            val bytes = try {
                java.util.Base64.getDecoder().decode(buildString {
                    for (i in dataStart until end) if (!mime[i].isWhitespace()) append(mime[i])
                })
            } catch (_: IllegalArgumentException) {
                return@visitMimeParts
            }
            if (bytes.size.toLong() <= max) consume(cid, bytes)
        }
    }

    /** Scan by ranges so visiting a large file part does not copy its payload. */
    private fun visitMimeParts(
        mime: String, done: () -> Boolean, consumePart: (headers: String, start: Int, end: Int) -> Unit
    ) {
        fun visit(start: Int, end: Int, depth: Int) {
            if (depth > 10 || done()) return
            val headerLimit = minOf(end, start + 64 * 1024)
            // Limit header parsing even for a malformed part with no empty line.
            var dataStart = start
            var headerEnd = start
            while (dataStart < headerLimit) {
                val lineStart = dataStart
                while (dataStart < headerLimit && mime[dataStart] != '\n') dataStart++
                if (dataStart == headerLimit) return
                val emptyLine = dataStart == lineStart || dataStart == lineStart + 1 && mime[lineStart] == '\r'
                dataStart++
                if (emptyLine) { headerEnd = lineStart; break }
            }
            if (headerEnd == start) return
            val headers = mime.substring(start, headerEnd)
            if (headers.hasContentType("multipart/")) {
                val boundary = BOUNDARY.find(headers)?.groupValues?.get(1)?.trim()?.substringBefore(';') ?: return
                val delimiter = "--$boundary"
                var next = dataStart
                var partStart = -1
                while (next < end) {
                    val at = mime.indexOf(delimiter, next)
                    if (at < 0 || at + delimiter.length >= end) break
                    next = at + delimiter.length
                    if (at > dataStart && mime[at - 1] != '\n') continue
                    val closing = mime.startsWith("--", next)
                    var after = next + if (closing) 2 else 0
                    while (after < end && (mime[after] == ' ' || mime[after] == '\t' || mime[after] == '\r')) after++
                    if (after < end && mime[after] != '\n') continue
                    if (partStart >= 0) visit(partStart, at, depth + 1)
                    if (closing || done()) return
                    partStart = after + 1
                    next = partStart
                }
                return
            }
            consumePart(headers, dataStart, end)
        }
        visit(0, mime.length, 0)
    }

    /**
     * Декодирует MIME-part body с учётом Content-Transfer-Encoding и charset=.
     * RFC 2045/2046: transfer encoding и charset задаются на уровне конкретной части MIME.
     * RFC 2045 §5 + §6: после base64/quoted-printable нужно декодировать байты
     * в charset, указанной в Content-Type.
     */
    fun decodeMimePartContent(headers: String, body: String): String =
        decodeMimePartContent(headers, body, byteView = false)

    private fun decodeMimePartContent(headers: String, body: String, byteView: Boolean): String {
        val encoding = TRANSFER_ENCODING.find(headers)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.lowercase()
            ?: "7bit"
        val charsetName = CHARSET_HEADER.find(headers)
            ?.groupValues
            ?.get(1)
            ?.trim()

        val bytes = when (encoding) {
            "base64" -> try {
                Base64.getMimeDecoder().decode(body)
            } catch (_: IllegalArgumentException) {
                return body
            }
            "quoted-printable" -> decodeQuotedPrintableBytes(body)
            else -> {
                // Для 7bit/8bit MIME сервер может отдать либо lossless byte-view
                // (например после base64-wrapper -> ISO-8859-1), либо уже корректно
                // декодированную Unicode строку. Во втором случае повторное
                // toByteArray(ISO_8859_1) сломает кириллицу. Поэтому raw re-decode
                // делаем только если строка ещё выглядит как 1-byte view.
                if (byteView || charsetName != null && body.all { it.code <= 0xFF }) {
                    body.toByteArray(Charsets.ISO_8859_1)
                } else {
                    return body
                }
            }
        }

        return decodeText(bytes, charsetName)
    }

    /**
     * Decodes quoted-printable encoded content.
     * RFC 2045 §6.7: =XX hex encoding, =CRLF soft line break.
     */
    fun decodeQuotedPrintable(input: String, charsetName: String? = null): String {
        return decodeText(decodeQuotedPrintableBytes(input), charsetName)
    }

    private fun decodeQuotedPrintableBytes(input: String): ByteArray {
        val text = input.replace("=\r\n", "").replace("=\n", "")
        val bytes = mutableListOf<Byte>()
        var i = 0

        while (i < text.length) {
            val c = text[i]
            if (c == '=' && i + 2 < text.length) {
                val hex = text.substring(i + 1, i + 3)
                try {
                    bytes.add(hex.toInt(16).toByte())
                    i += 3
                    continue
                } catch (_: Exception) { }
            }
            bytes.add(c.code.toByte())
            i++
        }

        return bytes.toByteArray()
    }

    /**
     * MIME wrapper сам по себе ASCII-совместим: заголовки, boundary и transfer-encoded body
     * состоят из безопасных ASCII символов. Поэтому для raw MIME wrapper используем
     * ISO-8859-1 как lossless byte-to-char mapping до декодирования отдельных частей.
     */
    fun decodeMimeWrapper(mimeData: String): String = try {
        if (mimeData.matches(BASE64_DETECT) && mimeData.length > 100) {
            String(android.util.Base64.decode(mimeData, android.util.Base64.DEFAULT), Charsets.ISO_8859_1)
        } else {
            mimeData
        }
    } catch (_: Exception) {
        mimeData
    }

    /**
     * MIME письма байтами — для файла .eml. Base64-обёртка (так Exchange иногда отдаёт MIME в ItemOperations)
     * раскодируется без потерь, остальное — тем же UTF-8, которым его прочитал разбор WBXML.
     */
    fun mimeBytes(mimeData: String): ByteArray {
        if (mimeData.length > 100 && mimeData.matches(BASE64_DETECT)) {
            try {
                return java.util.Base64.getMimeDecoder().decode(mimeData)
            } catch (_: IllegalArgumentException) {
                // не base64 — как есть
            }
        }
        return mimeData.toByteArray(Charsets.UTF_8)
    }

    private fun decodeText(bytes: ByteArray, charsetName: String?): String {
        val declaredCharset = charsetName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::safeCharset)
        if (declaredCharset != null) {
            return String(bytes, declaredCharset)
        }

        decodeUtf8Strict(bytes)?.let { return it }
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String? {
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
            decoder.onMalformedInput(CodingErrorAction.REPORT)
            decoder.onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun safeCharset(name: String): Charset? = try {
        Charset.forName(name)
    } catch (_: Exception) {
        null
    }

    private fun extractImagesRecursive(mimeSection: String, images: MutableMap<String, String>, depth: Int = 0) {
        if (depth > 10) return // guard от глубоко-вложенного/зловредного MIME (переполнение стека)
        val boundaryMatch = BOUNDARY.find(mimeSection) ?: return
        val boundary = boundaryMatch.groupValues[1]
        val parts = mimeSection.split("--$boundary")

        for (part in parts) {
            if (part.hasContentType("multipart/")) {
                extractImagesRecursive(part, images, depth + 1)
                continue
            }

            if (!part.hasContentType("image/")) continue

            val contentId = CID_HEADER.find(part)?.groupValues?.get(1) ?: continue
            val contentType = IMAGE_TYPE.find(part)?.groupValues?.get(1)?.trim() ?: "image/png"

            val contentStart = part.indexOf("\r\n\r\n")
            if (contentStart == -1) continue

            var content = part.substring(contentStart + 4).trim()
            if (content.endsWith("--")) {
                content = content.dropLast(2).trim()
            }
            content = content.replace("\r\n", "").replace("\n", "").replace(" ", "")

            if (content.isNotBlank()) {
                images[contentId] = "data:$contentType;base64,$content"
            }
        }
    }
}
