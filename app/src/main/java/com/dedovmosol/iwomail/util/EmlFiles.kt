package com.dedovmosol.iwomail.util

/** Файлы писем .eml (RFC 5322, message/rfc822): их открывают Outlook, Thunderbird и почта на любом устройстве. */
object EmlFiles {
    const val MIME_TYPE = "message/rfc822"

    private const val MAX_NAME_CHARS = 100
    private val FORBIDDEN = Regex("""[\\/:*?"<>|\u0000-\u001F\u007F]""")
    private val WHITESPACE = Regex("\\s+")

    /** Имя файла по теме: без недопустимых в именах символов, не длиннее [MAX_NAME_CHARS]; темы нет — «email». */
    fun fileName(subject: String): String {
        // Сначала пробелы (табуляция, переносы строк темы — один пробел), потом запрещённые символы
        val clean = subject.replace(WHITESPACE, " ").replace(FORBIDDEN, "_").trim().trim('.')
        // Не резать суррогатную пару (эмодзи в теме): половина символа — недопустимое имя файла
        val cut = if (clean.length > MAX_NAME_CHARS && Character.isHighSurrogate(clean[MAX_NAME_CHARS - 1])) {
            MAX_NAME_CHARS - 1
        } else {
            MAX_NAME_CHARS
        }
        val base = clean.take(cut).trim()
        return "${base.ifEmpty { "email" }}.eml"
    }
}
