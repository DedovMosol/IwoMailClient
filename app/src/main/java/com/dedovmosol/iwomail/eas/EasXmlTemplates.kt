package com.dedovmosol.iwomail.eas

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
            <CollectionId>$collectionId</CollectionId>
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
            <SyncKey>$syncKey</SyncKey>
            <CollectionId>$collectionId</CollectionId>
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
            <SyncKey>$syncKey</SyncKey>
            <CollectionId>$collectionId</CollectionId>
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
            <SyncKey>$syncKey</SyncKey>
            <CollectionId>$collectionId</CollectionId>
            <DeletesAsMoves>${if (deletesAsMoves) "1" else "0"}</DeletesAsMoves>
            <Commands>
                <Delete>
                    <ServerId>$serverId</ServerId>
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
    <SyncKey>$syncKey</SyncKey>
</FolderSync>""".trimIndent()
    
    // ==================== Search шаблоны ====================
    
    /**
     * Search запрос для GAL (глобальная адресная книга)
     */
    fun searchGal(query: String, maxResults: Int = 100): String = """<?xml version="1.0" encoding="UTF-8"?>
<Search xmlns="Search" xmlns:gal="Gal">
    <Store>
        <Name>GAL</Name>
        <Query>$query</Query>
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
                <airsync:CollectionId>$collectionId</airsync:CollectionId>
                <FreeText>$freeText</FreeText>
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
            <t:DistinguishedFolderId Id="$folderId"/>
        </m:ParentFolderIds>
    </m:FindItem>
    """.trimIndent()
    
    /**
     * EWS DeleteItem запрос
     */
    fun ewsDeleteItem(itemId: String, deleteType: String = "MoveToDeletedItems"): String = """
    <m:DeleteItem DeleteType="$deleteType">
        <m:ItemIds>
            <t:ItemId Id="$itemId"/>
        </m:ItemIds>
    </m:DeleteItem>
    """.trimIndent()
    
    /**
     * EWS GetItem запрос
     */
    fun ewsGetItem(itemIds: List<String>): String {
        val itemIdsXml = itemIds.joinToString("\n            ") { 
            """<t:ItemId Id="$it"/>""" 
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
            categories.joinToString("") { "<notes:Category>$it</notes:Category>" } +
            "</notes:Categories>"
        } else ""
        
        return """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:notes="Notes">
    <Collections>
        <Collection>
            <SyncKey>$syncKey</SyncKey>
            <CollectionId>$collectionId</CollectionId>
            <Commands>
                <Add>
                    <ClientId>$clientId</ClientId>
                    <ApplicationData>
                        <notes:Subject>$subject</notes:Subject>
                        <airsyncbase:Body>
                            <airsyncbase:Type>1</airsyncbase:Type>
                            <airsyncbase:Data>$body</airsyncbase:Data>
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
            <SyncKey>$syncKey</SyncKey>
            <CollectionId>$collectionId</CollectionId>
            <Commands>
                <Change>
                    <ServerId>$serverId</ServerId>
                    <ApplicationData>
                        <notes:Subject>$subject</notes:Subject>
                        <airsyncbase:Body>
                            <airsyncbase:Type>1</airsyncbase:Type>
                            <airsyncbase:Data>$body</airsyncbase:Data>
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
                <t:Subject>$subject</t:Subject>
                <t:Body BodyType="Text">$body</t:Body>
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
                <t:ItemId Id="$itemId" ChangeKey="$changeKey"/>
                <t:Updates>
                    <t:SetItemField>
                        <t:FieldURI FieldURI="item:Subject"/>
                        <t:Message>
                            <t:Subject>$subject</t:Subject>
                        </t:Message>
                    </t:SetItemField>
                    <t:SetItemField>
                        <t:FieldURI FieldURI="item:Body"/>
                        <t:Message>
                            <t:Body BodyType="Text">$body</t:Body>
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
        val dueDateXml = if (!dueDate.isNullOrBlank()) "<t:DueDate>$dueDate</t:DueDate>" else ""
        val bodyXml = if (body.isNotBlank()) """<t:Body BodyType="Text">$body</t:Body>""" else ""
        
        // КРИТИЧНО: НЕ используем MessageDisposition для Task — только для Message-элементов.
        // Exchange 2007 SP1 возвращает ErrorSchemaValidation если он указан для Task.
        // Порядок элементов СТРОГО по EWS-схеме: ItemType (Subject, Body, Importance) → TaskType (DueDate, Status)
        return """
    <CreateItem xmlns="http://schemas.microsoft.com/exchange/services/2006/messages"
                xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
        <Items>
            <t:Task>
                <t:Subject>$subject</t:Subject>
                $bodyXml
                <t:Importance>$importance</t:Importance>
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
     */
    fun ewsFindCalendarItems(): String {
        return """
            ${SOAP_ENVELOPE_START}
            <soap:Body>
                <FindItem xmlns="http://schemas.microsoft.com/exchange/services/2006/messages"
                          xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                          Traversal="Shallow">
                    <ItemShape>
                        <t:BaseShape>AllProperties</t:BaseShape>
                    </ItemShape>
                    <CalendarView MaxEntriesReturned="2000"
                                  StartDate="2020-01-01T00:00:00Z"
                                  EndDate="2030-12-31T23:59:59Z"/>
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
        val locationXml = if (location.isNotBlank()) "<t:Location>$location</t:Location>" else ""
        val bodyXml = if (body.isNotBlank()) """<t:Body BodyType="Text">$body</t:Body>""" else ""
        
        return """
            ${SOAP_ENVELOPE_START}
            <soap:Body>
                <CreateItem xmlns="http://schemas.microsoft.com/exchange/services/2006/messages"
                            xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                            SendMeetingInvitations="SendToNone">
                    <Items>
                        <t:CalendarItem>
                            <t:Subject>$subject</t:Subject>
                            $bodyXml
                            <t:Start>$startTime</t:Start>
                            <t:End>$endTime</t:End>
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
        val movesXml = items.joinToString("\n") { (srcMsgId, srcFldId) ->
            """<Move>
    <SrcMsgId>$srcMsgId</SrcMsgId>
    <SrcFldId>$srcFldId</SrcFldId>
    <DstFldId>$dstFolderId</DstFldId>
</Move>""".trimIndent()
        }
        
        return """<?xml version="1.0" encoding="UTF-8"?>
<MoveItems xmlns="Move">
    $movesXml
</MoveItems>""".trimIndent()
    }
}
