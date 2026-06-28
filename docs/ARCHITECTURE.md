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
| UI | Jetpack Compose + Material 3 |
| DB | Room `MailDatabase` version `42`, schema export enabled |
| Runtime model | Offline-first: Room + Flow → UI, background sync updates DB |

Основной production-сценарий — on-premise Exchange, особенно Exchange 2007 SP1/SP2. IMAP/POP3 реализованы как beta-клиенты для базового чтения/синхронизации почты и не дают parity с Exchange-функциями.

---

## 2. Слои приложения

```text
MainActivity / MailApplication
  ├─ старт приложения, Conscrypt, notification channels, permissions
  ├─ обработка mailto/share/update/shortcut intents
  └─ Compose content + AppNavigation

UI Layer
  ├─ Jetpack Compose screens
  ├─ Navigation, Theme, Localization
  ├─ feature subpackages: calendar, compose, contacts, emaildetail
  └─ reusable components, animations, custom icons, rich text widgets

Repository Layer
  ├─ AccountRepository
  ├─ MailRepository
  ├─ CalendarRepository / TaskRepository / NoteRepository / ContactRepository
  ├─ SettingsRepository
  ├─ AccountServerHealthRepository
  └─ EmailSyncService / EmailOperationsService / FolderSyncService / AppFileCleanupService

Protocol Layer
  ├─ EasClient facade
  ├─ EasTransport: EAS WBXML + EWS SOAP + Provision
  ├─ EAS feature services: email, folders, contacts, calendar, tasks, notes, drafts, attachments
  ├─ EwsClient + NtlmAuthenticator
  └─ ImapClient / Pop3Client beta via JavaMail

Persistence / System Layer
  ├─ Room: accounts, folders, emails, attachments, contacts, groups, signatures, notes, calendar, tasks
  ├─ DataStore: UI settings, sync checkpoints, notification checkpoints, update settings
  ├─ HttpClientProvider: shared OkHttp, TLS, mTLS, certificate pinning
  ├─ FileProvider + MediaStore/Downloads integration
  └─ Glance widget + Android shortcuts/deep links

Background Layer
  ├─ PushService: EAS Ping Direct Push foreground service
  ├─ SyncWorker: periodic/manual sync, cleanup, notification pass
  ├─ OutboxWorker: offline outbox send
  ├─ CalendarReminderReceiver / TaskReminderReceiver / reschedule worker
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

Следующий кандидат — тяжёлый `ComposeScreen`.

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
│   │   ├── SetupScreen.kt / VerificationScreen.kt / OnboardingScreen.kt
│   │   ├── EmailListScreen.kt / EmailDetailScreen.kt / ComposeScreen.kt
│   │   ├── ContactsScreen.kt / CalendarScreen.kt / NotesScreen.kt / TasksScreen.kt
│   │   ├── SettingsScreen.kt / AccountSettingsScreen.kt / PersonalizationScreen.kt
│   │   ├── SyncCleanupScreen.kt / UserFoldersScreen.kt / UpdatesScreen.kt / AboutScreen.kt
│   │   ├── ScheduleSendDialog.kt / ScheduledEmailWorker.kt / SearchScreen.kt
│   │   ├── calendar/ — AgendaView, MonthView, event dialogs, attachments, selection top bar
│   │   ├── compose/ — compose models and text/html helpers
│   │   ├── contacts/ — contact dialogs, list views and utilities
│   │   └── emaildetail/ — attachment section and action state holder
│   ├── components/
│   ├── theme/
│   └── utils/
│
├── data/
│   ├── database/ — Room database, entities, DAOs, notification projections
│   ├── model/ — server health model
│   └── repository/ — repositories, sync/operation services, provider, caches, helpers
│
├── eas/ — EAS/EWS protocol layer and Exchange feature services
├── imap/ImapClient.kt
├── pop3/Pop3Client.kt
├── shared/ — JavaMail shared interfaces/parsers/converters
├── network/ — HttpClientProvider, NetworkMonitor
├── sync/ — workers, receivers, PushService, notifications and reminders
├── update/UpdateChecker.kt
├── util/ — date/html/email/mime/ics/sound/deleted-id utilities
└── widget/ — Glance widget and config activity
```

---

## 4. Данные и хранилище

### Room

`MailDatabase` содержит 10 entity-классов и 11 DAO/accessors:

- **AccountEntity:** тип аккаунта, URL, alternate URL, sync mode, certificate metadata, draft mode, per-account intervals.
- **FolderEntity:** Exchange/IMAP/POP3 folders and per-folder SyncKey.
- **EmailEntity:** message metadata/body, flags, attachments marker, MDN fields, meeting message class, Internet Message-ID.
- **AttachmentEntity:** email attachments, local download state, inline CID metadata.
- **ContactEntity / ContactGroupEntity:** local and Exchange contacts, groups, favorites and usage counters.
- **SignatureEntity:** HTML signatures.
- **NoteEntity:** Exchange notes with local trash flag.
- **CalendarEventEntity:** calendar events, meetings, recurrence fields, exceptions, attachments JSON, online links, meeting request IDs.
- **TaskEntity:** task fields, deadlines, priority, reminders, owner/assignee and local trash flag.

Current DB version is **42**. Migrations currently cover `23→42`; missing migration fallback recreates DB and triggers full resync behavior.

Widget hot paths use dedicated lightweight projections instead of full entities:

| Use case | DAO projection / query shape |
|----------|------------------------------|
| Recent unread Inbox emails | `WidgetRecentEmailSummary` without `EmailEntity.body` |
| Current/upcoming calendar event | `WidgetCalendarEventSummary` without body/attendees/attachments JSON |
| Next task title | `WidgetTaskSummary` without task body |

Room v42 adds indexes for these home-screen widget paths: `emails(read, dateReceived)`, `folders(type)`, `tasks(complete, isDeleted, dueDate, subject)`, `calendar_events(isDeleted, startTime, endTime)` and `calendar_events(isDeleted, endTime, startTime)`.

### DataStore

`SettingsRepository` stores UI preferences, default draft mode, update-check settings, sync checkpoints, notification checkpoints and per-account cleanup/non-email sync timestamps.

### Secure storage

`AccountRepository` stores passwords in `EncryptedSharedPreferences`. If Android Keystore is unavailable or corrupted, it falls back to obfuscated SharedPreferences instead of plaintext.

---

## 5. Exchange protocol architecture

### EasClient

`EasClient` is the facade used by repositories. It owns stable `DeviceId`, shared OkHttp client, `EasTransport`, `EasVersionDetector`, EAS feature services and `EwsClient`.

### EasTransport

`EasTransport` is responsible for WBXML generation/parsing, `PolicyKey` handling, HTTP `449` Provision retry, EWS execution with Basic Auth first and NTLM fallback, and shared error normalization.

### EWS

EWS requests use SOAP and `RequestServerVersion="Exchange2007_SP1"` by default. EWS is used where EAS 12.x is incomplete or unreliable: notes, many task paths, server drafts, calendar details/meetings/exceptions/attachments and selected fallback deletes.

### IMAP/POP3 beta

`ImapClient` and `Pop3Client` implement `shared.MailClient` via JavaMail. IMAP supports folders and message flags. POP3 is INBOX-only and cannot preserve server read/flag state. Full SMTP parity is not implemented as a separate production layer.

---

## 6. Основные runtime flows

### Startup

1. `MailApplication.onCreate()` installs Conscrypt.
2. Notification channels are created for mail, sync, reminders, outbox, scheduled send and updates.
3. Stale temp files, duplicate DB records and old attachments are cleaned.
4. Periodic sync is scheduled.
5. `PushService` is started when at least one Exchange account is in Push mode.
6. `MainActivity` handles permissions and external intents, then starts Compose navigation.

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
4. `EmailOperationsService` handles move, delete, permanent delete, mark read, flag and MDN flows.
5. `OutboxWorker` retries offline outgoing messages when network returns.

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

### Home-screen widget

1. `MailWidget` is a Glance `GlanceAppWidget` with `SizeMode.Responsive` to avoid launcher crashes caused by exact size-map RemoteViews on problematic ROMs.
2. Widget data is loaded on `Dispatchers.IO` from Room and DataStore using `applicationContext`.
3. Recent mail, next task and calendar event queries use widget-specific projections and indexes to avoid loading heavy email bodies, task bodies, attendees or calendar attachment JSON.
4. `updateMailWidget(context)` serializes `MailWidget().updateAll()` through a process-level mutex so sync, notification, account and personalization paths cannot run concurrent widget updates.
5. Widget clicks route through `MainActivity` deep links/extras; manual sync uses `SyncAlarmReceiver.ACTION_SYNC_NOW` and is delegated to `SyncWorker`.

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
| `PushService` | Foreground Direct Push service, adaptive EAS Ping heartbeat, per-account jobs |
| `SyncWorker` | Periodic/manual sync, notification pass, auto-cleanup, PushService health check |
| `OutboxWorker` | Offline outbox send |
| `PushRestartWorker` | Push restart after failures |
| `BootReceiver` | Start sync/push after reboot or package replacement |
| `SyncAlarmReceiver` | AlarmManager fallback for aggressive OEM ROMs |
| `ServiceWatchdogReceiver` | PushService watchdog on screen/user/power events |
| `MailNotificationActionReceiver` | Notification action handling for mail |
| `CalendarReminderReceiver` | Calendar reminder actions |
| `TaskReminderReceiver` | Task reminder actions |
| `MarkEmailReadWorker` | Unique worker to mark email as read |
| `MarkTaskCompleteWorker` | Worker to complete tasks from notifications |
| `RescheduleRemindersWorker` | Rebuild reminder alarms after reboot/update |

---

## 8. Security and network

`HttpClientProvider` centralizes OkHttp creation to avoid connection/thread pool leaks.

Supported network/security paths:

- normal system trust;
- accept-all mode for legacy/self-signed environments;
- custom server certificate;
- PKCS#12 client certificate with mTLS;
- certificate pinning with saved SHA-256 and metadata;
- Conscrypt provider for legacy TLS compatibility.

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
- `EmailDetailScreen` loads sanitized HTML with `loadDataWithBaseURL(null, ...)`. Null-baseURL blocks cross-origin requests, cookie/localStorage exfiltration and service workers, complementing the tag-level blocklist.

### Password storage

Passwords are persisted via `EncryptedSharedPreferences` (AES256-GCM / AES256-SIV with a master key stored in Android Keystore). When Keystore is unavailable or corrupted, `AccountRepository` falls back to an obfuscated `SharedPreferences` store instead of plaintext, and password char arrays are zeroed (`fill('\u0000')`) after use in `HttpClientProvider`.

---

## 9. Exchange 2007 SP1 compatibility

Exchange 2007 SP1 exposes EAS 12.1. The app keeps Exchange 2007 behavior as a first-class constraint:

| Feature | Main path | Fallback / detail |
|---------|-----------|-------------------|
| Mail sync/send/move/flag | EAS | EWS fallback for selected hard-delete/error paths |
| Folder sync/CRUD | EAS FolderSync | Serialized per account to protect SyncKey state |
| Direct Push | EAS Ping | Adaptive heartbeat |
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
- Calendar/task/note repositories use per-account sync locks; calendar delete/restore/permanent-delete/trash operations share the same lock as calendar sync.
- Notification display uses a shared mutex across push and worker paths.
- Glance widget updates are serialized through `widgetUpdateMutex`.

### UI state preservation across configuration changes

Compose screens keep user-editable state across rotation/process death and prevent loader effects from overwriting it:

- Editable fields use `rememberSaveable` (large bodies use file-backed savers).
- One-shot loaders guard against re-running on configuration change with a `rememberSaveable` flag — e.g. `replyLoaded` / `forwardLoaded` / `signatureInitialized` (`ComposeScreen`), `datesInitialized` (`CreateEventDialog` / `CreateTaskDialog`), `dataLoaded` (`TasksScreen` / `NotesScreen`). The effect returns early when its flag is set.
- `RichTextEditor` restores from the freshest source (`controller.latestHtml`, persisted via a `Saver`) and reconciles the parent body on restore, so the value-driven update effect cannot clobber recovered content.

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

Covered ViewModels: `EmailDetailViewModel` (7 functions: `openEmail`, `deleteToTrash`, `move`, `restore`, `markUnread`, `sendMdn`, `dismissMdn`), `EmailListViewModel` (6 batch functions: `deleteSelectedToTrash`, `deleteSelectedDrafts`, `markSelectedAsRead`, `moveSelectedTo`, `restoreSelected`, `moveSelectedToSpam`). Both are covered by crash-resistance unit tests (`EmailDetailViewModelTest`, `EmailListViewModelTest`) that verify exceptions from repository/actions calls produce `Error` events instead of propagating.

---

## 11. Current constraints

- OAuth 2.0 / Modern Auth is not implemented.
- Office 365 / Exchange Online is not a primary target without Basic Auth.
- IMAP/POP3 are beta and do not support Exchange groupware features.
- EAS 16.x is not the current optimization target.
- XML parsing is still largely regex/string based; `EasXmlParser.kt` exists for planned XmlPullParser migration.
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
| `res/xml/` | backup rules, FileProvider paths, widget info, shortcuts, network security config |
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
| `docs/XMLPULLPARSER_MIGRATION_PLAN.md` | Planned XML parser migration |
| `docs/EXCHANGE_DELETE_OPTIMIZATION.md` | Exchange delete optimization notes |
| `docs/PERFORMANCE_AUDIT.md` | Performance audit notes |

---

## English summary

iwo Mail Client is an offline-first Android mail client focused on on-premise Microsoft Exchange, especially Exchange 2007 SP1. The app uses Jetpack Compose, Room, DataStore, WorkManager, OkHttp and Conscrypt. Exchange support is implemented through EAS/WBXML and EWS/SOAP with Basic/NTLM authentication. IMAP/POP3 support exists as a beta JavaMail path for basic mail reading and sync.

The architecture is layered as:

```text
Compose UI → Repositories → EAS/EWS/IMAP/POP3 protocol services → Room/DataStore/Network → Background services
```

Current main constraints are no OAuth/Modern Auth, beta IMAP/POP3, no S/MIME, and regex/string XML parsing pending a planned XmlPullParser migration.