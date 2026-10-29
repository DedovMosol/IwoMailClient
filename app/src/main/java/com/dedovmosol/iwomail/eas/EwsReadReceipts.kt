package com.dedovmosol.iwomail.eas

/** Папка письма для поиска через EWS: стандартная (DistinguishedFolderId) или пользовательская — по имени. */
sealed interface EwsMailFolder {
    data class Distinguished(val id: String) : EwsMailFolder
    data class Named(val displayName: String) : EwsMailFolder

    companion object {
        /** Папка письма по типу EAS ([FolderType]) и имени. */
        fun of(folderType: Int, displayName: String): EwsMailFolder = when (folderType) {
            FolderType.INBOX -> Distinguished("inbox")
            FolderType.DELETED_ITEMS -> Distinguished("deleteditems")
            FolderType.JUNK_EMAIL -> Distinguished("junkemail")
            FolderType.SENT_ITEMS -> Distinguished("sentitems")
            FolderType.DRAFTS -> Distinguished("drafts")
            FolderType.OUTBOX -> Distinguished("outbox")
            else -> Named(displayName)
        }
    }
}

/**
 * Отказ от отчёта о прочтении (EWS, Exchange 2007 SP1+).
 *
 * Exchange сам отправляет отчёт, когда письмо с запросом становится прочитанным: и через EWS
 * (IsReadReceiptRequested: «setting IsRead to true sends a read receipt»), и через ActiveSync.
 * Отказаться можно только до пометки — ответом SuppressReadReceipt (CreateItem с ReferenceItemId), как
 * `EmailMessage.SuppressReadReceipt()` в EWS Managed API (без MessageDisposition).
 * https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/isreadreceiptrequested
 * https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/suppressreadreceipt
 *
 * Письмо ищется по Message-ID в его папке: EAS ServerId в EWS ItemId не переводится.
 */
internal class EwsReadReceipts(
    /** SOAP-запрос → ответ; null — нет ответа (сеть, авторизация). */
    private val post: suspend (soap: String, operation: String) -> String?,
    /** Тело запроса → SOAP-конверт с RequestServerVersion. */
    private val envelope: (body: String) -> String
) {

    /** true — отчёт не уйдёт: отказ принят или сервер уже не ждёт отчёта; false — отказаться не удалось. */
    suspend fun suppress(internetMessageId: String, folder: EwsMailFolder): Boolean = try {
        trySuppress(internetMessageId, folder)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        android.util.Log.w("EwsReadReceipts", "SuppressReadReceipt failed: ${e.javaClass.simpleName}")
        false
    }

    private suspend fun trySuppress(internetMessageId: String, folder: EwsMailFolder): Boolean {
        val parent = parentFolderXml(folder) ?: return false
        val found = post(envelope(findByMessageId(internetMessageId, parent)), "FindItem") ?: return false
        if (!EwsClient.isEwsSuccess(found) || !EwsClient.isCompleteEnvelope(found)) return false
        val items = EwsIds.parseAllItemIdsAndChangeKeys(found)
        if (items.isEmpty()) return false
        val response = post(envelope(suppressRequest(items)), "CreateItem") ?: return false
        return isSuppressed(response, items.size)
    }

    private suspend fun parentFolderXml(folder: EwsMailFolder): String? = when (folder) {
        is EwsMailFolder.Distinguished -> """<t:DistinguishedFolderId Id="${XmlUtils.escape(folder.id)}"/>"""
        is EwsMailFolder.Named -> folderIdByName(folder.displayName)?.let { """<t:FolderId Id="${XmlUtils.escape(it)}"/>""" }
    }

    /** Пользовательская папка по имени во всём ящике; только если она одна с таким именем. */
    private suspend fun folderIdByName(displayName: String): String? {
        if (displayName.isBlank()) return null
        val response = post(envelope(findFolderByName(displayName)), "FindFolder") ?: return null
        if (!EwsClient.isEwsSuccess(response) || !EwsClient.isCompleteEnvelope(response)) return null
        return FOLDER_ID.findAll(response).map { it.groupValues[1] }.toList().singleOrNull()
    }

    companion object {
        /** Тег FolderId с любым префиксом; ParentFolderId не совпадает (имя тега другое). */
        private val FOLDER_ID = Regex("<(?:[A-Za-z][\\w.-]*:)?FolderId\\b[^>]*?\\bId=\"([^\"]+)\"")
        private val RESPONSE_CODE = Regex("<(?:[A-Za-z][\\w.-]*:)?ResponseCode>([^<]+)</")

        /** Сервер уже не ждёт отчёта: отправлен/подавлен раньше, не запрашивался или письмо в «Нежелательной почте». */
        private const val NOT_PENDING = "ErrorReadReceiptNotPending"

        /** Порядок по XSD: ItemShape → IndexedPageItemView → Restriction → ParentFolderIds. */
        internal fun findByMessageId(internetMessageId: String, parentFolderXml: String): String = """
<m:FindItem Traversal="Shallow">
  <m:ItemShape>
    <t:BaseShape>IdOnly</t:BaseShape>
  </m:ItemShape>
  <m:IndexedPageItemView MaxEntriesReturned="10" Offset="0" BasePoint="Beginning"/>
  <m:Restriction>
    <t:IsEqualTo>
      <t:FieldURI FieldURI="message:InternetMessageId"/>
      <t:FieldURIOrConstant>
        <t:Constant Value="${XmlUtils.escape(internetMessageId)}"/>
      </t:FieldURIOrConstant>
    </t:IsEqualTo>
  </m:Restriction>
  <m:ParentFolderIds>
    $parentFolderXml
  </m:ParentFolderIds>
</m:FindItem>""".trim()

        internal fun findFolderByName(displayName: String): String = """
<m:FindFolder Traversal="Deep">
  <m:FolderShape>
    <t:BaseShape>IdOnly</t:BaseShape>
  </m:FolderShape>
  <m:Restriction>
    <t:IsEqualTo>
      <t:FieldURI FieldURI="folder:DisplayName"/>
      <t:FieldURIOrConstant>
        <t:Constant Value="${XmlUtils.escape(displayName)}"/>
      </t:FieldURIOrConstant>
    </t:IsEqualTo>
  </m:Restriction>
  <m:ParentFolderIds>
    <t:DistinguishedFolderId Id="msgfolderroot"/>
  </m:ParentFolderIds>
</m:FindFolder>""".trim()

        /** Одно и то же письмо может лежать в папке дважды (тот же Message-ID) — отказываемся за все копии. */
        internal fun suppressRequest(items: List<Pair<String, String>>): String = buildString {
            append("<m:CreateItem>\n  <m:Items>\n")
            for ((id, changeKey) in items) {
                append("    <t:SuppressReadReceipt>\n")
                append("      <t:ReferenceItemId Id=\"${XmlUtils.escape(id)}\" ChangeKey=\"${XmlUtils.escape(changeKey)}\"/>\n")
                append("    </t:SuppressReadReceipt>\n")
            }
            append("  </m:Items>\n</m:CreateItem>")
        }

        /** Каждое из [expected] писем: NoError или «отчёт уже не ожидается». */
        internal fun isSuppressed(response: String, expected: Int): Boolean {
            if (!EwsClient.isCompleteEnvelope(response)) return false
            val codes = RESPONSE_CODE.findAll(response).map { it.groupValues[1].trim() }.toList()
            return codes.size == expected && codes.all { it == "NoError" || it == NOT_PENDING }
        }
    }
}
