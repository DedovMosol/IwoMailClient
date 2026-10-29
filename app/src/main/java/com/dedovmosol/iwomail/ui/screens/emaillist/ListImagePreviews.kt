package com.dedovmosol.iwomail.ui.screens.emaillist

import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.util.isImageAttachment
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Миниатюра строки списка: файл первой картинки и сколько картинок у письма всего (на миниатюре — «+ ещё»). */
internal data class ListPreview(val path: String, val imageCount: Int) {
    /** Сколько картинок не видно на миниатюре; 0 — подпись не нужна. */
    val moreCount: Int get() = (imageCount - 1).coerceAtLeast(0)
}

/**
 * Миниатюры картинок в строках списка писем.
 *
 * Миниатюра — картинка-вложение, уже скачанная на устройство. Черновикам ([find] с `download = true`) недостающую
 * небольшую картинку скачивают: EAS не отдаёт уменьшенных копий (ItemOperations Fetch — только файл целиком), а без
 * этого черновики с сервера были без миниатюр, хотя созданные в приложении — с ними.
 *
 * Одна попытка скачать на вложение за жизнь экрана, не больше [maxParallelDownloads] сразу — прокрутка списка не
 * запускает лавину загрузок. Методы вызываются с главного потока (состояние без синхронизации), работа с БД и сетью —
 * в переданных функциях.
 */
internal class ListImagePreviews(
    /** Записи вложений писем (IO). */
    private val attachmentsOf: suspend (emailIds: List<String>) -> List<AttachmentEntity>,
    /** Скачать вложение письма аккаунта в файл и записать путь в запись (IO); null — не вышло. */
    private val downloadToFile: suspend (accountId: Long, AttachmentEntity) -> String?,
    /** Файл на месте (IO). */
    private val fileExists: suspend (String) -> Boolean,
    /** Скачивать сейчас можно (например, не мобильная сеть при «только по Wi-Fi»). */
    val downloadAllowed: suspend () -> Boolean = { true },
    maxParallelDownloads: Int = 2
) {
    private val gate = Semaphore(maxParallelDownloads)
    private val attempted = HashSet<Long>()

    /** Картинка, которую стоит скачать для миниатюры, и сколько картинок у письма. */
    class Pending(val row: AttachmentEntity, val imageCount: Int)

    /** Что есть для писем [emailIds]: миниатюры из скачанных картинок и картинки, которые стоит скачать (по одной на письмо). */
    class Found(val local: Map<String, ListPreview>, val toDownload: Map<String, Pending>)

    suspend fun find(emailIds: List<String>, download: Boolean): Found {
        val byEmail = attachmentsOf(emailIds).groupBy { it.emailId }
        val local = HashMap<String, ListPreview>()
        val toDownload = HashMap<String, Pending>()
        for ((emailId, rows) in byEmail) {
            val images = rows.filter { isImageAttachment(it.contentType, it.displayName) }
            if (images.isEmpty()) continue
            val path = images.firstNotNullOfOrNull { row -> row.localPath?.takeIf { fileExists(it) } }
            if (path != null) {
                local[emailId] = ListPreview(path, images.size)
            } else if (download) {
                images.firstOrNull { canDownload(it) && it.id !in attempted }?.let { toDownload[emailId] = Pending(it, images.size) }
            }
        }
        return Found(local, toDownload)
    }

    /** Скачать картинку для миниатюры (один раз на вложение); null — уже пробовали или не вышло. */
    suspend fun download(accountId: Long, pending: Pending): ListPreview? {
        if (!attempted.add(pending.row.id)) return null
        val path = gate.withPermit { downloadToFile(accountId, pending.row) } ?: return null
        return ListPreview(path, pending.imageCount)
    }

    companion object {
        /** Крупнее не скачиваем ради миниатюры: снимки экрана и фото в тексте обычно меньше. */
        const val MAX_DOWNLOAD_BYTES = 2L * 1024 * 1024

        /** Есть ссылка на сервер и известный небольшой размер (EstimatedDataSize, [MS-ASAIRS]). */
        internal fun canDownload(row: AttachmentEntity): Boolean =
            row.fileReference.isNotBlank() && row.estimatedSize in 1..MAX_DOWNLOAD_BYTES
    }
}
