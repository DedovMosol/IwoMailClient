package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Юнит-тесты для EasXmlTemplates
 * Проверяем корректность генерации XML-шаблонов
 */
class EasXmlTemplatesTest {
    
    @Test
    fun `syncInitial generates valid XML`() {
        val xml = EasXmlTemplates.syncInitial("folder123")
        
        assertThat(xml).contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        assertThat(xml).contains("<Sync xmlns=\"AirSync\">")
        assertThat(xml).contains("<SyncKey>0</SyncKey>")
        assertThat(xml).contains("<CollectionId>folder123</CollectionId>")
        assertThat(xml).contains("</Sync>")
    }
    
    @Test
    fun `syncWithBody generates valid XML with BodyPreference`() {
        val xml = EasXmlTemplates.syncWithBody(
            syncKey = "key456",
            collectionId = "folder789",
            windowSize = 50
        )
        
        assertThat(xml).contains("<SyncKey>key456</SyncKey>")
        assertThat(xml).contains("<CollectionId>folder789</CollectionId>")
        assertThat(xml).contains("<WindowSize>50</WindowSize>")
        assertThat(xml).contains("<airsyncbase:BodyPreference>")
        assertThat(xml).contains("<DeletesAsMoves>1</DeletesAsMoves>")
    }
    
    @Test
    fun `syncDelete generates valid delete command`() {
        val xml = EasXmlTemplates.syncDelete(
            syncKey = "sync123",
            collectionId = "folder456",
            serverId = "item789",
            deletesAsMoves = true
        )
        
        assertThat(xml).contains("<SyncKey>sync123</SyncKey>")
        assertThat(xml).contains("<CollectionId>folder456</CollectionId>")
        assertThat(xml).contains("<DeletesAsMoves>1</DeletesAsMoves>")
        assertThat(xml).contains("<Delete>")
        assertThat(xml).contains("<ServerId>item789</ServerId>")
    }
    
    @Test
    fun `syncDelete with deletesAsMoves false generates hard delete`() {
        val xml = EasXmlTemplates.syncDelete(
            syncKey = "sync123",
            collectionId = "folder456",
            serverId = "item789",
            deletesAsMoves = false
        )
        
        assertThat(xml).contains("<DeletesAsMoves>0</DeletesAsMoves>")
    }
    
    @Test
    fun `folderSync generates valid XML`() {
        val xml = EasXmlTemplates.folderSync("0")
        
        assertThat(xml).contains("<FolderSync xmlns=\"FolderHierarchy\">")
        assertThat(xml).contains("<SyncKey>0</SyncKey>")
    }
    
    @Test
    fun `searchGal generates valid search request`() {
        val xml = EasXmlTemplates.searchGal("John", 50)
        
        assertThat(xml).contains("<Search xmlns=\"Search\"")
        assertThat(xml).contains("<Name>GAL</Name>")
        assertThat(xml).contains("<Query>John</Query>")
        assertThat(xml).contains("<Range>0-49</Range>")
    }
    
    @Test
    fun `searchMailbox generates valid search request`() {
        val xml = EasXmlTemplates.searchMailbox(
            collectionId = "inbox123",
            freeText = "test",
            rangeStart = 0,
            rangeEnd = 25
        )
        
        assertThat(xml).contains("<Search xmlns=\"Search\"")
        assertThat(xml).contains("<Name>Mailbox</Name>")
        assertThat(xml).contains("<CollectionId>inbox123</CollectionId>")
        assertThat(xml).contains("<FreeText>test</FreeText>")
        assertThat(xml).contains("<Range>0-25</Range>")
    }
    
    @Test
    fun `ewsSoapRequest wraps body content correctly`() {
        val bodyContent = "<TestBody>Content</TestBody>"
        val xml = EasXmlTemplates.ewsSoapRequest(bodyContent)
        
        assertThat(xml).contains(EasXmlTemplates.SOAP_ENVELOPE_START)
        assertThat(xml).contains(EasXmlTemplates.SOAP_HEADER_2007)
        assertThat(xml).contains("<soap:Body>")
        assertThat(xml).contains(bodyContent)
        assertThat(xml).contains("</soap:Body>")
        assertThat(xml).contains(EasXmlTemplates.SOAP_ENVELOPE_END)
    }
    
    @Test
    fun `ewsDeleteItem generates valid DeleteItem request`() {
        val xml = EasXmlTemplates.ewsDeleteItem("itemId123", "MoveToDeletedItems")
        
        assertThat(xml).contains("<m:DeleteItem DeleteType=\"MoveToDeletedItems\">")
        assertThat(xml).contains("<t:ItemId Id=\"itemId123\"/>")
    }
    
    @Test
    fun `ewsDeleteItem with HardDelete generates permanent delete`() {
        val xml = EasXmlTemplates.ewsDeleteItem("itemId123", "HardDelete")
        
        assertThat(xml).contains("DeleteType=\"HardDelete\"")
    }
    
    @Test
    fun `noteCreate generates valid note creation XML`() {
        val xml = EasXmlTemplates.noteCreate(
            syncKey = "key123",
            collectionId = "notes456",
            clientId = "client789",
            subject = "Test Note",
            body = "Test Body"
        )
        
        assertThat(xml).contains("<SyncKey>key123</SyncKey>")
        assertThat(xml).contains("<CollectionId>notes456</CollectionId>")
        assertThat(xml).contains("<Add>")
        assertThat(xml).contains("<ClientId>client789</ClientId>")
        assertThat(xml).contains("<notes:Subject>Test Note</notes:Subject>")
        assertThat(xml).contains("<airsyncbase:Data>Test Body</airsyncbase:Data>")
    }
    
    @Test
    fun `noteCreate with categories includes categories XML`() {
        val xml = EasXmlTemplates.noteCreate(
            syncKey = "key123",
            collectionId = "notes456",
            clientId = "client789",
            subject = "Test",
            body = "Body",
            categories = listOf("Work", "Important")
        )
        
        assertThat(xml).contains("<notes:Categories>")
        assertThat(xml).contains("<notes:Category>Work</notes:Category>")
        assertThat(xml).contains("<notes:Category>Important</notes:Category>")
    }
    
    @Test
    fun `noteUpdate generates valid update XML`() {
        val xml = EasXmlTemplates.noteUpdate(
            syncKey = "key123",
            collectionId = "notes456",
            serverId = "note789",
            subject = "Updated Subject",
            body = "Updated Body"
        )
        
        assertThat(xml).contains("<Change>")
        assertThat(xml).contains("<ServerId>note789</ServerId>")
        assertThat(xml).contains("<notes:Subject>Updated Subject</notes:Subject>")
        assertThat(xml).contains("<airsyncbase:Data>Updated Body</airsyncbase:Data>")
    }
    
    @Test
    fun `moveItems generates valid move request`() {
        val items = listOf(
            "msg1" to "folder1",
            "msg2" to "folder1"
        )
        val xml = EasXmlTemplates.moveItems(items, "folder2")
        
        assertThat(xml).contains("<MoveItems xmlns=\"Move\">")
        assertThat(xml).contains("<SrcMsgId>msg1</SrcMsgId>")
        assertThat(xml).contains("<SrcFldId>folder1</SrcFldId>")
        assertThat(xml).contains("<DstFldId>folder2</DstFldId>")
        assertThat(xml).contains("<SrcMsgId>msg2</SrcMsgId>")
    }
    
    @Test
    fun `ewsTaskCreate generates valid task creation XML`() {
        val xml = EasXmlTemplates.ewsTaskCreate(
            subject = "Test Task",
            body = "Task Description",
            dueDate = "2025-12-31T23:59:59",
            importance = "High"
        )
        
        assertThat(xml).contains("<CreateItem xmlns=")
        assertThat(xml).contains("<t:Task>")
        assertThat(xml).contains("<t:Subject>Test Task</t:Subject>")
        assertThat(xml).contains("<t:Body BodyType=\"Text\">Task Description</t:Body>")
        assertThat(xml).contains("<t:DueDate>2025-12-31T23:59:59</t:DueDate>")
        assertThat(xml).contains("<t:Importance>High</t:Importance>")
    }
    
    @Test
    fun `ewsTaskCreate without dueDate omits DueDate element`() {
        val xml = EasXmlTemplates.ewsTaskCreate(
            subject = "Test Task",
            body = "Description",
            dueDate = null
        )
        
        assertThat(xml).contains("<t:Subject>Test Task</t:Subject>")
        assertThat(xml).doesNotContain("<t:DueDate>")
    }
    
    @Test
    fun `SOAP constants are properly defined`() {
        assertThat(EasXmlTemplates.SOAP_ENVELOPE_START).contains("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        assertThat(EasXmlTemplates.SOAP_ENVELOPE_START).contains("<soap:Envelope")
        assertThat(EasXmlTemplates.SOAP_ENVELOPE_END).isEqualTo("</soap:Envelope>")
        assertThat(EasXmlTemplates.SOAP_HEADER_2007).contains("Exchange2007_SP1")
    }
}
