package com.dedovmosol.iwomail.eas

import java.util.concurrent.ConcurrentHashMap

/**
 * Предкомпилированные regex паттерны для EAS/EWS парсинга
 * Вынесены из EasClient для соблюдения DRY и улучшения читаемости
 */
object EasPatterns {
    
    // === Email patterns ===
    val EMAIL_BRACKET = "<([^>]+)>".toRegex()
    
    // === EWS patterns ===
    val EWS_ITEM_ID = """<t:ItemId Id="([^"]+)"""".toRegex()
    val EWS_SUBJECT = """<t:Subject>(.*?)</t:Subject>""".toRegex(RegexOption.DOT_MATCHES_ALL)
    val EWS_BODY = """<t:Body[^>]*>(.*?)</t:Body>""".toRegex(RegexOption.DOT_MATCHES_ALL)
    val EWS_DATE_CREATED = """<t:DateTimeCreated>(.*?)</t:DateTimeCreated>""".toRegex()
    val EWS_EMAIL_ADDRESS = """<t:EmailAddress>([^<]+)</t:EmailAddress>""".toRegex()
    val EWS_TO_RECIPIENTS = """<t:ToRecipients>(.*?)</t:ToRecipients>""".toRegex(RegexOption.DOT_MATCHES_ALL)
    val EWS_CC_RECIPIENTS = """<t:CcRecipients>(.*?)</t:CcRecipients>""".toRegex(RegexOption.DOT_MATCHES_ALL)
    val EWS_MESSAGE_TEXT = """<m:MessageText>(.*?)</m:MessageText>""".toRegex(RegexOption.DOT_MATCHES_ALL)
    val EWS_RESPONSE_CODE = """<m:ResponseCode>(.*?)</m:ResponseCode>""".toRegex()
    
    // === Notes/Calendar category patterns ===
    val NOTES_CATEGORY = "<notes:Category>(.*?)</notes:Category>".toRegex()
    val CALENDAR_CATEGORY = "<calendar:Category>(.*?)</calendar:Category>".toRegex()
    
    // === MDN (Message Disposition Notification) patterns ===
    val MDN_DISPOSITION = "Disposition-Notification-To:\\s*<?([^>\\r\\n]+)>?".toRegex(RegexOption.IGNORE_CASE)
    val MDN_RETURN_RECEIPT = "Return-Receipt-To:\\s*<?([^>\\r\\n]+)>?".toRegex(RegexOption.IGNORE_CASE)
    val MDN_CONFIRM_READING = "X-Confirm-Reading-To:\\s*<?([^>\\r\\n]+)>?".toRegex(RegexOption.IGNORE_CASE)
    
    // === MIME patterns ===
    val BOUNDARY = "boundary=\"?([^\"\\r\\n]+)\"?".toRegex(RegexOption.IGNORE_CASE)
    
    // === EAS response patterns ===
    val MOVE_RESPONSE = "<Response>(.*?)</Response>".toRegex(RegexOption.DOT_MATCHES_ALL)
    val ITEM_OPS_GLOBAL_STATUS = "<ItemOperations>.*?<Status>(\\d+)</Status>".toRegex(RegexOption.DOT_MATCHES_ALL)
    val ITEM_OPS_FETCH_STATUS = "<Fetch>.*?<Status>(\\d+)</Status>".toRegex(RegexOption.DOT_MATCHES_ALL)
    val ITEM_OPS_DATA = "<Data>(.*?)</Data>".toRegex(RegexOption.DOT_MATCHES_ALL)
    val ITEM_OPS_PROPS_DATA = "<Properties>.*?<Data>(.*?)</Data>.*?</Properties>".toRegex(RegexOption.DOT_MATCHES_ALL)
    val FOLDER = "<Folder>(.*?)</Folder>".toRegex(RegexOption.DOT_MATCHES_ALL)
    
    // === Кэш для динамических regex паттернов (thread-safe) ===
    private val cache = ConcurrentHashMap<String, Regex>()
    
    /**
     * Получает или создаёт regex для извлечения значения тега
     * Кэширует паттерны для производительности
     */
    fun getTagPattern(tag: String): Regex {
        return cache.getOrPut(tag) {
            "<$tag>(.*?)</$tag>".toRegex(RegexOption.DOT_MATCHES_ALL)
        }
    }
    
    /**
     * Получает или создаёт regex для извлечения значения тега с namespace
     */
    fun getTagPatternWithNs(namespace: String, tag: String): Regex {
        val key = "$namespace:$tag"
        return cache.getOrPut(key) {
            "<$namespace:$tag>(.*?)</$namespace:$tag>".toRegex(RegexOption.DOT_MATCHES_ALL)
        }
    }
}
