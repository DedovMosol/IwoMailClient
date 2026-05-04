package com.dedovmosol.iwomail.eas

object XmlUtils {
    private data class ParsedTag(
        val end: Int,
        val name: String,
        val closing: Boolean,
        val selfClosing: Boolean
    )

    fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    fun unescape(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun matchesName(name: String, tag: String): Boolean {
        return name == tag || (!tag.contains(":") && name.substringAfter(':') == tag)
    }

    private fun parseTagAt(xml: String, start: Int): ParsedTag? {
        if (start < 0 || start >= xml.length || xml[start] != '<') return null
        val closing = xml.getOrNull(start + 1) == '/'
        val nameStart = start + if (closing) 2 else 1
        val first = xml.getOrNull(nameStart) ?: return null
        if (first == '!' || first == '?') return null
        var nameEnd = nameStart
        while (nameEnd < xml.length) {
            val ch = xml[nameEnd]
            if (ch.isWhitespace() || ch == '>' || ch == '/') break
            nameEnd++
        }
        if (nameEnd == nameStart) return null
        val end = findTagEnd(xml, nameEnd) ?: return null
        var beforeEnd = end - 1
        while (beforeEnd > nameEnd && xml[beforeEnd].isWhitespace()) beforeEnd--
        return ParsedTag(
            end = end,
            name = xml.substring(nameStart, nameEnd),
            closing = closing,
            selfClosing = !closing && beforeEnd >= nameEnd && xml[beforeEnd] == '/'
        )
    }

    private fun findTagEnd(xml: String, from: Int): Int? {
        var quote: Char? = null
        var i = from
        while (i < xml.length) {
            val ch = xml[i]
            if (quote != null) {
                if (ch == quote) quote = null
            } else {
                if (ch == '"' || ch == '\'') quote = ch
                if (ch == '>') return i
            }
            i++
        }
        return null
    }

    private fun nextMarkupIndex(xml: String, index: Int): Int {
        if (xml.startsWith("<![CDATA[", index)) {
            return xml.indexOf("]]>", index + 9).takeIf { it >= 0 }?.plus(3) ?: xml.length
        }
        if (xml.startsWith("<!--", index)) {
            return xml.indexOf("-->", index + 4).takeIf { it >= 0 }?.plus(3) ?: xml.length
        }
        if (xml.startsWith("<?", index)) {
            return xml.indexOf("?>", index + 2).takeIf { it >= 0 }?.plus(2) ?: xml.length
        }
        if (xml.startsWith("<!", index)) {
            return xml.indexOf('>', index + 2).takeIf { it >= 0 }?.plus(1) ?: xml.length
        }
        return index + 1
    }

    private fun findOpenTag(xml: String, tag: String, from: Int): ParsedTag? {
        var idx = xml.indexOf('<', from)
        while (idx >= 0) {
            val parsed = parseTagAt(xml, idx)
            if (parsed != null && !parsed.closing && matchesName(parsed.name, tag)) {
                return parsed
            }
            idx = xml.indexOf('<', parsed?.end?.plus(1) ?: nextMarkupIndex(xml, idx))
        }
        return null
    }

    fun extractTagValue(xml: String, tag: String): String? {
        val openTag = findOpenTag(xml, tag, 0)
            ?: return null

        if (openTag.selfClosing) return ""

        val contentStart = openTag.end + 1
        var depth = 1
        var i = contentStart
        while (i < xml.length && depth > 0) {
            val next = xml.indexOf('<', i)
            if (next < 0) break
            val parsed = parseTagAt(xml, next)
            if (parsed == null) {
                i = nextMarkupIndex(xml, next)
                continue
            }
            if (matchesName(parsed.name, tag)) {
                if (parsed.closing) {
                    depth--
                    if (depth == 0) return xml.substring(contentStart, next)
                } else if (!parsed.selfClosing) {
                    depth++
                }
            }
            i = parsed.end + 1
        }
        return xml.substring(contentStart)
    }

    fun extractTopLevelBlocks(xml: String, tag: String): List<String> {
        val result = mutableListOf<String>()
        var searchFrom = 0

        while (searchFrom < xml.length) {
            val openTag = findOpenTag(xml, tag, searchFrom) ?: break
            if (openTag.selfClosing) {
                result.add("")
                searchFrom = openTag.end + 1
                continue
            }
            val contentStart = openTag.end + 1

            var depth = 1
            var i = contentStart
            while (i < xml.length && depth > 0) {
                val next = xml.indexOf('<', i)
                if (next < 0) break
                val parsed = parseTagAt(xml, next)
                if (parsed == null) {
                    i = nextMarkupIndex(xml, next)
                    continue
                }
                if (matchesName(parsed.name, tag)) {
                    if (parsed.closing) {
                        depth--
                        if (depth == 0) {
                            result.add(xml.substring(contentStart, next))
                            searchFrom = parsed.end + 1
                        }
                    } else if (!parsed.selfClosing) {
                        depth++
                    }
                }
                i = parsed.end + 1
            }
            if (depth > 0) break
        }
        return result
    }
}
