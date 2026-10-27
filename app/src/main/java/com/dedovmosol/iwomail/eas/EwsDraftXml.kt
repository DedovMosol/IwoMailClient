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
        escape: (String) -> String
    ): String {
        val message = StringBuilder()
            .append("<t:Subject>").append(escape(subject)).append("</t:Subject>")
            .append("<t:Body BodyType=\"HTML\">").append(escape(htmlBody)).append("</t:Body>")
        if (easImportance != 1) message.append("<t:Importance>").append(importance(easImportance)).append("</t:Importance>")
        recipientsElement("ToRecipients", to, escape)?.let { message.append(it) }
        recipientsElement("CcRecipients", cc, escape)?.let { message.append(it) }
        recipientsElement("BccRecipients", bcc, escape)?.let { message.append(it) }
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
        escape: (String) -> String
    ): String {
        val updates = StringBuilder()
        subject?.let { updates.append(setField("item:Subject", "<t:Subject>${escape(it)}</t:Subject>")) }
        htmlBody?.let { updates.append(setField("item:Body", "<t:Body BodyType=\"HTML\">${escape(it)}</t:Body>")) }
        easImportance?.let { updates.append(setField("item:Importance", "<t:Importance>${importance(it)}</t:Importance>")) }
        to?.let { updates.append(recipientsUpdate("ToRecipients", it, escape)) }
        cc?.let { updates.append(recipientsUpdate("CcRecipients", it, escape)) }
        bcc?.let { updates.append(recipientsUpdate("BccRecipients", it, escape)) }
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
