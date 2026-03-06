# Архитектура проекта / Project Architecture

**iwo Mail Client** — Android-клиент для Microsoft Exchange (EAS/EWS), IMAP, POP3.
**iwo Mail Client** — Android client for Microsoft Exchange (EAS/EWS), IMAP, POP3.

**Версия / Version:** 1.6.2  
**Пакет / Package:** com.dedovmosol.iwomail  
**Min SDK:** 26 (Android 8.0) | **Target SDK:** 36 (Android 16)

---

## English

### Package Structure

`
com.dedovmosol.iwomail/
├── MainActivity.kt                    # Entry point, intent handling (mailto, share)
├── MailApplication.kt                 # Application class, Conscrypt initialization
│
├── ui/                                # UI Layer (Jetpack Compose)
│   ├── MainScreen.kt                  # Main screen with folder cards
│   ├── MainScreenDrawer.kt            # Navigation Drawer
│   ├── Localization.kt                # Bilingual localization (RU/EN)
│   ├── navigation/
│   │   └── AppNavigation.kt           # Screen navigation
│   ├── screens/                       # 21 screens + 2 utilities
│   │   ├── AboutScreen.kt             # About + easter egg
│   │   ├── AccountSettingsScreen.kt   # Account settings
│   │   ├── AddAnotherAccountScreen.kt # Add account
│   │   ├── CalendarScreen.kt          # Calendar with events
│   │   ├── ComposeScreen.kt           # Compose/reply/forward email
│   │   ├── ComposeUtils.kt            # ComposeScreen utilities
│   │   ├── ContactsScreen.kt          # Contacts (personal + GAL)
│   │   ├── EmailDetailScreen.kt       # Email viewer
│   │   ├── EmailListScreen.kt         # Email list in folder
│   │   ├── NotesScreen.kt             # Notes
│   │   ├── OnboardingScreen.kt        # Onboarding for new users
│   │   ├── PersonalizationScreen.kt   # Themes and personalization
│   │   ├── ScheduleSendDialog.kt      # Scheduled send dialog
│   │   ├── ScheduledEmailWorker.kt    # Worker for scheduled send
│   │   ├── SearchScreen.kt            # Email search + easter egg
│   │   ├── SettingsScreen.kt          # General settings
│   │   ├── SetupScreen.kt             # New account setup
│   │   ├── SyncCleanupScreen.kt       # Sync cleanup
│   │   ├── TasksScreen.kt             # Tasks
│   │   ├── UpdatesScreen.kt           # Update check (GitHub)
│   │   ├── UserFoldersScreen.kt       # Folder management
│   │   └── VerificationScreen.kt      # Server connection verification
│   ├── components/                    # 8 reusable components
│   │   ├── ComposableUtils.kt         # Common Compose utilities
│   │   ├── ContactPickerDialog.kt     # Contact picker
│   │   ├── DeletionProgressBar.kt     # Deletion progress
│   │   ├── EasterEggOverlay.kt        # Easter egg (music + animation)
│   │   ├── NetworkBanner.kt           # No-network banner
│   │   ├── RichTextEditor.kt          # Rich Text editor (HTML)
│   │   ├── RichTextWithImages.kt      # HTML display with inline images
│   │   └── SendProgressBar.kt         # Send progress
│   ├── utils/                         # Shared UI utilities (DRY)
│   │   ├── AnimationHelpers.kt      # Compose animation helpers
│   │   ├── AnimationSpecs.kt        # Shared animation specs
│   │   ├── AvatarColors.kt           # Avatar color palette + getAvatarColor()
│   │   └── DateFormatUtils.kt        # Relative date formatting (formatRelativeDate)
│   └── theme/                         # Theme and styles
│       ├── AppIcons.kt                # File icons by extension
│       ├── CustomTextToolbar.kt       # Custom text toolbar
│       └── Theme.kt                   # Material 3 theme (4 color schemes)
│
├── data/                              # Data Layer
│   ├── database/                      # Room Database
│   │   ├── MailDatabase.kt            # Database (migrations up to v34)
│   │   ├── Daos.kt                    # EmailDao, FolderDao, AccountDao
│   │   ├── CalendarEventDao.kt        # Calendar event DAO
│   │   ├── CalendarEventEntity.kt     # Entity (11+ fields from MS-ASCAL)
│   │   ├── ContactDao.kt             # Contact DAO
│   │   ├── ContactEntity.kt          # Contact entity
│   │   ├── ContactGroupDao.kt        # Contact group DAO
│   │   ├── ContactGroupEntity.kt     # Group entity
│   │   ├── NoteDao.kt                # Note DAO
│   │   ├── NoteEntity.kt             # Note entity
│   │   ├── SignatureDao.kt            # Signature DAO
│   │   ├── SignatureEntity.kt         # HTML signature entity
│   │   ├── TaskDao.kt                # Task DAO
│   │   └── TaskEntity.kt             # Task entity
│   └── repository/                    # Repositories and services
│       ├── AccountRepository.kt       # Account management (CRUD, Keystore)
│       ├── MailRepository.kt          # Mail: sync, send, drafts, move, delete
│       ├── CalendarRepository.kt      # Calendar: sync, CRUD, attachments, reminders
│       ├── ContactRepository.kt       # Contacts: sync, GAL, import/export
│       ├── NoteRepository.kt          # Notes: sync, CRUD
│       ├── TaskRepository.kt          # Tasks: sync, CRUD, reminders
│       ├── SettingsRepository.kt      # Settings (DataStore)
│       ├── EmailSyncService.kt        # Email sync (incremental/full)
│       ├── EmailOperationsService.kt  # Operations: move, delete, flag, markRead
│       ├── FolderSyncService.kt       # Folder sync
│       ├── RepositoryProvider.kt      # Manual DI (singleton)
│       ├── RepositoryExtensions.kt    # Extension functions
│       ├── RepositoryErrors.kt        # Error handling
│       └── RecurrenceHelper.kt        # Recurring event helper
│
├── eas/                               # Protocol Layer — Exchange
│   ├── EasClient.kt                   # EAS facade (delegates to services)
│   ├── EwsClient.kt                   # Exchange Web Services (NTLM/Basic)
│   ├── EasEmailService.kt            # Mail: sync, send, fetch body
│   ├── EasCalendarService.kt         # Calendar: sync, CRUD (EAS + EWS)
│   ├── EasContactsService.kt         # Contacts: sync, GAL search
│   ├── EasNotesService.kt            # Notes: sync, CRUD (EAS + EWS)
│   ├── EasTasksService.kt            # Tasks: sync, CRUD (EAS + EWS)
│   ├── EasDraftsService.kt           # Drafts: create, update, delete (EWS)
│   ├── EasAttachmentService.kt       # Attachment download
│   ├── EasProvisioning.kt            # Provisioning (security policies)
│   ├── EasXmlTemplates.kt            # XML templates for EAS/EWS requests
│   ├── EasXmlParser.kt               # XML response parser
│   ├── EasPatterns.kt                # Regex patterns for parsing
│   ├── EasCodePages.kt               # WBXML code pages (EAS)
│   ├── EasResultExtensions.kt        # Extensions for EasResult<T>
│   ├── WbxmlParser.kt                # WBXML parser (binary XML)
│   ├── XmlUtils.kt                   # Shared XML escape/unescape (DRY)
│   ├── XmlValueExtractor.kt          # XML value extraction
│   ├── FolderType.kt                 # Exchange folder types
│   ├── NtlmAuthenticator.kt          # NTLMv2 authentication
│   └── AttachmentManager.kt          # Attachment file management
│
├── imap/
│   └── ImapClient.kt                 # IMAP client (JavaMail) — beta
│
├── pop3/
│   └── Pop3Client.kt                 # POP3 client (JavaMail) — beta
│
├── shared/                            # Cross-protocol shared code
│   ├── MailClient.kt                  # Mail client interface
│   ├── MailMessageParser.kt           # MIME message parser
│   └── MessageToEntityConverter.kt    # Message → Entity converter
│
├── network/                           # Network Layer
│   ├── HttpClientProvider.kt          # OkHttpClient factory (SSL, mTLS, cert pinning)
│   └── NetworkMonitor.kt              # Connection monitor
│
├── sync/                              # Background Services
│   ├── PushService.kt                 # Direct Push (Foreground Service, EAS Ping)
│   ├── SyncWorker.kt                 # Periodic sync (WorkManager)
│   ├── SyncHelper.kt                 # Shared sync utilities (syncFolderWithRetry)
│   ├── OutboxWorker.kt               # Outbox send (offline-first)
│   ├── PushRestartWorker.kt          # Push restart after failure
│   ├── BootReceiver.kt               # Start sync after reboot
│   ├── SyncAlarmReceiver.kt          # AlarmManager fallback for OEM
│   ├── ServiceWatchdogReceiver.kt    # PushService watchdog
│   ├── MailNotificationActionReceiver.kt # Notification action handler
│   ├── CalendarReminderReceiver.kt   # Calendar event reminders
│   ├── TaskReminderReceiver.kt       # Task reminders
│   ├── MarkEmailReadWorker.kt       # Worker to mark emails as read
│   └── MarkTaskCompleteWorker.kt    # Worker to mark tasks as complete
│
├── update/
│   └── UpdateChecker.kt              # Update check (GitHub API)
│
├── util/                              # Utilities
│   ├── DateUtils.kt                   # Date formatting
│   ├── HtmlUtils.kt                   # HTML processing
│   └── SoundPlayer.kt                # Sound effects (send/receive/delete)
│
└── widget/                            # Home-screen widget
    ├── MailWidget.kt                  # GlanceAppWidget (deep link navigation via iwomail:// URIs)
    └── WidgetConfigActivity.kt        # Widget configuration
`

---

### Architecture Layers

`
┌─────────────────────────────────────────────────────────┐
│  UI Layer (Jetpack Compose)                             │
│  21 screens, 8 components, 1 Navigation, 3 Theme files │
│  Material Design 3, 4 color schemes                     │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│  Repository Layer                                        │
│  7 repositories + 3 services                             │
│  AccountRepository, MailRepository, CalendarRepository,  │
│  ContactRepository, NoteRepository, TaskRepository,      │
│  SettingsRepository                                      │
│  + EmailSyncService, EmailOperationsService,             │
│    FolderSyncService                                     │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│  Protocol Layer                                          │
│  EAS/EWS: EasClient → 7 services + EwsClient            │
│  IMAP: ImapClient  │  POP3: Pop3Client                   │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│  Database Layer              │  Network Layer            │
│  Room — 11 DAO, 10 Entity   │  HttpClientProvider       │
│  MailDatabase (v34)          │  NetworkMonitor           │
│                              │  NtlmAuthenticator        │
└──────────────────────────────┴──────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│  Background Services                                     │
│  PushService, SyncWorker, OutboxWorker                   │
│  BootReceiver, SyncAlarmReceiver, PushRestartWorker      │
│  ServiceWatchdogReceiver                                 │
│  CalendarReminderReceiver, TaskReminderReceiver          │
└─────────────────────────────────────────────────────────┘
`

---

### Tech Stack

| Category | Technology | Version |
|----------|------------|---------|
| Language | Kotlin | 1.9.22 |
| UI | Jetpack Compose | — |
| Design | Material Design 3 | — |
| Async | Coroutines + Flow | — |
| Database | Room | — |
| Settings | DataStore | — |
| HTTP | OkHttp | 4.12.0 |
| TLS | Conscrypt | 2.5.2 |
| Protocols | EAS 12.0-14.1, EWS (NTLM), IMAP, POP3 | — |
| Mail | JavaMail (com.sun.mail) | — |
| DI | Manual (RepositoryProvider) | — |
| Background | WorkManager, AlarmManager, Foreground Service | — |
| Images | Coil | — |
| Widget | Glance (AppWidget) | — |

---

### Exchange 2007 SP1 Compatibility

Exchange 2007 SP1 supports EAS 12.0 only. Limitations and fallback mechanisms:

| Feature | EAS 12.0 | Fallback (EWS) |
|---------|----------|----------------|
| Mail: sync/send/delete/move/flag | ✅ | EWS HardDelete (fallback when syncKey=0) |
| Contacts: sync, GAL search | ✅ | — |
| Folders: sync/create/rename/delete | ✅ | — |
| Provisioning (security policies) | ✅ | — |
| Direct Push (Ping) | ✅ | — |
| Notes: create/update | Limited | EWS CreateItem/UpdateItem (NTLMv2) |
| Tasks: create/delete | Limited | EWS CreateItem/DeleteItem (NTLMv2) |
| Calendar: single occurrence edit | ❌ | EWS FindItem(CalendarView) + UpdateItem |
| Calendar invitations (iCalendar) | ❌ | EWS CreateItem (MeetingRequest) |
| Server drafts | Limited | EWS CreateItem (MimeContent) + 4-step delete fallback |

Conscrypt 2.5.2 provides TLS compatibility with legacy Exchange 2007 servers.

---

### Key Design Decisions

#### Manual DI (RepositoryProvider)

Instead of Dagger/Hilt, manual Dependency Injection via RepositoryProvider is used. Reasons:
- Minimal dependencies
- Full lifecycle control
- Simple debugging
- Shared EasClient via AccountRepository

#### Dual-body approach for drafts

When saving drafts with inline images, two body representations are used:

| | Server body | Local body |
|---|---|---|
| **Image format** | cid:img1_timestamp | data:image/png;base64,... |
| **Storage** | Exchange Server (EWS CreateItem + MimeContent) | Room Database (EmailEntity.body) |
| **Reason** | Outlook (Word HTML engine does not support data: URLs) | App (WebView, instant display) |

#### Offline-first

All data is stored in Room DB. UI reads data via Flow. Background sync updates the DB, UI reacts automatically.

### Sync Levels

1. **Direct Push** (PushService) — instant notifications from Exchange
2. **WorkManager** — periodic synchronization of all data types
3. **AlarmManager** — fallback for aggressive OEMs (Xiaomi, Huawei, Samsung)
4. **ServiceWatchdog** — PushService health monitoring and restart

### Multi-account

- Each account has its own type (Exchange/IMAP/POP3), sync mode (Push/Scheduled), interval
- Per-account settings: night mode, Battery Saver level
- Shared EasClient with accountId for SSL connection reuse

---

### Resources

| Directory | Content |
|-----------|---------|
| `res/drawable/` | 135+ icons (ic_*.xml) |
| `res/layout/` | widget_loading.xml, widget_preview.xml |
| `res/raw/` | Sounds: delete_message.mp3, get_message.mp3, send_message.mp3 |
| `res/xml/` | backup_rules, data_extraction_rules, file_paths, mail_widget_info, network_security_config, shortcuts |
| `res/values/` | strings.xml (EN), themes.xml |
| `res/values-ru/` | strings.xml (RU) |

---

### Code Quality (Audit v1.6.2, 03.03.2026)

Full security, reliability, and code quality audit completed. Results: 122 findings.

| | Total | Fixed | Remaining |
|---|---|---|---|
| CRITICAL | 24 | 23 + C13a | 1 (partial) |
| HIGH | 24 | 23 | 1 |
| MEDIUM | 35 | 25 | 10 |
| LOW | 38 | 20 | 18 |
| DRY | 1 | 1 | 0 |
| **Total** | **122** | **92** | **30** |

Key fixes:
- **Security:** XML injection in 6 points, NTLM locale fix, hostname verification, URL encoding for User parameter
- **Stability:** CancellationException rethrow in 28 suspend functions, 7 transaction wraps
- **WBXML:** Root cause fix — unescape XML entities in SimpleXmlParser (W3C WBXML §5.8.4.6)
- **DRY:** `XmlUtils.kt` — single source of truth for XML escape/unescape (replaced 10+ duplicates)
- **Performance:** Regex compilation moved to companion objects in 6 EAS services (~35 patterns); Density memoized with `remember`
- **Dead code:** Removed 11 unused private functions/variables/composables
- **Bug fixes:** Integer overflow in notification IDs (CalendarReminderReceiver, TaskReminderReceiver)

Full report: `docs/audit-03.03.2026-v1.6.2.md`

---

### Documentation

| File | Description |
|------|-------------|
| docs/ARCHITECTURE.md | This document — project architecture |
| docs/CHANGELOG_RU.md | Detailed changelog in Russian |
| docs/CHANGELOG_EN.md | Detailed changelog in English |
| docs/XMLPULLPARSER_MIGRATION_PLAN.md | XmlPullParser migration plan |
| docs/PRIVACY_POLICY.md | Privacy policy (EN + RU) |
| docs/audit-03.03.2026-v1.6.2.md | Security and code quality audit v1.6.2 |
| README.md | README in Russian |
| README_EN.md | README in English |

---

## Русский

## Структура пакетов

`
com.dedovmosol.iwomail/
├── MainActivity.kt                    # Точка входа, обработка intent (mailto, share)
├── MailApplication.kt                 # Application class, инициализация Conscrypt
│
├── ui/                                # UI Layer (Jetpack Compose)
│   ├── MainScreen.kt                  # Главный экран с карточками папок
│   ├── MainScreenDrawer.kt            # Navigation Drawer
│   ├── Localization.kt                # Двуязычная локализация (RU/EN)
│   ├── navigation/
│   │   └── AppNavigation.kt           # Навигация между экранами
│   ├── screens/                       # 21 экран + 2 утилиты
│   │   ├── AboutScreen.kt             # О приложении + пасхалка
│   │   ├── AccountSettingsScreen.kt   # Настройки аккаунта
│   │   ├── AddAnotherAccountScreen.kt # Добавление аккаунта
│   │   ├── CalendarScreen.kt          # Календарь с событиями
│   │   ├── ComposeScreen.kt           # Написание/ответ/пересылка письма
│   │   ├── ComposeUtils.kt            # Утилиты для ComposeScreen
│   │   ├── ContactsScreen.kt          # Контакты (личные + GAL)
│   │   ├── EmailDetailScreen.kt       # Просмотр письма
│   │   ├── EmailListScreen.kt         # Список писем в папке
│   │   ├── NotesScreen.kt             # Заметки
│   │   ├── OnboardingScreen.kt        # Онбординг для новых пользователей
│   │   ├── PersonalizationScreen.kt   # Темы и персонализация
│   │   ├── ScheduleSendDialog.kt      # Диалог отложенной отправки
│   │   ├── ScheduledEmailWorker.kt    # Worker для отложенной отправки
│   │   ├── SearchScreen.kt            # Поиск писем + пасхалка
│   │   ├── SettingsScreen.kt          # Общие настройки
│   │   ├── SetupScreen.kt             # Настройка нового аккаунта
│   │   ├── SyncCleanupScreen.kt       # Очистка синхронизации
│   │   ├── TasksScreen.kt             # Задачи
│   │   ├── UpdatesScreen.kt           # Проверка обновлений (GitHub)
│   │   ├── UserFoldersScreen.kt       # Управление папками
│   │   └── VerificationScreen.kt      # Верификация подключения к серверу
│   ├── components/                    # 8 переиспользуемых компонентов
│   │   ├── ComposableUtils.kt         # Общие Compose-утилиты
│   │   ├── ContactPickerDialog.kt     # Выбор контактов
│   │   ├── DeletionProgressBar.kt     # Прогресс удаления
│   │   ├── EasterEggOverlay.kt        # Пасхалка (музыка + анимация)
│   │   ├── NetworkBanner.kt           # Баннер отсутствия сети
│   │   ├── RichTextEditor.kt          # Rich Text редактор (HTML)
│   │   ├── RichTextWithImages.kt      # Отображение HTML с inline-картинками
│   │   └── SendProgressBar.kt         # Прогресс отправки
│   ├── utils/                         # Общие UI-утилиты (DRY)
│   │   ├── AnimationHelpers.kt      # Compose-хелперы анимаций
│   │   ├── AnimationSpecs.kt        # Общие спеки анимаций
│   │   ├── AvatarColors.kt           # Палитра цветов аватаров + getAvatarColor()
│   │   └── DateFormatUtils.kt        # Относительное форматирование дат (formatRelativeDate)
│   └── theme/                         # Тема и стили
│       ├── AppIcons.kt                # Иконки файлов по расширению
│       ├── CustomTextToolbar.kt       # Кастомный toolbar для текста
│       └── Theme.kt                   # Material 3 тема (4 цветовые схемы)
│
├── data/                              # Data Layer
│   ├── database/                      # Room Database
│   │   ├── MailDatabase.kt            # База данных (миграции до v34)
│   │   ├── Daos.kt                    # EmailDao, FolderDao, AccountDao
│   │   ├── CalendarEventDao.kt        # DAO для событий календаря
│   │   ├── CalendarEventEntity.kt     # Entity (11+ полей из MS-ASCAL)
│   │   ├── ContactDao.kt             # DAO для контактов
│   │   ├── ContactEntity.kt          # Entity контакта
│   │   ├── ContactGroupDao.kt        # DAO для групп контактов
│   │   ├── ContactGroupEntity.kt     # Entity группы
│   │   ├── NoteDao.kt                # DAO для заметок
│   │   ├── NoteEntity.kt             # Entity заметки
│   │   ├── SignatureDao.kt            # DAO для подписей
│   │   ├── SignatureEntity.kt         # Entity HTML-подписи
│   │   ├── TaskDao.kt                # DAO для задач
│   │   └── TaskEntity.kt             # Entity задачи
│   └── repository/                    # Репозитории и сервисы
│       ├── AccountRepository.kt       # Управление аккаунтами (CRUD, Keystore)
│       ├── MailRepository.kt          # Почта: sync, send, drafts, move, delete
│       ├── CalendarRepository.kt      # Календарь: sync, CRUD, вложения, напоминания
│       ├── ContactRepository.kt       # Контакты: sync, GAL, import/export
│       ├── NoteRepository.kt          # Заметки: sync, CRUD
│       ├── TaskRepository.kt          # Задачи: sync, CRUD, напоминания
│       ├── SettingsRepository.kt      # Настройки (DataStore)
│       ├── EmailSyncService.kt        # Синхронизация писем (incremental/full)
│       ├── EmailOperationsService.kt  # Операции: move, delete, flag, markRead
│       ├── FolderSyncService.kt       # Синхронизация папок
│       ├── RepositoryProvider.kt      # Manual DI (singleton)
│       ├── RepositoryExtensions.kt    # Extension-функции
│       ├── RepositoryErrors.kt        # Обработка ошибок
│       └── RecurrenceHelper.kt        # Помощник повторяющихся событий
│
├── eas/                               # Protocol Layer — Exchange
│   ├── EasClient.kt                   # Фасад EAS (делегирует в сервисы)
│   ├── EwsClient.kt                   # Exchange Web Services (NTLM/Basic)
│   ├── EasEmailService.kt            # Почта: sync, send, fetch body
│   ├── EasCalendarService.kt         # Календарь: sync, CRUD (EAS + EWS)
│   ├── EasContactsService.kt         # Контакты: sync, GAL search
│   ├── EasNotesService.kt            # Заметки: sync, CRUD (EAS + EWS)
│   ├── EasTasksService.kt            # Задачи: sync, CRUD (EAS + EWS)
│   ├── EasDraftsService.kt           # Черновики: create, update, delete (EWS)
│   ├── EasAttachmentService.kt       # Скачивание вложений
│   ├── EasProvisioning.kt            # Provisioning (политики безопасности)
│   ├── EasXmlTemplates.kt            # XML-шаблоны EAS/EWS запросов
│   ├── EasXmlParser.kt               # Парсинг XML-ответов
│   ├── EasPatterns.kt                # Regex-паттерны для парсинга
│   ├── EasCodePages.kt               # WBXML code pages (EAS)
│   ├── EasResultExtensions.kt        # Extensions для EasResult<T>
│   ├── WbxmlParser.kt                # Парсер WBXML (бинарный XML)
│   ├── XmlUtils.kt                   # Общий XML escape/unescape (DRY)
│   ├── XmlValueExtractor.kt          # Извлечение значений из XML
│   ├── FolderType.kt                 # Типы папок Exchange
│   ├── NtlmAuthenticator.kt          # NTLMv2 аутентификация
│   └── AttachmentManager.kt          # Управление файлами вложений
│
├── imap/
│   └── ImapClient.kt                 # IMAP клиент (JavaMail) — beta
│
├── pop3/
│   └── Pop3Client.kt                 # POP3 клиент (JavaMail) — beta
│
├── shared/                            # Общий код для протоколов
│   ├── MailClient.kt                  # Интерфейс почтового клиента
│   ├── MailMessageParser.kt           # Парсинг MIME-сообщений
│   └── MessageToEntityConverter.kt    # Конвертация Message → Entity
│
├── network/                           # Сетевой слой
│   ├── HttpClientProvider.kt          # OkHttpClient factory (SSL, mTLS, cert pinning)
│   └── NetworkMonitor.kt              # Мониторинг подключения
│
├── sync/                              # Background Services
│   ├── PushService.kt                 # Direct Push (Foreground Service, EAS Ping)
│   ├── SyncWorker.kt                 # Периодическая синхронизация (WorkManager)
│   ├── SyncHelper.kt                 # Общие утилиты синхронизации (syncFolderWithRetry)
│   ├── OutboxWorker.kt               # Отправка из Outbox (offline-first)
│   ├── PushRestartWorker.kt          # Перезапуск Push после сбоя
│   ├── BootReceiver.kt               # Запуск sync после перезагрузки
│   ├── SyncAlarmReceiver.kt          # AlarmManager fallback для OEM
│   ├── ServiceWatchdogReceiver.kt    # Watchdog для PushService
│   ├── MailNotificationActionReceiver.kt # Обработчик действий из уведомлений
│   ├── CalendarReminderReceiver.kt   # Напоминания о событиях
│   ├── TaskReminderReceiver.kt       # Напоминания о задачах
│   ├── MarkEmailReadWorker.kt       # Worker для пометки писем прочитанными
│   └── MarkTaskCompleteWorker.kt    # Worker для пометки задач выполненными
│
├── update/
│   └── UpdateChecker.kt              # Проверка обновлений (GitHub API)
│
├── util/                              # Утилиты
│   ├── DateUtils.kt                   # Форматирование дат
│   ├── HtmlUtils.kt                   # Обработка HTML
│   └── SoundPlayer.kt                # Звуковые эффекты (send/receive/delete)
│
└── widget/                            # Виджет на домашнем экране
    ├── MailWidget.kt                  # GlanceAppWidget (deep link навигация через iwomail:// URI)
    └── WidgetConfigActivity.kt        # Настройка виджета
`

---

## Слои архитектуры

`
┌─────────────────────────────────────────────────────────┐
│  UI Layer (Jetpack Compose)                             │
│  21 экран, 8 компонентов, 1 Navigation, 3 Theme files  │
│  Material Design 3, 4 цветовые схемы                     │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│  Repository Layer                                        │
│  7 репозиториев + 3 сервиса                              │
│  AccountRepository, MailRepository, CalendarRepository,  │
│  ContactRepository, NoteRepository, TaskRepository,      │
│  SettingsRepository                                      │
│  + EmailSyncService, EmailOperationsService,             │
│    FolderSyncService                                     │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│  Protocol Layer                                          │
│  EAS/EWS: EasClient → 7 сервисов + EwsClient            │
│  IMAP: ImapClient  │  POP3: Pop3Client                   │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│  Database Layer              │  Network Layer            │
│  Room — 11 DAO, 10 Entity   │  HttpClientProvider       │
│  MailDatabase (v34)          │  NetworkMonitor           │
│                              │  NtlmAuthenticator        │
└──────────────────────────────┴──────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│  Background Services                                     │
│  PushService, SyncWorker, OutboxWorker                   │
│  BootReceiver, SyncAlarmReceiver, PushRestartWorker      │
│  ServiceWatchdogReceiver                                 │
│  CalendarReminderReceiver, TaskReminderReceiver          │
└─────────────────────────────────────────────────────────┘
`

---

## Технологический стек

| Категория | Технология | Версия |
|-----------|-----------|--------|
| Язык | Kotlin | 1.9.22 |
| UI | Jetpack Compose | — |
| Дизайн | Material Design 3 | — |
| Async | Coroutines + Flow | — |
| БД | Room | — |
| Настройки | DataStore | — |
| HTTP | OkHttp | 4.12.0 |
| TLS | Conscrypt | 2.5.2 |
| Протоколы | EAS 12.0-14.1, EWS (NTLM), IMAP, POP3 | — |
| JavaMail | JavaMail (com.sun.mail) | — |
| DI | Manual (RepositoryProvider) | — |
| Background | WorkManager, AlarmManager, Foreground Service | — |
| Изображения | Coil | — |
| Виджет | Glance (AppWidget) | — |

---

## Совместимость с Exchange 2007 SP1

Exchange 2007 SP1 поддерживает только EAS 12.0. Ограничения и fallback-механизмы:

| Функция | EAS 12.0 | Fallback (EWS) |
|---------|----------|---------------------|
| Почта: sync, send, delete, move, flag | ✅ | EWS HardDelete (fallback при syncKey=0) |
| Контакты: sync, GAL search | ✅ | — |
| Папки: sync, create, rename, delete | ✅ | — |
| Provisioning (политики безопасности) | ✅ | — |
| Direct Push (Ping) | ✅ | — |
| Заметки: создание и редактирование | Ограничено | EWS CreateItem/UpdateItem с NTLMv2 |
| Задачи: создание и удаление | Ограничено | EWS CreateItem/DeleteItem с NTLMv2 |
| Календарь: редактирование одного вхождения | ❌ | EWS FindItem(CalendarView) + UpdateItem |
| Приглашения в календарь (iCalendar) | ❌ | EWS CreateItem (MeetingRequest) |
| Черновики на сервере | Ограничено | EWS CreateItem (MimeContent) + 4-шаговый fallback удаления |

Conscrypt 2.5.2 обеспечивает TLS-совместимость со старыми серверами Exchange 2007.

---

## Ключевые архитектурные решения

### Manual DI (RepositoryProvider)

Вместо Dagger/Hilt используется ручное Dependency Injection через RepositoryProvider. Причины:
- Минимум зависимостей
- Полный контроль над lifecycle
- Простая отладка
- Shared EasClient через AccountRepository

### Dual-body подход для черновиков

При сохранении черновиков с inline-картинками используются два представления body:

| | Серверный body | Локальный body |
|---|---|---|
| **Формат изображений** | cid:img1_timestamp | data:image/png;base64,... |
| **Хранение** | Exchange Server (EWS CreateItem + MimeContent) | Room Database (EmailEntity.body) |
| **Причина** | Outlook (Word HTML engine не поддерживает data: URL) | Приложение (WebView, мгновенное отображение) |

### Offline-first

Все данные сохраняются в Room DB. UI читает данные через Flow. Фоновая синхронизация обновляет БД, UI реагирует автоматически.

### Уровни синхронизации

1. **Direct Push** (PushService) — мгновенные уведомления от Exchange
2. **WorkManager** — периодическая синхронизация всех типов данных
3. **AlarmManager** — fallback для агрессивных OEM (Xiaomi, Huawei, Samsung)
4. **ServiceWatchdog** — мониторинг и перезапуск PushService

### Мультиаккаунт

- Каждый аккаунт имеет свой тип (Exchange/IMAP/POP3), режим синхронизации (Push/Scheduled), интервал
- Per-account настройки: ночной режим, уровень Battery Saver
- Shared EasClient с accountId для переиспользования SSL-соединений

---

## Ресурсы

| Каталог | Содержимое |
|---------|------------|
| `res/drawable/` | 135+ иконок (ic_*.xml) |
| `res/layout/` | widget_loading.xml, widget_preview.xml |
| `res/raw/` | Звуки: delete_message.mp3, get_message.mp3, send_message.mp3 |
| `res/xml/` | backup_rules, data_extraction_rules, file_paths, mail_widget_info, network_security_config, shortcuts |
| `res/values/` | strings.xml (EN), themes.xml |
| `res/values-ru/` | strings.xml (RU) |

---

## Качество кода (Audit v1.6.2, 03.03.2026)

Проведён полный аудит безопасности, надёжности и качества кода. Результаты: 122 находки.

| | Всего | Исправлено | Осталось |
|---|---|---|---|
| CRITICAL | 24 | 23 + C13a | 1 (partial) |
| HIGH | 24 | 23 | 1 |
| MEDIUM | 35 | 25 | 10 |
| LOW | 38 | 20 | 18 |
| DRY | 1 | 1 | 0 |
| **Итого** | **122** | **92** | **30** |

Ключевые исправления:
- **Безопасность:** XML injection в 6 точках, NTLM locale fix, hostname verification, URL encoding для User parameter
- **Стабильность:** CancellationException rethrow в 28 suspend функциях, 7 transaction wraps
- **WBXML:** Root cause fix — unescape XML entities в SimpleXmlParser (W3C WBXML §5.8.4.6)
- **DRY:** `XmlUtils.kt` — единое место для escape/unescape XML (замена 10+ дубликатов)
- **Производительность:** Regex компиляция вынесена в companion objects 6 EAS-сервисов (~35 паттернов); Density мемоизирован через `remember`
- **Мёртвый код:** Удалены 11 неиспользуемых private функций/переменных/composables
- **Баг-фиксы:** Integer overflow в notification IDs (CalendarReminderReceiver, TaskReminderReceiver)

Полный отчёт: `docs/audit-03.03.2026-v1.6.2.md`

---

## Документация

| Файл | Описание |
|------|----------|
| docs/ARCHITECTURE.md | Этот документ — архитектура проекта |
| docs/CHANGELOG_RU.md | Подробный changelog на русском |
| docs/CHANGELOG_EN.md | Подробный changelog на английском |
| docs/XMLPULLPARSER_MIGRATION_PLAN.md | План миграции на XmlPullParser |
| docs/PRIVACY_POLICY.md | Политика конфиденциальности (EN + RU) |
| docs/audit-03.03.2026-v1.6.2.md | Аудит безопасности и качества кода v1.6.2 |
| README.md | README на русском |
| README_EN.md | README на английском |