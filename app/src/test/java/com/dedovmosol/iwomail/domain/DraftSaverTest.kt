package com.dedovmosol.iwomail.domain

import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.data.database.DraftMode
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.eas.DraftAttachmentData
import com.dedovmosol.iwomail.eas.DraftIds
import com.dedovmosol.iwomail.eas.EasResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Сохранение черновика: когда UpdateItem, когда новая версия и удаление прежней, что происходит без связи
 * и при отказе сервера. Главное свойство — прежняя версия не удаляется, пока новая не на сервере.
 */
class DraftSaverTest {

    private val account = AccountEntity(
        id = 1L, email = "me@corp.example", displayName = "Me", serverUrl = "mail.corp.example", username = "me"
    )
    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
    private val pngUrl = "data:image/png;base64,$png"

    /** Фейк хранилища: черновики по emailId, журнал действий. */
    private class FakeStore : DraftSaver.Store {
        val drafts = linkedMapOf<String, EmailEntity>()
        val attachmentRows = mutableMapOf<String, List<AttachmentEntity>>()
        val bytes = mutableMapOf<Long, ByteArray>()
        var reachable = true
        var createResult: (DraftSaver.NewDraft, Boolean) -> String? = { _, deviceOnly ->
            if (deviceOnly) DraftIds.newDeviceOnly() else "AAMkNEW="
        }
        var updateResult: EasResult<String> = EasResult.Success("ok")
        var deleteResult: (String) -> EasResult<Boolean> = { EasResult.Success(true) }
        var serverBccValue: String? = null
        val serverBccById = mutableMapOf<String, String?>()
        val successors = mutableMapOf<String, String>()
        val networkAllowed = mutableListOf<Boolean>()
        var bySubject: String? = null
        var restoreOnCreate = true

        val created = mutableListOf<Pair<DraftSaver.NewDraft, Boolean>>()
        val updates = mutableListOf<Triple<String, String?, Boolean>>()
        val updateBodies = mutableListOf<String>()
        val deleted = mutableListOf<Pair<String, String?>>()
        val removed = mutableListOf<String>()
        val replacing = mutableMapOf<String, String?>()
        val carried = mutableListOf<Pair<List<AttachmentEntity>, String>>()
        val protectedIds = mutableSetOf<String>()
        var probes = 0
        var exclusiveCalls = 0

        override suspend fun draft(emailId: String) = drafts[emailId]
        override fun successor(emailId: String) = successors[emailId]
        override suspend fun attachments(emailId: String) = attachmentRows[emailId].orEmpty()
        override suspend fun attachmentBytes(accountId: Long, attachment: AttachmentEntity, allowNetwork: Boolean): ByteArray? {
            networkAllowed += allowNetwork
            return bytes[attachment.id]
        }
        override suspend fun create(account: AccountEntity, draft: DraftSaver.NewDraft, deviceOnly: Boolean): String? {
            created += draft to deviceOnly
            val id = createResult(draft, deviceOnly) ?: return null
            if (restoreOnCreate) {
                drafts["${account.id}_$id"] = EmailEntity(
                    id = "${account.id}_$id", accountId = account.id, folderId = "1_5", serverId = id,
                    from = "", to = draft.to, subject = draft.subject, body = draft.localBody,
                    dateReceived = 1L, read = true, bcc = draft.bcc
                )
            }
            return id
        }
        override suspend fun update(
            account: AccountEntity, serverId: String, content: DraftSaver.Content, bcc: String?, serverBody: String, appendBcc: Boolean
        ): EasResult<String> {
            updates += Triple(serverId, bcc, appendBcc)
            updateBodies += serverBody
            return updateResult
        }
        override suspend fun delete(accountId: Long, serverId: String, excludeServerId: String?): EasResult<Boolean> {
            deleted += serverId to excludeServerId
            val result = deleteResult(serverId)
            if (result is EasResult.Success) drafts.remove("${accountId}_$serverId")
            return result
        }
        override suspend fun removeLocally(emailId: String) {
            removed += emailId
            drafts.remove(emailId)
        }
        override suspend fun markReplacing(emailId: String, marker: String?) {
            replacing[emailId] = marker
        }
        override suspend fun carryAttachments(rows: List<AttachmentEntity>, toEmailId: String) {
            carried += rows to toEmailId
        }
        override suspend fun serverBcc(accountId: Long, serverId: String) =
            if (serverId in serverBccById) serverBccById[serverId] else serverBccValue
        override suspend fun serverReachable(accountId: Long): Boolean {
            probes++
            return reachable
        }
        override suspend fun hasOtherDraftWithSubject(folderId: String, subject: String, exceptIds: Set<String>) = false
        override suspend fun restore(email: EmailEntity, attachments: List<DraftAttachmentData>) {
            drafts[email.id] = email
        }
        override fun protect(emailId: String) {
            protectedIds += emailId
        }
        override fun unprotect(emailId: String) {
            protectedIds -= emailId
        }
        override suspend fun <T> exclusive(accountId: Long, block: suspend () -> T): T {
            exclusiveCalls++
            return block()
        }
        override suspend fun findServerDraftBySubject(accountId: Long, subject: String, excludeServerId: String?) = bySubject
    }

    private val store = FakeStore()
    private val saver = DraftSaver(store, clock = { 42L })

    private fun content(
        body: String = "<p>Текст</p>",
        files: List<DraftAttachmentData> = emptyList(),
        filesUnchanged: Boolean = true,
        bcc: String = "",
        bccKnown: Boolean = true,
        knownImageCids: Map<String, String> = emptyMap(),
        unloaded: Set<Long> = emptySet()
    ) = DraftSaver.Content(
        to = "a@corp.example", cc = "", bcc = bcc, bccKnown = bccKnown, subject = "Отчёт", body = body,
        importance = 1, fromEmail = "me@corp.example", fromName = "Me", files = files,
        filesUnchanged = filesUnchanged, knownImageCids = knownImageCids, unloadedAttachmentIds = unloaded
    )

    private fun serverDraft(serverId: String = "5:1", replaces: String? = null) = EmailEntity(
        id = "1_$serverId", accountId = 1L, folderId = "1_5", serverId = serverId, from = "", to = "a@corp.example",
        subject = "Отчёт", body = "<p>Старый</p>", dateReceived = 1L, read = true, replacesServerId = replaces
    ).also { store.drafts[it.id] = it }

    private fun save(existingId: String?, content: DraftSaver.Content, acc: AccountEntity = account) =
        runBlocking { saver.save(acc, existingId, content) }

    @Test
    fun `new draft goes to the server when it answers`() {
        assertThat(save(null, content())).isEqualTo(DraftSaver.Result.Saved)
        assertThat(store.created.single().second).isFalse()
        assertThat(store.exclusiveCalls).isEqualTo(1)
    }

    @Test
    fun `new draft without connection is kept on the device at once`() {
        store.reachable = false

        assertThat(save(null, content())).isEqualTo(DraftSaver.Result.SavedOffline)
        // Без попытки на сервер — сразу на устройстве (не ждём таймаута подключения).
        assertThat(store.created.single().second).isTrue()
    }

    @Test
    fun `new draft refused by an answering server is reported as device only`() {
        store.createResult = { _, _ -> DraftIds.newDeviceOnly() }

        assertThat(save(null, content())).isEqualTo(DraftSaver.Result.SavedOnDeviceOnly)
        assertThat(store.probes).isEqualTo(2) // до сохранения и повторно — отличить отказ от пропавшей связи
    }

    @Test
    fun `device-only mode never probes the server`() {
        val local = account.copy(draftMode = DraftMode.LOCAL.name)

        assertThat(save(null, content(), local)).isEqualTo(DraftSaver.Result.Saved)
        assertThat(store.probes).isEqualTo(0)
        assertThat(store.created.single().second).isTrue()
    }

    @Test
    fun `text edit of a server draft is an UpdateItem with known images as their cids`() {
        val old = serverDraft()
        val body = """<p>Новый</p><img src="$pngUrl">"""

        val result = save(old.id, content(body = body, knownImageCids = mapOf(pngUrl to "image001.png@01D9")))

        assertThat(result).isEqualTo(DraftSaver.Result.Saved)
        assertThat(store.updates.single().first).isEqualTo("5:1")
        assertThat(store.updateBodies.single()).isEqualTo("""<p>Новый</p><img src="cid:image001.png@01D9">""")
        assertThat(store.created).isEmpty()
        assertThat(store.deleted).isEmpty()
    }

    @Test
    fun `new picture in a server draft is a new version, not an UpdateItem`() {
        val old = serverDraft()

        save(old.id, content(body = """<p>Новый</p><img src="$pngUrl">"""))

        assertThat(store.updates).isEmpty()
        val created = store.created.single().first
        assertThat(created.serverBody).contains("cid:img1_42")
        assertThat(created.attachments.single().isInline).isTrue()
        assertThat(store.deleted).containsExactly("5:1" to "AAMkNEW=")
    }

    @Test
    fun `server draft that cannot be updated is saved anew, old one deleted by its id after`() {
        val old = serverDraft()
        store.updateResult = EasResult.Error(DraftIds.NOT_RESOLVED)

        assertThat(save(old.id, content())).isEqualTo(DraftSaver.Result.Saved)

        assertThat(store.created).hasSize(1)
        assertThat(store.deleted).containsExactly("5:1" to "AAMkNEW=")
        assertThat(store.drafts).containsKey("1_AAMkNEW=")
    }

    @Test
    fun `offline edit of a server draft is kept on the device and replaces it later`() {
        val old = serverDraft()
        store.reachable = false

        assertThat(save(old.id, content(filesUnchanged = false))).isEqualTo(DraftSaver.Result.SavedOffline)

        val newEmailId = store.replacing.keys.single()
        assertThat(store.replacing[newEmailId]).isEqualTo("5:1")
        // Прежняя версия на сервере не удаляется, пока новая не загружена.
        assertThat(store.deleted).isEmpty()
        assertThat(store.removed).containsExactly(old.id)
    }

    @Test
    fun `server that answers but refuses keeps the old version and drops the local copy`() {
        val old = serverDraft()
        store.createResult = { _, _ -> DraftIds.newDeviceOnly() }

        val result = save(old.id, content(filesUnchanged = false))

        assertThat(result).isEqualTo(DraftSaver.Result.Failed(DraftSaver.SERVER_REJECTED))
        // Удаляется только копия на устройстве; прежняя версия на сервере цела и снова видна синхронизации.
        assertThat(store.deleted.single().first).startsWith("local_draft_d_")
        assertThat(store.drafts).containsKey(old.id)
        assertThat(store.protectedIds).doesNotContain(old.id)
    }

    @Test
    fun `attachments the editor did not load are carried into the new version`() {
        val old = serverDraft()
        store.attachmentRows[old.id] = listOf(
            AttachmentEntity(id = 7, emailId = old.id, fileReference = "5%3a1%3a0", displayName = "report.pdf", contentType = "application/pdf")
        )
        store.bytes[7] = byteArrayOf(1, 2)

        save(old.id, content(filesUnchanged = false, unloaded = setOf(7)))

        val parts = store.created.single().first.attachments
        assertThat(parts.map { it.name to it.isInline }).containsExactly("report.pdf" to false)
        assertThat(store.deleted).containsExactly("5:1" to "AAMkNEW=")
    }

    @Test
    fun `unloadable attachment while online blocks the save, offline it is carried by reference`() {
        val old = serverDraft()
        val row = AttachmentEntity(id = 7, emailId = old.id, fileReference = "5%3a1%3a0", displayName = "report.pdf", contentType = "application/pdf")
        store.attachmentRows[old.id] = listOf(row)

        assertThat(save(old.id, content(filesUnchanged = false, unloaded = setOf(7))))
            .isEqualTo(DraftSaver.Result.Failed(DraftSaver.ATTACHMENTS_UNAVAILABLE))
        assertThat(store.created).isEmpty()
        assertThat(store.deleted).isEmpty()

        store.reachable = false
        assertThat(save(old.id, content(filesUnchanged = false, unloaded = setOf(7)))).isEqualTo(DraftSaver.Result.SavedOffline)
        val (rows, to) = store.carried.single()
        assertThat(rows).containsExactly(row)
        assertThat(store.replacing[to]).isEqualTo("5:1")
    }

    @Test
    fun `unknown Bcc of an Outlook draft - kept, merged or appended, never wiped`() {
        val old = serverDraft()

        save(old.id, content(bcc = "", bccKnown = false))
        assertThat(store.updates.last()).isEqualTo(Triple("5:1", null, false))

        store.serverBccValue = "boss@corp.example"
        save(old.id, content(bcc = "me2@corp.example", bccKnown = false))
        assertThat(store.updates.last()).isEqualTo(Triple("5:1", "me2@corp.example, boss@corp.example", false))

        store.serverBccValue = null
        save(old.id, content(bcc = "me2@corp.example", bccKnown = false))
        assertThat(store.updates.last()).isEqualTo(Triple("5:1", "me2@corp.example", true))
    }

    @Test
    fun `old version that could not be deleted is remembered on the new one`() {
        val old = serverDraft()
        store.deleteResult = { EasResult.Error("[NET:TIMEOUT] timeout") }

        assertThat(save(old.id, content(filesUnchanged = false))).isEqualTo(DraftSaver.Result.Saved)

        assertThat(store.replacing["1_AAMkNEW="]).isEqualTo("5:1")
        assertThat(store.removed).containsExactly(old.id)
    }

    @Test
    fun `device draft is uploaded on save when online and replaces what it replaced`() {
        val local = EmailEntity(
            id = "1_local_draft_d_1_x", accountId = 1L, folderId = "1_5", serverId = "local_draft_d_1_x", from = "",
            to = "a@corp.example", subject = "Отчёт", body = "<p>Текст</p>", dateReceived = 1L, read = true,
            replacesServerId = "5:9"
        ).also { store.drafts[it.id] = it }

        assertThat(save(local.id, content())).isEqualTo(DraftSaver.Result.Saved)

        assertThat(store.created.single().second).isFalse()
        assertThat(store.deleted).containsExactly("5:9" to "AAMkNEW=")
        assertThat(store.removed).containsExactly(local.id)
    }

    @Test
    fun `device draft edited offline stays a device draft`() {
        val local = EmailEntity(
            id = "1_local_draft_d_1_x", accountId = 1L, folderId = "1_5", serverId = "local_draft_d_1_x", from = "",
            to = "a@corp.example", subject = "Отчёт", body = "<p>Текст</p>", dateReceived = 1L, read = true
        ).also { store.drafts[it.id] = it }
        store.reachable = false

        assertThat(save(local.id, content())).isEqualTo(DraftSaver.Result.SavedOffline)
        assertThat(store.updates.single().first).isEqualTo("local_draft_d_1_x")
        assertThat(store.created).isEmpty()
    }

    @Test
    fun `device draft over the database limit is saved anew with cid images, not updated in place`() {
        val local = EmailEntity(
            id = "1_local_draft_d_1_x", accountId = 1L, folderId = "1_5", serverId = "local_draft_d_1_x", from = "",
            to = "a@corp.example", subject = "Отчёт", body = "<p>Текст</p>", dateReceived = 1L, read = true,
            replacesServerId = "5:9"
        ).also { store.drafts[it.id] = it }
        store.reachable = false
        val body = "<p>" + "x".repeat(com.dedovmosol.iwomail.data.repository.DraftUpload.MAX_LOCAL_BODY_CHARS) +
            """</p><img src="$pngUrl">"""

        assertThat(save(local.id, content(body = body))).isEqualTo(DraftSaver.Result.SavedOffline)

        assertThat(store.updates).isEmpty()
        val (created, deviceOnly) = store.created.single()
        assertThat(deviceOnly).isTrue()
        assertThat(created.serverBody).contains("cid:img1_42")
        assertThat(created.attachments.single().isInline).isTrue()
        // Новая запись заменяет прежнюю и то, что заменяла она.
        assertThat(store.replacing.values.single()).isEqualTo("5:9")
        assertThat(store.removed).containsExactly(local.id)
    }

    @Test
    fun `edit of a draft whose id the server did not return deletes it by unique subject`() {
        val local = EmailEntity(
            id = "1_local_draft_u_1_x", accountId = 1L, folderId = "1_5", serverId = "local_draft_u_1_x", from = "",
            to = "a@corp.example", subject = "Отчёт", body = "<p>Текст</p>", dateReceived = 1L, read = true
        ).also { store.drafts[it.id] = it }
        store.bySubject = "AAMkUNCONFIRMED="

        save(local.id, content())

        assertThat(store.deleted).containsExactly("AAMkUNCONFIRMED=" to "AAMkNEW=")
    }

    @Test
    fun `recipients are merged without duplicates, case-insensitively`() {
        assertThat(com.dedovmosol.iwomail.util.mergeRecipients("A@corp.example; b@corp.example", "a@corp.example, c@corp.example"))
            .isEqualTo("A@corp.example, b@corp.example, c@corp.example")
        assertThat(com.dedovmosol.iwomail.util.mergeRecipients("", null)).isEmpty()
    }

    @Test
    fun `file with a content id that is not in the body is carried, its picture twin already in the body is not`() {
        val old = serverDraft()
        val pdf = byteArrayOf(1, 2, 3)
        val png = java.util.Base64.getDecoder().decode(this.png)
        // Синхронизация помечает inline любое вложение с Content-ID (EasAttachment.effectiveIsInline).
        store.attachmentRows[old.id] = listOf(
            AttachmentEntity(id = 7, emailId = old.id, fileReference = "5%3a1%3a0", displayName = "report.pdf",
                contentType = "application/pdf", isInline = true, contentId = "report@corp"),
            AttachmentEntity(id = 8, emailId = old.id, fileReference = "5%3a1%3a1", displayName = "logo.png",
                contentType = "image/png", isInline = true, contentId = "logo@corp")
        )
        store.bytes[7] = pdf
        store.bytes[8] = png

        save(old.id, content(body = """<p>Текст</p><img src="$pngUrl">""", filesUnchanged = false, unloaded = setOf(7, 8)))

        val parts = store.created.single().first.attachments
        // Файл не пропал; картинка логотипа — одна (из тела), не вторая копия файлом.
        assertThat(parts.filter { !it.isInline }.map { it.name }).containsExactly("report.pdf")
        assertThat(parts.filter { it.isInline }.map { it.name }).containsExactly("image_1.png")
    }

    @Test
    fun `offline save does not try to download what the editor did not load`() {
        val old = serverDraft()
        store.attachmentRows[old.id] = listOf(
            AttachmentEntity(id = 7, emailId = old.id, fileReference = "5%3a1%3a0", displayName = "report.pdf", contentType = "application/pdf")
        )
        store.reachable = false

        save(old.id, content(filesUnchanged = false, unloaded = setOf(7)))

        // Без связи — только файл на устройстве: попытка скачать ждала бы таймаута подключения.
        assertThat(store.networkAllowed).containsExactly(false)
    }

    @Test
    fun `unknown Bcc that the server cannot tell keeps the previous version instead of losing recipients`() {
        val old = serverDraft()
        store.serverBccValue = null // GetItem не ответил или адреса не SMTP

        val result = save(old.id, content(bcc = "", bccKnown = false, filesUnchanged = false))

        assertThat(result).isEqualTo(DraftSaver.Result.SavedKeepingPrevious(offline = false))
        assertThat(store.deleted).isEmpty()
        assertThat(store.drafts).containsKey(old.id)
        assertThat(store.protectedIds).doesNotContain(old.id)
    }

    @Test
    fun `unknown Bcc is taken from the previous version when the server tells it`() {
        val old = serverDraft()
        store.serverBccById["5:1"] = "boss@corp.example"

        assertThat(save(old.id, content(bcc = "me2@corp.example", bccKnown = false, filesUnchanged = false)))
            .isEqualTo(DraftSaver.Result.Saved)

        assertThat(store.created.single().first.bcc).isEqualTo("me2@corp.example, boss@corp.example")
        assertThat(store.deleted).containsExactly("5:1" to "AAMkNEW=")
    }

    @Test
    fun `offline edit with unknown Bcc keeps it unknown on the device copy`() {
        val old = serverDraft()
        store.reachable = false

        assertThat(save(old.id, content(bcc = "", bccKnown = false, filesUnchanged = false))).isEqualTo(DraftSaver.Result.SavedOffline)

        // null — «не известны»: при загрузке их возьмут с заменяемой версии.
        assertThat(store.created.single().first.bcc).isNull()
        assertThat(store.replacing.values.single()).isEqualTo("5:1")
    }

    @Test
    fun `every previous version that could not be deleted stays marked`() {
        val old = serverDraft(replaces = "5:0")
        store.deleteResult = { EasResult.Error("[NET:TIMEOUT] timeout") }

        save(old.id, content(filesUnchanged = false))

        assertThat(DraftIds.replacedIds(store.replacing["1_AAMkNEW="])).containsExactly("5:1", "5:0")
    }

    @Test
    fun `record replaced while the editor was open is saved through its successor, not duplicated`() {
        val uploaded = serverDraft("AAMkUPLOADED=")
        store.successors["1_local_draft_d_1_x"] = uploaded.id // загружен синхронизацией, пока черновик был открыт

        assertThat(save("1_local_draft_d_1_x", content())).isEqualTo(DraftSaver.Result.Saved)

        assertThat(store.updates.single().first).isEqualTo("AAMkUPLOADED=")
        assertThat(store.created).isEmpty()
    }

    @Test
    fun `draft whose id the server did not return is not edited in place offline`() {
        val local = EmailEntity(
            id = "1_local_draft_u_1_x", accountId = 1L, folderId = "1_5", serverId = "local_draft_u_1_x", from = "",
            to = "a@corp.example", subject = "Отчёт", body = "<p>Текст</p>", dateReceived = 1L, read = true
        ).also { store.drafts[it.id] = it }
        store.reachable = false

        save(local.id, content())

        // Такой синхронизация не загружает и убирает при приходе серверной копии — правка стала бы черновиком на устройстве.
        assertThat(store.updates).isEmpty()
        assertThat(store.created.single().second).isTrue() // новая — «только на устройстве», её загрузит синхронизация
        assertThat(store.removed).containsExactly(local.id)
    }

    @Test
    fun `device-only mode without connection marks the server draft instead of waiting for a delete`() {
        val localMode = account.copy(draftMode = DraftMode.LOCAL.name)
        val old = serverDraft()
        store.reachable = false

        assertThat(save(old.id, content(filesUnchanged = false), localMode)).isEqualTo(DraftSaver.Result.Saved)

        assertThat(store.deleted).isEmpty()
        assertThat(store.replacing.values.single()).isEqualTo("5:1")
        assertThat(store.removed).containsExactly(old.id)
    }
}
