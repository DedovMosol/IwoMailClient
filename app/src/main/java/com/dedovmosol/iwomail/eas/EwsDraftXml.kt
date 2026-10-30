package com.dedovmosol.iwomail.eas

/**
 * SOAP-запросы EWS для черновиков — чистые функции (тестируются без сети).
 *
 * Порядок элементов t:Message — по схеме MessageType: Subject, Body, Importance, ToRecipients,
 * CcRecipients, BccRecipients (learn.microsoft.com: Message element). Exchange 2007 SP1 и новее.
 */
internal object EwsDraftXml {

    private const val NS = """xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/" """ +
        """xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types" """ +
        """xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages""""

    /** EAS Importance (MS-ASEMAIL: 0 — низкая, 1 — обычная, 2 — высокая) → EWS ImportanceChoicesType. */
    fun importance(easImportance: Int): String = when (easImportance) {
        0 -> "Low"
        2 -> "High"
        else -> "Normal"
    }

    /** Адреса через запятую или точку с запятой → t:Mailbox. */
    fun mailboxes(recipients: String, escape: (String) -> String): String =
        recipients.split(",", ";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("") { "<t:Mailbox><t:EmailAddress>${escape(it)}</t:EmailAddress></t:Mailbox>" }

    fun createItem(
        version: String,
        subject: String,
        htmlBody: String,
        to: String,
        cc: String,
        bcc: String,
        easImportance: Int,
        escape: (String) -> String,
        receipts: ReceiptRequests? = null
    ): String {
        val message = StringBuilder()
            .append("<t:Subject>").append(escape(subject)).append("</t:Subject>")
            .append("<t:Body BodyType=\"HTML\">").append(escape(htmlBody)).append("</t:Body>")
        if (easImportance != 1) message.append("<t:Importance>").append(importance(easImportance)).append("</t:Importance>")
        recipientsElement("ToRecipients", to, escape)?.let { message.append(it) }
        recipientsElement("CcRecipients", cc, escape)?.let { message.append(it) }
        recipientsElement("BccRecipients", bcc, escape)?.let { message.append(it) }
        // По схеме — сразу после BccRecipients; по умолчанию у нового письма оба false — не пишем
        if (receipts?.read == true) message.append("<t:IsReadReceiptRequested>true</t:IsReadReceiptRequested>")
        if (receipts?.delivery == true) message.append("<t:IsDeliveryReceiptRequested>true</t:IsDeliveryReceiptRequested>")
        return envelope(
            version,
            "<m:CreateItem MessageDisposition=\"SaveOnly\"><m:SavedItemFolderId>" +
                "<t:DistinguishedFolderId Id=\"drafts\"/></m:SavedItemFolderId>" +
                "<m:Items><t:Message>$message</t:Message></m:Items></m:CreateItem>"
        )
    }

    /**
     * UpdateItem черновика. Пустой список получателей — DeleteItemField (так делает и EWS Managed API:
     * SetItemField с пустой коллекцией получателей не очищает поле). [bcc] = null — скрытые копии
     * не известны приложению (EAS до 16.0 их не передаёт): поле на сервере не трогается.
     * [subject]/[htmlBody] = null — не меняются (например, дописать скрытые копии к MIME-черновику).
     * [appendBcc] — скрытые копии на сервере не известны: [bcc] добавляется к ним (AppendToItemField),
     * а не заменяет.
     */
    fun updateItem(
        version: String,
        itemId: String,
        changeKey: String?,
        subject: String?,
        htmlBody: String?,
        to: String?,
        cc: String?,
        bcc: String?,
        easImportance: Int?,
        escape: (String) -> String,
        appendBcc: Boolean = false,
        // null — флажки отчётов приложению не известны (черновик из Outlook): на сервере не меняются
        receipts: ReceiptRequests? = null
    ): String {
        val updates = StringBuilder()
        receipts?.let {
            updates.append(setField("message:IsReadReceiptRequested", "<t:IsReadReceiptRequested>${it.read}</t:IsReadReceiptRequested>"))
            updates.append(
                setField("message:IsDeliveryReceiptRequested", "<t:IsDeliveryReceiptRequested>${it.delivery}</t:IsDeliveryReceiptRequested>")
            )
        }
        subject?.let { updates.append(setField("item:Subject", "<t:Subject>${escape(it)}</t:Subject>")) }
        htmlBody?.let { updates.append(setField("item:Body", "<t:Body BodyType=\"HTML\">${escape(it)}</t:Body>")) }
        easImportance?.let { updates.append(setField("item:Importance", "<t:Importance>${importance(it)}</t:Importance>")) }
        to?.let { updates.append(recipientsUpdate("ToRecipients", it, escape)) }
        cc?.let { updates.append(recipientsUpdate("CcRecipients", it, escape)) }
        bcc?.let { value ->
            if (appendBcc) {
                // Добавить к неизвестным скрытым копиям; пустое — ничего не менять (не DeleteItemField)
                recipientsElement("BccRecipients", value, escape)?.let { append ->
                    updates.append("<t:AppendToItemField><t:FieldURI FieldURI=\"message:BccRecipients\"/><t:Message>$append</t:Message></t:AppendToItemField>")
                }
            } else {
                updates.append(recipientsUpdate("BccRecipients", value, escape))
            }
        }
        val changeKeyAttr = changeKey?.takeIf { it.isNotBlank() }?.let { " ChangeKey=\"${escape(it)}\"" }.orEmpty()
        return envelope(
            version,
            "<m:UpdateItem MessageDisposition=\"SaveOnly\" ConflictResolution=\"AlwaysOverwrite\">" +
                "<m:ItemChanges><t:ItemChange><t:ItemId Id=\"${escape(itemId)}\"$changeKeyAttr/>" +
                "<t:Updates>$updates</t:Updates></t:ItemChange></m:ItemChanges></m:UpdateItem>"
        )
    }

    /** Безвозвратное удаление (уборка черновика, который не удалось собрать целиком). */
    fun hardDelete(version: String, itemId: String, escape: (String) -> String): String = envelope(
        version,
        "<m:DeleteItem DeleteType=\"HardDelete\"><m:ItemIds><t:ItemId Id=\"${escape(itemId)}\"/></m:ItemIds></m:DeleteItem>"
    )

    /** FindItem в «Черновиках» по точной теме, самые новые первыми (IdOnly). */
    fun findBySubject(version: String, subject: String, escape: (String) -> String): String = envelope(
        version,
        "<m:FindItem Traversal=\"Shallow\"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape></m:ItemShape>" +
            "<m:Restriction><t:IsEqualTo><t:FieldURI FieldURI=\"item:Subject\"/>" +
            "<t:FieldURIOrConstant><t:Constant Value=\"${escape(subject)}\"/></t:FieldURIOrConstant></t:IsEqualTo></m:Restriction>" +
            "<m:SortOrder><t:FieldOrder Order=\"Descending\"><t:FieldURI FieldURI=\"item:DateTimeReceived\"/></t:FieldOrder></m:SortOrder>" +
            "<m:ParentFolderIds><t:DistinguishedFolderId Id=\"drafts\"/></m:ParentFolderIds></m:FindItem>"
    )

    /**
     * GetItem черновика: тело (HTML), скрытые копии и PR_LAST_MODIFICATION_TIME (0x3008) — свойство MAPI,
     * доступное и на Exchange 2007 SP1 (item:LastModifiedTime — только с Exchange 2010). Порядок в
     * ItemShape по схеме: BaseShape, BodyType, AdditionalProperties.
     */
    fun getDraftDetails(version: String, itemId: String, escape: (String) -> String): String = envelope(
        version,
        "<m:GetItem><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape><t:BodyType>HTML</t:BodyType>" +
            "<t:AdditionalProperties><t:FieldURI FieldURI=\"item:Body\"/><t:FieldURI FieldURI=\"message:BccRecipients\"/>" +
            "<t:FieldURI FieldURI=\"message:IsReadReceiptRequested\"/><t:FieldURI FieldURI=\"message:IsDeliveryReceiptRequested\"/>" +
            "<t:ExtendedFieldURI PropertyTag=\"0x3008\" PropertyType=\"SystemTime\"/></t:AdditionalProperties>" +
            "</m:ItemShape><m:ItemIds><t:ItemId Id=\"${escape(itemId)}\"/></m:ItemIds></m:GetItem>"
    )

    /** Тело, скрытые копии и время последнего изменения черновика на сервере. */
    data class DraftDetails(
        val body: String,
        /** null — не известны: среди адресов есть не SMTP (X.500 из адресной книги) — их не трогаем. */
        val bcc: String?,
        val lastModified: Long?,
        /** Флажки «запросить отчёт»; null — сервер их не вернул. */
        val receipts: ReceiptRequests? = null
    )

    private val READ_RECEIPT = Regex("<(?:t:)?IsReadReceiptRequested>\\s*(true|false)\\s*<", RegexOption.IGNORE_CASE)
    private val DELIVERY_RECEIPT = Regex("<(?:t:)?IsDeliveryReceiptRequested>\\s*(true|false)\\s*<", RegexOption.IGNORE_CASE)

    private val BODY = Regex("<(?:t:)?Body[^>]*>(.*?)</(?:t:)?Body>", RegexOption.DOT_MATCHES_ALL)
    private val BCC = Regex("<(?:t:)?BccRecipients>(.*?)</(?:t:)?BccRecipients>", RegexOption.DOT_MATCHES_ALL)
    private val MAILBOX = Regex("<(?:t:)?Mailbox>(.*?)</(?:t:)?Mailbox>", RegexOption.DOT_MATCHES_ALL)
    private val EMAIL_ADDRESS = Regex("<(?:t:)?EmailAddress>(.*?)</(?:t:)?EmailAddress>", RegexOption.DOT_MATCHES_ALL)
    private val ROUTING_TYPE = Regex("<(?:t:)?RoutingType>(.*?)</(?:t:)?RoutingType>")
    private val LAST_MODIFIED = Regex(
        """PropertyTag="(?:0x3008|12296)"[^>]*/>\s*<(?:t:)?Value>([^<]+)</(?:t:)?Value>""", RegexOption.IGNORE_CASE
    )

    /** Ответ [getDraftDetails]. Нет элемента BccRecipients — скрытых копий нет (пустые свойства EWS не отдаёт). */
    fun parseDraftDetails(response: String): DraftDetails {
        val rawBody = BODY.find(response)?.groupValues?.get(1)?.trim().orEmpty()
        // Exchange 2007 SP1 отдаёт тело и экранированным (&lt;html&gt;), и как есть.
        val body = if (rawBody.startsWith("&lt;") || (!rawBody.startsWith("<") && rawBody.contains("&lt;"))) {
            XmlUtils.unescape(rawBody)
        } else {
            rawBody
        }
        // Нет элемента — скрытых копий нет (""); есть адрес не SMTP — неизвестны (null).
        val bccBlock = BCC.find(response)?.groupValues?.get(1)
        val bcc: String? = if (bccBlock == null) "" else {
            val addresses = MAILBOX.findAll(bccBlock).map { it.groupValues[1] }.map { mailbox ->
                val address = EMAIL_ADDRESS.find(mailbox)?.groupValues?.get(1)?.let { XmlUtils.unescape(it).trim() }.orEmpty()
                val routing = ROUTING_TYPE.find(mailbox)?.groupValues?.get(1)?.trim()
                address.takeIf { it.contains('@') && (routing == null || routing.equals("SMTP", ignoreCase = true)) }
            }.toList()
            if (addresses.any { it == null }) null else addresses.filterNotNull().joinToString(", ")
        }
        val lastModified = LAST_MODIFIED.find(response)?.groupValues?.get(1)?.trim()?.let { value ->
            try {
                java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
        val receipts = ReceiptRequests.of(
            READ_RECEIPT.find(response)?.groupValues?.get(1)?.equals("true", ignoreCase = true),
            DELIVERY_RECEIPT.find(response)?.groupValues?.get(1)?.equals("true", ignoreCase = true)
        )
        return DraftDetails(body, bcc, lastModified, receipts)
    }

    private fun envelope(version: String, body: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><soap:Envelope $NS><soap:Header>" +
            "<t:RequestServerVersion Version=\"$version\"/></soap:Header><soap:Body>$body</soap:Body></soap:Envelope>"

    private fun setField(fieldUri: String, value: String) =
        "<t:SetItemField><t:FieldURI FieldURI=\"$fieldUri\"/><t:Message>$value</t:Message></t:SetItemField>"

    private fun recipientsElement(name: String, recipients: String, escape: (String) -> String): String? =
        mailboxes(recipients, escape).takeIf { it.isNotEmpty() }?.let { "<t:$name>$it</t:$name>" }

    private fun recipientsUpdate(name: String, recipients: String, escape: (String) -> String): String =
        recipientsElement(name, recipients, escape)?.let { setField("message:$name", it) }
            ?: "<t:DeleteItemField><t:FieldURI FieldURI=\"message:$name\"/></t:DeleteItemField>"
}
