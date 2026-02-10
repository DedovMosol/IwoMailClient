package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Юнит-тесты для EasNotesService
 * Проверяем корректность работы с заметками через моки зависимостей
 */
class EasNotesServiceTest {
    
    private lateinit var service: EasNotesService
    private lateinit var deps: EasNotesService.NotesServiceDependencies
    
    // Моки для suspend функций
    private val executeEasCommand = mockk<EasNotesService.EasCommandExecutor>()
    private val folderSync = mockk<suspend (String) -> EasResult<FolderSyncResponse>>()
    private val refreshSyncKey = mockk<suspend (String, String) -> EasResult<String>>()
    private val extractValue = mockk<(String, String) -> String?>()
    private val escapeXml = mockk<(String) -> String>()
    private val detectEasVersion = mockk<suspend () -> EasResult<String>>()
    private val getNotesFolderId = mockk<suspend () -> String?>()
    private val getDeletedItemsFolderId = mockk<suspend () -> String?>()
    private val performNtlmHandshake = mockk<suspend (String, String, String) -> String?>()
    private val executeNtlmRequest = mockk<suspend (String, String, String, String) -> String?>()
    private val findEwsNoteItemId = mockk<suspend (String, String, Boolean) -> String?>()
    
    @Before
    fun setup() {
        // Создаем зависимости с моками
        deps = EasNotesService.NotesServiceDependencies(
            executeEasCommand = executeEasCommand,
            folderSync = folderSync,
            refreshSyncKey = refreshSyncKey,
            extractValue = extractValue,
            escapeXml = escapeXml,
            getEasVersion = { "14.1" },
            isVersionDetected = { true },
            detectEasVersion = detectEasVersion,
            getNotesFolderId = getNotesFolderId,
            getDeletedItemsFolderId = getDeletedItemsFolderId,
            performNtlmHandshake = performNtlmHandshake,
            executeNtlmRequest = executeNtlmRequest,
            tryBasicAuthEws = { _, _, _ -> null },
            getEwsUrl = { "https://exchange.local/EWS/Exchange.asmx" },
            findEwsNoteItemId = findEwsNoteItemId
        )
        
        service = EasNotesService(deps)
        
        // По умолчанию escapeXml возвращает исходную строку
        every { escapeXml(any()) } answers { firstArg() }
    }
    
    @After
    fun teardown() {
        clearAllMocks()
    }
    
    @Test
    fun `createNote EAS 14+ creates note successfully`() = runTest {
        // Arrange
        val notesFolderId = "notes123"
        val syncKey = "key456"
        val serverId = "note789"
        
        coEvery { getNotesFolderId() } returns notesFolderId
        coEvery { refreshSyncKey(notesFolderId, "0") } returns EasResult.Success(syncKey)
        coEvery {
            executeEasCommand.invoke<String>("Sync", any(), any())
        } answers {
            val parser = thirdArg<(String) -> String>()
            val mockResponse = """
                <Sync>
                    <Collections>
                        <Collection>
                            <Status>1</Status>
                            <Responses>
                                <Add>
                                    <ServerId>$serverId</ServerId>
                                </Add>
                            </Responses>
                        </Collection>
                    </Collections>
                </Sync>
            """.trimIndent()
            every { extractValue(mockResponse, "Status") } returns "1"
            every { extractValue(mockResponse, "ServerId") } returns serverId
            EasResult.Success(parser(mockResponse))
        }
        
        // Act
        val result = service.createNote("Test Note", "Test Body")
        
        // Assert
        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        assertThat((result as EasResult.Success).data).isEqualTo(serverId)
        
        coVerify { getNotesFolderId() }
        coVerify { refreshSyncKey(notesFolderId, "0") }
        coVerify { executeEasCommand.invoke<String>("Sync", any(), any()) }
    }
    
    @Test
    fun `createNote returns error when notes folder not found`() = runTest {
        // Arrange
        coEvery { getNotesFolderId() } returns null
        
        // Act
        val result = service.createNote("Test", "Body")
        
        // Assert
        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        assertThat((result as EasResult.Error).message).contains("не найдена")
    }
    
    @Test
    fun `deleteNote EAS 14+ deletes note successfully`() = runTest {
        // Arrange
        val notesFolderId = "notes123"
        val syncKey = "key456"
        val serverId = "note789"
        
        coEvery { getNotesFolderId() } returns notesFolderId
        coEvery { refreshSyncKey(notesFolderId, "0") } returns EasResult.Success(syncKey)
        coEvery {
            executeEasCommand.invoke<Boolean>("Sync", any(), any())
        } answers {
            val parser = thirdArg<(String) -> Boolean>()
            val mockResponse = "<Status>1</Status>"
            every { extractValue(mockResponse, "Status") } returns "1"
            EasResult.Success(parser(mockResponse))
        }
        
        // Act
        val result = service.deleteNote(serverId)
        
        // Assert
        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        assertThat((result as EasResult.Success).data).isTrue()
        
        coVerify { executeEasCommand.invoke<Boolean>("Sync", any(), any()) }
    }
    
    @Test
    fun `deleteNotePermanently uses DeletesAsMoves=0`() = runTest {
        // Arrange
        val deletedItemsFolderId = "deleted123"
        val syncKey = "key456"
        val serverId = "note789"
        
        coEvery { getDeletedItemsFolderId() } returns deletedItemsFolderId
        coEvery { refreshSyncKey(deletedItemsFolderId, "0") } returns EasResult.Success(syncKey)
        coEvery {
            executeEasCommand.invoke<Boolean>("Sync", any(), any())
        } answers {
            val xmlArg = secondArg<String>()
            // Проверяем что DeletesAsMoves=0 (жесткое удаление)
            assertThat(xmlArg).contains("<DeletesAsMoves>0</DeletesAsMoves>")
            EasResult.Success(true)
        }
        
        every { extractValue(any(), "Status") } returns "1"
        
        // Act
        val result = service.deleteNotePermanently(serverId)
        
        // Assert
        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        coVerify { getDeletedItemsFolderId() }
    }
    
    @Test
    fun `updateNote EAS 14+ updates note successfully`() = runTest {
        // Arrange
        val notesFolderId = "notes123"
        val syncKey = "key456"
        val serverId = "note789"
        
        coEvery { getNotesFolderId() } returns notesFolderId
        coEvery { refreshSyncKey(notesFolderId, "0") } returns EasResult.Success(syncKey)
        coEvery {
            executeEasCommand.invoke<Boolean>("Sync", any(), any())
        } answers {
            val xmlArg = secondArg<String>()
            assertThat(xmlArg).contains("<Change>")
            assertThat(xmlArg).contains("<ServerId>$serverId</ServerId>")
            EasResult.Success(true)
        }
        
        every { extractValue(any(), "Status") } returns "1"
        
        // Act
        val result = service.updateNote(serverId, "Updated Subject", "Updated Body")
        
        // Assert
        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        assertThat((result as EasResult.Success).data).isTrue()
    }
    
    @Test
    fun `syncNotes EAS 14+ syncs from Notes and Deleted Items folders`() = runTest {
        // Arrange
        val notesFolderId = "notes123"
        val deletedItemsFolderId = "deleted456"
        val folders = listOf(
            EasFolder("notes123", "Notes", "0", 10),
            EasFolder("deleted456", "Deleted Items", "0", 4)
        )
        
        coEvery { folderSync("0") } returns EasResult.Success(
            FolderSyncResponse("1", folders)
        )
        
        // Mock initial SyncKey requests
        coEvery {
            executeEasCommand.invoke<String>(
                "Sync",
                match { it.contains("<SyncKey>0</SyncKey>") },
                any()
            )
        } answers {
            val parser = thirdArg<(String) -> String>()
            every { extractValue(any(), "SyncKey") } returns "key123"
            EasResult.Success(parser("<SyncKey>key123</SyncKey>"))
        }
        
        // Mock notes sync
        coEvery {
            executeEasCommand.invoke<List<EasNote>>(
                "Sync",
                match { it.contains("<SyncKey>key123</SyncKey>") },
                any()
            )
        } returns EasResult.Success(emptyList())
        
        // Act
        val result = service.syncNotes()
        
        // Assert
        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        coVerify { folderSync("0") }
        coVerify(atLeast = 2) {
            executeEasCommand.invoke<Any>(eq("Sync"), any(), any())
        }
    }
    
    @Test
    fun `restoreNote EAS 14+ moves note from Deleted Items to Notes`() = runTest {
        // Arrange
        val notesFolderId = "notes123"
        val deletedItemsFolderId = "deleted456"
        val serverId = "note789"
        val newServerId = "note999"
        
        coEvery { getNotesFolderId() } returns notesFolderId
        coEvery { getDeletedItemsFolderId() } returns deletedItemsFolderId
        coEvery {
            executeEasCommand.invoke<String>("MoveItems", any(), any())
        } answers {
            val xmlArg = secondArg<String>()
            assertThat(xmlArg).contains("<SrcMsgId>$serverId</SrcMsgId>")
            assertThat(xmlArg).contains("<DstFldId>$notesFolderId</DstFldId>")
            
            val parser = thirdArg<(String) -> String>()
            val mockResponse = """
                <MoveItems>
                    <Response>
                        <Status>3</Status>
                        <DstMsgId>$newServerId</DstMsgId>
                    </Response>
                </MoveItems>
            """.trimIndent()
            every { extractValue(mockResponse, "Status") } returns "3"
            every { extractValue(mockResponse, "DstMsgId") } returns newServerId
            EasResult.Success(parser(mockResponse))
        }
        
        // Act
        val result = service.restoreNote(serverId)
        
        // Assert
        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        assertThat((result as EasResult.Success).data).isEqualTo(newServerId)
        
        coVerify { executeEasCommand.invoke<String>("MoveItems", any(), any()) }
    }
    
    @Test
    fun `escapeXml is called for subject and body in createNote`() = runTest {
        // Arrange
        val notesFolderId = "notes123"
        val syncKey = "key456"
        val subject = "Test & <Note>"
        val body = "Body with \"quotes\""
        
        coEvery { getNotesFolderId() } returns notesFolderId
        coEvery { refreshSyncKey(any(), any()) } returns EasResult.Success(syncKey)
        coEvery { executeEasCommand.invoke<String>(any(), any(), any()) } returns EasResult.Success("noteId")
        
        every { extractValue(any(), "Status") } returns "1"
        every { extractValue(any(), "ServerId") } returns "noteId"
        
        // Act
        service.createNote(subject, body)
        
        // Assert
        verify { escapeXml(subject) }
        verify { escapeXml(body) }
    }
}
