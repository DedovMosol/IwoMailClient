package com.dedovmosol.iwomail.util

/**
 * Страница письма для печати и PDF: шапка (тема, от, кому, копия, дата) и тело — как печатают Outlook и OWA.
 * Тело — уже очищенный HTML (sanitizeEmailHtml, картинки cid: заменены) или обычный текст; значения шапки
 * экранируются. Сеть — по тем же правилам, что при просмотре ([RemoteImages]): скриптов нет совсем.
 */
object EmailPrintDocument {

    data class Header(val subject: String, val from: String, val to: String, val cc: String, val date: String)

    data class Labels(val from: String, val to: String, val cc: String, val date: String)

    fun html(header: Header, labels: Labels, body: String, bodyIsHtml: Boolean, remoteAllowed: Boolean): String {
        val rows = listOf(labels.from to header.from, labels.to to header.to, labels.cc to header.cc, labels.date to header.date)
            .filter { (_, value) -> value.isNotBlank() }
            .joinToString("") { (label, value) -> "<tr><th>${label.escapeHtml()}</th><td>${value.escapeHtml()}</td></tr>" }
        val content = if (bodyIsHtml) body else "<div class=\"plain\">${body.escapeHtml()}</div>"
        // script-src без nonce: на странице печати скриптов нет
        val csp = RemoteImages.contentSecurityPolicy(nonce = "none", remoteAllowed = remoteAllowed).escapeHtml()
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"$csp\">" +
            "<title>${header.subject.escapeHtml()}</title><style>$STYLE</style></head><body>" +
            "<header class=\"mail-header\"><h1>${header.subject.escapeHtml()}</h1><table>$rows</table></header>" +
            "<div class=\"mail-body\">$content</div></body></html>"
    }

    private const val STYLE =
        "body{margin:0;font-family:sans-serif;color:#000;background:#fff}" +
            ".mail-header{border-bottom:1px solid #999;padding-bottom:8px;margin-bottom:12px}" +
            ".mail-header h1{font-size:18pt;margin:0 0 8px}" +
            ".mail-header table{border-collapse:collapse;font-size:10pt}" +
            ".mail-header th{text-align:left;padding:1px 12px 1px 0;vertical-align:top;white-space:nowrap;color:#444}" +
            ".mail-header td{padding:1px 0;word-break:break-word}" +
            ".plain{white-space:pre-wrap;word-wrap:break-word;font-size:11pt}" +
            "img{max-width:100%;height:auto}" +
            "@page{margin:15mm}"
}
