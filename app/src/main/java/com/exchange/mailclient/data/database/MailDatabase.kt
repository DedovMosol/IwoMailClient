package com.exchange.mailclient.data.database

import android.content.Context
import androidx.room.*

@Database(
    entities = [AccountEntity::class, EmailEntity::class, FolderEntity::class, AttachmentEntity::class],
    version = 10,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class MailDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun emailDao(): EmailDao
    abstract fun folderDao(): FolderDao
    abstract fun attachmentDao(): AttachmentDao
    
    companion object {
        @Volatile
        private var INSTANCE: MailDatabase? = null
        
        fun getInstance(context: Context): MailDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    MailDatabase::class.java,
                    "mail_database"
                )
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
            }
        }
    }
}

class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): java.util.Date? = value?.let { java.util.Date(it) }
    
    @TypeConverter
    fun dateToTimestamp(date: java.util.Date?): Long? = date?.time
}

// === ENTITIES ===

/**
 * Тип протокола для почтового аккаунта
 */
enum class AccountType(val displayName: String) {
    EXCHANGE("Exchange"),
    IMAP("IMAP"),
    POP3("POP3")
}

/**
 * Режим синхронизации для аккаунта
 */
enum class SyncMode(val displayNameRu: String, val displayNameEn: String) {
    PUSH("Push (мгновенно)", "Push (instant)"),
    SCHEDULED("По расписанию", "Scheduled");
    
    fun getDisplayName(isRussian: Boolean): String = if (isRussian) displayNameRu else displayNameEn
}

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val email: String,
    val displayName: String,
    val serverUrl: String,
    val username: String,
    val domain: String = "",
    val acceptAllCerts: Boolean = false,
    val folderSyncKey: String = "0",
    val isActive: Boolean = false,
    val color: Int = 0xFF1976D2.toInt(), // Material Blue
    // Новые поля для POP3/IMAP
    val accountType: String = AccountType.EXCHANGE.name,
    val incomingPort: Int = 993, // IMAP SSL по умолчанию
    val outgoingServer: String = "", // SMTP сервер
    val outgoingPort: Int = 587, // SMTP TLS
    val useSSL: Boolean = true,
    // EAS PolicyKey для авторизации после Provision
    val policyKey: String? = null,
    // Режим синхронизации (PUSH или SCHEDULED)
    val syncMode: String = SyncMode.PUSH.name,
    // Интервал синхронизации в минутах (для SCHEDULED режима)
    val syncIntervalMinutes: Int = 15,
    // Подпись для писем
    val signature: String = ""
)

@Entity(
    tableName = "folders",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("accountId")]
)
data class FolderEntity(
    @PrimaryKey val id: String, // accountId_serverId
    val accountId: Long,
    val serverId: String,
    val displayName: String,
    val parentId: String,
    val type: Int,
    val syncKey: String = "0",
    val unreadCount: Int = 0,
    val totalCount: Int = 0
)

@Entity(
    tableName = "emails",
    foreignKeys = [ForeignKey(
        entity = FolderEntity::class,
        parentColumns = ["id"],
        childColumns = ["folderId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("folderId"), Index("accountId"), Index("dateReceived")]
)
data class EmailEntity(
    @PrimaryKey val id: String, // accountId_serverId
    val accountId: Long,
    val folderId: String,
    val serverId: String,
    val from: String,
    val fromName: String = "",
    val to: String,
    val cc: String = "",
    val subject: String,
    val preview: String = "",
    val body: String,
    val bodyType: Int = 1,
    val dateReceived: Long,
    val read: Boolean,
    val flagged: Boolean = false,
    val importance: Int = 1,
    val hasAttachments: Boolean = false,
    val originalFolderId: String? = null, // Исходная папка до перемещения в корзину
    val mdnRequestedBy: String? = null, // Email для отправки отчёта о прочтении (MDN)
    val mdnSent: Boolean = false // Отчёт о прочтении уже отправлен
)

@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(
        entity = EmailEntity::class,
        parentColumns = ["id"],
        childColumns = ["emailId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("emailId")]
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val emailId: String,
    val fileReference: String,
    val displayName: String,
    val contentType: String,
    val estimatedSize: Long = 0,
    val isInline: Boolean = false,
    val contentId: String? = null, // Для inline изображений (cid:)
    val localPath: String? = null,
    val downloaded: Boolean = false
)

