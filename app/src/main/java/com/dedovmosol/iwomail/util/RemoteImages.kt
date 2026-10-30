package com.dedovmosol.iwomail.util

/**
 * Картинки из интернета в письме. По ним отправитель узнаёт, что письмо открыли, когда и с какого адреса (пиксели
 * отслеживания), поэтому по умолчанию они не загружаются — как в Outlook и OWA. Встроенные в письмо картинки
 * (cid:, data:) показываются всегда: они пришли вместе с письмом.
 */
object RemoteImages {

    /**
     * Ссылки на сеть, которые грузит отрисовка письма: src/srcset/background/poster у тегов и url(...) в CSS.
     * Ссылки (href) не в счёт — они открываются только по нажатию, во внешнем браузере.
     */
    private val REMOTE_REFERENCE = Regex(
        """(?:\b(?:src|srcset|background|poster)\s*=\s*["']?\s*|url\(\s*["']?\s*)(?:https?:)?//""",
        RegexOption.IGNORE_CASE
    )

    /** В письме есть картинки (или другие ресурсы отрисовки) из интернета. */
    fun present(html: String): Boolean = REMOTE_REFERENCE.containsMatchIn(html)

    /** Адрес отправителя для списка доверенных: без имени и регистра. */
    fun senderKey(from: String): String = extractEmailAddress(from).trim().lowercase()

    /**
     * Загружать ли картинки письма от [from]: всегда (настройка), доверенный отправитель или пользователь нажал
     * «Показать» для этого письма.
     */
    fun allowed(alwaysLoad: Boolean, trustedSenders: Set<String>, from: String, shownForThisEmail: Boolean): Boolean =
        alwaysLoad || shownForThisEmail || senderKey(from).let { it.isNotEmpty() && it in trustedSenders }

    /**
     * Content-Security-Policy страницы письма. Без разрешения сеть закрыта и для картинок, и для шрифтов (тоже
     * пиксели отслеживания); стили только встроенные — внешний CSS и @import не грузятся никогда.
     */
    fun contentSecurityPolicy(nonce: String, remoteAllowed: Boolean): String {
        val network = if (remoteAllowed) " https: http:" else ""
        return "default-src 'none'; script-src 'nonce-$nonce'; style-src 'unsafe-inline'; " +
            "img-src data: cid: blob:$network; font-src data:$network;"
    }
}
