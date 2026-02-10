package com.dedovmosol.iwomail.data.database

import android.content.Context
import android.util.Log
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [AccountEntity::class, EmailEntity::class, FolderEntity::class, AttachmentEntity::class, ContactEntity::class, ContactGroupEntity::class, SignatureEntity::class, NoteEntity::class, CalendarEventEntity::class, TaskEntity::class],
    version = 31,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class MailDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun emailDao(): EmailDao
    abstract fun folderDao(): FolderDao
    abstract fun signatureDao(): SignatureDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun contactDao(): ContactDao
    abstract fun contactGroupDao(): ContactGroupDao
    abstract fun noteDao(): NoteDao
    abstract fun calendarEventDao(): CalendarEventDao
    abstract fun taskDao(): TaskDao
    abstract fun syncDao(): SyncDao
    
    companion object {
        private const val TAG = "MailDatabase"
        
        @Volatile
        private var INSTANCE: MailDatabase? = null
        
        /**
         * Флаг: БД была пересоздана из-за отсутствующей миграции.
         * Все данные потеряны — нужна полная повторная синхронизация.
         * Проверяется в InitialSyncController для принудительного запуска full sync.
         */
        @Volatile
        var wasDestructivelyMigrated = false
            private set
        
        fun clearDestructiveMigrationFlag() {
            wasDestructivelyMigrated = false
        }
        
        /**
         * Миграции базы данных.
         * При добавлении новых полей в таблицы — добавлять миграцию здесь.
         */
        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Добавляем индексы для ускорения фильтров
                db.execSQL("CREATE INDEX IF NOT EXISTS index_emails_flagged ON emails(flagged)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_emails_read ON emails(read)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_emails_importance ON emails(importance)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_emails_accountId_folderId_dateReceived ON emails(accountId, folderId, dateReceived)")
            }
        }
        
        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Добавляем поля для ответа на приглашения в календаре
                db.execSQL("ALTER TABLE calendar_events ADD COLUMN responseStatus INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE calendar_events ADD COLUMN isMeeting INTEGER NOT NULL DEFAULT 0")
            }
        }
        
        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Добавляем per-account настройки ночного режима и игнорирования экономии батареи
                db.execSQL("ALTER TABLE accounts ADD COLUMN nightModeEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE accounts ADD COLUMN ignoreBatterySaver INTEGER NOT NULL DEFAULT 0")
            }
        }
        
        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Добавляем поле isHtml для подписей с форматированием
                db.execSQL("ALTER TABLE signatures ADD COLUMN isHtml INTEGER NOT NULL DEFAULT 0")
            }
        }
        
        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Добавляем поле isDeleted для задач (корзина)
                db.execSQL("ALTER TABLE tasks ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_isDeleted ON tasks(isDeleted)")
            }
        }
        
        private val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Добавляем поле clientCertificatePath для клиентских сертификатов
                db.execSQL("ALTER TABLE accounts ADD COLUMN clientCertificatePath TEXT DEFAULT NULL")
            }
        }
        
        private val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Добавляем поле isDeleted для заметок (корзина)
                db.execSQL("ALTER TABLE notes ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
            }
        }
        
        private val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Добавляем поля для Certificate Pinning (защита от MITM атак)
                db.execSQL("ALTER TABLE accounts ADD COLUMN pinnedCertificateHash TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE accounts ADD COLUMN pinnedCertificateCN TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE accounts ADD COLUMN pinnedCertificateOrg TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE accounts ADD COLUMN pinnedCertificateValidFrom INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE accounts ADD COLUMN pinnedCertificateValidTo INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE accounts ADD COLUMN certificatePinningEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE accounts ADD COLUMN certificatePinningFailCount INTEGER NOT NULL DEFAULT 0")
            }
        }
        
        private val ALL_MIGRATIONS = arrayOf<Migration>(
            MIGRATION_23_24,
            MIGRATION_24_25,
            MIGRATION_25_26,
            MIGRATION_26_27,
            MIGRATION_27_28,
            MIGRATION_28_29,
            MIGRATION_29_30,
            MIGRATION_30_31
        )
        
        fun getInstance(context: Context): MailDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    MailDatabase::class.java,
                    "mail_database"
                )
                .addMigrations(*ALL_MIGRATIONS)
                // Если миграция не найдена — пересоздать БД (данные потеряются, но не крашнется)
                .fallbackToDestructiveMigration()
                // При откате на старую версию — пересоздать БД (данные синхронизируются с сервера)
                .fallbackToDestructiveMigrationOnDowngrade()
                .addCallback(object : Callback() {
                    override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                        super.onDestructiveMigration(db)
                        Log.w(TAG, "Database was recreated due to missing migration. User data was lost.")
                        wasDestructivelyMigrated = true
                    }
                })
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
    
    @TypeConverter
    fun fromContactSource(source: ContactSource): String = source.name
    
    @TypeConverter
    fun toContactSource(value: String): ContactSource = ContactSource.valueOf(value)
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
    val signature: String = "",
    // Путь к файлу сертификата сервера (для самоподписанных)
    val certificatePath: String? = null,
    // Путь к файлу клиентского сертификата (.p12/.pfx)
    val clientCertificatePath: String? = null,
    // Автоочистка папок (0 = выключено, иначе количество дней)
    val autoCleanupTrashDays: Int = 60,
    val autoCleanupDraftsDays: Int = 60,
    val autoCleanupSpamDays: Int = 60,
    // Синхронизация контактов Exchange (0 = никогда, иначе интервал в днях)
    val contactsSyncIntervalDays: Int = 1, // По умолчанию каждый день
    // Ключ синхронизации для папки контактов
    val contactsSyncKey: String = "0",
    // Синхронизация заметок Exchange (0 = никогда, иначе интервал в днях)
    val notesSyncIntervalDays: Int = 1,
    val notesSyncKey: String = "0",
    // Синхронизация календаря Exchange (0 = никогда, иначе интервал в днях)
    val calendarSyncIntervalDays: Int = 1,
    val calendarSyncKey: String = "0",
    // Синхронизация задач Exchange (0 = никогда, иначе интервал в днях)
    val tasksSyncIntervalDays: Int = 1,
    val tasksSyncKey: String = "0",
    // Ночной режим синхронизации (per-account)
    val nightModeEnabled: Boolean = false,
    // Игнорировать режим экономии батареи (per-account)
    val ignoreBatterySaver: Boolean = false,
    // Certificate Pinning для защиты от MITM атак
    val pinnedCertificateHash: String? = null,  // SHA-256 hash сертификата
    val pinnedCertificateCN: String? = null,  // Common Name (CN=...)
    val pinnedCertificateOrg: String? = null,  // Организация (O=...)
    val pinnedCertificateValidFrom: Long? = null,  // Дата выдачи
    val pinnedCertificateValidTo: Long? = null,  // Дата истечения
    val certificatePinningEnabled: Boolean = true,  // Можно отключить через UI
    val certificatePinningFailCount: Int = 0  // Счётчик ошибок подряд
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
    indices = [
        Index("folderId"),
        Index("accountId"),
        Index("dateReceived"),
        Index("flagged"),  // Для фильтра "Избранные"
        Index("read"),     // Для фильтра "Непрочитанные"
        Index("importance"), // Для фильтра "Важные"
        Index(value = ["accountId", "folderId", "dateReceived"]) // Составной для сортировки
    ]
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

