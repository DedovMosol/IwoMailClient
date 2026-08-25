package com.dedovmosol.iwomail.domain

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.ui.screens.compose.AttachmentInfo
import com.dedovmosol.iwomail.ui.screens.compose.SAFE_FILENAME_COMPOSE_REGEX
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Единый загрузчик вложений для reply/forward/draft.
 * Устраняет CS-15: DRY — 3 дублированных блока по ~80-100 строк в ComposeScreen.
 *
 * **Инварианты:**
 * - Протокол EAS/EWS не затрагивается (использует существующие EasClient.downloadAttachment)
 * - Exchange 2007 SP1/SP2 compatibility сохранена
 * - OOM protection: лимит inline-картинок (CS-4, max 20)
 * - Crash-free: все exceptions логируются, но не прерывают обработку остальных вложений
 * - Memory-safe: файлы читаются только если localPath существует
 *
 * @param context Application context (не Activity!)
 * @param mailRepository Репозиторий почты для получения вложений
 * @param dispatcher Dispatcher для IO операций (тестируемость)
 */
class AttachmentLoader(
    private val context: Context,
    private val mailRepository: MailRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Стратегия получения content-URI для локального файла вложения (DIP).
     * Прод (дефолт): FileProvider — безопасный обмен между приложениями.
     * Тесты: детерминированный резолвер без зависимости от провайдера/манифеста.
     */
    private val fileUriResolver: (File) -> Uri = { file ->
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
) {

    /**
     * Загруженное вложение.
     *
     * @param attachmentInfo Информация для UI (uri, name, size, mimeType)
     * @param isInline Inline-картинка (не показывается в списке вложений)
     * @param contentId Content-ID для замены в HTML (только для inline)
     */
    data class LoadedAttachment(
        val attachmentInfo: AttachmentInfo,
        val isInline: Boolean,
        val contentId: String?
    )

    /**
     * Результат загрузки вложений.
     */
    sealed class LoadResult {
        /**
         * Успешная загрузка.
         *
         * @param fileAttachments Файловые вложения (для списка в UI)
         * @param inlineImages Inline-картинки (contentId -> data:image/...;base64,...)
         * @param skippedCount Количество пропущенных вложений (ошибки/лимиты)
         */
        data class Success(
            val fileAttachments: List<AttachmentInfo>,
            val inlineImages: Map<String, String>,
            val skippedCount: Int = 0
        ) : LoadResult()

        /**
         * Критическая ошибка (весь процесс загрузки провален).
         */
        data class Error(val message: String) : LoadResult()
    }

    /**
     * Источник вложений.
     *
     * @param attachmentsDirName Подкаталог filesDir для скачанных вложений.
     *   Совпадает с эталонным ComposeScreen и с file_paths.xml (files-path):
     *   файлы переживают очистку кэша ОС и доступны FileProvider для отправки.
     */
    enum class AttachmentSource(val attachmentsDirName: String) {
        Reply("reply_attachments"),
        Forward("forward_attachments"),
        Draft("draft_attachments")
    }

    /**
     * Загружает вложения и inline-картинки для reply/forward/draft.
     *
     * **Алгоритм:**
     * 1. Получить список вложений из БД
     * 2. Для каждого вложения:
     *    - Если localPath существует → читать с диска
     *    - Иначе если fileReference не пуст → скачать через EAS
     *    - Иначе пропустить с warning
     * 3. Разделить на inline-картинки (contentId → base64) и файловые вложения
     * 4. Применить лимит inline-картинок (CS-4, max 20)
     *
     * **OOM protection:**
     * - Inline-картинок не больше MAX_INLINE_IMAGES (20)
     * - Файлы читаются только если localPath существует (не через contentResolver)
     *
     * **Crash resistance:**
     * - Ошибка загрузки одного вложения не прерывает обработку остальных
     * - CancellationException пробрасывается (корректная отмена корутины)
     * - Все остальные exceptions логируются и увеличивают skippedCount
     *
     * @param source Источник (Reply/Forward/Draft) — для логирования
     * @param email Исходное письмо
     * @param easClient EAS-клиент для скачивания (может быть null для offline-режима)
     * @param collectionId Server ID папки источника (для EAS downloadAttachment)
     * @param emailServerId Server ID письма источника (для EAS downloadAttachment)
     * @param onProgress Callback прогресса (0.0 .. 1.0), опционально
     * @return LoadResult.Success с вложениями или LoadResult.Error
     */
    suspend fun loadAttachments(
        source: AttachmentSource,
        email: EmailEntity,
        easClient: EasClient?,
        collectionId: String?,
        emailServerId: String?,
        onProgress: (Float) -> Unit = {}
    ): LoadResult = withContext(dispatcher) {
        try {
            // Получить список вложений из БД
            val attachments = mailRepository.getAttachmentsSync(email.id)

            if (attachments.isEmpty()) {
                return@withContext LoadResult.Success(
                    fileAttachments = emptyList(),
                    inlineImages = emptyMap(),
                    skippedCount = 0
                )
            }

            val fileAttachments = mutableListOf<AttachmentInfo>()
            val inlineImages = mutableMapOf<String, String>()
            var skippedCount = 0

            attachments.forEachIndexed { index, att ->
                // Обновить прогресс
                onProgress(index.toFloat() / attachments.size)

                try {
                    // Проверка лимита inline-картинок (CS-4)
                    if (att.isInline && !att.contentId.isNullOrBlank()) {
                        if (inlineImages.size >= MAX_INLINE_IMAGES) {
                            android.util.Log.w(
                                TAG,
                                "$source: Inline limit reached (max $MAX_INLINE_IMAGES), skipping ${att.displayName}"
                            )
                            skippedCount++
                            return@forEachIndexed
                        }
                    }

                    // Попытка 1: localPath существует
                    val localPath = att.localPath
                    if (localPath != null && File(localPath).exists()) {
                        processLocalAttachment(att, source, fileAttachments, inlineImages)
                        return@forEachIndexed
                    }

                    // Попытка 2: скачивание через EAS/EWS
                    val downloaded = when {
                        // Draft: fileReference черновика может быть либо EAS-ссылкой (с ":"),
                        // либо EWS ItemId (без ":") — требуется роутинг через
                        // downloadDraftAttachment (эталонное поведение старого ComposeScreen,
                        // критично для Exchange 2007 SP1/SP2, где ItemOperations для черновиков
                        // может быть недоступен).
                        source == AttachmentSource.Draft && easClient != null && att.fileReference.isNotBlank() ->
                            downloadDraftAttachment(att, easClient, source)
                        easClient != null && att.fileReference.isNotBlank() && collectionId != null && emailServerId != null ->
                            downloadAttachment(att, easClient, source)
                        else -> null
                    }
                    if (downloaded != null) {
                        processLocalAttachment(att.copy(localPath = downloaded.absolutePath), source, fileAttachments, inlineImages)
                        return@forEachIndexed
                    }

                    // Вложение недоступно
                    android.util.Log.w(
                        TAG,
                        "$source: Skipping attachment ${att.displayName} (localPath=$localPath, fileRef='${att.fileReference}', hasClient=${easClient != null})"
                    )
                    skippedCount++

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "$source: Error loading attachment ${att.displayName}: ${e.message}")
                    skippedCount++
                }
            }

            onProgress(1f)

            LoadResult.Success(
                fileAttachments = fileAttachments,
                inlineImages = inlineImages,
                skippedCount = skippedCount
            )

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadResult.Error(e.message ?: "Failed to load attachments")
        }
    }

    /**
     * Обработать локально доступное вложение (из localPath).
     * Разделяет на inline-картинки (contentId → base64) и файловые вложения (uri).
     */
    private fun processLocalAttachment(
        att: AttachmentEntity,
        source: AttachmentSource,
        fileAttachments: MutableList<AttachmentInfo>,
        inlineImages: MutableMap<String, String>
    ) {
        val file = File(att.localPath!!)

        if (att.isInline && !att.contentId.isNullOrBlank()) {
            // Inline-картинка: читать в base64
            val bytes = file.readBytes()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val mimeType = if (att.contentType.isNotBlank()) att.contentType else "image/png"
            inlineImages[att.contentId] = "data:$mimeType;base64,$base64"

            android.util.Log.d(TAG, "$source: Loaded inline image cid:${att.contentId} (${bytes.size} bytes)")
        } else {
            // Файловое вложение: получить content URI через инжектированную
            // стратегию (прод-дефолт — FileProvider)
            val uri = fileUriResolver(file)
            val attInfo = AttachmentInfo(
                uri = uri,
                name = att.displayName,
                size = att.estimatedSize,
                mimeType = att.contentType.ifBlank { "application/octet-stream" }
            )
            fileAttachments.add(attInfo)

            android.util.Log.d(TAG, "$source: Loaded file attachment ${att.displayName} (${att.estimatedSize} bytes)")
        }
    }

    /**
     * Скачать вложение через EAS.
     *
     * @return Скачанный файл или null при ошибке
     */
    private suspend fun downloadAttachment(
        att: AttachmentEntity,
        easClient: EasClient,
        source: AttachmentSource
    ): File? {
        val downloadResult = easClient.downloadAttachment(att.fileReference)
        return persistDownload(att, downloadResult, source)
    }

    /**
     * Скачать вложение черновика через роутинг EAS/EWS ([EasClient.downloadDraftAttachment]).
     * fileReference черновика хранится как EAS-ссылка (с ":") либо как EWS ItemId (без ":"),
     * и только этот метод умеет выбирать протокол — критично для черновиков, созданных
     * в Outlook/OWA на Exchange 2007 SP1/SP2.
     *
     * @return Скачанный файл или null при ошибке
     */
    private suspend fun downloadDraftAttachment(
        att: AttachmentEntity,
        easClient: EasClient,
        source: AttachmentSource
    ): File? {
        val downloadResult = easClient.downloadDraftAttachment(att.fileReference)
        return persistDownload(att, downloadResult, source)
    }

    /**
     * Записать скачанные байты в файловое хранилище.
     * Каталог — `filesDir/<source.attachmentsDirName>` (совпадает с эталонным
     * ComposeScreen и file_paths.xml): файлы НЕ вычищаются ОС как кэш и доступны
     * FileProvider для последующей отправки.
     *
     * @return Файл или null при ошибке скачивания
     */
    private fun persistDownload(
        att: AttachmentEntity,
        downloadResult: EasResult<ByteArray>,
        source: AttachmentSource
    ): File? {
        if (downloadResult !is EasResult.Success) {
            android.util.Log.w(
                TAG,
                "$source: Download failed for ${att.displayName} (ref=${att.fileReference})"
            )
            return null
        }

        val attachmentsDir = File(context.filesDir, source.attachmentsDirName)
        if (!attachmentsDir.exists()) {
            attachmentsDir.mkdirs()
        }

        // Безопасное имя файла (без недопустимых символов)
        val safeFileName = att.displayName.replace(SAFE_FILENAME_COMPOSE_REGEX, "_")
        val file = File(attachmentsDir, "${System.currentTimeMillis()}_$safeFileName")

        file.writeBytes(downloadResult.data)

        android.util.Log.d(TAG, "$source: Downloaded ${att.displayName} (${downloadResult.data.size} bytes)")
        return file
    }

    companion object {
        private const val TAG = "AttachmentLoader"

        /**
         * Максимальное количество inline-картинок для предотвращения OOM (CS-4).
         * Exchange 2007 SP1 может возвращать письма с большим количеством inline-вложений.
         */
        const val MAX_INLINE_IMAGES = 20
    }
}
