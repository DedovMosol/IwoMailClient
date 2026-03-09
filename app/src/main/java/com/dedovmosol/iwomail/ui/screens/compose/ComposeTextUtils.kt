package com.dedovmosol.iwomail.ui.screens.compose

import com.dedovmosol.iwomail.util.escapeHtml

internal const val BODY_SAVE_THRESHOLD = 100_000

internal fun createLargeStringSaver(cacheDir: java.io.File, tag: String) =
    androidx.compose.runtime.saveable.Saver<String, String>(
        save = { value ->
            if (value.length <= BODY_SAVE_THRESHOLD) value
            else try {
                val file = java.io.File(cacheDir, "compose_state_$tag.tmp")
                file.writeText(value)
                "\u0000FILE:${file.absolutePath}"
            } catch (_: Exception) { value.take(BODY_SAVE_THRESHOLD) }
        },
        restore = { saved ->
            if (saved.startsWith("\u0000FILE:")) {
                try {
                    val file = java.io.File(saved.removePrefix("\u0000FILE:"))
                    if (file.exists()) file.readText().also { file.delete() } else ""
                } catch (_: Exception) { "" }
            } else saved
        }
    )

internal val HTML_SIGNATURE_REGEX = Regex(
    "<div class=\"signature\"[^>]*>.*</div><!--/signature-->",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)
internal val CID_PATTERN = Regex("cid:([^\"'\\s>]+)")
internal val DATA_URL_REGEX = """src\s*=\s*"data:([^;]+);base64,([^"]+)"""".toRegex()
internal val HTML_TAG_STRIP_REGEX = Regex("<[^>]*>")
internal val WHITESPACE_COLLAPSE_REGEX = Regex("\\s+")
internal val TRAILING_EMPTY_DIV_REGEX = Regex("(<div>\\s*(<br>|&nbsp;)?\\s*</div>\\s*)+$", RegexOption.IGNORE_CASE)
internal val TRAILING_EMPTY_P_REGEX = Regex("(<p>\\s*(<br>|&nbsp;)?\\s*</p>\\s*)+$", RegexOption.IGNORE_CASE)
internal val TRAILING_BR_REGEX = Regex("(<br\\s*/?>\\s*)+$", RegexOption.IGNORE_CASE)
internal val NON_HTML_TAG_REGEX = Regex("<(?!/?[a-zA-Z][a-zA-Z0-9]*[\\s>/])(?![!?])[^>]*>")
internal val HTML_TAG_REGEX = Regex("<[a-zA-Z][^>]*>")
internal val BRACKET_EMAIL_REGEX = Regex("<([^>]+@[^>]+)>")
internal val NORMALIZE_EMAIL_REGEX = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
internal val NORMALIZE_BRACKET_REGEX = Regex("<([^>]+)>")
internal val SAFE_FILENAME_COMPOSE_REGEX = Regex("[\\\\/:*?\"<>|]")
internal val SIGNATURE_DIV_REGEX = Regex("<div class=\"signature\">.*</div>", RegexOption.DOT_MATCHES_ALL)

internal fun formatHtmlSignature(text: String?, isHtml: Boolean = false): String {
    if (text.isNullOrBlank()) return ""

    val content = if (isHtml) {
        text
    } else {
        text.escapeHtml().replace("\n", "<br>")
    }
    return "<div class=\"signature\"><br>--<br>$content</div><!--/signature-->"
}

internal fun formatHtmlQuote(
    header: String,
    from: String,
    date: String,
    subject: String,
    toField: String?,
    originalBody: String
): String {
    val toLine = if (toField != null) "<b>To:</b> ${toField.escapeHtml()}<br>" else ""
    return """
        <br><br>
        <div style="border-left: 2px solid #ccc; padding-left: 10px; margin-left: 5px; color: #666;">
            <b>--- ${header.escapeHtml()} ---</b><br>
            <b>From:</b> ${from.escapeHtml()}<br>
            <b>Date:</b> ${date.escapeHtml()}<br>
            <b>Subject:</b> ${subject.escapeHtml()}<br>
            $toLine
            <br>
            $originalBody
        </div>
    """.trimIndent()
}

internal fun String.looksLikeHtml(): Boolean = HTML_TAG_REGEX.containsMatchIn(this)

internal fun extractEmailFromString(raw: String, queryHint: String? = null): String? {
    if (raw.isBlank()) return null
    val cleaned = raw.replace("\r", " ").replace("\n", " ").trim()
    if (cleaned.isBlank()) return null
    val emails = NORMALIZE_EMAIL_REGEX.findAll(cleaned).map { it.value }.toList()
    val queryLower = queryHint?.trim()?.lowercase().orEmpty()
    if (queryLower.isNotEmpty()) {
        emails.firstOrNull { it.lowercase().contains(queryLower) }?.let { return it }
    }
    BRACKET_EMAIL_REGEX.find(cleaned)?.groupValues?.getOrNull(1)?.let { return it }
    return emails.firstOrNull()
}

internal fun replaceCidWithDataUrl(html: String, inlineImages: Map<String, String>): String {
    var result = html
    inlineImages.forEach { (cid, dataUrl) ->
        result = result
            .replace("cid:$cid", dataUrl)
            .replace("cid:${cid.removePrefix("<").removeSuffix(">")}", dataUrl)
    }
    return result
}
