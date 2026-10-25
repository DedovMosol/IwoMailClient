package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.util.detectImageMimeType
import com.dedovmosol.iwomail.util.normalizeContentId
import com.dedovmosol.iwomail.util.referencedContentIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

/**
 * Inline-картинки тела события: `cid:` → data URL (SoC — репозиторий хранит серверное тело с `cid:`,
 * подстановка делается только для показа/редактирования и никогда не пишется в БД).
 *
 * Источник картинки — вложение события с тем же Content-ID ([MS-ASAIRS] ContentId / EWS
 * `t:ContentId`). Флаг IsInline не обязателен: в EWS он есть только с Exchange 2010, а Exchange 2007
 * помечает inline-вложение лишь Content-ID, на который ссылается HTML. Поэтому критерий — «на
 * Content-ID вложения ссылается тело».
 *
 * Байты кэшируются в `cacheDir/calendar_inline` (повторное открытие события — без сети); каталог
 * чистится по возрасту/размеру вместе с прочими временными вложениями.
 *
 * Ограничения как у писем (защита от OOM): картинка ≤ [MAX_IMAGE_BYTES], сумма ≤ [MAX_TOTAL_BYTES],
 * не больше [MAX_IMAGES] штук. Что не уложилось — остаётся `cid:` (плейсхолдер битой картинки).
 */
internal class CalendarInlineImages(
    cacheRoot: File,
    private val download: suspend (accountId: Long, fileReference: String) -> EasResult<ByteArray>
) {
    private val cacheDir = File(cacheRoot, CACHE_DIR_NAME)

    /** Вложение, на которое может ссылаться тело. */
    internal data class InlineRef(val contentId: String, val fileReference: String, val size: Long)

    /** Content-ID (как в метаданных вложения) → data URL. Пустая карта — подставлять нечего. */
    suspend fun load(accountId: Long, body: String, attachmentsJson: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            val referenced = referencedContentIds(body)
            if (referenced.isEmpty()) return@withContext emptyMap()
            val refs = parseInlineRefs(attachmentsJson)
                .filter { normalizeContentId(it.contentId) in referenced }
                .distinctBy { normalizeContentId(it.contentId) }
            val result = LinkedHashMap<String, String>()
            var total = 0L
            for (ref in refs) {
                if (result.size >= MAX_IMAGES) break
                if (ref.size > MAX_IMAGE_BYTES) continue
                val bytes = bytesFor(accountId, ref) ?: continue
                if (bytes.size > MAX_IMAGE_BYTES || total + bytes.size > MAX_TOTAL_BYTES) continue
                val mime = detectImageMimeType(bytes) ?: continue
                total += bytes.size
                result[ref.contentId] = "data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
            }
            result
        }

    private suspend fun bytesFor(accountId: Long, ref: InlineRef): ByteArray? {
        val file = File(cacheDir, cacheKey(accountId, ref.fileReference))
        if (file.isFile && file.length() > 0) {
            try {
                return file.readBytes()
            } catch (_: Exception) {
                // Файл удалила очистка между проверкой и чтением — скачиваем заново.
            }
        }
        val downloaded = try {
            download(accountId, ref.fileReference)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return null
        }
        val bytes = (downloaded as? EasResult.Success)?.data ?: return null
        if (bytes.isEmpty()) return null
        writeCacheAtomically(file, bytes)
        return bytes
    }

    /**
     * Атомарная запись (уникальный tmp + rename): параллельное открытие того же события не читает
     * обрубок и не пишет в чужой tmp.
     */
    private fun writeCacheAtomically(file: File, bytes: ByteArray) {
        var tmp: File? = null
        try {
            if (!cacheDir.exists() && !cacheDir.mkdirs()) return
            tmp = File.createTempFile("inl", ".tmp", cacheDir).also { it.writeBytes(bytes) }
            if (tmp.renameTo(file)) tmp = null
        } catch (_: Exception) {
            // Кэш — оптимизация: сбой записи не мешает показу.
        } finally {
            tmp?.delete()
        }
    }

    companion object {
        const val CACHE_DIR_NAME = "calendar_inline"
        const val MAX_IMAGE_BYTES = 2L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 5L * 1024 * 1024
        const val MAX_IMAGES = 20

        /** Вложения с Content-ID из JSON события (формат CalendarXmlParser / CalendarAttachmentService). */
        internal fun parseInlineRefs(attachmentsJson: String): List<InlineRef> {
            if (attachmentsJson.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(attachmentsJson)
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    val cid = obj.optString("contentId", "")
                    val ref = obj.optString("fileReference", "")
                    if (cid.isBlank() || ref.isBlank()) null
                    else InlineRef(cid, ref, obj.optLong("size", 0L))
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        /** Имя файла кэша: SHA-256 от аккаунта и ссылки (EWS AttachmentId длинный и с '/', '+'). */
        internal fun cacheKey(accountId: Long, fileReference: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("$accountId|$fileReference".toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
