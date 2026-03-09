package com.dedovmosol.iwomail.eas

import android.util.Base64

/**
 * Централизованные XML-шаблоны для EAS и EWS запросов
 * Выделено из EasClient.kt для соблюдения принципа DRY
 * 
 * Преимущества:
 * - Единое место для всех XML-шаблонов
 * - Легко поддерживать и обновлять
 * - Переиспользование между сервисами
 */
object EasXmlTemplates {

    // ==================== EWS SOAP Константы ====================
    
    const val SOAP_ENVELOPE_START = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">"""
    
    const val SOAP_ENVELOPE_END = "</soap:Envelope>"
    
    const val SOAP_HEADER_2007 = """<soap:Header>
    <t:RequestServerVersion Version="Exchange2007_SP1"/>
</soap:Header>"""
    
    const val SOAP_HEADER_2010 = """<soap:Header>
    <t:RequestServerVersion Version="Exchange2010"/>
</soap:Header>"""
    
    // ==================== EAS Sync шаблоны ====================
    
    /**
     * Начальный Sync запрос для получения SyncKey
     */
    fun syncInitial(collectionId: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>0</SyncKey>
            <CollectionId>${XmlUtils.escape(collectionId)}</CollectionId>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
    
    /**
     * Sync запрос с BodyPreference для EAS 12.x+
     */
    fun syncWithBody(
        syncKey: String, 
        collectionId: String, 
        windowSize: Int = 100,
        bodyType: Int = 1,
        truncationSize: Int = 200000
    ): String = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase">
    <Collections>
        <Collection>
            <SyncKey>${XmlUtils.escape(syncKey)}</SyncKey>
            <CollectionId>${XmlUtils.escape(collectionId)}</CollectionId>
            <DeletesAsMoves>1</DeletesAsMoves>
            <GetChanges>1</GetChanges>
            <WindowSize>$windowSize</WindowSize>
            <Options>
                <airsyncbase:BodyPreference>
                    <airsyncbase:Type>$bodyType</airsyncbase:Type>
                    <airsyncbase:TruncationSize>$truncationSize</airsyncbase:TruncationSize>
                </airsyncbase:BodyPreference>
            </Options>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
    
    /**
     * Sync запрос для EAS 2.5 (Exchange 2003) с Truncation
     */
    fun syncLegacy(
        syncKey: String, 
        collectionId: String, 
        windowSize: Int = 100
    ): String = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>${XmlUtils.escape(syncKey)}</SyncKey>
            <CollectionId>${XmlUtils.escape(collectionId)}</CollectionId>
            <DeletesAsMoves/>
            <GetChanges/>
            <WindowSize>$windowSize</WindowSize>
            <Options>
                <Truncation>7</Truncation>
            </Options>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
    
    /**
     * Sync Delete команда
     */
    fun syncDelete(
        syncKey: String,
        collectionId: String,
        serverId: String,
        deletesAsMoves: Boolean = true
    ): String = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>${XmlUtils.escape(syncKey)}</SyncKey>
            <CollectionId>${XmlUtils.escape(collectionId)}</CollectionId>
            <DeletesAsMoves>${if (deletesAsMoves) "1" else "0"}</DeletesAsMoves>
            <Commands>
                <Delete>
                    <ServerId>${XmlUtils.escape(serverId)}</ServerId>
                </Delete>
            </Commands>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
    
    // ==================== FolderSync шаблоны ====================
    
    /**
     * FolderSync запрос
     */
    fun folderSync(syncKey: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<FolderSync xmlns="FolderHierarchy">
    <SyncKey>${XmlUtils.escape(syncKey)}</SyncKey>
</FolderSync>""".trimIndent()
    
    // ==================== ItemOperations шаблоны ====================

    /**
     * ItemOperations Fetch для скачивания вложения по FileReference.
     * MS-ASCMD 2.2.3.67.1: airsyncbase:FileReference — EAS 12.0+.
     * MS-ASCMD 2.2.3.143.2: Range — "m-n", zero-indexed, EAS 12.0+.
     *
     * @param fileRef XML-экранированная FileReference вложения
     * @param withAirSyncBaseNs true → xmlns="AirSyncBase" на FileReference (стандарт)
     * @param range опциональный byte-range ("0-999999999"); null → весь файл
     */
    fun itemOperationsFetchAttachment(
        fileRef: String,
        withAirSyncBaseNs: Boolean = true,
        range: String? = null
    ): String {
        val nsAttr = if (withAirSyncBaseNs) """ xmlns="AirSyncBase"""" else ""
        val optionsBlock = if (range != null) """
        <Options>
            <Range>$range</Range>
        </Options>""" else ""
        return """<?xml version="1.0" encoding="UTF-8"?>
<ItemOperations xmlns="ItemOperations">
    <Fetch>
        <Store>Mailbox</Store>
        <FileReference$nsAttr>$fileRef</FileReference>$optionsBlock
    </Fetch>
</ItemOperations>""".trimIndent()
    }

    /**
     * ItemOperations Fetch для скачивания целого письма (MIME) по CollectionId + ServerId.
     * Используется как fallback для извлечения вложения из MIME тела.
     * MS-ASCMD 2.2.3.67.1: airsync:CollectionId + airsync:ServerId — EAS 12.0+.
     */
    fun itemOperationsFetchEmail(collectionId: String, serverId: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<ItemOperations xmlns="ItemOperations">
    <Fetch>
        <Store>Mailbox</Store>
        <CollectionId xmlns="AirSync">${XmlUtils.escape(collectionId)}</CollectionId>
        <ServerId xmlns="AirSync">${XmlUtils.escape(serverId)}</ServerId>
        <Options>
            <MIMESupport xmlns="AirSync">2</MIMESupport>
            <BodyPreference xmlns="AirSyncBase">
                <Type>4</Type>
            </BodyPreference>
        </Options>
    </Fetch>
</ItemOperations>""".trimIndent()

    // ==================== Search шаблоны ====================
    
    /**
     * Search запрос для GAL (глобальная адресная книга)
     */
    fun searchGal(query: String, maxResults: Int = 100): String = """<?xml version="1.0" encoding="UTF-8"?>
<Search xmlns="Search" xmlns:gal="Gal">
    <Store>
        <Name>GAL</Name>
        <Query>${XmlUtils.escape(query)}</Query>
        <Options>
            <Range>0-${maxResults - 1}</Range>
        </Options>
    </Store>
</Search>""".trimIndent()
    
    /**
     * Search запрос для Mailbox
     */
    fun searchMailbox(
        collectionId: String,
        freeText: String = "*",
        rangeStart: Int = 0,
        rangeEnd: Int = 99
    ): String = """<?xml version="1.0" encoding="UTF-8"?>
<Search xmlns="Search" xmlns:airsync="AirSync" xmlns:airsyncbase="AirSyncBase">
    <Store>
        <Name>Mailbox</Name>
        <Query>
            <And>
                <airsync:CollectionId>${XmlUtils.escape(collectionId)}</airsync:CollectionId>
                <FreeText>${XmlUtils.escape(freeText)}</FreeText>
            </And>
        </Query>
        <Options>
            <Range>$rangeStart-$rangeEnd</Range>
            <airsyncbase:BodyPreference>
                <airsyncbase:Type>1</airsyncbase:Type>
                <airsyncbase:TruncationSize>200000</airsyncbase:TruncationSize>
            </airsyncbase:BodyPreference>
        </Options>
    </Store>
</Search>""".trimIndent()
    
    // ==================== EWS SOAP шаблоны ====================
    
    /**
     * Обёртка EWS SOAP запроса
     */
    fun ewsSoapRequest(bodyContent: String, version: String = "Exchange2007_SP1"): String {
        val header = if (version == "Exchange2007_SP1") SOAP_HEADER_2007 else SOAP_HEADER_2010
        return """$SOAP_ENVELOPE_START
  $header
  <soap:Body>
$bodyContent
  </soap:Body>
$SOAP_ENVELOPE_END"""
    }
    
    /**
     * EWS FindItem запрос для папки
     */
    fun ewsFindItem(folderId: String): String = """
    <m:FindItem Traversal="Shallow">
        <m:ItemShape>
            <t:BaseShape>AllProperties</t:BaseShape>
        </m:ItemShape>
        <m:IndexedPageItemView MaxEntriesReturned="1000" Offset="0" BasePoint="Beginning"/>
        <m:ParentFolderIds>
            <t:DistinguishedFolderId Id="${XmlUtils.escape(folderId)}"/>
        </m:ParentFolderIds>
    </m:FindItem>
    """.trimIndent()
    
    /**
     * EWS DeleteItem запрос
     */
    fun ewsDeleteItem(itemId: String, deleteType: String = "MoveToDeletedItems"): String = """
    <m:DeleteItem DeleteType="${XmlUtils.escape(deleteType)}">
        <m:ItemIds>
            <t:ItemId Id="${XmlUtils.escape(itemId)}"/>
        </m:ItemIds>
    </m:DeleteItem>
    """.trimIndent()
    
    /**
     * EWS GetItem запрос
     */
    fun ewsGetItem(itemIds: List<String>): String {
        val itemIdsXml = itemIds.joinToString("\n            ") { 
            """<t:ItemId Id="${XmlUtils.escape(it)}"/>""" 
        }
        return """
    <GetItem xmlns="http://schemas.microsoft.com/exchange/services/2006/messages">
        <ItemShape>
            <t:BaseShape>AllProperties</t:BaseShape>
        </ItemShape>
        <ItemIds>
            $itemIdsXml
        </ItemIds>
    </GetItem>
    """.trimIndent()
    }
    
    // ==================== Notes специфичные шаблоны ====================
    
    /**
     * Создание заметки через EAS
     */
    fun noteCreate(
        syncKey: String,
        collectionId: String,
        clientId: String,
        subject: String,
        body: String,
        categories: List<String> = emptyList()
    ): String {
        val categoriesXml = if (categories.isNotEmpty()) {
            "<notes:Categories>" + 
            categories.joinToString("") { "<notes:Category>${XmlUtils.escape(it)}</notes:Category>" } +
            "</notes:Categories>"
        } else ""
        
        return """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:notes="Notes">
    <Collections>
        <Collection>
            <SyncKey>${XmlUtils.escape(syncKey)}</SyncKey>
            <CollectionId>${XmlUtils.escape(collectionId)}</CollectionId>
            <Commands>
                <Add>
                    <ClientId>${XmlUtils.escape(clientId)}</ClientId>
                    <ApplicationData>
                        <notes:Subject>${XmlUtils.escape(subject)}</notes:Subject>
                        <airsyncbase:Body>
                            <airsyncbase:Type>1</airsyncbase:Type>
                            <airsyncbase:Data>${XmlUtils.escape(body)}</airsyncbase:Data>
                        </airsyncbase:Body>
                        $categoriesXml
                    </ApplicationData>
                </Add>
            </Commands>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
    }
    
    /**
     * Обновление заметки через EAS
     */
    fun noteUpdate(
        syncKey: String,
        collectionId: String,
        serverId: String,
        subject: String,
        body: String
    ): String = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:notes="Notes">
    <Collections>
        <Collection>
            <SyncKey>${XmlUtils.escape(syncKey)}</SyncKey>
            <CollectionId>${XmlUtils.escape(collectionId)}</CollectionId>
            <Commands>
                <Change>
                    <ServerId>${XmlUtils.escape(serverId)}</ServerId>
                    <ApplicationData>
                        <notes:Subject>${XmlUtils.escape(subject)}</notes:Subject>
                        <airsyncbase:Body>
                            <airsyncbase:Type>1</airsyncbase:Type>
                            <airsyncbase:Data>${XmlUtils.escape(body)}</airsyncbase:Data>
                        </airsyncbase:Body>
                    </ApplicationData>
                </Change>
            </Commands>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
    
    /**
     * EWS CreateItem для заметки
     */
    fun ewsNoteCreate(subject: String, body: String): String = """
    <CreateItem xmlns="http://schemas.microsoft.com/exchange/services/2006/messages"
                xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                MessageDisposition="SaveOnly">
        <SavedItemFolderId>
            <t:DistinguishedFolderId Id="notes"/>
        </SavedItemFolderId>
        <Items>
            <t:Message>
                <t:ItemClass>IPM.StickyNote</t:ItemClass>
                <t:Subject>${XmlUtils.escape(subject)}</t:Subject>
                <t:Body BodyType="Text">${XmlUtils.escape(body)}</t:Body>
            </t:Message>
        </Items>
    </CreateItem>
    """.trimIndent()
    
    /**
     * EWS UpdateItem для заметки
     */
    fun ewsNoteUpdate(itemId: String, changeKey: String, subject: String, body: String): String = """
    <UpdateItem xmlns="http://schemas.microsoft.com/exchange/services/2006/messages"
                xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                MessageDisposition="SaveOnly"
                ConflictResolution="AlwaysOverwrite">
        <ItemChanges>
            <t:ItemChange>
                <t:ItemId Id="${XmlUtils.escape(itemId)}" ChangeKey="${XmlUtils.escape(changeKey)}"/>
                <t:Updates>
                    <t:SetItemField>
                        <t:FieldURI FieldURI="item:Subject"/>
                        <t:Message>
                            <t:Subject>${XmlUtils.escape(subject)}</t:Subject>
                        </t:Message>
                    </t:SetItemField>
                    <t:SetItemField>
                        <t:FieldURI FieldURI="item:Body"/>
                        <t:Message>
                            <t:Body BodyType="Text">${XmlUtils.escape(body)}</t:Body>
                        </t:Message>
                    </t:SetItemField>
                </t:Updates>
            </t:ItemChange>
        </ItemChanges>
    </UpdateItem>
    """.trimIndent()
    
    // ==================== Tasks специфичные шаблоны ====================
    
    /**
     * EWS FindItem для задач
     */
    fun ewsFindTasks(): String = """
    <m:FindItem Traversal="Shallow">
        <m:ItemShape>
            <t:BaseShape>IdOnly</t:BaseShape>
            <t:AdditionalProperties>
                <t:FieldURI FieldURI="item:Subject"/>
                <t:FieldURI FieldURI="task:StartDate"/>
                <t:FieldURI FieldURI="task:DueDate"/>
                <t:FieldURI FieldURI="task:Status"/>
                <t:FieldURI FieldURI="task:CompleteDate"/>
                <t:FieldURI FieldURI="item:Importance"/>
            </t:AdditionalProperties>
        </m:ItemShape>
        <m:IndexedPageItemView MaxEntriesReturned="1000" Offset="0" BasePoint="Beginning"/>
        <m:ParentFolderIds>
            <t:DistinguishedFolderId Id="tasks"/>
        </m:ParentFolderIds>
    </m:FindItem>
    """.trimIndent()
    
    /**
     * EWS CreateItem для задачи
     */
    fun ewsTaskCreate(
        subject: String,
        body: String,
        dueDate: String?,
        importance: String = "Normal"
    ): String {
        val dueDateXml = if (!dueDate.isNullOrBlank()) "<t:DueDate>${XmlUtils.escape(dueDate)}</t:DueDate>" else ""
        val bodyXml = if (body.isNotBlank()) """<t:Body BodyType="Text">${XmlUtils.escape(body)}</t:Body>""" else ""
        
        // КРИТИЧНО: НЕ используем MessageDisposition для Task — только для Message-элементов.
        // Exchange 2007 SP1 возвращает ErrorSchemaValidation если он указан для Task.
        // Порядок элементов СТРОГО по EWS-схеме: ItemType (Subject, Body, Importance) → TaskType (DueDate, Status)
        return """
    <CreateItem xmlns="http://schemas.microsoft.com/exchange/services/2006/messages"
                xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
        <Items>
            <t:Task>
                <t:Subject>${XmlUtils.escape(subject)}</t:Subject>
                $bodyXml
                <t:Importance>${XmlUtils.escape(importance)}</t:Importance>
                $dueDateXml
                <t:Status>NotStarted</t:Status>
            </t:Task>
        </Items>
    </CreateItem>
    """.trimIndent()
    }
    
    // ==================== Calendar EWS шаблоны ====================
    
    /**
     * EWS FindItem для календаря
     * Динамический диапазон: 1 год назад — 2 года вперёд
     */
    fun ewsFindCalendarItems(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val now = System.currentTimeMillis()
        val startDate = sdf.format(java.util.Date(now - 365L * 24 * 60 * 60 * 1000)) // 1 год назад
        val endDate = sdf.format(java.util.Date(now + 2L * 365 * 24 * 60 * 60 * 1000)) // 2 года вперёд
        return """
            ${SOAP_ENVELOPE_START}
            ${SOAP_HEADER_2007}
            <soap:Body>
                <FindItem xmlns="http://schemas.microsoft.com/exchange/services/2006/messages"
                          xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                          Traversal="Shallow">
                    <ItemShape>
                        <t:BaseShape>AllProperties</t:BaseShape>
                    </ItemShape>
                    <CalendarView MaxEntriesReturned="5000"
                                  StartDate="$startDate"
                                  EndDate="$endDate"/>
                    <ParentFolderIds>
                        <t:DistinguishedFolderId Id="calendar"/>
                    </ParentFolderIds>
                </FindItem>
            </soap:Body>
            ${SOAP_ENVELOPE_END}
        """.trimIndent()
    }
    
    /**
     * EWS CreateItem для события календаря
     */
    fun ewsCreateCalendarItem(
        subject: String,
        body: String,
        startTime: String,
        endTime: String,
        location: String = "",
        isAllDay: Boolean = false
    ): String {
        val locationXml = if (location.isNotBlank()) "<t:Location>${XmlUtils.escape(location)}</t:Location>" else ""
        val bodyXml = if (body.isNotBlank()) """<t:Body BodyType="Text">${XmlUtils.escape(body)}</t:Body>""" else ""
        
        return """
            ${SOAP_ENVELOPE_START}
            ${SOAP_HEADER_2007}
            <soap:Body>
                <CreateItem xmlns="http://schemas.microsoft.com/exchange/services/2006/messages"
                            xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                            SendMeetingInvitations="SendToNone">
                    <Items>
                        <t:CalendarItem>
                            <t:Subject>${XmlUtils.escape(subject)}</t:Subject>
                            $bodyXml
                            <t:Start>${XmlUtils.escape(startTime)}</t:Start>
                            <t:End>${XmlUtils.escape(endTime)}</t:End>
                            $locationXml
                            <t:IsAllDayEvent>$isAllDay</t:IsAllDayEvent>
                            <t:LegacyFreeBusyStatus>Busy</t:LegacyFreeBusyStatus>
                        </t:CalendarItem>
                    </Items>
                </CreateItem>
            </soap:Body>
            ${SOAP_ENVELOPE_END}
        """.trimIndent()
    }
    
    // ==================== MoveItems шаблон ====================
    
    /**
     * MoveItems запрос
     */
    fun moveItems(items: List<Pair<String, String>>, dstFolderId: String): String {
        val safeDst = XmlUtils.escape(dstFolderId)
        val movesXml = items.joinToString("\n") { (srcMsgId, srcFldId) ->
            """<Move>
    <SrcMsgId>${XmlUtils.escape(srcMsgId)}</SrcMsgId>
    <SrcFldId>${XmlUtils.escape(srcFldId)}</SrcFldId>
    <DstFldId>$safeDst</DstFldId>
</Move>""".trimIndent()
        }
        
        return """<?xml version="1.0" encoding="UTF-8"?>
<MoveItems xmlns="Move">
    $movesXml
</MoveItems>""".trimIndent()
    }
}

/**
 * Добавляет стандартные MIME-заголовки письма (RFC 2822 / MS-OXCMAIL) в StringBuilder.
 * DRY: единое место для buildMimeMessageBytes (без вложений) и buildMimeWithAttachments (с вложениями).
 *
 * @param date                   RFC 2822 дата ("EEE, dd MMM yyyy HH:mm:ss Z")
 * @param fromEmail              Email отправителя
 * @param to                     Email(и) получателей
 * @param cc                     Email(и) копии (пусто если нет)
 * @param bcc                    Email(и) скрытой копии (пусто если нет)
 * @param messageId              Message-ID в формате "<timestamp@deviceId>"
 * @param subject                Тема письма (кодируется в UTF-8 Base64)
 * @param importance             Приоритет: 0=низкий, 1=обычный, 2=высокий
 * @param requestReadReceipt     Запросить отчёт о прочтении (MDN)
 * @param requestDeliveryReceipt Запросить отчёт о доставке (DSN)
 */
internal fun StringBuilder.appendMimeHeaders(
    date: String,
    fromEmail: String,
    to: String,
    cc: String,
    bcc: String,
    messageId: String,
    subject: String,
    importance: Int,
    requestReadReceipt: Boolean,
    requestDeliveryReceipt: Boolean
) {
    append("Date: $date\r\n")
    append("From: $fromEmail\r\n")
    append("To: $to\r\n")
    if (cc.isNotEmpty()) append("Cc: $cc\r\n")
    if (bcc.isNotEmpty()) append("Bcc: $bcc\r\n")
    append("Message-ID: $messageId\r\n")
    val encodedSubject = "=?UTF-8?B?${Base64.encodeToString(
        subject.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
    )}?="
    append("Subject: $encodedSubject\r\n")
    // Приоритет — RFC 2156 / MS-OXCMAIL (importance: 0=low, 1=normal, 2=high)
    when (importance) {
        0 -> {
            append("X-Priority: 5\r\n")
            append("Importance: Low\r\n")
            append("X-MSMail-Priority: Low\r\n")
        }
        2 -> {
            append("X-Priority: 1\r\n")
            append("Importance: High\r\n")
            append("X-MSMail-Priority: High\r\n")
        }
        else -> {
            append("X-Priority: 3\r\n")
            append("Importance: Normal\r\n")
            append("X-MSMail-Priority: Normal\r\n")
        }
    }
    // Запрос отчёта о прочтении (MDN) — RFC 2298
    if (requestReadReceipt) {
        append("Disposition-Notification-To: $fromEmail\r\n")
        append("X-Confirm-Reading-To: $fromEmail\r\n")
    }
    // Запрос отчёта о доставке — MS-OXCMAIL 2.2.3.1.8:
    // Return-Receipt-To устанавливает PidTagOriginatorDeliveryReportRequested=TRUE.
    // Exchange читает этот заголовок и генерирует DSN; значение заголовка игнорируется.
    if (requestDeliveryReceipt) {
        append("Return-Receipt-To: $fromEmail\r\n")
    }
    append("MIME-Version: 1.0\r\n")
}
