package com.dedovmosol.iwomail.util

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

    private val HTML_PART = "Content-Type:\\s*text/html.*?\\r?\\n\\r?\\n(.*?)(?=--|\$)".toRegex(
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private val TEXT_PART = "Content-Type:\\s*text/plain.*?\\r?\\n\\r?\\n(.*?)(?=--|\$)".toRegex(
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
        val decoded = decodeMimeBase64Wrapper(mimeData)

        val htmlMatch = HTML_PART.find(decoded)
        if (htmlMatch != null) {
            var content = htmlMatch.groupValues[1].trim()
            if (decoded.contains("Content-Transfer-Encoding: quoted-printable", ignoreCase = true)) {
                content = decodeQuotedPrintable(content)
            }
            return content
        }

        val textMatch = TEXT_PART.find(decoded)
        if (textMatch != null) {
            var content = textMatch.groupValues[1].trim()
            if (decoded.contains("Content-Transfer-Encoding: quoted-printable", ignoreCase = true)) {
                content = decodeQuotedPrintable(content)
            }
            return content.replace("\n", "<br>")
        }

        return mimeData
    }

    /**
     * Extracts inline images from MIME data as CID → data:URI map.
     * Recursively handles nested multipart structures:
     * multipart/mixed { multipart/related { html + image } + attachment }
     */
    fun extractInlineImagesFromMime(mimeData: String): Map<String, String> {
        val images = mutableMapOf<String, String>()

        val decoded = try {
            decodeMimeBase64Wrapper(mimeData)
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
     * Decodes quoted-printable encoded content with full UTF-8 support.
     * RFC 2045 §6.7: =XX hex encoding, =CRLF soft line break.
     */
    fun decodeQuotedPrintable(input: String): String {
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

        return try {
            String(bytes.toByteArray(), Charsets.UTF_8)
        } catch (_: Exception) {
            String(bytes.toByteArray(), Charsets.ISO_8859_1)
        }
    }

    private fun decodeMimeBase64Wrapper(mimeData: String): String = try {
        if (mimeData.matches(BASE64_DETECT) && mimeData.length > 100) {
            String(android.util.Base64.decode(mimeData, android.util.Base64.DEFAULT), Charsets.UTF_8)
        } else {
            mimeData
        }
    } catch (_: Exception) {
        mimeData
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
