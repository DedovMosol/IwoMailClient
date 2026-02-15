# Архитектура проекта / Project Architecture

**iwo Mail Client** — Android-клиент для Microsoft Exchange (EAS/EWS), IMAP, POP3.

**Версия:** 1.6.2  
**Пакет:** com.dedovmosol.iwomail  
**Min SDK:** 26 (Android 8.0) | **Target SDK:** 36 (Android 16)

---

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
│   ├── screens/                       # 22 экрана
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
│   └── theme/                         # Тема и стили
│       ├── AppIcons.kt                # Иконки файлов по расширению
│       ├── CustomTextToolbar.kt       # Кастомный toolbar для текста
│       └── Theme.kt                   # Material 3 тема (7 цветовых схем)
│
├── data/                              # Data Layer
│   ├── database/                      # Room Database
│   │   ├── MailDatabase.kt            # База данных (миграции до v33)
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
├── smtp/
│   └── SmtpClient.kt                 # SMTP клиент (JavaMail)
│
├── shared/                            # Общий код для протоколов
│   ├── MailClient.kt                  # Интерфейс почтового клиента
│   ├── MailMessageParser.kt           # Парсинг MIME-сообщений
│   └── MessageToEntityConverter.kt    # Конвертация Message → Entity
│
├── network/                           # Сетевой слой
│   ├── HttpClientProvider.kt          # OkHttpClient factory (SSL, mTLS, cert pinning)
│   ├── NetworkMonitor.kt              # Мониторинг подключения
│   └── RetryUtils.kt                 # Retry с exponential backoff
│
├── sync/                              # Background Services
│   ├── PushService.kt                 # Direct Push (Foreground Service, EAS Ping)
│   ├── SyncWorker.kt                 # Периодическая синхронизация (WorkManager)
│   ├── OutboxWorker.kt               # Отправка из Outbox (offline-first)
│   ├── PushRestartWorker.kt          # Перезапуск Push после сбоя
│   ├── BootReceiver.kt               # Запуск sync после перезагрузки
│   ├── SyncAlarmReceiver.kt          # AlarmManager fallback для OEM
│   ├── ServiceWatchdogReceiver.kt    # Watchdog для PushService
│   ├── CalendarReminderReceiver.kt   # Напоминания о событиях
│   └── TaskReminderReceiver.kt       # Напоминания о задачах
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
    ├── MailWidget.kt                  # AppWidgetProvider (Glance)
    └── WidgetConfigActivity.kt        # Настройка виджета
`

---

## Слои архитектуры

`
┌─────────────────────────────────────────────────────────┐
│  UI Layer (Jetpack Compose)                             │
│  22 экрана, 8 компонентов, 1 Navigation, 3 Theme files  │
│  Material Design 3, 7 цветовых схем                     │
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
│  IMAP: ImapClient  │  POP3: Pop3Client  │  SMTP: SmtpClient │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│  Database Layer              │  Network Layer            │
│  Room — 8 DAO, 7 Entity     │  HttpClientProvider       │
│  MailDatabase (v33)          │  NetworkMonitor           │
│                              │  RetryUtils               │
│                              │  NtlmAuthenticator        │
└──────────────────────────────┴──────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│  Background Services                                     │
│  PushService, SyncWorker, OutboxWorker                   │
│  BootReceiver, SyncAlarmReceiver, PushRestartWorker      │
│  ServiceWatchdogReceiver                                 │
│  CalendarReminderReceiver, TaskReminderReceiver          │
│  ScheduledEmailWorker                                    │
└─────────────────────────────────────────────────────────┘
`

---

## Технологический стек

| Категория | Технология | Версия |
|-----------|-----------|--------|
| Язык | Kotlin | 1.9.24 |
| UI | Jetpack Compose | — |
| Дизайн | Material Design 3 | — |
| Async | Coroutines + Flow | — |
| БД | Room | — |
| Настройки | DataStore | — |
| HTTP | OkHttp | 4.12.0 |
| TLS | Conscrypt | 2.5.2 |
| Протоколы | EAS 12.0-14.1, EWS (NTLM), IMAP, POP3, SMTP | — |
| JavaMail | Jakarta Mail | — |
| DI | Manual (RepositoryProvider) | — |
| Background | WorkManager, AlarmManager, Foreground Service | — |
| Изображения | Coil | — |
| Виджет | Glance (AppWidget) | — |

---

## Совместимость с Exchange 2007 SP1

Exchange 2007 SP1 поддерживает только EAS 12.0. Ограничения и обходные пути:

| Функция | EAS 12.0 | Обходной путь (EWS) |
|---------|----------|---------------------|
| Почта: sync, send, delete, move, flag | ✅ | EWS HardDelete (fallback при syncKey=0) |
| Контакты: sync, GAL search | ✅ | — |
| Папки: sync, create, rename, delete | ✅ | — |
| Provisioning (политики безопасности) | ✅ | — |
| Direct Push (Ping) | ✅ | — |
| Заметки: создание и редактирование | Ограничено | EWS CreateItem/UpdateItem с NTLMv2 |
| Задачи: создание и удаление | Ограничено | EWS CreateItem/DeleteItem с NTLMv2 |
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
| es/drawable/ | 135+ иконок (ic_*.xml) |
| es/layout/ | widget_loading.xml, widget_preview.xml |
| es/raw/ | Звуки: delete_message.mp3, get_message.mp3, send_message.mp3, pashalka_iwo.m4a |
| es/xml/ | backup_rules, data_extraction_rules, file_paths, mail_widget_info, network_security_config, shortcuts |
| es/values/ | strings.xml (EN), themes.xml |
| es/values-ru/ | strings.xml (RU) |

---

## Документация

| Файл | Описание |
|------|----------|
| docs/ARCHITECTURE.md | Этот документ — архитектура проекта |
| docs/CHANGELOG_RU.md | Подробный changelog на русском |
| docs/CHANGELOG_EN.md | Подробный changelog на английском |
| docs/PRIVACY_POLICY.md | Политика конфиденциальности (EN + RU) |
| README.md | README на русском |
| README_EN.md | README на английском |