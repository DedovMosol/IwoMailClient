package com.dedovmosol.iwomail.ui.utils

import androidx.compose.runtime.saveable.Saver
import java.io.File

/**
 * Строки длиннее этого (UTF-16: ~20 КБ в Parcel) сохраняются в файл, а в сохранённом состоянии — только путь.
 * Всё состояние Activity уходит одной транзакцией Binder с общим на процесс буфером ~1 МБ; крупнее —
 * TransactionTooLargeException и падение приложения при сворачивании
 * (developer.android.com/develop/ui/views/layout/webapps/manage-state).
 */
internal const val LARGE_STATE_INLINE_CHARS = 10_000

internal const val LARGE_STATE_FILE_PREFIX = "compose_state_"

// Сохранённое значение всегда помечено своим видом, поэтому пользовательский текст не спутать со ссылкой на файл
// (NAV-09: раньше строка из «Поделиться» вида "\u0000FILE:/data/.../mail_database" при восстановлении читалась
// как путь — файл приложения читался в тело письма и удалялся).
// Управляющий символ в начале: значение из прежней версии (обычный текст без пометки) с пометкой не спутать.
private const val KIND_TEXT = "\u0001T"
private const val KIND_FILE = "\u0001F"
/** Прежний формат ссылки (до NAV-09) — принимается только для своего файла в [cacheDir]. */
private const val LEGACY_FILE_MARKER = "\u0000FILE:"
private val STATE_FILE_NAME = Regex("""${LARGE_STATE_FILE_PREFIX}[A-Za-z0-9_-]{1,64}_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.tmp""")

/**
 * Saver для крупного текста (тело письма, HTML редактора): короткий — как есть, длинный — файлом в [cacheDir].
 *
 * Файл свой у каждого экземпляра ([tag] + случайная часть): два экрана с одинаковым [tag] в стеке навигации не
 * перезаписывают друг друга. Повторные сохранения одного экземпляра пишут в тот же файл; восстановление файл удаляет.
 * В состоянии хранится только имя файла; восстановление принимает лишь имя своего формата прямо в [cacheDir].
 * Неиспользованные файлы (экран закрыт без восстановления) удаляет [isStaleLargeStateFile] при старте приложения.
 */
internal fun createLargeStringSaver(cacheDir: File, tag: String): Saver<String, String> {
    val safeTag = tag.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(64).ifEmpty { "state" }
    val file by lazy { File(cacheDir, "$LARGE_STATE_FILE_PREFIX${safeTag}_${java.util.UUID.randomUUID()}.tmp") }
    return Saver(
        save = { value ->
            if (value.length <= LARGE_STATE_INLINE_CHARS) KIND_TEXT + value
            else try {
                file.writeText(value)
                KIND_FILE + file.name
            } catch (_: Exception) {
                KIND_TEXT + value.take(LARGE_STATE_INLINE_CHARS)
            }
        },
        restore = { saved ->
            when {
                saved.startsWith(KIND_TEXT) -> saved.substring(KIND_TEXT.length)
                saved.startsWith(KIND_FILE) -> readOwnStateFile(cacheDir, saved.substring(KIND_FILE.length))
                saved.startsWith(LEGACY_FILE_MARKER) -> readOwnStateFile(cacheDir, File(saved.removePrefix(LEGACY_FILE_MARKER)).name)
                else -> saved
            }
        }
    )
}

/** Читает и удаляет файл состояния, только если [name] — файл нашего формата прямо в [cacheDir]; иначе "". */
private fun readOwnStateFile(cacheDir: File, name: String): String {
    if (!STATE_FILE_NAME.matches(name)) return ""
    return try {
        val dir = cacheDir.canonicalFile
        val stored = File(dir, name).canonicalFile
        if (stored.parentFile != dir || !stored.isFile) return ""
        stored.readText().also { stored.delete() }
    } catch (_: Exception) {
        ""
    }
}

/** Файлы состояния старше суток — от закрытых экранов: состояние для восстановления после смерти процесса моложе. */
internal fun isStaleLargeStateFile(file: File, now: Long): Boolean =
    file.name.startsWith(LARGE_STATE_FILE_PREFIX) && file.name.endsWith(".tmp") &&
        now - file.lastModified() > STALE_AFTER_MS

private const val STALE_AFTER_MS = 24 * 60 * 60 * 1000L
