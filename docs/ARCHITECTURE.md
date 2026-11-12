# Архитектура проекта / Project Architecture

**iwo Mail Client** — Android-клиент для Microsoft Exchange (EAS/EWS) с beta-поддержкой IMAP/POP3.

**Версия:** 1.6.3b
**Пакет:** `com.dedovmosol.iwomail`
**Gradle root:** `IwoMailClient`
**Android module:** `:app`

---

## 1. Состояние проекта v1.6.3b

| Область | Значение |
|---------|----------|
| Application ID / namespace | `com.dedovmosol.iwomail` |
| Version | `versionName = 1.6.3b`, `versionCode = 26` |
| SDK | `minSdk = 26`, `compileSdk = 36`, `targetSdk = 36` |
| JVM target | Java/Kotlin `17` |
| Kotlin | `1.9.22` |
| AGP | `8.7.3` |
| Build tooling | Gradle `8.12`, KSP `1.9.22-1.0.17`, Kover `0.7.6` |
| UI | Jetpack Compose (BOM `2024.06.00`) + Material 3 |
| DB | Room `2.6.1`, `MailDatabase` version `50` (12 entities), schema export enabled (`app/schemas`) |
| Published release | `update.json` → `1.6.2` (versionCode 25); `1.6.3b` is a beta build |
| Runtime model | Offline-first: Room + Flow → UI, background sync updates DB |

Основной production-сценарий — on-premise Exchange, особенно Exchange 2007 SP1/SP2. IMAP/POP3 реализованы как beta-клиенты для базового чтения/синхронизации почты и не дают parity с Exchange-функциями.

---

## 2. Слои приложения

```text
MainActivity (FragmentActivity) / MailApplication
  ├─ старт приложения, Conscrypt, app lock (AppLockManager), notification channels, permissions
  ├─ обработка mailto/share/update/shortcut intents
  └─ Compose content + AppNavigation; LockScreen поверх навигации, пока приложение заблокировано

UI Layer
  ├─ Jetpack Compose screens
  ├─ Navigation, Theme, Localization
  ├─ feature subpackages: calendar, compose, contacts, emaildetail, emaillist
  └─ reusable components, animations, custom icons, rich text widgets

Domain (domain/)
  └─ чистая логика без UI: черновики, отправка, цепочки, флаги, отписка, отчёты о прочтении, настройки почты

Repository Layer
  ├─ AccountRepository
  ├─ MailRepository
  ├─ CalendarRepository / TaskRepository / NoteRepository / ContactRepository / CategoryColorRepository
  ├─ SettingsRepository
  ├─ AccountServerHealthRepository
  ├─ EmailSyncService / EmailOperationsService / FolderSyncService / AppFileCleanupService
  └─ FolderSyncGate (один EAS Sync на папку) / FlagWriteTracker (оптимистичная запись флагов)

Protocol Layer
  ├─ EasClient facade
  ├─ EasTransport: EAS WBXML + EWS SOAP + Provision
  ├─ EAS feature services: email, folders, contacts, calendar, tasks, notes, drafts, attachments
  ├─ EwsClient + NtlmAuthenticator
  └─ ImapClient / Pop3Client beta via JavaMail

Persistence / System Layer
  ├─ Room: accounts, folders, emails, attachments, contacts, groups, signatures, notes, calendar, tasks, category colors
  ├─ DataStore: UI settings, sync checkpoints, notification checkpoints, update settings
  ├─ data/security: PasswordStorage, EncryptedStoreProvider, AppLockManager, SecurityTelemetry
  ├─ HttpClientProvider: shared OkHttp, TLS, mTLS, certificate pinning, Rfc6125HostnameVerifier
  ├─ FileProvider + MediaStore/Downloads integration
  └─ Glance widget + dynamic Android shortcuts/deep links

Background Layer
  ├─ PushService: EAS Ping Direct Push foreground service
  ├─ SyncWorker: periodic/manual sync, cleanup, notification pass
  ├─ OutboxWorker: offline outbox send
  ├─ ScheduledSendReceiver + ScheduledEmailWorker: scheduled send
  ├─ CalendarReminderReceiver / TaskReminderReceiver / FlagReminderReceiver / reschedule worker
  └─ BootReceiver / SyncAlarmReceiver / PushRestartWorker / ServiceWatchdogReceiver
```

---

## 2.1. Модель состояния UI (MVVM, внедряется инкрементально)

Исторически экраны держат состояние прямо в `@Composable` через `remember`/`rememberSaveable`/`LaunchedEffect`, без ViewModel. Это требует ручных защит от пересоздания при повороте (`*Loaded`/`*Initialized`-флаги) и смешивает презентационную логику с отрисовкой.

Вводится слой **`androidx.lifecycle.ViewModel` + неизменяемый `UiState` + `StateFlow`**, экран за экраном (чисто слой представления — протокол EAS/EWS и совместимость с Exchange 2007 SP1/SP2 не затрагиваются):

```text
Composable ──collectAsStateWithLifecycle──> StateFlow<UiState>  (ViewModel)
Composable ──вызовы событий (vm.setX())───> ViewModel ──> Repository (RepositoryProvider)
```

Принципы:
- **UiState** — `data class` с неизменяемыми полями; единый источник правды экрана.
- **ViewModel** переживает поворот → состояние сохраняется «бесплатно», убирая ручные флаги и лишние чтения из БД (выигрыш по производительности/энергии).
- Корутины запускаются в `viewModelScope` (живёт дольше композиции) → операции записи в БД не отменяются при повороте.
- Зависимости берутся из существующего `RepositoryProvider` через `ViewModelProvider.Factory` (nav-аргументы передаются в фабрику). `RepositoryProvider` сохраняется как DI-механизм.
- **Одноразовые события** (тосты, звук, навигация, пасхалка) — НЕ часть состояния. Передаются через `Channel(Channel.BUFFERED)` → `receiveAsFlow()`, который UI собирает в `LaunchedEffect`. ViewModel остаётся независимой от языка/ресурсов: эмитит семантическое событие, локализация — в UI.
- **Тестируемость (DIP).** Зависимости (репозитории, диспетчеры, системные side-effect'ы) передаются через конструктор. Фабрика подставляет боевые реализации из `RepositoryProvider`/Android-контекста, юнит-тест — моки. Системные эффекты (`PushService`/`SyncWorker`) скрыты за интерфейсом (напр. `SyncEffects`), поэтому ViewModel не зависит от Android-классов и тестируется обычным JUnit + MockK без Robolectric.

**Мигрировано:**
- `SyncCleanupScreen` ↔ `SyncCleanupViewModel`/`SyncCleanupUiState` — настройки аккаунта (fire-and-forget записи + перезагрузка). Plain `ViewModel` с конструкторной инъекцией: `AccountRepository` + `SettingsRepository` + `SyncEffects` (push/reschedule за интерфейсом). Покрыт `SyncCleanupViewModelTest`.
- `SearchScreen` ↔ `SearchViewModel`/`SearchUiState` + `SearchEvent` — поиск с дебаунсом, выделение, batch-операции; результаты живут в VM и переживают поворот (раньше — хрупкая связка `rememberSaveable(ids)` + повторный запрос в БД, с риском `TransactionTooLargeException` на больших выборках). После полной смерти процесса поиск сбрасывается — осознанный безопасный компромисс. Plain `ViewModel` с инъекцией репозиториев + `CoroutineDispatcher`; покрыт `SearchViewModelTest`.
- `NotesScreen` ↔ `NotesViewModel`/`NotesUiState` + `NotesEvent` — заметки/корзина/выделение/поиск + авто-синхронизация и троттлинг синка корзины. Реактивная связка `activeAccount → getNotes/getDeletedNotes/getDeletedNotesCount` через `flatMapLatest`-подобный `collectLatest`; убран флаг `dataLoaded`, дававший лишнюю синхронизацию при повороте/смене аккаунта. Операции с прогресс-баром (окончательное удаление/восстановление/очистка корзины) — тонкие `suspend`-обёртки VM, вызываемые из `DeletionController` (Compose-механизм undo/прогресса остаётся в UI). Plain `ViewModel` с инъекцией репозиториев + `CoroutineDispatcher`; покрыт `NotesViewModelTest`.
- `TasksScreen` ↔ `TasksViewModel`/`TasksUiState` + `TasksEvent` — задачи/корзина/фильтр (`TaskFilter` из 7 значений)/два независимых набора выделения/поиск + авто-синхронизация и троттлинг синка корзины. Тот же реактивный `collectLatest(activeAccount) → combine(getTasks, getDeletedTasks)`; убран флаг `dataLoaded`. Так как `TaskRepository` даёт только одиночные `delete/restore/permanent`, пакетные `suspend`-обёртки VM (`restoreTasks`/`deleteTasksPermanently` с `onProgress`) делают цикл внутри VM — логика перенесена из Composable в тестируемый слой, поведение прогресс-бара сохранено 1:1. `initialFilter` прокидывается в фабрику. Plain `ViewModel` с инъекцией репозиториев + `CoroutineDispatcher`; покрыт `TasksViewModelTest`.
- `UserFoldersScreen` ↔ `UserFoldersViewModel`/`UserFoldersUiState` + `UserFoldersEvent` — пользовательские папки (фильтр по EAS-типам `1`/`12` и сортировка по имени выполняются в VM), выделение/select-all, CRUD и пакетное удаление с прогрессом в состоянии. Реактивный `collectLatest(activeAccount) → getFolders`; убран флаг авто-синка по `userFolders.isEmpty()` в пользу `isInitialLoadDone` (синк один раз при первом открытии, без повторов при удалении последней папки). Долгие операции перенесены из `rememberSyncScope` (отменялся при повороте!) в `viewModelScope` — теперь переживают поворот. Так как `MailRepository` даёт только одиночные `createFolder/renameFolder/deleteFolder` (`EasResult<Unit>`, per-account `Mutex` во `FolderSyncService`), пакетное удаление делает последовательный цикл внутри VM с публикацией прогресса в `UiState`. Локализация ошибок (`NotificationStrings.localizeError`) и звук удаления остаются в UI. Plain `ViewModel` с инъекцией репозиториев + `CoroutineDispatcher`; покрыт `UserFoldersViewModelTest`.
- `EmailListScreen` ↔ `EmailListViewModel`/`EmailListUiState` + `EmailListEvent` — список писем папки/избранного/«Сегодня», фильтры (`MailFilter`×5 + `EmailDateFilter`×5), выделение/select-all, синхронизация и пакетные операции (в корзину/спам, перемещение, восстановление, прочитано/непрочитано, флаги). Единый реактивный источник `collectLatest(activeAccount) → combine(emailsSource, getFolders)`: текущая папка и список папок для диалога переноса derive'ятся из одного потока (убран отдельный одноразовый `database.folderDao()`-вызов из Composable). Авто-синк только для «Черновиков» и ровно один раз (флаг `draftsSynced` в VM вместо `rememberSaveable`, чтобы поворот не вызывал повторный sync → нет гонки с PushService/`INVALID_SYNCKEY`). Все пакетные операции (`moveToTrash`/`moveEmails`/`restoreFromTrash`/`moveToSpam`/`markAsReadBatch`/`toggleFlag`/`deleteDrafts`) перенесены из `rememberCoroutineScope` (отменялся при повороте!) в `viewModelScope` — теперь переживают поворот; результат — семантические события для тостов. Окончательное удаление с прогрессом (корзина/спам и очистка корзины) — тонкая `suspend`-обёртка VM, вызываемая из `DeletionController` (его собственный scope сохранён). Проверка сети (`NetworkMonitor`), звук удаления, отмена уведомления «Входящих» и локализация остаются в UI. Фильтрация (presentation) считается в Composable через `remember`. Фильтры из nav-аргументов прокидываются в фабрику. Кеш-хелперы строк списка (`EmailListContent`/`EmailListItem`) сохраняют локальный доступ к репозиторию (превью вложений/кеш имён — не бизнес-логика). Plain `ViewModel` с инъекцией репозиториев + `CoroutineDispatcher`; покрыт `EmailListViewModelTest` (including crash-resistance tests).
- `EmailDetailScreen` ↔ `EmailDetailViewModel`/`EmailDetailUiState` + `EmailDetailEvent` (мигрировано **ядро** экрана) — реактивное отслеживание письма/вложений/папок одним источником `collectLatest(activeAccount) → combine(getEmail, getAttachments, getFolders)`, из которого derive'ятся флаги текущей папки (корзина/отправленные/черновики); раньше письмо/папки тянулись отдельными `LaunchedEffect`/одноразовыми `folderDao`-вызовами. Открытие письма (пометка прочитанным + загрузка тела, если локально пусто) — один раз на инстанс VM (переживает поворот; исходник полагался на хрупкий guard `body.isEmpty()`). Inline-картинки грузятся реактивно и **кэшируются в состоянии** → `distinctUntilChanged` гасит повторную сетевую загрузку при повороте (исправление перф-бага исходного экрана, где `remember` сбрасывался и картинки тянулись заново). Top-level операции (обновление, мягкое удаление в корзину, перенос, восстановление, прочитано/непрочитано, флаг, отправка/отклонение MDN) перенесены из `rememberCoroutineScope` (отменялся при повороте → обрыв delete/move/restore!) в `viewModelScope`; результат — семантические события для тостов и навигации назад. Окончательное удаление с прогрессом — тонкая `suspend`-обёртка VM, вызываемая из `DeletionController` (его scope сохранён). Бизнес-логика делегирована в use-case слой `EmailDetailActions`. UI-only концерны остаются в Composable: WebView/рендер тела, launcher'ы и файловый I/O вложений, парсинг iCal/задач, приглашения на встречи, локализация ошибок (`BodyLoadError`/`NotificationStrings.localizeError`). Protocol EAS/EWS и совместимость с Exchange 2007 SP1/SP2 не затрагиваются. Plain `ViewModel` с инъекцией `EmailDetailActions` + репозиториев + `CoroutineDispatcher`; покрыт `EmailDetailViewModelTest` (including crash-resistance tests).

- `ScheduledMailScreen` ↔ `ScheduledMailViewModel` + `ScheduledMailEvent` — список запланированных писем (`StateFlow` из `ScheduledSend.pending`, обновляется по `ScheduledSend.changes`), «отправить сейчас»/перенести/отменить в `viewModelScope`. Получает `Application` (не репозитории) и `CoroutineDispatcher`; покрыт `ScheduledMailViewModelTest` (Robolectric).

**Не подключено:** `ComposeViewModel` + `ComposeUiState`/`ComposeEvent` (`ui/screens/compose/`) существует и покрыт `ComposeViewModelTest`, но `ComposeScreen` его пока не использует — экран создания письма держит состояние в Composable. План — `docs/COMPOSESCREEN_MVVM_PLAN.md`.

Зависимости: `lifecycle-viewmodel-ktx`, `lifecycle-viewmodel-compose`, `lifecycle-runtime-compose` (`2.7.0`).

---

## 3. Пакетная структура

```text
com.dedovmosol.iwomail/
├── MainActivity.kt
├── MailApplication.kt
│
├── ui/
│   ├── MainScreen.kt
│   ├── MainScreenDrawer.kt
│   ├── Localization.kt
│   ├── navigation/AppNavigation.kt
│   ├── screens/
│   │   ├── SetupScreen.kt / VerificationScreen.kt / OnboardingScreen.kt / AddAnotherAccountScreen.kt
│   │   ├── LockScreen.kt — разблокировка паролем/отпечатком
│   │   ├── EmailListScreen.kt / EmailDetailScreen.kt / ComposeScreen.kt / ComposeUtils.kt
│   │   ├── ContactsScreen.kt / CalendarScreen.kt / NotesScreen.kt / TasksScreen.kt
│   │   ├── SettingsScreen.kt (корень настроек) / SettingsScaffold.kt
│   │   ├── SettingsAccountsScreen.kt / SettingsMailScreen.kt (Mail + Notifications) / SettingsSecurityScreen.kt
│   │   ├── MailSettingsSection.kt / MailAlertsSettingsSection.kt
│   │   ├── AccountSettingsScreen.kt / PersonalizationScreen.kt / SyncCleanupScreen.kt
│   │   ├── UserFoldersScreen.kt / UpdatesScreen.kt / AboutScreen.kt / SearchScreen.kt
│   │   ├── ScheduleSendDialog.kt / ScheduleSendTimes.kt / ScheduledMailScreen.kt / ScheduledEmailWorker.kt
│   │   ├── *ViewModel.kt — EmailList, EmailDetail, Notes, Tasks, Search, SyncCleanup, UserFolders, ScheduledMail
│   │   ├── calendar/ — AgendaView, MonthView, event dialogs, attachments, category colors UI, selection top bar
│   │   ├── compose/ — ComposeViewModel/UiState/Event, models, text/html helpers, embedded attachments
│   │   ├── contacts/ — contact dialogs, list views and utilities
│   │   ├── emaildetail/ — attachments section, inline attachments, EmailDetailActions
│   │   └── emaillist/ — SwipeableEmailRow, SwipeRules, ThreadRows, row names, image previews
│   ├── components/ — HtmlBodyView, RichTextEditor, SendController, FlagMenu, EmailPrinter, WebViewRenderer/RendererRecovery, TouchDirectionLock, banners, dialogs
│   ├── theme/
│   └── utils/ — animations, avatar colors, date formatting, ContextExtensions, LargeStateSaver
│
├── domain/ — AttachmentLoader, DraftSaver, EmailThreads, FlagPresets, MailAlerts, MailPreferences,
│             OutgoingMail, ReadReceipts, SendFailures, SentFolder, Unsubscribe, ...
│
├── data/
│   ├── database/ — Room database, entities, DAOs (incl. PendingEmailChangeEntity — offline change queue), notification projections
│   ├── model/ — server health model
│   ├── security/ — PasswordStorage, EncryptedStoreProvider, AppLockManager, SecurityTelemetry
│   └── repository/ — repositories, sync/operation services, provider, caches, helpers
│                     (incl. FolderSyncGate.kt, FlagWriteTracker.kt, PendingMailChanges.kt, CategoryColorRepository.kt, RepositoryErrors.kt)
│
├── eas/ — EAS/EWS protocol layer and Exchange feature services (incl. MailFlag.kt, ListUnsubscribe.kt, RssItems.kt)
├── imap/ImapClient.kt
├── pop3/Pop3Client.kt
├── shared/ — JavaMail shared interfaces/parsers/converters
├── network/ — HttpClientProvider, NetworkMonitor, ConnectionFailure, Rfc6125HostnameVerifier
├── sync/ — PushService, workers, receivers, notifications, reminders:
│           AppForegroundTracker, BackgroundWork, InitialSyncController, PingFailurePolicy, PushRestartPolicy,
│           ScheduledSend/ScheduledSendReceiver, FlagReminderReceiver, PendingMailChangesWorker, ServerHealthNotifier,
│           MailNotificationChannels, SendNotifications, NotificationHelper, SyncHelper
├── update/UpdateChecker.kt
├── util/ — date/html/email/mime/ics/sound/deleted-id utilities, RemoteImages, EmlFiles, EmailPrintDocument,
│           AppShortcuts, AppCoroutines
└── widget/ — Glance widget (MailWidget, MailWidgetReceiver) and config activity
```

---

## 4. Данные и хранилище

### Room

`MailDatabase` содержит 12 entity-классов и 13 DAO/accessors (включая `syncDao()`, `categoryColorDao()` и `pendingEmailChangeDao()`):

- **AccountEntity:** тип аккаунта, URL, alternate URL, sync mode, certificate metadata (отдельный пин для alternate URL), draft mode, per-account intervals.
- **FolderEntity:** Exchange/IMAP/POP3 folders and per-folder SyncKey.
- **EmailEntity:** message metadata/body, read/importance, attachments marker, MDN fields, meeting message class, Internet Message-ID, draft `bcc` and `replacesServerId` (draft that replaces a server draft edited offline), follow-up flag (`flagged`, `flagCompleted`, `flagStartDate`, `flagDueDate`, `flagReminder`), `listUnsubscribe`/`listUnsubscribeOneClick`, draft `requestReadReceipt`/`requestDeliveryReceipt`, `conversationId` (EAS 14.0+).
- **AttachmentEntity:** email attachments, local download state, inline CID metadata.
- **ContactEntity / ContactGroupEntity:** local and Exchange contacts, groups, favorites and usage counters.
- **SignatureEntity:** HTML signatures.
- **NoteEntity:** Exchange notes with local trash flag.
- **CalendarEventEntity:** calendar events, meetings, recurrence fields, exceptions, attachments JSON, online links, meeting request IDs.
- **TaskEntity:** task fields, deadlines, priority, reminders, owner/assignee and local trash flag.
- **CategoryColorEntity:** per-account Outlook category colors (`serverColor` from the Master Category List on Exchange 2010+, `userColor` set in the app).
- **PendingEmailChangeEntity** (`pending_email_changes`, v50): email changes made without a connection that the server has not accepted yet — `flag` and/or `read`. The value itself is not stored: the current one from `emails` is sent, so several offline edits go as one result. No FK to `emails` on purpose (some paths rewrite emails with REPLACE, a cascade would drop the pending edit); FK to `accounts` with CASCADE. Plain `INSERT OR REPLACE … COALESCE` instead of UPSERT (SQLite on Android 8 is 3.18).

Current DB version is **50**. Registered migrations cover `23→50` (`MailDatabase.ALL_MIGRATIONS`, one step per version). A missing upgrade migration recreates the DB only in **debug** builds (`fallbackToDestructiveMigration()` under `BuildConfig.DEBUG`); in release it fails explicitly when the DB is opened. A downgrade (older APK) always recreates the DB (`fallbackToDestructiveMigrationOnDowngrade()`), and server data is resynced.

Widget hot paths use dedicated lightweight projections instead of full entities:

| Use case | DAO projection / query shape |
|----------|------------------------------|
| Recent unread Inbox emails | `WidgetRecentEmailSummary` without `EmailEntity.body` |
| Current/upcoming calendar event | `WidgetCalendarEventSummary` without body/attendees/attachments JSON |
| Next task title | `WidgetTaskSummary` without task body |

Migration 41→42 (Room v42) added indexes for these home-screen widget paths: `emails(read, dateReceived)`, `folders(type)`, `tasks(complete, isDeleted, dueDate, subject)`, `calendar_events(isDeleted, startTime, endTime)` and `calendar_events(isDeleted, endTime, startTime)`.

### DataStore

`SettingsRepository` stores UI preferences, default draft mode, update-check settings, sync checkpoints, notification checkpoints and per-account cleanup/non-email sync timestamps.

### Secure storage

`AccountRepository` stores passwords through `data/security/PasswordStorage`, which gets its store from `EncryptedStoreProvider`: `EncryptedSharedPreferences` by default. If Android Keystore is unavailable or corrupted, it falls back to `ObfuscatedSharedPreferences` (XOR with a PBKDF2-derived key, see section 8) instead of plaintext. The app-lock password hash (`AppLockManager`) uses the same provider.

---

## 5. Exchange protocol architecture

### EasClient

`EasClient` is the facade used by repositories. It owns stable `DeviceId`, shared OkHttp client, `EasTransport`, `EasVersionDetector`, EAS feature services and `EwsClient`.

### EasTransport

`EasTransport` is responsible for WBXML generation/parsing, `PolicyKey` handling, HTTP `449` Provision retry, EWS execution with Basic Auth first and NTLM fallback, and shared error normalization.

### EWS

EWS requests use SOAP and `RequestServerVersion="Exchange2007_SP1"` by default. EWS is used where EAS 12.x is incomplete or unreliable: notes, many task paths, server drafts, calendar details/meetings/exceptions/attachments and selected fallback deletes.

The NTLM handshake (Type1 → Type2 → Type3) runs on a dedicated OkHttp client with `ConnectionPool(1)` and is serialized via a lock, so Type1 and Type3 share one TCP connection and cannot be interleaved with parallel EAS/EWS traffic — NTLM authenticates the connection, not the request. The 401 challenge body is fully consumed so the connection returns to the pool.

MIME body extraction (`MimeHtmlProcessor.extractHtmlFromMime`) uses a proper RFC 2046 boundary split with recursion into nested multipart; a regex fallback covers single-part MIME.

### IMAP/POP3 beta

`ImapClient` and `Pop3Client` implement `shared.MailClient` via JavaMail. IMAP supports folders and message flags. POP3 is INBOX-only and cannot preserve server read/flag state. Full SMTP parity is not implemented as a separate production layer.

---

## 6. Основные runtime flows

### Startup

1. `MailApplication.onCreate()` installs Conscrypt, registers `ServiceWatchdogReceiver` dynamically and starts `AppForegroundTracker` (`ProcessLifecycleOwner`).
2. `AppLockManager.init()` evaluates the lock state (cold start with a password set = locked); the app re-locks when the whole process goes to the background (short trips to system pickers excepted).
3. Notification channels are created for sync, sync status, calendar/task reminders, outbox, scheduled send, updates and connection status; mail channels are per account (`MailNotificationChannels`: a channel group per account with "new mail" and "VIP senders" channels).
4. Stale temp files (including `LargeStateSaver` files older than 24 h in `cacheDir`), duplicate DB records and old attachments are cleaned; a legacy outbox queue is drained.
5. Periodic sync is scheduled.
6. `PushService` is started when at least one Exchange account is in Push mode; server health is observed for connection-loss notifications (`ServerHealthNotifier`) and dynamic app shortcuts are kept in sync.
7. `MainActivity` handles permissions and external intents, then starts Compose navigation.

### Account setup

1. `SetupScreen` collects account data.
2. Exchange accounts go through `VerificationScreen`.
3. Verification checks server connectivity and can send a test email.
4. Sensitive passwords are passed through in-memory `VerificationSecrets`, then persisted by `AccountRepository`.
5. IMAP/POP3 accounts are beta accounts with simpler validation.
6. Server certificates, self-signed certificates, client `.p12/.pfx` certs and pinning metadata are handled through `HttpClientProvider` and account fields.

### Mail sync

1. `FolderSyncService` syncs folders and serializes folder operations per account.
2. `EmailSyncService` syncs system folders and user folders with full/incremental strategies.
3. `MailRepository` exposes UI-facing methods and delegates heavy logic.
4. `EmailOperationsService` handles move, delete, permanent delete, mark read, flag and read-receipt flows (Exchange sends the read receipt itself when the message is marked read; declining uses EWS `SuppressReadReceipt` before marking read).
5. **One EAS Sync request per folder at a time (`FolderSyncGate`).** The SyncKey ([MS-ASCMD] Sync) is a chain: two requests with the same key (the sync loop and a star/read change) make the client and server keys diverge, the next request gets Status 3 and the folder is resynced from scratch. `FolderSyncGate.withFolder(folderId)` is a process-wide per-folder `Mutex` (SyncWorker and PushService create separate service instances). Through it go each iteration of the folder sync loop in `EmailSyncService.syncEmailsEas` (request + batch processing + SyncKey save, also the initial `SyncKey=0` request) and local changes in `EmailOperationsService` (`setFlag`/`toggleFlag`, `markAsRead`, `markAsReadBatch` per folder). Inside the gate the SyncKey is re-read from the DB, so the loop continues from a key advanced by a local change. The mutex is not reentrant: the same folder's gate must not be taken inside the block.
6. **Optimistic flag writes (`FlagWriteTracker.shared`).** A star/flag change is written to the DB immediately, then sent as a Sync Change. Each tap gets a ticket; only the latest tap is sent and only its outcome settles the DB, so responses to earlier taps do not flip the star back. A server rejection rolls back to the last server-confirmed flag (not to the snapshot before the tap, which may itself be optimistic). No network or no response keeps the local value. A sync response requested before or during a local flag write does not overwrite it (`applyServerFlag` compares the request time). Lock order is `FolderSyncGate`, then the tracker's lock. The server part runs in `NonCancellable`, so leaving the screen does not cut a request halfway. A rejection returns `RepositoryErrors.FLAG_REJECTED`; `EmailListViewModel`/`EmailDetailViewModel` show it as a localized toast ("The server did not accept the flag — kept as on the server").
7. **Offline star and read state (`pending_email_changes` → `PendingMailChangesWorker`).** Only an explicit rejection of the change itself rolls it back (`isChangeRejected`: `Change Status=N`, or collection Status 4/6/7/8). A stale SyncKey (3), changed folder hierarchy (12), server error (5), HTTP errors and no connection keep the local value, mark it in `pending_email_changes` and return success — the star does not go out, an email read offline does not become unread. `PendingMailChangesWorker` (unique work, `KEEP`, `NetworkType.CONNECTED`, exponential backoff from 30 s, up to 20 attempts) sends them through the same path as a tap (`EmailOperationsService.retryPendingFlag`/`retryPendingRead`, via `PendingMailChanges.flush`, which also picks up edits made during the run). `KEEP` is deliberate: a failed send inside the worker re-enqueues nothing (no loop without a pause); leftovers are retried with backoff, and `SyncWorker` and `MailApplication` call `enqueueIfPending`. While a field waits, sync does not overwrite it (`EmailDao.updateReadStatusFromServer`, `saveServerFlag` — `NOT EXISTS` on the queue). The queue entry moves with the email on a ServerId migration (`PendingEmailChangeDao.rename`).
8. **Permanent delete.** Before EAS Sync Delete the folder is synced by the regular `syncEmails` (its changes are processed), then the batch delete runs under `FolderSyncGate` with the DB key and the new key is saved (Delete is sent with `GetChanges=0`). Emails are re-resolved by content after the sync (`resolveEmailIds`, EAS 12.1 ServerIds can change after `SyncKey=0`). Earlier the code sent up to 10 "refresh" Syncs and dropped their payload while saving the advanced key — server changes in that folder were lost until a full resync. `deleteEmailsPermanentlyBatch` no longer resets the server sync state with `Sync(SyncKey=0)` on Exchange 2007. The Drafts EAS delete (`MailRepository.deleteDraftViaEas`) also runs under the gate and saves the returned key.
9. `OutboxWorker` retries offline outgoing messages when network returns. Each queued message carries a stable `ClientId`/`Message-ID` reused across retries, so a retry after a lost response is deduplicated by the server on EAS 14+ (MS-ASCMD `SendMail` ClientId) instead of being delivered twice; on Exchange 2007 (EAS 12.1, raw-MIME `SendMail` without ClientId) the stable Message-ID is a best-effort identity (N-11).

Orphan detection after full resync uses lightweight DAO projections to avoid loading full `EmailEntity` for large folders:

| Use case | Projection |
|----------|-----------|
| Sent full resync orphan detection (`syncSentFull`) | `EmailDedupInfo` (6 fields) |
| Sent reconcile after full resync (`reconcileSentAfterFullResync`) | `List<String>` ids only |
| Generic folder reconcile after full resync (`reconcileGenericFolderAfterFullResync`) | `List<String>` ids only |
| ServerId content-matching after SyncKey=0 reset (`resolveEmailIds`) | `EmailDedupInfo` |

Drafts-specific reconcile keeps full `EmailEntity` because body/bodyType migration is required.

### Calendar/tasks/notes/contacts

1. Repositories own local Room state and UI operations.
2. EAS/EWS services own protocol details.
3. Calendar and task reminders are scheduled via Android receivers/workers.
4. Local `isDeleted` flags and `DeletedIdsTracker` prevent resurrection while Exchange state catches up.
5. Calendar attachments are represented as JSON metadata in `CalendarEventEntity.attachments`; file bytes are downloaded only on demand.
6. Recurring calendar series store attachment metadata on the master/exception path and virtual occurrences reuse it instead of duplicating payloads.
7. Permanent calendar deletion requires server success first; local deletion/trash clearing happens only after confirmed EAS/EWS delete.
8. Attendee meeting deletion sends `MeetingResponse` or EWS `DeclineItem` before delete to prevent Calendar Repair Assistant resurrection.

### Notifications

1. `PushService` receives EAS Ping changes.
2. `SyncWorker` also checks notifications during periodic/manual sync.
3. Both use `NotificationHelper.notificationMutex` to avoid duplicate notification races.
4. Notification queries use lightweight projections and bounded action batches.
5. Mail notifications go to per-account channels (`MailNotificationChannels`); sound, importance and Do Not Disturb are set per account in system settings. Quiet hours and VIP senders (`domain/MailAlerts`, global settings) make mail silent during quiet hours unless a sender is VIP.
6. `ServerHealthNotifier` posts one notification per account when the connection to the server is lost; flag reminders are delivered by `FlagReminderReceiver`.

### Home-screen widget

1. `MailWidget` is a Glance `GlanceAppWidget` with `SizeMode.Responsive` to avoid launcher crashes caused by exact size-map RemoteViews on problematic ROMs.
2. Widget data is loaded on `Dispatchers.IO` from Room and DataStore using `applicationContext`.
3. Recent mail, next task and calendar event queries use widget-specific projections and indexes to avoid loading heavy email bodies, task bodies, attendees or calendar attachment JSON.
4. `updateMailWidget(context)` serializes `MailWidget().updateAll()` through a process-level mutex so sync, notification, account and personalization paths cannot run concurrent widget updates.
5. Widget clicks route through `MainActivity` deep links/extras; manual sync uses `SyncAlarmReceiver.ACTION_SYNC_NOW` and is delegated to `SyncWorker`. Each clickable carries a **distinct `data` URI** (`iwomail://widget/…`, `iwomail://email/{id}`, `iwomail://account/{id}`) because `PendingIntent.filterEquals` ignores extras — same-shaped intents would otherwise collide and share a single click target.
6. **Width-adaptive content, not just scaling.** Glance `Row` maps to a non-wrapping horizontal `LinearLayout`, so any content wider than the widget is silently clipped (there is no flow/wrap in RemoteViews). The bottom action row therefore renders **fewer children on narrow sizes** (`size.width >= 300.dp` gate: 4 vs 2 account avatars, last-sync label only when wide), the avatars scale with the shared `scale` factor, and the last-sync label carries `defaultWeight()` + `maxLines=1` so it **truncates** rather than pushing the compose/sync buttons off-screen (robust for any locale/length — the label sits before the buttons in source order, so an unweighted long label would otherwise starve their `AT_MOST` measure). Timestamps use a shared `isSameLocalDay` helper: today → time, otherwise → date (`dd.MM`). Note: `cornerRadius(Dp)` is a no-op below API 31; square corners on Android 8–11 are an accepted trade-off because the drawable-background fallback would reintroduce a known HyperOS launcher crash.

### Updates and rollback

1. `UpdateChecker` reads GitHub `update.json`.
2. APK URL is selected by device ABI with universal fallback.
3. Downloads use shared OkHttp clients with long timeouts.
4. Install intents use `FileProvider`.
5. Rollback preparation copies APKs to Downloads/MediaStore and exposes an Open Downloads flow before uninstall.

---

## 7. Background components

| Component | Responsibility |
|-----------|----------------|
| `PushService` | Foreground Direct Push service, adaptive EAS Ping heartbeat (on Ping `Status=5` the server-supplied `HeartbeatInterval` is accepted within the 120–1800 s bounds per MS-ASCMD), per-account jobs |
| `SyncWorker` | Periodic/manual sync, notification pass, auto-cleanup, PushService health check |
| `OutboxWorker` | Offline outbox send |
| `PushRestartWorker` | Push restart after failures |
| `BootReceiver` | Start sync/push after reboot or package replacement |
| `SyncAlarmReceiver` | AlarmManager fallback for aggressive OEM ROMs |
| `ServiceWatchdogReceiver` | PushService watchdog on screen/user/power events. **Registered dynamically in `MailApplication`** (`ContextCompat.registerReceiver` + `RECEIVER_NOT_EXPORTED`) — `SCREEN_ON`/`USER_PRESENT`/`POWER_CONNECTED` are implicit broadcasts not delivered to manifest receivers on API 26+. Restart goes through the exemption-safe `PushService.requestRestart` (exact-alarm `getForegroundService` + WorkManager). |
| `MailNotificationActionReceiver` | Notification action handling for mail (mark read is delegated to `MarkEmailReadWorker`) |
| `CalendarReminderReceiver` | Calendar reminder actions (reminder, mark read, snooze 5 min) |
| `TaskReminderReceiver` | Task reminder actions |
| `FlagReminderReceiver` | Reminders for flagged emails (`tasks:ReminderTime`); alarms only for the nearest reminders, the flag is re-checked in the DB when the alarm fires |
| `ScheduledSendReceiver` | Exact alarm for a scheduled email: starts sending now (`ScheduledSend`) |
| `ScheduledEmailWorker` | Sends a scheduled email (`ui/screens`, class name kept for already enqueued work); also the WorkManager backup for the exact alarm; retries temporary errors, otherwise moves the email to Drafts with a notification |
| `MarkEmailReadWorker` | Unique worker to mark email as read |
| `MarkTaskCompleteWorker` | Worker to complete tasks from notifications; a server error is retried (up to 10 attempts with exponential backoff) instead of being swallowed |
| `PendingMailChangesWorker` | Sends star and read changes made without a connection (`pending_email_changes`) when the network is back |
| `RescheduleRemindersWorker` | Rebuild calendar, task and flag reminder alarms after reboot/update |
| `MailWidgetReceiver` | Glance widget receiver (`widget/MailWidget.kt`) |

Only `BootReceiver` and `MailWidgetReceiver` are exported; the other manifest receivers are `exported="false"`.

---

## 8. Security and network

`HttpClientProvider` centralizes OkHttp creation to avoid connection/thread pool leaks.

Supported network/security paths:

- normal system trust;
- accept-all mode for legacy/self-signed environments;
- custom server certificate;
- PKCS#12 client certificate with mTLS;
- certificate pinning with saved SHA-256 and metadata (separate pin for the alternate URL);
- Conscrypt provider for legacy TLS compatibility; Exchange clients use `ConnectionSpec.COMPATIBLE_TLS` (TLS 1.0–1.3 in one handshake), the update channel uses `MODERN_TLS` and system CAs only.

Hostname verification: with an imported server certificate (`certificatePath`) the client uses `Rfc6125HostnameVerifier` (SAN dNSName/iPAddress per RFC 6125, CN fallback only when the certificate has no SAN — common for Exchange 2007 self-signed certificates); accept-all mode disables verification by the user's choice; otherwise OkHttp's default verifier is used.

Cleartext traffic is disabled globally (`network_security_config`, `cleartextTrafficPermitted="false"`); Exchange (EAS over OkHttp) is therefore HTTPS-only and the setup screen no longer offers a plaintext-HTTP option for Exchange accounts, forcing `useSSL=true` on save (N-13). IMAP/POP3 run through JavaMail (outside the network-security-config) and may still use a plaintext port.

`AccountRepository` caches `EasClient` per account and invalidates cache when certificate/server settings change. Alternate Exchange URL fallback is supported for connection-level errors, with later probe/switchback to primary.

### Email body rendering (WebView)

Email HTML bodies are sanitized before being loaded into `WebView`:

- `HtmlUtils.sanitizeEmailHtml` removes:
  - `<script>` blocks and standalone tags;
  - plugin containers: `<iframe>`, `<object>`, `<embed>`, `<applet>` (both paired and self-closing forms);
  - `<meta http-equiv="refresh">` redirects;
  - inline event handlers `on*="..."`/`on*='...'`/unquoted;
  - `javascript:` and `data:text/html` URIs in `href`/`src`/`action`/`formaction`/`xlink:href` attributes.
- All regexes are idempotent and ReDoS-safe (no catastrophic backtracking).
- `HtmlBodyView` (`ui/components`, used by `EmailDetailScreen` and the calendar `EventDetailDialog`) loads sanitized HTML with `loadDataWithBaseURL(null, ...)`; `EmailPrinter` does the same for printing. Null-baseURL blocks cross-origin requests, cookie/localStorage exfiltration and service workers, complementing the tag-level blocklist.
- **Gestures in the viewer.** The `HtmlBodyView` WebView has the height of its content and does not scroll vertically; the parent `Column.verticalScroll` does. `TouchDirectionLock` decides the owner once per gesture after the system touch slop: horizontal drag and pinch (second pointer) → WebView (`requestDisallowInterceptTouchEvent(true)`), vertical → screen. Earlier the owner was re-decided on every move by the offset from the touch point: panning left-right in one gesture passed through zero on X, vertical drift won, the screen intercepted, the WebView got `ACTION_CANCEL` — jerky horizontal scrolling. `overScrollMode = NEVER` (no Android 12+ stretch on vertical drift). Content height also takes `documentElement.getBoundingClientRect().height` (collapsed margins of the last block pass through `body` but not through `html`), so the WebView is never a few pixels shorter than the page and has nothing to scroll internally.
- **Renderer death.** Every WebView (`HtmlBodyView`, `RichTextEditor`, `EmailPrinter`) overrides `onRenderProcessGone` and returns `true` (otherwise the system kills the whole app). Shared helpers: `WebViewRenderer` (mark the dead WebView, release it with `destroy()` only) and `RendererRecovery` (recreate the WebView after one crash; a second crash on the same content stops; a renderer killed for memory is always recreated). Viewer fallback after the second crash: the plain text of the message (extracted off the main thread, selectable). Editor fallback: a message instead of the editor, the text stays in `latestHtml` and is still sent/saved. Printer: the pending WebView is destroyed.
- **Remote content.** Images and fonts from the internet are blocked by default (tracking pixels): the CSP from `RemoteImages` allows only `data:`/`cid:`/`blob:` images unless remote content is allowed, and the WebView additionally sets `blockNetworkImage`/`blockNetworkLoads`. Allowed per message ("Show"), for trusted senders or always (setting).
- **CSP (defense-in-depth).** Both WebViews inject a per-load `Content-Security-Policy` meta with `script-src 'nonce-<random>'` (no `'unsafe-inline'`): the viewer (`HtmlBodyView`) and — since Stage-2 fix L-3 — the rich-text editor (`RichTextEditor`, whose own script is nonce-tagged; `evaluateJavascript`/`@JavascriptInterface` calls run outside CSP). This blocks injected inline event handlers (`onerror`/`onload`) and `javascript:` URIs even if a regex bypass slips through — including the editor **paste** path. The editor additionally sanitizes injected HTML twice: Kotlin `stripDangerousTags` (delegates to `sanitizeEmailHtml`, DRY) before `evaluateJavascript`, and a JS-side `sanitizeHtml` (detached-`div`, strips `on*`/`javascript:`) before `innerHTML`.

### Outbound MIME header sanitization

When building raw MIME for `SendMail`/`SmartForward` and meeting invitations, address header values (`From`/`To`/`Cc`/`Bcc`) pass through `stripHeaderCrlf` (a single helper in `EasXmlTemplates.kt` — DRY) which strips bare CR/LF. An injected CR/LF in a header body (RFC 5322 §2.2) would otherwise let a crafted value append arbitrary headers (`\r\nBcc: …`). The compose UI already normalizes recipients, but the protocol layer does not rely on that (defense-in-depth / DIP). The client no longer builds its own MDN message: Exchange sends read receipts itself (see `domain/ReadReceipts.kt`). Covered by `EasMimeHeaderSanitizeTest`.

`Subject` headers are RFC 2047 encoded-words and, when long, folded across multiple CRLF-space-separated words (`encodeMimeHeaderText`, sharing `mimeEncodedWord`/`chunkByUtf8Bytes` — a single encoder reused by all three send paths and the attachment-name parameter) so no header line exceeds the RFC 5322 998-octet limit; multi-octet UTF-8 characters are never split across words. Covered by `EasMimeSubjectEncodingTest` (N-3).

### Password storage

Passwords are persisted by `data/security/PasswordStorage` via `EncryptedSharedPreferences` (keys AES256-SIV, values AES256-GCM, master key in Android Keystore), created by `EncryptedStoreProvider`. When Keystore is unavailable or corrupted, the provider falls back to `ObfuscatedSharedPreferences`: values XOR-ed with a key derived by PBKDF2-HMAC-SHA256 (10 000 iterations, static salt) from `ANDROID_ID` — obfuscation, not encryption. The fallback is recorded by `SecurityTelemetry` and shown to the user once as a toast. A fail-closed mode (`SecurityTelemetry.setFailClosedMode`, refuses to save passwords to the fallback) exists in code but has no settings switch, so it is off. Password char arrays are zeroed (`fill('\u0000')`) after use in `HttpClientProvider`.

The app-lock password (`AppLockManager`) is stored only as a PBKDF2-HMAC-SHA256 hash (600 000 iterations, 16-byte random salt, constant-time comparison); fingerprint unlock uses `BiometricPrompt` (`BIOMETRIC_WEAK`), which is why `MainActivity` extends `FragmentActivity`.

---

## 9. Exchange 2007 SP1 compatibility

Exchange 2007 SP1 exposes EAS 12.1. The app keeps Exchange 2007 behavior as a first-class constraint:

| Feature | Main path | Fallback / detail |
|---------|-----------|-------------------|
| Mail sync/send/move/flag | EAS | EWS fallback for selected hard-delete/error paths |
| Folder sync/CRUD | EAS FolderSync | Serialized per account to protect SyncKey state |
| Folder content sync vs. local changes | EAS Sync | One Sync request per folder at a time (`FolderSyncGate`), SyncKey re-read from DB under the gate |
| Follow-up flags | EAS Sync Change `<Flag>` ([MS-ASEMAIL], EAS 12.0+) | Cleared flag = empty `<Flag/>`; dateless flag rejected with Status 6 → one retry with today's dates |
| Conversation threads | `email2:ConversationId` on EAS 14.0+ | Exchange 2007 does not send it: threads by subject (only if the thread has a reply/forward) |
| Read receipts | Server sends them on mark-read (EAS/EWS) | Decline: EWS `SuppressReadReceipt` before marking read |
| Category colors | Master Category List (Exchange 2010+) | Exchange 2007: standard Outlook names or deterministic color by name; manual override |
| Direct Push | EAS Ping | Adaptive heartbeat; honors server `HeartbeatInterval` on `Status=5` |
| Contacts | EAS + GAL search | Local contacts also supported |
| Notes | EWS on Exchange 2007 | EAS Notes class is 14.0+ |
| Tasks | EAS/EWS depending on version/path | EWS for Exchange 2007 task gaps |
| Calendar sync | EAS primary | EWS supplement for details/attachments/meeting paths |
| Calendar attachments | EWS CreateAttachment/GetAttachment | EWS ItemId, recurring master ItemId and RootItemChangeKey sequencing required |
| Calendar delete | EAS Sync Delete / EWS DeleteItem | SyncKey catch-up, server-confirmed local delete, DeclineItem/MeetingResponse for attendees |
| Drafts | EWS server drafts or local mode | Dual body for inline images |

Important compatibility rules captured in code:

- EWS outgoing SOAP uses declared namespace prefixes and `Exchange2007_SP1`.
- EWS response parsing must tolerate prefixed and unprefixed XML elements.
- Calendar EAS Sync chains must not force inconsistent `FilterType`.
- EAS operations that require current SyncKey perform catch-up/refresh before sending commands where needed.
- Meeting deletion distinguishes attendee/organizer/non-meeting paths to avoid Exchange Calendar Repair Assistant resurrection.
- Calendar permanent delete and trash cleanup must not remove local server-backed records until the server delete path succeeds.
- Recurring calendar attachments must use metadata references and EWS master ItemId resolution rather than duplicating file payloads per occurrence.
- A folder's Sync requests (sync loop and local flag/read changes) never run in parallel with the same SyncKey: they go through `FolderSyncGate`, otherwise the next request gets Status 3 and the folder is fully resynced.
- Mail flags (`eas/MailFlag.kt`, `EasEmailService.setFlag`): the whole `<Flag>` is written on every change because a missing child element is deleted by the server ([MS-ASEMAIL] implicit deletes); a cleared flag is sent as an empty `<Flag/>` ([MS-ASEMAIL] Flag: "SHOULD be included as an empty container element"); the four date elements (`tasks:StartDate`/`DueDate`/`UtcStartDate`/`UtcDueDate`) are sent all together or not at all. Some Exchange servers reject an active flag without dates with Sync Change Status 6; it is retried once with today's dates (`MailFlags.dueToday`, like Outlook's quick flag). The new SyncKey from the response is saved even when the Change itself is rejected (`FlagWrite.syncKey`: the server issues a new key and reports only failed changes in `Responses`).

---

## 10. Key design decisions

### Manual DI

The app uses `RepositoryProvider` instead of Hilt/Dagger: minimal dependency footprint, simple lifecycle/debugging, shared singleton repositories and centralized client creation in `AccountRepository`.

### Offline-first

UI reads Room/Flow. Network operations update local DB. This keeps the app usable with unstable Exchange connections and gives predictable UI state during long syncs.

### Service decomposition

Large protocol logic is split into specialized services: `EasTransport` for transport/provision/auth, feature services for mail/folders/calendar/tasks/notes/drafts/contacts/attachments, and repository-level services for sync and operations.

### Draft body model

Drafts with inline images use two body representations:

| Representation | Purpose |
|----------------|---------|
| `cid:` server body | Compatible with Outlook/Exchange rendering |
| `data:image/...base64` local body | Instant in-app display/editing |

Server mode stores drafts through EWS. Local mode stores drafts only in Room and is beta.

### Concurrency guards

- `SyncWorker` has a process-level mutex.
- Folder operations are serialized per account to protect SyncKey state.
- EAS Sync requests are serialized per folder (`FolderSyncGate`); optimistic flag writes are tracked by `FlagWriteTracker` (lock order: folder gate, then tracker).
- Calendar/task/note repositories use per-account sync locks; calendar delete/restore/permanent-delete/trash operations share the same lock as calendar sync.
- Notification display uses a shared mutex across push and worker paths.
- Glance widget updates are serialized through `widgetUpdateMutex`.

### UI state preservation across configuration changes

Compose screens keep user-editable state across rotation/process death and prevent loader effects from overwriting it:

- Editable fields use `rememberSaveable`. Large bodies use `createLargeStringSaver` (`ui/utils/LargeStateSaver.kt`): strings over 10 000 chars are written to a per-instance file in `cacheDir` (`compose_state_<tag>_<uuid>.tmp`) and only the path goes into the saved state. The whole Activity state is one Binder transaction with a ~1 MB process-wide buffer; larger state crashes the app on backgrounding with `TransactionTooLargeException`. The file is deleted on restore; files older than 24 h (screens closed without restore) are removed at app start (`MailApplication.cleanupStaleTempFiles`). Used by `ComposeScreen` (body, draft body), `CreateEventDialog` and `RichTextEditor`.
- One-shot loaders guard against re-running on configuration change with a `rememberSaveable` flag — e.g. `replyLoaded` / `forwardLoaded` / `signatureInitialized` (`ComposeScreen`), `datesInitialized` (`CreateEventDialog` / `CreateTaskDialog` in `TasksScreen`). `TasksScreen`/`NotesScreen` use `isInitialLoadDone` from their ViewModels instead of the former `dataLoaded` flag. The effect returns early when its flag is set.
- `RichTextEditor` restores from the freshest source (`controller.latestHtml`, persisted via the controller's `Saver`, large values file-backed) and reconciles the parent body on restore, so the value-driven update effect cannot clobber recovered content. The editor HTML is stored once in the saved state (the separate copy that duplicated it was removed).
- `RichTextEditor` sanitizes and chunks large HTML off the main thread (`setHtmlAsync`: `Dispatchers.Default`, chunks of 200 000 chars read by the page through `@JavascriptInterface` instead of one huge `evaluateJavascript`); a newer call discards an older result; `OutOfMemoryError` shows a message instead of crashing.
- WebView renderer death in the editor (`onRenderProcessGone`) recreates the WebView once; after the second crash the editor is replaced by a message, and the text stays in `controller.latestHtml` and is saved/sent from there.

### Coroutine exception handling (crash resistance)

`viewModelScope` uses `SupervisorJob` without a `CoroutineExceptionHandler` — any uncaught exception in a `viewModelScope.launch` block **crashes the app**. All ViewModel coroutine launches follow a mandatory defensive pattern:

```kotlin
viewModelScope.launch {
    _uiState.update { it.copy(isLoading = true) }
    try {
        val result = withContext(ioDispatcher) { repo.operation() }
        when (result) {
            is EasResult.Success -> sendEvent(Event.Success)
            is EasResult.Error -> sendEvent(Event.Error(result.message))
        }
    } catch (e: CancellationException) {
        throw e  // cooperative cancellation — must propagate
    } catch (e: Exception) {
        sendEvent(Event.Error(e.message ?: "Unknown error"))
    } finally {
        _uiState.update { it.copy(isLoading = false) }
    }
}
```

Rules:
- **`CancellationException` is always rethrown** — per Kotlin coroutines best practice, it must propagate for cooperative cancellation to work.
- **All other exceptions are caught** — `RuntimeException`, `SQLiteException`, `IllegalArgumentException` (e.g. from `AccountType.valueOf()`), network errors — and converted to UI-friendly `Error` events (toast) instead of crashing.
- **Loading flags are reset in `finally`** — `isDeleting`, `isMoving`, `isRestoring`, `isSendingMdn`, `isLoadingBody` — ensuring the UI never gets stuck in a loading state, regardless of success or failure.
- **Selection is cleared in `finally`** (batch operations in `EmailListViewModel`) — DRY: single call point instead of duplicating in both `try` and `catch`.

Covered ViewModels: **the ViewModels migrated in Stage 1** (fix M-1, 2026-07-01), listed below. `ScheduledMailViewModel` (added later) launches its operations in `viewModelScope` without this `try/catch` wrapper.
- `EmailDetailViewModel` (7 functions), `EmailListViewModel` (6 batch functions) — inline pattern.
- `NotesViewModel`, `SearchViewModel`, `TasksViewModel`, `UserFoldersViewModel` — the same inline pattern applied to **every** mutation function (`saveNote`/`deleteNoteToTrash`/`deleteSelectedToTrash`, `deleteSelected`/`markSelectedAsRead`/`starSelected`, `saveTask`/`toggleComplete`/`deleteToTrash`/`deleteSelectedToTrash`, `createFolder`/`renameFolder`/`deleteFolder`/`deleteSelectedFolders`). Loading/selection flags reset in `finally`.
- `SyncCleanupViewModel` — its `set*` operations are uniform fire-and-forget with **no Error channel**, so instead of duplicating `try/catch` 13× it uses a single private `launchSafe { }` helper (DRY/KISS) that wraps every `viewModelScope.launch` in the same `catch(CancellationException){throw}` / `catch(Exception){ Log.e }` guard.

All are covered by crash-resistance unit tests (`*ViewModelTest`) that verify a repository/settings exception produces an `Error` event (or is safely swallowed for `SyncCleanup`) instead of propagating and crashing the process.

**Legacy (non-MVVM) screens** that still launch work from a `rememberCoroutineScope()` in the Composable use `rememberSafeScope()` (`ComposableUtils`) instead — a crash-safe scope built on `SupervisorJob` (a failing operation does not cancel siblings) + a `CoroutineExceptionHandler` (an uncaught exception from a throwing suspend/DAO call is logged, not propagated to the process). Its lifecycle matches `rememberCoroutineScope` (the supervisor is a child of the base scope's `Job`, cancelled when the Composable leaves composition), and the `launch` bodies are unchanged. The `rememberSyncScope()` helper gained the same handler. This closes the M-1 crash class for screens not yet migrated to MVVM (UI-1, 2026-07-01).

The same guarantee applies to long-lived **non-UI** scopes: `MailApplication`, the push/sync services, alarm/boot receivers and repositories build their scopes via `supervisedScope(dispatcher, tag)` (`util/AppCoroutines.kt`) — `SupervisorJob` + a `CoroutineExceptionHandler` — instead of a bare `CoroutineScope(SupervisorJob() + dispatcher)`, so an uncaught error in one background task (including `OutOfMemoryError`, which `catch(Exception)` does not catch) is logged rather than crashing the process. There is no `GlobalScope`/`runBlocking` in the codebase.

**Config-change-aware `onDispose` cleanup.** Composables that tear down state in `onDispose` — `SetupScreen` deleting orphaned certificate files (SET-1), `AppNavigation` clearing `VerificationSecrets` on the verification route (SET-2), `AboutScreen`/`SearchScreen` stopping the easter-egg player — guard the teardown with `context.findActivity()?.isChangingConfigurations` (`ui/utils/ContextExtensions.kt`, one shared `tailrec` helper — the previous private copies were de-duplicated). `MainActivity` declares `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboard|keyboardHidden|navigation"`, so a rotation no longer recreates the Activity, but other changes (theme/`uiMode`, locale, font scale, density) still recreate it and dispose the composition; without the guard the cleanup would destroy resources that the `rememberSaveable` state still references after recreation, breaking verification/save. `findActivity()` (not a raw `as? Activity` cast) is required because `LocalContext.current` is often a `ContextThemeWrapper`, not the Activity itself — a direct cast returns null and silently defeats the guard. The check distinguishes a real screen-leave (tear down) from a rotation (keep).

`viewModelScope` itself carries a `SupervisorJob` but no `CoroutineExceptionHandler`, so migrated ViewModels guard every one-shot mutation with the inline `try/catch` pattern above; their long-lived reactive **observe** launches (which `collect` account/folder/email Room flows) additionally pass `loggingExceptionHandler(tag)` to `viewModelScope.launch(handler) { … }`, so a Room-flow error in the collector is logged instead of crashing the process (CR-2).

---

## 11. Current constraints

- OAuth 2.0 / Modern Auth is not implemented.
- Office 365 / Exchange Online is not a primary target without Basic Auth.
- IMAP/POP3 are beta and do not support Exchange groupware features.
- EAS 16.x is not the current optimization target.
- XML parsing is mixed: `EasXmlParser` (XmlPullParser, DOCTYPE disabled) is used by the calendar, contacts, email, notes, provisioning and sync-stream parsers; many EWS/SOAP and EAS paths still use regex/string extraction (see `docs/XMLPULLPARSER_MIGRATION_PLAN.md`).
- S/MIME signing/encryption is not implemented.

---

## 12. Build and resources

Recommended production build path is Android Studio with JDK 17. The command line examples below are supplemental and require a correctly configured `JAVA_HOME`.

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

On Windows:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

| Directory | Content |
|-----------|---------|
| `res/drawable/` | custom vector icons, file icons |
| `res/layout/` | widget preview/loading layouts |
| `res/raw/` | send/receive/delete sounds |
| `res/xml/` | backup rules, data extraction rules, FileProvider paths, widget info, network security config (app shortcuts are dynamic, `util/AppShortcuts.kt`) |
| `res/values/` | default strings/theme resources |
| `res/values-ru/` | Russian strings |

---

## 13. Documentation map

| File | Purpose |
|------|---------|
| `README.md` | Russian project overview |
| `README_EN.md` | English project overview |
| `docs/ARCHITECTURE.md` | This architecture document |
| `docs/CHANGELOG_RU.md` | Russian changelog |
| `docs/CHANGELOG_EN.md` | English changelog |
| `docs/PRIVACY_POLICY.md` | Privacy policy |
| `docs/SECURITY.md` | Security model / threat model |
| `SECURITY.md` | Vulnerability disclosure policy |
| `docs/XMLPULLPARSER_MIGRATION_PLAN.md` | Planned XML parser migration |
| `docs/COMPOSESCREEN_MVVM_PLAN.md` / `docs/COMPOSESCREEN_AUDIT.md` | ComposeScreen MVVM migration plan and audit |

---

## English summary

iwo Mail Client is an offline-first Android mail client focused on on-premise Microsoft Exchange, especially Exchange 2007 SP1. The app uses Jetpack Compose, Room, DataStore, WorkManager, OkHttp and Conscrypt. Exchange support is implemented through EAS/WBXML and EWS/SOAP with Basic/NTLM authentication. IMAP/POP3 support exists as a beta JavaMail path for basic mail reading and sync.

The architecture is layered as:

```text
Compose UI → Repositories → EAS/EWS/IMAP/POP3 protocol services → Room/DataStore/Network → Background services
```

Current main constraints are no OAuth/Modern Auth, beta IMAP/POP3, no S/MIME, and partly regex/string XML parsing pending the rest of the XmlPullParser migration.