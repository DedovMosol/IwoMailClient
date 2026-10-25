package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.util.looksLikeHtmlEmailContent

/**
 * Формат тела события календаря — единственное место, где решается «HTML или текст» (SoC).
 *
 * Чтение: синк просит HTML (AirSyncBase BodyPreference Type=2 / EWS BodyType=HTML). Exchange сам
 * конвертирует RTF/текст Outlook в HTML, поэтому разметка, цвета и ссылки на inline-картинки
 * (`cid:`) доходят до клиента. HTML хранится как есть: построчная дедупликация
 * ([CalendarDateUtils.removeDuplicateLines]) превращает теги в переводы строк и разрушает разметку,
 * она применяется только к plain text (повторы строк Outlook 2007 в текстовых телах).
 *
 * Запись: HTML уходит как HTML, текст — как текст ([MS-ASAIRS] Type: 1 — plain text, 2 — HTML;
 * для календаря допустимы только эти два значения).
 */
internal object CalendarBodyFormat {
    const val EAS_TYPE_TEXT = 1
    const val EAS_TYPE_HTML = 2

    /**
     * Верхняя граница тела в Sync-ответе (байты). [MS-ASAIRS] TruncationSize: без элемента сервер
     * отдаёт тело целиком, но тогда одно аномальное тело раздувает WBXML-ответ на 100 событий.
     * 1 МиБ с запасом покрывает HTML-тела Outlook (inline-картинки — отдельные вложения, не тело).
     */
    const val SYNC_TRUNCATION_SIZE = 1_048_576

    fun isHtml(body: String): Boolean = looksLikeHtmlEmailContent(body)

    fun easType(body: String): Int = if (isHtml(body)) EAS_TYPE_HTML else EAS_TYPE_TEXT

    fun ewsType(body: String): String = if (isHtml(body)) "HTML" else "Text"

    /** Тело, пришедшее с сервера: HTML — без изменений, текст — с прежней дедупликацией строк. */
    fun normalizeIncoming(body: String): String =
        if (isHtml(body)) body.trim() else CalendarDateUtils.removeDuplicateLines(body)
}
