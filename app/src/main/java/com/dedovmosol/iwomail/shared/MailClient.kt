package com.dedovmosol.iwomail.shared

import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.database.FolderEntity

/**
 * Интерфейс для почтовых клиентов (IMAP/POP3)
 * Принцип ISP (Interface Segregation): только базовые операции чтения
 * 
 * Примечание: EasClient не реализует этот интерфейс,
 * т.к. Exchange ActiveSync имеет свою архитектуру (синхронизация, а не запросы)
 */
interface MailClient {
    
    /**
     * Подключение к серверу
     */
    suspend fun connect(): Result<Unit>
    
    /**
     * Отключение от сервера
     */
    suspend fun disconnect()
    
    /**
     * Получение списка папок
     */
    suspend fun getFolders(): Result<List<FolderEntity>>
    
    /**
     * Получение писем из папки
     * @param folderId ID папки (для POP3 игнорируется — всегда INBOX)
     * @param limit максимальное количество писем
     */
    suspend fun getEmails(folderId: String, limit: Int = 50): Result<List<EmailEntity>>
}
