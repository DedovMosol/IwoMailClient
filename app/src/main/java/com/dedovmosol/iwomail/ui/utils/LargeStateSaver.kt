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
private const val FILE_MARKER = "\u0000FILE:"

/**
 * Saver для крупного текста (тело письма, HTML редактора): короткий — как есть, длинный — файлом в [cacheDir].
 *
 * Файл свой у каждого экземпляра ([tag] + случайная часть): два экрана с одинаковым [tag] в стеке навигации не
 * перезаписывают друг друга. Повторные сохранения одного экземпляра пишут в тот же файл; восстановление файл удаляет.
 * Неиспользованные файлы (экран закрыт без восстановления) удаляет [isStaleLargeStateFile] при старте приложения.
 */
internal fun createLargeStringSaver(cacheDir: File, tag: String): Saver<String, String> {
    val file by lazy { File(cacheDir, "$LARGE_STATE_FILE_PREFIX${tag}_${java.util.UUID.randomUUID()}.tmp") }
    return Saver(
        save = { value ->
            if (value.length <= LARGE_STATE_INLINE_CHARS) value
            else try {
                file.writeText(value)
                FILE_MARKER + file.absolutePath
            } catch (_: Exception) {
                value.take(LARGE_STATE_INLINE_CHARS)
            }
        },
        restore = { saved ->
            if (saved.startsWith(FILE_MARKER)) {
                try {
                    val stored = File(saved.removePrefix(FILE_MARKER))
                    if (stored.exists()) stored.readText().also { stored.delete() } else ""
                } catch (_: Exception) {
                    ""
                }
            } else saved
        }
    )
}

/** Файлы состояния старше суток — от закрытых экранов: состояние для восстановления после смерти процесса моложе. */
internal fun isStaleLargeStateFile(file: File, now: Long): Boolean =
    file.name.startsWith(LARGE_STATE_FILE_PREFIX) && file.name.endsWith(".tmp") &&
        now - file.lastModified() > STALE_AFTER_MS

private const val STALE_AFTER_MS = 24 * 60 * 60 * 1000L
