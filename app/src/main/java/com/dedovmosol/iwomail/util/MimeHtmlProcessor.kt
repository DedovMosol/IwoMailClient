package com.dedovmosol.iwomail.util

import java.nio.ByteBuffer
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

    private val HTML_PART = "(Content-Type:\\s*text/html[^\\r\\n]*(?:\\r?\\n[^\\r\\n]+)*)\\r?\\n\\r?\\n(.*?)(?=--|\$)".toRegex(
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private val TEXT_PART = "(Content-Type:\\s*text/plain[^\\r\\n]*(?:\\r?\\n[^\\r\\n]+)*)\\r?\\n\\r?\\n(.*?)(?=--|\$)".toRegex(
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private val BOUNDARY = "boundary=\"?([^\"\\r\\n]+)\"?".toRegex(RegexOption.IGNORE_CASE)
    private val CID_HEADER = "Content-ID:\\s*<([^>]+)>".toRegex(RegexOption.IGNORE_CASE)
    private val IMAGE_TYPE = "Content-Type:\\s*(image/[^;\\r\\n]+)".toRegex(RegexOption.IGNORE_CASE)

    /**
     * Extracts HTML from MIME data (bodyType=4).
     * Falls back to text/plain → HTML conversion if no HTML part found.
     */
    fun extractHtmlFromMime(mimeData: String): String {
        val decoded = decodeMimeWrapper(mimeData)

        val htmlMatch = HTML_PART.find(decoded)
        if (htmlMatch != null) {
            return decodeMimePartContent(
                headers = htmlMatch.groupValues[1],
                body = htmlMatch.groupValues[2].trim()
            )
        }

        val textMatch = TEXT_PART.find(decoded)
        if (textMatch != null) {
            val content = decodeMimePartContent(
                headers = textMatch.groupValues[1],
                body = textMatch.groupValues[2].trim()
            )
            return content.replace("\n", "<br>")
        }

        return decoded
    }

    /**
     * Extracts inline images from MIME data as CID → data:URI map.
     * Recursively handles nested multipart structures:
     * multipart/mixed { multipart/related { html + image } + attachment }
     */
    fun extractInlineImagesFromMime(mimeData: String): Map<String, String> {
        val images = mutableMapOf<String, String>()

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

    /**
     * Декодирует MIME-part body с учётом Content-Transfer-Encoding и charset=.
     * RFC 2045/2046: transfer encoding и charset задаются на уровне конкретной части MIME.
     * RFC 2045 §5 + §6: после base64/quoted-printable нужно декодировать байты
     * в charset, указанной в Content-Type.
     */
    fun decodeMimePartContent(headers: String, body: String): String {
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
                android.util.Base64.decode(body, android.util.Base64.DEFAULT)
            } catch (_: Exception) {
                return body
            }
            "quoted-printable" -> decodeQuotedPrintableBytes(body)
            else -> {
                // Для 7bit/8bit MIME сервер может отдать либо lossless byte-view
                // (например после base64-wrapper -> ISO-8859-1), либо уже корректно
                // декодированную Unicode строку. Во втором случае повторное
                // toByteArray(ISO_8859_1) сломает кириллицу. Поэтому raw re-decode
                // делаем только если строка ещё выглядит как 1-byte view.
                if (charsetName != null && body.all { it.code <= 0xFF }) {
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

    private fun extractImagesRecursive(mimeSection: String, images: MutableMap<String, String>) {
        val boundaryMatch = BOUNDARY.find(mimeSection) ?: return
        val boundary = boundaryMatch.groupValues[1]
        val parts = mimeSection.split("--$boundary")

        for (part in parts) {
            val isNestedMultipart = part.contains("Content-Type: multipart/", ignoreCase = true) ||
                    part.contains("Content-Type:multipart/", ignoreCase = true)
            if (isNestedMultipart) {
                extractImagesRecursive(part, images)
                continue
            }

            val isImage = part.contains("Content-Type: image/", ignoreCase = true) ||
                    part.contains("Content-Type:image/", ignoreCase = true)
            if (!isImage) continue

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
