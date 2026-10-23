package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Юнит-тесты для EasNotesService
 * Проверяем корректность работы с заметками через моки зависимостей.
 * Robolectric — ради настоящего XmlPullParser (android.util.Xml): тест синка гоняет прод-парсер.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
    private val performNtlmHandshake = mockk<suspend () -> String?>()
    private val executeNtlmRequest = mockk<suspend (String, String) -> String?>()
    private val findEwsNoteItemId = mockk<suspend (String, Boolean) -> String?>()
    
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
            tryBasicAuthEws = { _, _ -> null },
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
            // Продакшн после Status=1 извлекает SyncKey (обновляет кэш ключа), затем ServerId.
            every { extractValue(mockResponse, "SyncKey") } returns "key999"
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
            // Продакшн после Status=1 извлекает SyncKey для обновления кэша ключа.
            every { extractValue(mockResponse, "SyncKey") } returns "key999"
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
        val folders = listOf(
            EasFolder("notes123", "Notes", "0", 10),
            EasFolder("deleted456", "Deleted Items", "0", 4)
        )
        
        coEvery { folderSync("0") } returns EasResult.Success(
            FolderSyncResponse("1", folders)
        )
        // Реальный extractValue (как в EasClient), ответы разбирает ПРОДОВЫЙ парсер-лямбда сервиса.
        every { extractValue(any(), any()) } answers { XmlUtils.extractTagValue(firstArg(), secondArg()) }
        coEvery { executeEasCommand.invoke<Any?>("Sync", any(), any()) } answers {
            val request = secondArg<String>()
            val parser = thirdArg<(String) -> Any?>()
            val response = when {
                request.contains("<SyncKey>0</SyncKey>") ->
                    "<Sync><Collections><Collection><SyncKey>key123</SyncKey><Status>1</Status></Collection></Collections></Sync>"
                request.contains("notes123") -> """<Sync><Collections><Collection><SyncKey>key124</SyncKey><CollectionId>notes123</CollectionId><Status>1</Status><Commands>
<Add><ServerId>n:1</ServerId><ApplicationData><Subject>Active note</Subject><MessageClass>IPM.StickyNote</MessageClass></ApplicationData></Add>
</Commands></Collection></Collections></Sync>"""
                else -> """<Sync><Collections><Collection><SyncKey>key125</SyncKey><CollectionId>deleted456</CollectionId><Status>1</Status><Commands>
<Add><ServerId>d:1</ServerId><ApplicationData><Subject>Deleted note</Subject><MessageClass>IPM.StickyNote</MessageClass></ApplicationData></Add>
<Add><ServerId>d:2</ServerId><ApplicationData><Subject>Some mail</Subject><MessageClass>IPM.Note</MessageClass></ApplicationData></Add>
</Commands></Collection></Collections></Sync>"""
            }
            EasResult.Success(parser(response))
        }
        
        // Act
        val result = service.syncNotes()
        
        // Assert: активная заметка, удалённая (письмо из Deleted Items отфильтровано), обе выдачи полны.
        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        val data = (result as EasResult.Success).data
        assertThat(data.notes.filter { !it.isDeleted }.map { it.serverId }).containsExactly("n:1")
        assertThat(data.notes.filter { it.isDeleted }.map { it.serverId }).containsExactly("d:1")
        assertThat(data.isActiveAuthoritative).isTrue()
        assertThat(data.isDeletedAuthoritative).isTrue()
        assertThat(data.isUnchanged).isFalse()
        coVerify { folderSync("0") }
        coVerify(exactly = 4) {
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
