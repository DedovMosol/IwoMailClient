package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Интеграционные тесты для проверки делегирования методов заметок
 * из EasClient в EasNotesService
 * 
 * Проверяем, что публичные методы EasClient корректно делегируют вызовы
 */
class EasClientNotesIntegrationTest {
    
    private lateinit var client: EasClient
    private lateinit var notesService: EasNotesService
    
    @Before
    fun setup() {
        // Создаем mock для EasNotesService
        notesService = mockk(relaxed = true)
        
        // Создаем EasClient с минимальными параметрами
        client = spyk(
            EasClient(
                serverUrl = "https://test.local",
                username = "test",
                password = "pass",
                domain = "TEST"
            )
        )
        
        // Подменяем notesService на наш мок
        every { client.notesService } returns notesService
    }
    
    @Test
    fun `syncNotes delegates to notesService`() = runTest {
        // Arrange
        val expectedNotes = listOf(
            EasNote(
                serverId = "note1",
                subject = "Test Note",
                body = "Body",
                messageClass = "IPM.StickyNote",
                lastModified = System.currentTimeMillis(),
                categories = emptyList(),
                isDeleted = false
            )
        )
        coEvery { notesService.syncNotes() } returns EasResult.Success(expectedNotes)
        
        // Act
        val result = client.syncNotes()
        
        // Assert
        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        assertThat((result as EasResult.Success).data).hasSize(1)
        assertThat(result.data[0].serverId).isEqualTo("note1")
        
        coVerify(exactly = 1) { notesService.syncNotes() }
    }
    
    @Test
    fun `createNote delegates to notesService`() = runTest {
        // Arrange
        val subject = "New Note"
        val body = "Note Body"
        val expectedId = "note123"
        
        coEvery { notesService.createNote(subject, body) } returns EasResult.Success(expectedId)
        
        // Act
        val result = client.createNote(subject, body)
        
        // Assert
        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        assertThat((result as EasResult.Success).data).isEqualTo(expectedId)
        
        coVerify(exactly = 1) { notesService.createNote(subject, body) }
    }
    
    @Test
    fun `updateNote delegates to notesService`() = runTest {
        // Arrange
        val serverId = "note456"
        val subject = "Updated"
        val body = "Updated Body"
        
        coEvery { notesService.updateNote(serverId, subject, body) } returns EasResult.Success(true)
        
        // Act
        val result = client.updateNote(serverId, subject, body)
        
        // Assert
        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        assertThat((result as EasResult.Success).data).isTrue()
        
        coVerify(exactly = 1) { notesService.updateNote(serverId, subject, body) }
    }
    
    @Test
    fun `deleteNote delegates to notesService`() = runTest {
        // Arrange
        val serverId = "note789"
        
        coEvery { notesService.deleteNote(serverId) } returns EasResult.Success(true)
        
        // Act
        val result = client.deleteNote(serverId)
        
        // Assert
        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        
        coVerify(exactly = 1) { notesService.deleteNote(serverId) }
    }
    
    @Test
    fun `deleteNotePermanently delegates to notesService`() = runTest {
        // Arrange
        val serverId = "note999"
        
        coEvery { notesService.deleteNotePermanently(serverId) } returns EasResult.Success(true)
        
        // Act
        val result = client.deleteNotePermanently(serverId)
        
        // Assert
        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        
        coVerify(exactly = 1) { notesService.deleteNotePermanently(serverId) }
    }
    
    @Test
    fun `restoreNote delegates to notesService`() = runTest {
        // Arrange
        val serverId = "note111"
        val newServerId = "note222"
        
        coEvery { notesService.restoreNote(serverId) } returns EasResult.Success(newServerId)
        
        // Act
        val result = client.restoreNote(serverId)
        
        // Assert
        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        assertThat((result as EasResult.Success).data).isEqualTo(newServerId)
        
        coVerify(exactly = 1) { notesService.restoreNote(serverId) }
    }
    
    @Test
    fun `error from notesService is propagated correctly`() = runTest {
        // Arrange
        val errorMessage = "Network error"
        coEvery { notesService.syncNotes() } returns EasResult.Error(errorMessage)
        
        // Act
        val result = client.syncNotes()
        
        // Assert
        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        assertThat((result as EasResult.Error).message).contains(errorMessage)
    }
}
