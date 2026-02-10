package com.dedovmosol.iwomail.eas

/**
 * Унифицированный экстрактор значений из XML
 * Заменяет множество похожих функций: extractValue, extractNoteValue, 
 * extractCalendarValue, extractContactValue, extractGalValue, extractEwsValue
 * 
 * Принцип DRY: единая логика извлечения значений
 */
object XmlValueExtractor {
    
    /**
     * Извлекает значение тега из XML
     * @param xml XML строка для поиска
     * @param tag имя тега (без namespace)
     * @return значение тега или null если не найдено
     */
    fun extract(xml: String, tag: String): String? {
        val pattern = EasPatterns.getTagPattern(tag)
        return pattern.find(xml)?.groupValues?.get(1)?.trim()
    }
    
    /**
     * Извлекает значение тега с возможными namespace prefixes
     * Пробует сначала с namespace, потом без
     * 
     * @param xml XML строка для поиска
     * @param tag имя тега (без namespace)
     * @param namespaces список namespace prefixes для проверки (например: ["notes", "t", "m"])
     * @return значение тега или null если не найдено
     */
    fun extractWithNamespaces(xml: String, tag: String, namespaces: List<String>): String? {
        // Сначала пробуем с namespace prefixes
        for (ns in namespaces) {
            val pattern = EasPatterns.getTagPatternWithNs(ns, tag)
            val match = pattern.find(xml)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        
        // Затем без namespace
        return extract(xml, tag)
    }
    
    // === Специализированные методы для удобства ===
    
    /**
     * Извлекает значение из Notes XML (namespace: notes)
     */
    fun extractNote(xml: String, tag: String): String? {
        return extractWithNamespaces(xml, tag, listOf("notes"))
    }
    
    /**
     * Извлекает значение из Calendar XML (namespace: calendar)
     */
    fun extractCalendar(xml: String, tag: String): String? {
        return extractWithNamespaces(xml, tag, listOf("calendar"))
    }
    
    /**
     * Извлекает значение из Tasks XML (namespace: tasks)
     */
    fun extractTask(xml: String, tag: String): String? {
        return extractWithNamespaces(xml, tag, listOf("tasks"))
    }
    
    /**
     * Извлекает значение из Contacts XML (namespace: contacts)
     */
    fun extractContact(xml: String, tag: String): String? {
        return extractWithNamespaces(xml, tag, listOf("contacts"))
    }
    
    /**
     * Извлекает значение из GAL XML (namespace: gal)
     */
    fun extractGal(xml: String, tag: String): String? {
        return extractWithNamespaces(xml, tag, listOf("gal"))
    }
    
    /**
     * Извлекает значение из EWS XML (namespaces: t, m)
     */
    fun extractEws(xml: String, tag: String): String? {
        return extractWithNamespaces(xml, tag, listOf("t", "m"))
    }
    
    /**
     * Извлекает атрибут из XML элемента
     * Например: extractAttribute(xml, "ItemId", "Id") для <t:ItemId Id="..."/>
     */
    fun extractAttribute(xml: String, element: String, attribute: String): String? {
        // Пробуем с namespace prefix "t:"
        val patternWithNs = """<t:$element[^>]*$attribute="([^"]+)"[^>]*>""".toRegex()
        patternWithNs.find(xml)?.let { return it.groupValues[1] }
        
        // Пробуем без namespace
        val pattern = """<$element[^>]*$attribute="([^"]+)"[^>]*>""".toRegex()
        return pattern.find(xml)?.groupValues?.get(1)
    }
}
