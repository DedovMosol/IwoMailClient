package com.dedovmosol.iwomail.eas

object XmlUtils {
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

    private fun findOpenTag(xml: String, tag: String, from: Int): Pair<Int, String>? {
        val simpleOpen = "<$tag>"
        val simpleIdx = xml.indexOf(simpleOpen, from)

        val nsMarker = ":$tag>"
        val nsIdx = xml.indexOf(nsMarker, from)
        var nsStart = -1
        var nsFullTag = ""
        if (nsIdx > 0) {
            val lt = xml.lastIndexOf('<', nsIdx)
            if (lt >= from) {
                val prefix = xml.substring(lt + 1, nsIdx)
                if (prefix.isNotEmpty() && prefix.all { it.isLetterOrDigit() || it == '_' }) {
                    nsStart = lt
                    nsFullTag = "$prefix:$tag"
                }
            }
        }

        return when {
            simpleIdx >= 0 && nsStart >= 0 ->
                if (simpleIdx <= nsStart) simpleIdx to tag else nsStart to nsFullTag
            simpleIdx >= 0 -> simpleIdx to tag
            nsStart >= 0 -> nsStart to nsFullTag
            else -> null
        }
    }

    private fun hasSelfClose(xml: String, tag: String): Boolean {
        return xml.contains("<$tag/>") || xml.contains(":$tag/>")
    }

    fun extractTagValue(xml: String, tag: String): String? {
        val (startIdx, fullTag) = findOpenTag(xml, tag, 0)
            ?: return if (hasSelfClose(xml, tag)) "" else null

        val openTag = "<$fullTag>"
        val closeTag = "</$fullTag>"
        val contentStart = startIdx + openTag.length

        var depth = 1
        var i = contentStart
        while (i < xml.length && depth > 0) {
            if (xml.startsWith(closeTag, i)) {
                depth--
                if (depth == 0) return xml.substring(contentStart, i)
                i += closeTag.length
            } else if (xml.startsWith(openTag, i)) {
                depth++
                i += openTag.length
            } else {
                i++
            }
        }
        return xml.substring(contentStart)
    }

    fun extractTopLevelBlocks(xml: String, tag: String): List<String> {
        val result = mutableListOf<String>()
        var searchFrom = 0

        while (searchFrom < xml.length) {
            val (startIdx, fullTag) = findOpenTag(xml, tag, searchFrom) ?: break
            val openTag = "<$fullTag>"
            val closeTag = "</$fullTag>"
            val contentStart = startIdx + openTag.length

            var depth = 1
            var i = contentStart
            while (i < xml.length && depth > 0) {
                if (xml.startsWith(closeTag, i)) {
                    depth--
                    if (depth == 0) {
                        result.add(xml.substring(contentStart, i))
                        searchFrom = i + closeTag.length
                    }
                    i += closeTag.length
                } else if (xml.startsWith(openTag, i)) {
                    depth++
                    i += openTag.length
                } else {
                    i++
                }
            }
            if (depth > 0) break
        }
        return result
    }
}
