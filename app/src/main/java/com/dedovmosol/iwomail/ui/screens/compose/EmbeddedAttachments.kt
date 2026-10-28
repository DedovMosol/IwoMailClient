package com.dedovmosol.iwomail.ui.screens.compose

import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.data.repository.DraftAttachmentRole
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.util.EmbeddedImages
import java.io.File

/**
 * Помеченное inline вложение без ссылки в теле ([role] FILE) при data:-картинках в теле ([embedded]): оно уже в теле,
 * только если его байты совпали с одной из картинок, — тогда null и [onEmbedded] с её data URL. Иначе — вложение
 * для обычной обработки (файл: Content-ID ставят и файлам, синхронизация помечает такие inline). Скачанное для
 * сравнения сохраняется в [dir] ([onDownloaded]) и возвращается с localPath — второй раз не скачивается.
 * Не скачалось — вложение как есть (дальше — обычная обработка недоступного вложения).
 */
internal suspend fun withLocalCopyUnlessEmbedded(
    attachment: AttachmentEntity,
    role: DraftAttachmentRole,
    embedded: EmbeddedImages,
    dir: File,
    download: suspend (fileReference: String) -> EasResult<ByteArray>?,
    onDownloaded: suspend (File) -> Unit = {},
    onEmbedded: (dataUrl: String) -> Unit = {}
): AttachmentEntity? {
    if (role != DraftAttachmentRole.FILE || !attachment.isInline || !embedded.present) return attachment
    return try {
        var result = attachment
        val local = attachment.localPath?.let(::File)?.takeIf { it.exists() }
        val bytes = if (local != null) {
            local.readBytes()
        } else {
            if (attachment.fileReference.isBlank()) return attachment
            val data = (download(attachment.fileReference) as? EasResult.Success)?.data ?: return attachment
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "${System.currentTimeMillis()}_${attachment.displayName.replace(SAFE_FILENAME_COMPOSE_REGEX, "_")}")
            file.writeBytes(data)
            onDownloaded(file)
            result = attachment.copy(localPath = file.absolutePath, downloaded = true)
            data
        }
        val dataUrl = embedded.dataUrlOf(bytes) ?: return result
        onEmbedded(dataUrl)
        null
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        // Сравнить не вышло — вложение не прячем (лучше лишний файл, чем пропавший).
        attachment
    }
}
