[Русская версия](ARCHITECTURE.md)

# Project Architecture

**iwo Mail Client** is an Android client for Microsoft Exchange (EAS/EWS) with beta IMAP/POP3 support.

**Version:** 1.6.3b
**Package:** `com.dedovmosol.iwomail`
**Gradle root:** `IwoMailClient`
**Android module:** `:app`

---

## 1. Project state v1.6.3b

| Area | Value |
|------|-------|
| Application ID / namespace | `com.dedovmosol.iwomail` |
| Version | `versionName = 1.6.3b`, `versionCode = 26` |
| SDK | `minSdk = 26`, `compileSdk = 36`, `targetSdk = 36` |
| JVM target | Java/Kotlin `17` |
| Kotlin | `1.9.22` (Compose compiler extension `1.5.8`) |
| AGP | `8.7.3` |
| Build tooling | Gradle `8.12`, KSP `1.9.22-1.0.17`, Kover `0.7.6` |
| UI | Jetpack Compose (BOM `2024.06.00`) + Material 3, Navigation Compose `2.7.6`, Coil `2.5.0` |
| DB | Room `2.6.1`, `MailDatabase` version `52` (13 entities), schema export enabled (`app/schemas`) |
| Network and background | OkHttp `4.12.0`, Conscrypt `2.5.2`, WorkManager `2.9.0`, DataStore `1.1.1`, Glance `1.1.1`, JavaMail (`android-mail`) `1.6.7` |
| Security | `security-crypto` `1.1.0-alpha06` (EncryptedSharedPreferences), `biometric` `1.1.0` |
| APK architectures | `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` + universal APK |
| Tests | Unit tests in `app/src/test`: 184 `.kt` files, 1601 `@Test` annotations (counted in the sources); there is no `androidTest` directory |
| Published release | GitHub Releases → `1.6.1` (`versionCode` 24); `1.6.3b` is a beta build |
| Runtime model | Offline-first: Room + Flow → UI, background sync updates the DB |

The main production scenario is on-premise Exchange, especially Exchange 2007 SP1/SP2. IMAP/POP3 are implemented as beta clients for reading (folder list and the latest messages; IMAP can also create, rename and delete folders) and do not reach parity with Exchange features.

---

## 2. Application layers

```text
MainActivity (FragmentActivity) / MailApplication
  ├─ app start, Conscrypt, app lock (AppLockManager), notification channels, permissions
  ├─ handling of mailto/share intents and iwomail://email, iwomail://widget deep links
  └─ Compose content + AppNavigation; LockScreen on top of navigation while the app is locked

UI layer
  ├─ Jetpack Compose screens
  ├─ navigation, theme, localization
  ├─ screen subpackages: calendar, compose, contacts, emaildetail, emaillist
  └─ reusable components, animations, custom icons, rich-text widgets

Domain (domain/)
  └─ logic without UI: drafts, sending, conversation threads, flags, unsubscribe, read receipts, mail settings

Repository layer
  ├─ AccountRepository
  ├─ MailRepository
  ├─ CalendarRepository / TaskRepository / NoteRepository / ContactRepository / CategoryColorRepository
  ├─ SettingsRepository
  ├─ AccountServerHealthRepository
  ├─ EmailSyncService / EmailOperationsService / FolderSyncService / AppFileCleanupService
  └─ FolderSyncGate (one EAS Sync per folder) / FlagWriteTracker (optimistic flag writes) / PendingMailChanges (offline change queue)

Protocol layer
  ├─ EasClient facade
  ├─ EasTransport: EAS WBXML + EWS SOAP + Provision
  ├─ EAS feature services: mail, folders, contacts, calendar, tasks, notes, drafts, attachments
  ├─ EwsClient + NtlmAuthenticator
  └─ ImapClient / Pop3Client (beta, JavaMail)

Persistence / system layer
  ├─ Room: accounts, folders, emails, attachments, contacts, groups, signatures, notes, calendar, tasks, category colors, email change queue
  ├─ DataStore: UI settings, sync checkpoints, notification checkpoints, update settings
  ├─ data/security: PasswordStorage, EncryptedStoreProvider, AppLockManager, SecurityTelemetry
  ├─ HttpClientProvider: shared OkHttp, TLS, mTLS, certificate pinning, Rfc6125HostnameVerifier
  ├─ FileProvider + MediaStore/Downloads integration
  └─ Glance widget + dynamic Android shortcuts and deep links

Background layer
  ├─ PushService: EAS Ping (Direct Push) foreground service
  ├─ SyncWorker: periodic and manual sync, cleanup, notification check
  ├─ OutboxWorker: offline outbox send
  ├─ PendingMailChangesWorker: sends stars and read state changed without a connection
  ├─ ScheduledSendReceiver + ScheduledEmailWorker: scheduled send
  ├─ CalendarReminderReceiver / TaskReminderReceiver / FlagReminderReceiver / RescheduleRemindersWorker
  └─ BootReceiver / SyncAlarmReceiver / PushRestartWorker / ServiceWatchdogReceiver
```

---

## 2.1. UI state model (MVVM, introduced incrementally)

Historically the screens keep state directly in `@Composable` functions through `remember`/`rememberSaveable`/`LaunchedEffect`, without a ViewModel. This needs manual guards against recreation on rotation (`*Loaded`/`*Initialized` flags) and mixes presentation logic with rendering.

On top of that, a layer of **`androidx.lifecycle.ViewModel` + immutable `UiState` + `StateFlow`** is being introduced screen by screen. It touches only the presentation layer: the EAS/EWS protocol and Exchange 2007 SP1/SP2 compatibility do not change.

```text
Composable ──collectAsStateWithLifecycle──> StateFlow<UiState>  (ViewModel)
Composable ──event calls (vm.setX())──────> ViewModel ──> Repository (RepositoryProvider)
```

Principles:
- **UiState** is a `data class` with immutable fields and is the single source of data for the screen.
- **A ViewModel** survives rotation: state is kept without manual flags and extra DB reads.
- Coroutines run in `viewModelScope` (it outlives the composition), so DB writes and network operations are not cancelled by rotation.
- Dependencies come from `RepositoryProvider` through a `ViewModelProvider.Factory` (`provideFactory`); navigation arguments are passed to the factory. `RepositoryProvider` remains the DI mechanism.
- **One-shot events** (toasts, sound, navigation, easter egg) are not part of the state. They are sent through `Channel(Channel.BUFFERED)` → `receiveAsFlow()`, which the UI collects in a `LaunchedEffect`. A ViewModel does not depend on language or resources: it emits a semantic event and the UI localizes it.
- **Testability.** Repositories and the `CoroutineDispatcher` are passed through the constructor; the factory supplies the real implementations and a unit test supplies mocks. The system effects of `SyncCleanupViewModel` (push / sync rescheduling) are hidden behind the `SyncEffects` interface, so the tests run on JUnit + MockK.

**Wired to screens** (all ViewModels extend `ViewModel` and are created with `viewModel(factory = …)`):

- `SyncCleanupScreen` ↔ `SyncCleanupViewModel`/`SyncCleanupUiState` — account sync and cleanup settings. Dependencies: `AccountRepository` + `SettingsRepository` + `SyncEffects`. The `set*` operations are fire-and-forget. Test: `SyncCleanupViewModelTest`.
- `SearchScreen` ↔ `SearchViewModel`/`SearchUiState` + `SearchEvent` — search, selection, batch operations (delete, mark read, star). A query of 2+ characters is started from the UI after a 300 ms delay (`LaunchedEffect(query)`); the ViewModel cancels the previous search. Results live in the ViewModel and survive rotation; after process death the search is reset. Test: `SearchViewModelTest`.
- `NotesScreen` ↔ `NotesViewModel`/`NotesUiState` + `NotesEvent` — notes, trash, selection, search, auto-sync with throttling of the trash sync. The data source is reactive: `collectLatest(activeAccount)` + `combine`; the `isInitialLoadDone` flag lives in the state. Permanent delete, restore and empty-trash with progress are thin `suspend` functions of the ViewModel called by `DeletionController` (`ui/components/DeletionProgressBar.kt`). Test: `NotesViewModelTest`.
- `TasksScreen` ↔ `TasksViewModel`/`TasksUiState` + `TasksEvent` — tasks, trash, the `TaskFilter` filter (7 values: `ALL`, `TODAY`, `ACTIVE`, `COMPLETED`, `HIGH_PRIORITY`, `OVERDUE`, `DELETED`), two independent selection sets, search, auto-sync. `TaskRepository` only provides single `delete/restore/permanent` calls, so the batch `restoreTasks`/`deleteTasksPermanently` (with `onProgress`) loop inside the ViewModel. `initialFilter` is passed to the factory. Test: `TasksViewModelTest`.
- `UserFoldersScreen` ↔ `UserFoldersViewModel`/`UserFoldersUiState` + `UserFoldersEvent` — user folders (the list contains only EAS types `1` and `12`, sorted by name), selection, create, rename, delete and batch delete with progress in the state. `MailRepository` only provides single folder operations (`FolderSyncService` serializes them per account), so batch delete is a sequential loop in the ViewModel. Operations run in `viewModelScope` and survive rotation. Test: `UserFoldersViewModelTest`.
- `EmailListScreen` ↔ `EmailListViewModel`/`EmailListUiState` + `EmailListEvent` — the message list of a folder, of "Favorites" (`favorites`) and of "Today" (`TODAY_ALL`), filters (`MailFilter` with 5 values and `EmailDateFilter` with 5 values), selection, sync and batch operations: move to trash, move, restore, move to spam, mark read, flags, delete drafts, permanent delete. The current folder and the folder list for the move dialog come from one reactive source (`collectLatest(activeAccount)` + `combine`). Auto-sync runs only for "Drafts" and once per ViewModel instance (the `draftsSynced` flag). The network check (`NetworkMonitor`), the delete sound and message localization stay in the UI. Test: `EmailListViewModelTest`.
- `EmailDetailScreen` ↔ `EmailDetailViewModel`/`EmailDetailUiState` + `EmailDetailEvent` (the screen **core** is in the ViewModel) — message, attachments and folders in one `collectLatest(activeAccount)` + `combine` stream, from which the current-folder attributes (trash, sent, drafts) are derived. Inline images are loaded reactively and kept in the state, so rotation does not trigger a reload. Operations (refresh, move to trash, move, restore, mark unread, flag, answering and postponing the read-receipt request, unsubscribe, saving `.eml`, permanent delete) run in `viewModelScope`; business logic is delegated to `EmailDetailActions` (`ui/screens/emaildetail/`). The Composable keeps the WebView and body rendering, attachment launchers and file I/O, iCal parsing and meeting invitations. Test: `EmailDetailViewModelTest`.
- `ScheduledMailScreen` ↔ `ScheduledMailViewModel` + `ScheduledMailEvent` — the list of scheduled messages (a `StateFlow` built from `ScheduledSend.pending` and refreshed on `ScheduledSend.changes`), "send now", reschedule and cancel in `viewModelScope`. Receives `Application` and a `CoroutineDispatcher`. Test: `ScheduledMailViewModelTest` (Robolectric).

**Not wired:** `ComposeViewModel` + `ComposeUiState`/`ComposeEvent` (`ui/screens/compose/`) exists and is covered by `ComposeViewModelTest`, but `ComposeScreen` does not use it (there are no references to it in `ComposeScreen.kt` or `AppNavigation.kt`): the compose screen keeps its state in the Composable.

Dependencies: `lifecycle-viewmodel-ktx`, `lifecycle-viewmodel-compose`, `lifecycle-runtime-compose` (`2.7.0`).

---

## 3. Package structure

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
│   │   ├── SetupScreen.kt / VerificationScreen.kt / OnboardingScreen.kt / OnboardingPageEnter.kt / AddAnotherAccountScreen.kt
│   │   ├── LockScreen.kt — unlock with password/fingerprint
│   │   ├── EmailListScreen.kt / EmailDetailScreen.kt / ComposeScreen.kt / ComposeUtils.kt
│   │   ├── ContactsScreen.kt / CalendarScreen.kt / NotesScreen.kt / TasksScreen.kt
│   │   ├── SettingsScreen.kt (settings root) / SettingsScaffold.kt
│   │   ├── SettingsAccountsScreen.kt / SettingsMailScreen.kt (mail + notifications) / SettingsSecurityScreen.kt
│   │   ├── MailSettingsSection.kt / MailAlertsSettingsSection.kt
│   │   ├── AccountSettingsScreen.kt / PersonalizationScreen.kt / SyncCleanupScreen.kt
│   │   ├── UserFoldersScreen.kt / UpdatesScreen.kt / AboutScreen.kt / SearchScreen.kt
│   │   ├── ScheduleSendDialog.kt / ScheduleSendTimes.kt / ScheduledMailScreen.kt / ScheduledEmailWorker.kt
│   │   ├── *ViewModel.kt — EmailList, EmailDetail, Notes, Tasks, Search, SyncCleanup, UserFolders, ScheduledMail
│   │   ├── calendar/ — AgendaView, MonthView, event dialogs, attachments, category colors UI, selection top bar
│   │   ├── compose/ — ComposeViewModel/UiState/Event (not wired to ComposeScreen), models, text/HTML helpers, embedded attachments
│   │   ├── contacts/ — contact dialogs, list views and utilities
│   │   ├── emaildetail/ — attachments section, inline attachments, EmailDetailActions
│   │   └── emaillist/ — SwipeableEmailRow, SwipeRules, ThreadRows, row names, image previews
│   ├── components/ — HtmlBodyView, RichTextEditor, SendController, FlagMenu, EmailPrinter, AppPermissionDialog,
│   │                 WebViewRenderer (+ RendererRecovery), TouchDirectionLock, banners, dialogs, DeletionProgressBar
│   ├── theme/ — Theme, AppIcons, CustomTextToolbar
│   └── utils/ — animations, avatar colors, date formatting, ContextExtensions, LargeStateSaver, AppHaptics
│
├── domain/ — AttachmentLoader, DraftSaver, EditorInlineBudget, EmailThreads, FlagPresets, MailAlerts, MailPreferences,
│             OutgoingMail, ReadReceipts, RepositoryDraftStore, SendFailures, SentFolder, Unsubscribe
│
├── data/
│   ├── database/ — MailDatabase (Room, entities, migrations), DAOs (Daos.kt, *Dao.kt), EmailFlags.kt,
│   │               PendingEmailChangeEntity.kt (offline email change queue), PendingPimChangeEntity.kt (offline task and
│   │               note change queue), NotificationEmailSummary.kt
│   ├── model/ — AccountServerHealth.kt (server health state)
│   ├── security/ — PasswordStorage, EncryptedStoreProvider, AppLockManager, SecurityTelemetry
│   └── repository/ — repositories, sync and operation services, RepositoryProvider, caches, helpers
│                     (incl. FolderSyncGate.kt, FlagWriteTracker.kt, PendingMailChanges.kt, CategoryColorRepository.kt,
│                     RepositoryErrors.kt, EndpointClientCache.kt, CertificatePins.kt)
│
├── eas/ — EAS/EWS protocol layer and Exchange feature services (incl. MailFlag.kt, WbxmlParser.kt, ListUnsubscribe.kt, RssItems.kt)
├── imap/ImapClient.kt
├── pop3/Pop3Client.kt
├── shared/ — shared JavaMail interfaces, parsers and converters (MailClient, MailMessageParser, MessageToEntityConverter)
├── smtp/ — empty directory without sources (there is no SMTP transport in the code)
├── network/ — HttpClientProvider, NetworkMonitor, ConnectionFailure, Rfc6125HostnameVerifier
├── sync/ — PushService, workers, receivers, notifications, reminders:
│           AppForegroundTracker, BackgroundWork, InitialSyncController, PingFailurePolicy, PushRestartPolicy,
│           ScheduledSend/ScheduledSendReceiver, FlagReminderReceiver, PendingMailChangesWorker, ServerHealthNotifier,
│           MailNotificationChannels, SendNotifications, NotificationHelper, SyncHelper
├── update/UpdateChecker.kt
├── util/ — date/HTML/email/MIME/ICS/sound/deleted-id utilities, RemoteImages, EmlFiles,
│           EmailPrintDocument, AppShortcuts, AppCoroutines
└── widget/ — Glance widget (MailWidget, MailWidgetReceiver) and WidgetConfigActivity
```

---

## 4. Data and storage

### Room

`MailDatabase` contains 13 entity classes and 14 abstract DAO accessors (`accountDao`, `emailDao`, `folderDao`, `signatureDao`, `attachmentDao`, `contactDao`, `contactGroupDao`, `noteDao`, `calendarEventDao`, `taskDao`, `syncDao`, `categoryColorDao`, `pendingEmailChangeDao`, `pendingPimChangeDao`):

- **AccountEntity:** account type, URL and alternate URL, sync mode, certificate metadata (a separate pin for the alternate URL), draft mode, per-account sync and cleanup intervals.
- **FolderEntity:** Exchange/IMAP/POP3 folders and the SyncKey of each folder.
- **EmailEntity:** message metadata and body, read/importance, attachments marker, MDN fields, meeting message class, Internet Message-ID, draft `bcc` and `replacesServerId` (a draft that replaces a server draft edited offline), follow-up flag (`flagged`, `flagCompleted`, `flagStartDate`, `flagDueDate`, `flagReminder`), `listUnsubscribe`/`listUnsubscribeOneClick`, draft `requestReadReceipt`/`requestDeliveryReceipt`, `conversationId` (EAS 14.0+).
- **AttachmentEntity:** message attachments, local download state, inline CID metadata.
- **ContactEntity / ContactGroupEntity:** local and Exchange contacts, groups, favorites, usage counters.
- **SignatureEntity:** HTML signatures.
- **NoteEntity:** Exchange notes with a local trash flag.
- **CalendarEventEntity:** calendar events, meetings, recurrence fields, exceptions, attachments JSON, online links, meeting request IDs.
- **TaskEntity:** task fields, deadlines, priority, reminders, owner/assignee, local trash flag.
- **CategoryColorEntity** (`category_colors`, primary key `accountId` + `nameKey`): per-account Outlook category colors (`serverColor` from the Master Category List on Exchange 2010+, `userColor` set in the app).
- **PendingPimChangeEntity** (`pending_pim_changes`, DB version 51): offline task and note changes — `pendingUpdate` and/or `pendingTrash`, `kind` (TASK/NOTE), `serverSubject`, `version`. The only foreign key is to `accounts` (cascade), for the same reason as the email queue.
- **PendingEmailChangeEntity** (`pending_email_changes`, DB version 50): email changes made without a connection that the server has not accepted yet — `flag` and/or `read`. The value itself is not stored: the current one from `emails` is sent, so several offline edits go out as one result. There is deliberately no foreign key to `emails`: some paths rewrite messages with REPLACE (DELETE + INSERT), and a cascade would drop the pending edit. The foreign key to `accounts` uses CASCADE. `INSERT OR REPLACE … COALESCE` is used instead of UPSERT because SQLite on Android 8 is version 3.18 and has no UPSERT.

The current DB version is **52**. The registered migrations cover `23→52` (`MailDatabase.ALL_MIGRATIONS`, 29 migrations, one per version). A missing upgrade migration recreates the DB only in **debug** builds (`fallbackToDestructiveMigration()` under `BuildConfig.DEBUG`); in release it fails explicitly when the DB is opened. A downgrade (installing an older APK) always recreates the DB (`fallbackToDestructiveMigrationOnDowngrade()`), and server data is resynced.

Widget hot paths use dedicated lightweight projections instead of full entities:

| Use case | DAO projection / query shape |
|----------|------------------------------|
| Recent unread Inbox messages | `WidgetRecentEmailSummary` without `EmailEntity.body` |
| Current/upcoming calendar event | `WidgetCalendarEventSummary` without body, attendees and attachments JSON |
| Next task title | `WidgetTaskSummary` without the task body |

Migration 41→42 (DB version 42) added indexes for the home-screen widget paths: `emails(read, dateReceived)`, `folders(type)`, `tasks(complete, isDeleted, dueDate, subject)`, `calendar_events(isDeleted, startTime, endTime)` and `calendar_events(isDeleted, endTime, startTime)`.

### DataStore

`SettingsRepository` stores UI preferences, the default draft mode, update-check settings, sync checkpoints, notification checkpoints and per-account cleanup and non-email sync timestamps.

### Secure storage

`AccountRepository` stores passwords through `data/security/PasswordStorage`, which gets its store from `EncryptedStoreProvider`: `EncryptedSharedPreferences` by default. If Android Keystore is unavailable or corrupted, it falls back to `ObfuscatedSharedPreferences` (XOR with a PBKDF2-derived key, see section 8) instead of plaintext. The app-lock password hash (`AppLockManager`) uses the same provider.

---

## 5. Exchange protocol architecture

### EasClient

`EasClient` is the facade used by the repositories. It owns the stable `DeviceId` (derived from the user name and the account suffix), the shared OkHttp client, `EasTransport`, `EasVersionDetector`, the EAS feature services and `EwsClient`.

### EasTransport

`EasTransport` is responsible for WBXML generation and parsing, `PolicyKey` handling, the retry after HTTP `449` through Provision (two-phase, under `provisionMutex`), EWS execution (Basic first, then NTLM) and shared error normalization.

`EasVersionDetector` picks the highest common version from `14.1`, `14.0`, `12.1`, `12.0`; Exchange 2007 SP1 reports `2.5, 12.0, 12.1`, so `12.1` is selected for it.

### EWS

EWS requests use SOAP and `RequestServerVersion="Exchange2007_SP1"` by default (`EwsClient.VERSION_2007`). EWS is used where EAS 12.x is incomplete or unreliable: notes, many task paths, server drafts, calendar details/meetings/exceptions/attachments and selected fallback deletes.

The NTLM handshake (Type1 → Type2 → Type3) runs on a dedicated OkHttp client with `ConnectionPool(1)` and is serialized by a lock (`ntlmLock`): Type1 and Type3 share one TCP connection and cannot be interleaved with parallel EAS/EWS traffic, because NTLM authenticates the connection, not the request.

MIME body extraction (`MimeHtmlProcessor.extractHtmlFromMime`) splits the message by multipart boundaries per RFC 2046 and recurses into nested parts; a regex fallback covers single-part MIME.

### IMAP/POP3 (beta)

`ImapClient` and `Pop3Client` implement `shared.MailClient` (`connect`, `disconnect`, `getFolders`, `getEmails`) through JavaMail. Folders are opened read-only (`Folder.READ_ONLY`) and the latest `limit` messages are read. IMAP takes read/star state from the `SEEN`/`FLAGGED` flags and can also create, rename and delete folders. POP3 works with INBOX only and always returns `read=false`, `flagged=false`. The outgoing server (`outgoingServer`/`outgoingPort`) is only stored in `AccountEntity`: there is no SMTP transport in the code, and the empty `smtp/` directory contains no sources.

---

## 6. Main runtime flows

### Startup

1. `MailApplication.onCreate()` installs Conscrypt, registers `ServiceWatchdogReceiver` dynamically and starts `AppForegroundTracker` (`ProcessLifecycleOwner`).
2. `AppLockManager.init()` evaluates the lock state (cold start with a password set = locked); the app locks again when the whole process goes to the background (short trips to system pickers excepted).
3. Notification channels are created for sync, sync status, calendar and task reminders, outbox, scheduled send, updates and connection status. Mail channels are per account (`MailNotificationChannels`: a channel group per account with "new mail" and "VIP senders" channels).
4. Stale temp files (including `LargeStateSaver` files older than 24 h in `cacheDir`) and old attachments are removed; duplicate message records are cleaned once; a legacy outbox queue from earlier versions is drained; if anything is left in `pending_email_changes`, `PendingMailChangesWorker` is enqueued.
5. Periodic sync is scheduled.
6. `PushService` is started when at least one Exchange account is in Push mode; server health is observed for connection-loss notifications (`ServerHealthNotifier`); dynamic app shortcuts are kept up to date.
7. `MainActivity` handles permissions and external intents, then starts Compose navigation.

### Account setup

1. `SetupScreen` collects the account data.
2. Exchange accounts go through `VerificationScreen`.
3. Verification connects to the server and can send a test message.
4. Passwords are passed in memory through `VerificationSecrets` and then persisted by `AccountRepository`.
5. IMAP/POP3 accounts are beta accounts with simpler validation.
6. Server certificates, self-signed certificates, client `.p12/.pfx` certificates and pin metadata are handled through `HttpClientProvider` and the account fields.

### Mail sync

1. `FolderSyncService` syncs folders and serializes folder operations per account (a `Mutex` per account).
2. `EmailSyncService` syncs system and user folders with full and incremental strategies.
3. `MailRepository` exposes the UI-facing methods and delegates the heavy logic to the services.
4. `EmailOperationsService` handles move, delete, permanent delete, mark read, flag and read-receipt flows (Exchange sends the read receipt itself when the message is marked read; declining uses EWS `SuppressReadReceipt` before marking read).
5. `OutboxWorker` retries offline outgoing messages when the network returns. Each queued message carries a stable `ClientId`/`Message-ID` reused across retries: on EAS 14+ the server discards a retry with the same `ClientId` after a lost response ([MS-ASCMD] `SendMail`); on Exchange 2007 (EAS 12.1, raw-MIME `SendMail` without `ClientId`) the stable Message-ID is only a best-effort identity.

#### One EAS Sync request per folder (`FolderSyncGate`)

The SyncKey ([MS-ASCMD] Sync) is a chain: every response issues the next key. Two requests with the same key (the sync loop and a star/read change) make the client and server keys diverge; the next request gets Status 3 and the folder is resynced from scratch.

`FolderSyncGate.withFolder(folderId)` is a process-wide per-folder `Mutex` (`SyncWorker` and `PushService` create separate service instances, so the gate is an `object`). It is used by:

- `EmailSyncService.syncEmailsEas`: each loop iteration (request, batch processing by the local function `processSyncBatch`, SyncKey save) and the initial `SyncKey=0` request;
- `EmailOperationsService`: flag writes (`setFlag`/`toggleFlag`/`retryPendingFlag`), `markAsRead` and `markAsReadBatch` (through `sendReadToServer`), and the batch permanent delete (`deleteBatchUnderGate`);
- `MailRepository.deleteDraftViaEas` and the key re-initialization path when deleting a draft from Drafts.

Inside the gate the SyncKey is re-read from the DB, so the loop continues from a key advanced by a local change. The mutex is not reentrant: the same folder's gate must not be taken inside the block.

There is no separate full sync of Sent Items. After sending, `refreshSentFolder` runs up to three regular folder syncs; the regular sync itself recovers from a stale key (Status 3/12) with a full resync through the gate and the `reconcileSentAfterFullResync` reconcile. On any other error (no connection, server failure) it stops: a full sync without a connection would only reset the key.

Only the new-account check (`VerificationScreen.findTestEmail`) stays outside the gate: it uses a temporary `EasClient` before the account is saved, writes nothing to the DB, and no other sync of that account exists yet.

#### Optimistic flag writes (`FlagWriteTracker.shared`)

A star/flag change is written to the DB immediately, then sent to the server as a Sync Change.

- Each tap gets a ticket; only the latest tap is sent to the server and only its outcome settles the DB. Responses to earlier taps do not flip the star back.
- A server rejection rolls the flag back to the last flag **confirmed by the server**, not to the snapshot before the tap (the snapshot may itself be the optimistic value of the previous tap).
- No network or no response keeps the local value.
- If the local value waits in the queue (`pending_email_changes`) and the record about this message was forgotten after a process restart, the tracker marks the flag `unconfirmed` and takes an assumed server value (`serverGuess`, the opposite of the local one) as the last confirmed one, so a rollback does not leave a rejected flag.
- A sync response requested before or during a local flag write does not overwrite the local flag: `applyServerFlag` compares the request time (`requestedAt`) with the time of the last write, and while a write is in flight it only remembers the server flag as confirmed.
- Tracker entries without active writes are evicted after 10 minutes idle.
- Lock order is `FolderSyncGate`, then the tracker's lock. The server part runs in `NonCancellable`, so leaving the screen does not cut a request halfway.
- A rejection returns `RepositoryErrors.FLAG_REJECTED`; `EmailListViewModel` and `EmailDetailViewModel` turn it into an error event and the UI shows a localized toast.

#### Offline changes (`pending_email_changes` → `PendingMailChangesWorker`)

Only an explicit rejection of the change itself rolls it back (`isChangeRejected`): `Change Status=N`, or collection Status 4/6/7/8. A stale SyncKey (3), a changed folder hierarchy (12), a server error (5), HTTP errors and no connection keep the local value, mark it in `pending_email_changes` and return success: the star does not disappear, and a message read offline does not become unread again.

- `PendingMailChangesWorker` is unique work (`ExistingWorkPolicy.KEEP`) with the `NetworkType.CONNECTED` constraint, exponential backoff starting at 30 s and at most 20 attempts. It sends through the same path as a tap (`EmailOperationsService.retryPendingFlag`/`retryPendingRead` via `PendingMailChanges.flush`, which also picks up edits made during the run).
- `KEEP` is deliberate: a failed send inside the worker enqueues nothing again, so there is no loop without a pause; leftovers are retried with backoff. After 20 failed attempts the queue stays in the DB, and `enqueueIfPending` is called from `SyncWorker` and `MailApplication`.
- While a field waits, sync does not overwrite it: `EmailDao.updateReadStatusFromServer` and `updateFlagFromServer` (through `EmailFlags.saveServerFlag`) contain a `NOT EXISTS` condition on the queue.
- On a ServerId change of the message (EAS 12.1 after `SyncKey=0`) the queue entry moves with it: `PendingEmailChangeDao.rename`.

#### Permanent delete

1. Before EAS Sync Delete, the folder is synced by the regular `syncEmails` (`syncAndResolve`), which processes server changes, and the messages are re-resolved by content (`resolveEmailIds`): on EAS 12.1 ServerIds can change after `SyncKey=0`.
2. Messages are read through the light projection `EmailServerRef` (`EmailDao.getServerRefs`, no bodies; queries in chunks of 500 because of SQLite's limit of 999 variables).
3. The batch Delete runs under `FolderSyncGate` with the key from the DB (`deleteBatchUnderGate`); Delete is sent with `GetChanges=0` and the new key is saved.
4. On `INVALID_SYNCKEY` or `DELETE_NOT_APPLIED` one more sync and a single retry follow.
5. The per-message EWS fallback runs if EAS did not delete the messages (or there is no key at all, only on Exchange 2007). Connection-level failures (`ConnectionFailure.connectionLevel`) skip it: otherwise there would be N timeouts in a row with no chance of success, and the result reports `DELETE_FAILED`.
6. On Exchange 2007 `EasEmailService.deleteEmailsPermanentlyBatch` returns `DELETE_NOT_APPLIED` on failure and does not reset the sync state with a `Sync(SyncKey=0)` request.
7. Draft deletion (`MailRepository.deleteDraftViaEas`) also runs under the gate and saves the returned key. No sync loop is started to refresh the key here: it would consume the Add of a new draft without processing it.

#### Orphan detection after a full resync

Lightweight DAO projections avoid loading full `EmailEntity` objects for large folders:

| Use case | Projection |
|----------|------------|
| Sent reconcile after a full resync (`reconcileSentAfterFullResync`) | `List<String>` ids only |
| Generic folder reconcile after a full resync (`reconcileGenericFolderAfterFullResync`) | `List<String>` ids only |
| ServerId content matching after a `SyncKey=0` reset (`resolveEmailIds`) | `EmailDedupInfo` |

The Drafts reconcile (`reconcileDraftsAfterFullResync`) keeps full `EmailEntity` objects because body and `bodyType` migration is required.

### Calendar, tasks, notes, contacts

1. Repositories own the local Room state and the UI operations.
2. EAS/EWS services own the protocol details.
3. Calendar and task reminders are scheduled through Android receivers and workers.
4. Local `isDeleted` flags and `DeletedIdsTracker` prevent deleted items from reappearing while the Exchange state catches up.
5. Calendar attachments are stored as JSON metadata in `CalendarEventEntity.attachments`; file bytes are downloaded only on demand.
6. Recurring series store attachment metadata on the master event or exception, and virtual occurrences reuse it instead of duplicating the data.
7. Permanent calendar deletion requires server success first: local deletion and trash clearing happen only after a confirmed EAS/EWS delete.
8. When an attendee deletes a meeting, `MeetingResponse` or EWS `DeclineItem` is sent before the delete so that the Calendar Repair Assistant does not bring the meeting back.

#### Offline task and note changes (`pending_pim_changes`)

An edit of a task (done, subject, dates) or note and a move to trash made without a connection are saved on the device and queued in `pending_pim_changes`; the same `PendingMailChangesWorker` sends them (one unique work, `NetworkType.CONNECTED`, exponential backoff).

- **When to defer** (`OfflineWrites`): no network — the server is not contacted at all (no timeout, no wasted battery); the request did not get through — a connection-level `[NET:…]` tag, HTTP 5xx (`isConnectionLevelError`), or the network dropped during the request (the EWS path on Exchange 2007 returns an untagged error on a drop). A server rejection is an error, as before.
- **No value is stored**: the current state of the task or note is sent, so several edits go out as one result. `serverSubject` is the subject before the first offline edit: EWS on Exchange 2007 finds a task by subject. `version` grows with each edit and the mark is cleared only for the same version, so an edit made during the send is not lost.
- **Order**: an online edit behind a queued edit of the same item is queued too; otherwise the server would get the older version last.
- **Protocol is chosen at send time**: the same `EasClient.updateTask`/`deleteTask`/`updateNote`/`deleteNote` as online (EAS 14+, EWS on Exchange 2007).
- **Sync** does not overwrite an item with a pending edit, does not treat it as a duplicate and does not restore it from trash. A server-side delete still wins. After sending, one sync per account and kind, not per item: it picks up new ServerIds (move to Deleted Items, UpdateItem on Exchange 2007).
- **Trash**: restoring before the send is device-only; the server is not touched. Permanent delete, empty trash and reset ("Reset and resync") send the queue first; without a connection they fail with `PENDING_CHANGES_NOT_SENT` and no data is lost.
- Task assignment (an email to the assignee) is not deferred offline; it is an error.
- UI: the `SavedOffline` event closes the dialog and shows "Saved on the device. It will be sent to the server when the connection is back".
- Creating tasks and notes offline is not supported yet.

### Notifications

1. `PushService` receives changes from EAS Ping.
2. `SyncWorker` also checks notifications during periodic and manual sync.
3. Both use `NotificationHelper.notificationMutex` to avoid duplicate notification races.
4. Notification queries use lightweight projections (`NotificationEmailSummary`) and bounded action batches (up to 20 messages shown, up to 25 messages per "mark read" action).
5. Mail notifications go to per-account channels (`MailNotificationChannels`); sound, importance and Do Not Disturb are set per account in system settings. Quiet hours and VIP senders (`domain/MailAlerts`, global settings) make mail silent during quiet hours unless the sender is a VIP.
6. `ServerHealthNotifier` posts one notification per account when the connection to the server is lost; flag reminders are delivered by `FlagReminderReceiver`.

### Home-screen widget

1. `MailWidget` is a Glance `GlanceAppWidget` with `SizeMode.Responsive` (three fixed sizes) to avoid launcher crashes: `SizeMode.Exact` builds `RemoteViews` with a size map that crashes the HyperOS 2.0 launcher.
2. Widget data is loaded on `Dispatchers.IO` from Room and DataStore using `applicationContext`.
3. Recent mail, next task and calendar event queries use the widget-specific projections and indexes (see section 4).
4. `updateMailWidget(context)` serializes `MailWidget().updateAll()` through a process-level mutex (`widgetUpdateMutex`), so the sync, notification, account and personalization paths cannot update the widget concurrently.
5. Clicks go through deep links/extras to `MainActivity`; manual sync uses `SyncAlarmReceiver.ACTION_SYNC_NOW` and is delegated to `SyncWorker`. Each clickable has its own **`data` URI** (`iwomail://widget/…`, `iwomail://email/{id}`), because `PendingIntent.filterEquals` ignores extras and intents of the same shape would otherwise share a single click target.
6. **Width-adaptive content.** A Glance `Row` is a non-wrapping horizontal `LinearLayout`, so anything wider than the widget is clipped. The bottom action row therefore renders fewer children on narrow sizes (gate `size.width >= 300.dp`: 4 account avatars versus 2, the last-sync label only on a wide widget), and the label uses `defaultWeight()` with `maxLines = 1` so it truncates instead of pushing the buttons out. Times in the message list are formatted with `isSameLocalDay`: today shows the time, otherwise the date.

### Updates and rollback

1. `UpdateChecker` reads `update.json` from GitHub.
2. The APK URL is selected by device ABI with a universal fallback.
3. Downloads use shared OkHttp clients with long timeouts (the update channel uses system CAs and `MODERN_TLS`).
4. Install intents use `FileProvider`.
5. Rollback preparation copies the APK to `Downloads/iwomail rollback` (file name `iwomail-rollback-v<version>.apk`) through MediaStore, after which the user uninstalls the current version and installs the saved one.

---

## 7. Background components

| Component | Responsibility |
|-----------|----------------|
| `PushService` | Direct Push foreground service (`foregroundServiceType="dataSync"`): adaptive EAS Ping heartbeat (starts at 480 s, bounds 120–1800 s, step 60 s; on Ping `Status=5` the server-supplied `HeartbeatInterval` is accepted within these bounds per [MS-ASCMD], otherwise the heartbeat is halved), per-account jobs; the reaction to failure series is defined by `PingFailurePolicy` |
| `SyncWorker` | Periodic and manual sync, notification check, auto-cleanup, `PushService` health check, enqueueing `PendingMailChangesWorker` (`enqueueIfPending`); a concurrent run is skipped (`doWorkMutex.tryLock()`) |
| `OutboxWorker` | Offline outbox send (unique work `outbox_send`, up to 10 attempts) |
| `PushRestartWorker` | Push restart after failures |
| `BootReceiver` | Start sync and push after reboot or package replacement |
| `SyncAlarmReceiver` | `AlarmManager` fallback for aggressive OEM ROMs; also handles manual sync (`ACTION_SYNC_NOW`) |
| `ServiceWatchdogReceiver` | `PushService` watchdog on screen, unlock and power events. **Registered dynamically in `MailApplication`** (`ContextCompat.registerReceiver` + `RECEIVER_NOT_EXPORTED`): `SCREEN_ON`/`USER_PRESENT`/`POWER_CONNECTED` are implicit broadcasts that are not delivered to manifest receivers on API 26+. The restart goes through `PushService.requestRestart`: exact alarm + `getForegroundService` + a WorkManager fallback, under the rate limiter `PushRestartPolicy` (a circuit breaker with exponential delay; when it opens, recovery is handed to `SyncWorker`) |
| `MailNotificationActionReceiver` | Notification actions for mail (mark read is delegated to `MarkEmailReadWorker`) |
| `CalendarReminderReceiver` | Calendar reminder actions (reminder, mark read, snooze for 5 minutes) |
| `TaskReminderReceiver` | Task reminder actions (completion enqueues `MarkTaskCompleteWorker`) |
| `FlagReminderReceiver` | Reminders for flagged messages (`tasks:ReminderTime`); alarms only for the nearest 50 reminders (`MAX_SCHEDULED`), the flag is re-checked in the DB when the alarm fires |
| `ScheduledSendReceiver` | Exact alarm for a scheduled message: starts the send on time (`ScheduledSend`) |
| `ScheduledEmailWorker` | Sends a scheduled message (`ui/screens`, class name kept for already enqueued work); also the WorkManager backup for the exact alarm; temporary errors are retried (up to 8 attempts), otherwise the message goes to Drafts with a notification |
| `MarkEmailReadWorker` | Unique work that marks a message as read (up to 3 retries) |
| `MarkTaskCompleteWorker` | Completes tasks from notifications; a server error is retried (up to 10 attempts with exponential backoff) instead of being swallowed |
| `PendingMailChangesWorker` | Sends stars and read state changed without a connection (`pending_email_changes`) when the network is back |
| `RescheduleRemindersWorker` | Rebuilds calendar, task and flag reminder alarms after reboot or update |
| `MailWidgetReceiver` | Glance widget receiver (`widget/MailWidget.kt`) |

The manifest declares `PushService`, WorkManager's `SystemForegroundService` (`dataSync`), `FileProvider`, the receivers `BootReceiver`, `SyncAlarmReceiver`, `CalendarReminderReceiver`, `TaskReminderReceiver`, `ScheduledSendReceiver`, `FlagReminderReceiver`, `MailNotificationActionReceiver`, `MailWidgetReceiver`, and the activities `MainActivity` and `WidgetConfigActivity`. WorkManager workers are not declared in the manifest. Among the receivers only `BootReceiver` and `MailWidgetReceiver` are exported; the other receivers are `exported="false"`.

---

## 8. Security and network

`HttpClientProvider` centralizes OkHttp creation to avoid connection and thread pool leaks.

Supported network and security modes:

- normal system trust: system root certificates and, for Exchange clients, additionally user-installed certificates (`AndroidCAStore`), which self-signed Exchange 2007 SP2 setups need;
- accept-all mode for legacy and self-signed environments;
- a custom server certificate (`certificatePath`);
- a PKCS#12 client certificate with mTLS;
- certificate pinning with a saved SHA-256 and metadata (a separate pin for the alternate URL); the pin is checked after the standard validation;
- the Conscrypt provider for legacy TLS compatibility; Exchange clients use `ConnectionSpec.COMPATIBLE_TLS` (TLS 1.0–1.3 in one handshake), the update channel uses `MODERN_TLS` and system roots only (`getSystemTrustOnlyClient`).

Hostname verification: with an imported server certificate (`certificatePath`) the client uses `Rfc6125HostnameVerifier` (SAN dNSName/iPAddress per RFC 6125, CN fallback only when the certificate has no SAN, which is common for Exchange 2007 self-signed certificates); accept-all mode disables verification by the user's choice; otherwise OkHttp's default verifier is used.

Cleartext traffic is disabled globally (`res/xml/network_security_config.xml`: `cleartextTrafficPermitted="false"`, only system certificates trusted in `base-config`). Exchange (EAS over OkHttp) is therefore HTTPS-only: the setup screen does not offer HTTP for Exchange accounts and forces `useSSL=true` on save. IMAP/POP3 run through JavaMail, not through `HttpClientProvider`: the session properties set `ssl.trust=*` and `ssl.checkserveridentity=false`, so the server certificate is not validated, and a non-SSL port (143/110) remains available.

`AccountRepository` caches `EasClient` per account (`EndpointClientCache`) and invalidates the cache when certificate or server settings change. Switching to the alternate Exchange URL is supported for connection-level errors, with a later probe and switch back to the primary.

### Email body rendering (WebView)

Email HTML bodies are sanitized before being loaded into a `WebView`:

- `HtmlUtils.sanitizeEmailHtml` removes:
  - `<script>` blocks and standalone tags;
  - plugin containers `<iframe>`, `<object>`, `<embed>`, `<applet>` (paired and self-closing forms);
  - `<meta http-equiv="refresh">` redirects;
  - inline event handlers `on*="..."`/`on*='...'`/unquoted;
  - `javascript:` and `data:text/html` URIs in `href`/`src`/`action`/`formaction`/`xlink:href` attributes.
- Sanitization is applied before display: `EmailDetailScreen` (viewing and printing) and `EventDetailDialog`; `RichTextEditor` additionally sanitizes inserted HTML through `stripDangerousTags`.
- `HtmlBodyView` (`ui/components`, used by `EmailDetailScreen` and the calendar `EventDetailDialog`) loads the sanitized HTML with `loadDataWithBaseURL(null, ...)`; `EmailPrinter` does the same for printing (in a separate WebView without JavaScript and without file access). A null base URL blocks cross-origin requests, cookie/localStorage exfiltration and service workers, complementing the tag-level blocklist. `HtmlBodyView` disables `allowFileAccess`, `allowContentAccess` and `domStorageEnabled`; link navigation is intercepted (`mailto` → `onMailto`, `tel` → dialer, everything else → external browser).
- **Gestures in the viewer.** The `HtmlBodyView` WebView has the height of its content and does not scroll vertically; the parent `Column.verticalScroll` does. `TouchDirectionLock` decides the owner once per gesture, after the system touch slop (`touchSlop`): horizontal movement and pinch (second pointer) go to the WebView (`requestDisallowInterceptTouchEvent(true)`), vertical movement goes to the screen. `overScrollMode` is always `OVER_SCROLL_NEVER` (no Android 12+ edge stretch on vertical drift). The height-measuring script (`MEASURE_CONTENT_JS`) also takes `documentElement.getBoundingClientRect()` into account: the margins of the last block pass through `body` but not through `html`, and without this the WebView would be a few pixels shorter than the page and scroll internally.
- **Renderer process death.** Every WebView (`HtmlBodyView`, `RichTextEditor`, `EmailPrinter`) overrides `onRenderProcessGone` and returns `true` (otherwise the system kills the whole app). The shared helpers live in `WebViewRenderer.kt`: `WebViewRenderer` (`markGone`/`isGone` through `R.id.webview_renderer_gone`, `release`, `crashed`) and `RendererRecovery` (recreate the WebView after the first crash; a second crash on the same content stops further attempts; a renderer unloaded by the system for memory does not count as a crash). The viewer fallback after the second crash is `HtmlBodyTextFallback`: the message as selectable plain text, extracted off the main thread. The editor fallback is a message instead of the editor (`isContentUnavailable`); the text stays in `latestHtml` and is still sent and saved. For printing, the pending WebView is destroyed.
- **Remote content.** Images and fonts from the internet are blocked by default (tracking pixels): the CSP from `RemoteImages.contentSecurityPolicy` allows only `data:`/`cid:`/`blob:` images and `data:` fonts until remote content is allowed, and the WebView additionally sets `blockNetworkImage`/`blockNetworkLoads`. It can be allowed per message ("Show"), for trusted senders, or always (a setting).
- **CSP (defense in depth).** Both WebViews inject a `Content-Security-Policy` with `script-src 'nonce-<random>'` (no `'unsafe-inline'`): the viewer (`HtmlBodyView`) and the editor (`RichTextEditor`, whose own script is nonce-tagged; `evaluateJavascript` and `@JavascriptInterface` calls run outside CSP). This blocks injected inline event handlers (`onerror`/`onload`) and `javascript:` URIs even if a regex bypass slips through, including the editor **paste** path. The editor additionally sanitizes injected HTML twice: Kotlin `stripDangerousTags` (delegates to `sanitizeEmailHtml`) before `evaluateJavascript`, and a JS-side `sanitizeHtml` (a detached `div`, strips `on*`/`javascript:`) before writing to `innerHTML`.

### Outbound MIME header sanitization

When building raw MIME for `SendMail`/`SmartForward` and meeting invitations, address header values (`From`/`To`/`Cc`/`Bcc`) pass through `stripHeaderCrlf` (a single function in `EasXmlTemplates.kt`), which strips bare CR/LF. An injected CR/LF in a header body (RFC 5322 §2.2) would otherwise let a crafted value append arbitrary headers (`\r\nBcc: …`). The compose UI already normalizes recipients, but the protocol layer does not rely on that. The client does not build its own MDN message: Exchange sends read receipts itself (see `domain/ReadReceipts.kt`). Covered by `EasMimeHeaderSanitizeTest`.

The `Subject` header is encoded as an RFC 2047 encoded-word and, when long, is folded across several words separated by CRLF and a space (`encodeMimeHeaderText`, built on `mimeEncodedWord` and `chunkByUtf8Bytes` in `EasXmlTemplates.kt`; the same encoder is used when building meeting invitations in `EasClient`), so that no header line exceeds the RFC 5322 limit of 998 octets; multi-octet UTF-8 characters are never split between words. Covered by `EasMimeSubjectEncodingTest`.

### Password storage

Passwords are persisted by `data/security/PasswordStorage` through `EncryptedSharedPreferences` (keys AES256-SIV, values AES256-GCM, master key in Android Keystore), created by `EncryptedStoreProvider`. When Keystore is unavailable or corrupted, the provider falls back to `ObfuscatedSharedPreferences`: values are XOR-ed with a key derived by PBKDF2-HMAC-SHA256 (10 000 iterations, static salt) from `ANDROID_ID`. This is obfuscation, not encryption. The fallback is recorded by `SecurityTelemetry` and shown to the user once as a toast. A fail-closed mode (`SecurityTelemetry.setFailClosedMode`, which refuses to save passwords to the fallback) exists in the code but has no settings switch, so it is off. Password char arrays are zeroed (`fill('\u0000')`) after use in `HttpClientProvider`.

The app-lock password (`AppLockManager`) is stored only as a PBKDF2-HMAC-SHA256 hash (600 000 iterations, 16-byte random salt, format `v1:iterations:b64salt:b64hash`, constant-time comparison). Fingerprint unlock uses `BiometricPrompt` (`BIOMETRIC_WEAK`), which is why `MainActivity` extends `FragmentActivity`.

---

## 9. Exchange 2007 SP1 compatibility

Exchange 2007 SP1 exposes EAS 12.1. The app treats Exchange 2007 behavior as a first-class constraint:

| Feature | Main path | Fallback / detail |
|---------|-----------|-------------------|
| Mail sync, send, move and flags | EAS | EWS fallback for selected permanent-delete and error paths |
| Folder sync and CRUD | EAS FolderSync | Serialized per account to protect the SyncKey state |
| Folder content sync and local changes | EAS Sync | One Sync request per folder at a time (`FolderSyncGate`), SyncKey re-read from the DB under the gate |
| Follow-up flags | EAS Sync Change `<Flag>` ([MS-ASEMAIL], EAS 12.0+) | A cleared flag is an empty `<Flag/>`; a dateless flag rejected with Status 6 is retried once with today's dates |
| Conversation threads | `email2:ConversationId` on EAS 14.0+ | Exchange 2007 does not send it: threads by subject (only if the thread has a reply or forward) |
| Read receipts | The server sends them on mark-read (EAS/EWS) | Decline: EWS `SuppressReadReceipt` before marking read |
| Category colors | Master Category List (Exchange 2010+) | Exchange 2007: standard Outlook names or a deterministic color by name; a manual override takes priority |
| Direct Push | EAS Ping | Adaptive heartbeat; honors the server `HeartbeatInterval` on `Status=5` |
| Contacts | EAS + GAL search | Exchange contacts are read-only (Sync reads Add only; edit and delete buttons exist for personal contacts only); local contacts are also supported |
| Notes | EWS on Exchange 2007 | The EAS Notes class is 14.0+ |
| Tasks | EAS/EWS depending on version and path | EWS for Exchange 2007 task gaps |
| Calendar sync | EAS primary | EWS supplements details, attachments and meeting paths |
| Calendar attachments | EWS CreateAttachment/GetAttachment | EWS ItemId, the recurring master ItemId and RootItemChangeKey sequencing are required |
| Calendar delete | EAS Sync Delete / EWS DeleteItem | SyncKey catch-up, server-confirmed local delete, DeclineItem/MeetingResponse for attendees |
| Drafts | Server drafts through EWS or local mode | Dual body for inline images |

Compatibility rules captured in the code:

- Outgoing EWS SOAP uses declared namespace prefixes and `Exchange2007_SP1`.
- EWS response parsing must tolerate prefixed and unprefixed XML elements.
- Calendar EAS Sync chains must not force an inconsistent `FilterType`.
- EAS operations that require the current SyncKey perform a catch-up sync before sending commands where needed.
- Meeting deletion distinguishes the attendee, organizer and non-meeting paths so that the Calendar Repair Assistant does not bring the meeting back.
- Calendar permanent delete and trash cleanup must not remove server-backed local records until the server delete succeeds.
- Recurring calendar attachments use metadata references and EWS master ItemId resolution instead of duplicating file payloads per occurrence.
- A folder's Sync requests (the sync loop and local flag/read changes) never run in parallel with the same SyncKey: they go through `FolderSyncGate`, otherwise the next request gets Status 3 and the folder is fully resynced.
- Mail flags (`eas/MailFlag.kt`, `EasEmailService.setFlag`): the whole `<Flag>` is written on every change because a missing child element is deleted by the server ([MS-ASEMAIL] implicit deletes); a cleared flag is sent as an empty `<Flag/>` ([MS-ASEMAIL] Flag: "SHOULD be included as an empty container element"); the four date elements (`tasks:StartDate`/`DueDate`/`UtcStartDate`/`UtcDueDate`) are sent all together or not at all. Some Exchange servers reject an active flag without dates with Sync Change Status 6; the write is then retried once with today's dates (`MailFlags.dueToday`, like Outlook's quick flag). The new SyncKey from the response is saved even when the Change itself is rejected (`FlagWrite.syncKey`: the server issues a new key and reports only failed changes in `Responses`).
- WBXML encoding (`eas/WbxmlParser.kt`): the namespace of an empty element (`<Flag xmlns="Email"/>`) applies only to that element and does not leak onto the elements that follow it.

---

## 10. Key design decisions

### Manual DI

Instead of Hilt/Dagger the app uses `RepositoryProvider`: a minimal dependency footprint, simple lifecycle and debugging, shared singleton repositories and centralized client creation in `AccountRepository`.

### Offline-first

The UI reads Room/Flow and network operations update the local DB. The app stays usable with an unstable connection to Exchange, and the UI state during long syncs is predictable.

### Service decomposition

Large protocol logic is split into specialized services: `EasTransport` for transport, Provision and authentication; feature services for mail, folders, calendar, tasks, notes, drafts, contacts and attachments; and repository-level services for sync and operations.

### Draft body model

Drafts with inline images keep two body representations:

| Representation | Purpose |
|----------------|---------|
| Server body with `cid:` | Compatible with Outlook/Exchange rendering |
| Local body with `data:image/...base64` | Instant in-app display and editing |

Server mode stores drafts through EWS (with attachments, in a single MIME `CreateItem` request). Local mode stores drafts only in Room (IDs like `local_draft_…`) and is beta.

### Concurrency guards

- `SyncWorker` does not run in parallel with itself: `doWorkMutex.tryLock()` skips the second run.
- `PushService` serializes sync per account (`accountSyncMutexes`).
- Folder operations are serialized per account (`FolderSyncService`) to protect the SyncKey state.
- EAS Sync requests are serialized per folder (`FolderSyncGate`); optimistic flag writes are tracked by `FlagWriteTracker` (lock order: folder gate, then tracker).
- Calendar, task and note repositories use per-account sync locks; calendar delete, restore, permanent delete and trash operations take the same lock as calendar sync.
- `EasTransport` runs Provision under `provisionMutex`; the NTLM handshake of `EwsClient` runs under `ntlmLock`.
- Notification display uses a shared mutex across the push and worker paths (`NotificationHelper.notificationMutex`).
- Scheduled send is guarded by a lock on the message directory (`ScheduledSend.lock`): the alarm, the backup and cancel cannot send a message twice.
- Glance widget updates are serialized through `widgetUpdateMutex`.

### UI state preservation across configuration changes

Compose screens keep user-editable state across rotation and process death and prevent loader effects from overwriting it:

- Editable fields use `rememberSaveable`. Large bodies use `createLargeStringSaver` (`ui/utils/LargeStateSaver.kt`): strings over 10 000 chars are written to a per-instance file in `cacheDir` (`compose_state_<tag>_<uuid>.tmp`) and only the path goes into the saved state. The whole Activity state is one Binder transaction with a process-wide buffer of about 1 MB; larger state crashes the app on backgrounding with `TransactionTooLargeException`. The file is deleted on restore; files older than 24 h (screens closed without restore) are removed at app start (`MailApplication.cleanupStaleTempFiles`). It is used by `ComposeScreen` (body and draft body), `CreateEventDialog` and `RichTextEditor`.
- One-shot loaders are guarded against re-running on configuration change by a `rememberSaveable` flag, for example `replyLoaded` / `forwardLoaded` / `signatureInitialized` (`ComposeScreen`) and `datesInitialized` (the create-task dialog in `TasksScreen`); the effect returns early when its flag is set. `TasksScreen` and `NotesScreen` take `isInitialLoadDone` from the state of their ViewModels.
- The state of `RichTextEditorController` (`latestHtml` and the formatting parameters) is saved by its own `Saver`; the editor HTML is stored once in the saved state (the `latestHtml` field through `createLargeStringSaver`).
- `RichTextEditor` sanitizes and chunks large HTML off the main thread (`setHtmlAsync`: `Dispatchers.Default`, chunks of 200 000 chars read by the page through `@JavascriptInterface` instead of one huge `evaluateJavascript`). A newer call discards an older result; the `isPreparingHtml` flag keeps `awaitCurrentHtml` from returning empty text while the HTML is still being prepared; `OutOfMemoryError` shows a message instead of crashing.
- WebView renderer death in the editor (`onRenderProcessGone`) recreates the WebView once; after the second crash the editor is replaced by a message, and the text stays in `controller.latestHtml` and is saved and sent from there.

### Coroutine exception handling (crash resistance)

`viewModelScope` uses `SupervisorJob` without a `CoroutineExceptionHandler`, so any uncaught exception in a `viewModelScope.launch` block **crashes the app**. All ViewModel coroutine launches therefore follow a mandatory defensive pattern:

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
        throw e  // cooperative cancellation must propagate
    } catch (e: Exception) {
        sendEvent(Event.Error(e.message ?: "Unknown error"))
    } finally {
        _uiState.update { it.copy(isLoading = false) }
    }
}
```

Rules:
- **`CancellationException` is always rethrown**: cooperative cancellation requires it to propagate.
- **All other exceptions are caught** (`RuntimeException`, `SQLiteException`, `IllegalArgumentException`, network errors) and converted into `Error` events (a toast) instead of crashing the process.
- **Loading flags are reset in `finally`** (`isDeleting`, `isMoving`, `isRestoring`, `isLoadingBody` and so on), so the UI never gets stuck in a loading state.
- **Selection is cleared in `finally`** (batch operations in `EmailListViewModel`), in a single place instead of duplicating it in `try` and `catch`.

The pattern is applied in `EmailListViewModel`, `EmailDetailViewModel`, `SearchViewModel`, `NotesViewModel`, `TasksViewModel` and `UserFoldersViewModel`. `SyncCleanupViewModel` has no error channel (its `set*` operations are fire-and-forget), so instead of duplicating `try/catch` it uses a single private `launchSafe { }` helper with the same `catch(CancellationException){throw}` / `catch(Exception){ Log.e }` branches. `ScheduledMailViewModel` launches its operations in `viewModelScope` without this wrapper. Exception handling in the ViewModels is covered by the `*ViewModelTest` unit tests.

The long-lived reactive **observe** launches (subscriptions to Room flows by account, folders and messages) in `EmailListViewModel`, `EmailDetailViewModel`, `NotesViewModel`, `TasksViewModel`, `UserFoldersViewModel` and `ComposeViewModel` additionally pass `loggingExceptionHandler(tag)` to `viewModelScope.launch(handler) { … }`, so a Room-flow error in the collector is logged instead of crashing the process.

**Screens without MVVM** that launch work from `rememberCoroutineScope()` use `rememberSafeScope()` (`ui/components/ComposableUtils.kt`): a crash-safe scope built on `SupervisorJob` (a failing operation does not cancel siblings) + a `CoroutineExceptionHandler` (an uncaught exception is logged instead of propagating to the process). Its lifecycle matches `rememberCoroutineScope` (the supervisor is a child of the base scope's `Job`). The `rememberSyncScope()` helper has the same handler.

The same guarantee applies to long-lived **non-UI** scopes: `MailApplication`, the push and sync services, alarm and boot receivers, `InitialSyncController`, `SendController` and some repositories build their scopes through `supervisedScope(dispatcher, tag)` (`util/AppCoroutines.kt`) — `SupervisorJob` + a `CoroutineExceptionHandler` — instead of a bare `CoroutineScope(SupervisorJob() + dispatcher)`. An uncaught error in one background task (including `OutOfMemoryError`, which `catch(Exception)` does not catch) is logged instead of crashing the process. `GlobalScope` and `runBlocking` are not used in the code (they appear only in comments).

**Config-change-aware `onDispose` cleanup.** Composables that tear down resources in `onDispose` (`SetupScreen` deleting orphaned certificate files, `AppNavigation` clearing `VerificationSecrets` on the verification route, `AboutScreen` and `SearchScreen` stopping the easter-egg player) guard the cleanup with `context.findActivity()?.isChangingConfigurations` (`ui/utils/ContextExtensions.kt`, one shared `tailrec` helper). `MainActivity` declares `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboard|keyboardHidden|navigation"`, so a rotation does not recreate the Activity, but other changes (theme/`uiMode`, locale, font scale, density) still recreate it and dispose the composition; without the guard the cleanup would destroy resources that the `rememberSaveable` state still references after recreation. `findActivity()` (not a raw `as? Activity` cast) is required because `LocalContext.current` is often a `ContextThemeWrapper`, so a direct cast would return `null` and silently defeat the guard.

---

## 11. Current constraints

- OAuth 2.0 / Modern Auth is not implemented.
- Office 365 / Exchange Online is not a primary target without Basic Auth.
- IMAP/POP3 are beta: read-only, with no Exchange groupware features and no SMTP transport.
- EAS 16.x is not the current optimization target (`EasVersionDetector` negotiates `14.1`, `14.0`, `12.1`, `12.0`).
- XML parsing is mixed: `XmlPullParser` (`EasXmlParser`, `EasSyncStream`; DOCTYPE processing disabled) parses Sync responses of calendar, contacts, notes and tasks, GAL search, the MoveItems response and Provision. Many EWS/SOAP and EAS paths (in particular in the mail, drafts and tasks services) still extract values with regular expressions or substrings (see `docs/XMLPULLPARSER_MIGRATION_PLAN.md`).
- S/MIME signing and encryption are not implemented.

---

## 12. Build and resources

The recommended production build path is Android Studio with JDK 17. The command-line examples below are supplemental and require a correctly configured `JAVA_HOME`.

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

On Windows:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

Unit tests are run with `./gradlew testDebugUnitTest` (the same command runs in CI, `.github/workflows/ci.yml`; on Windows there is `run-tests.bat`). Coverage is measured by Kover.

| Directory | Content |
|-----------|---------|
| `res/drawable/` | custom vector icons, file icons |
| `res/layout/` | widget preview and loading layouts |
| `res/raw/` | send/receive/delete sounds and the easter-egg audio |
| `res/xml/` | backup rules, data extraction rules, FileProvider paths, widget info, network security config (app shortcuts are dynamic, `util/AppShortcuts.kt`) |
| `res/values/` | default strings and theme resources |
| `res/values-ru/` | Russian strings |

---

## 13. Documentation map

| File | Purpose |
|------|---------|
| `README.md` | Project overview in Russian |
| `README_EN.md` | Project overview in English |
| `docs/ARCHITECTURE.md` | Architecture document (Russian version) |
| `docs/ARCHITECTURE_EN.md` | This document (English version) |
| `docs/CHANGELOG_RU.md` | Changelog in Russian |
| `docs/CHANGELOG_EN.md` | Changelog in English |
| `docs/PRIVACY_POLICY.md` | Privacy policy |
| `docs/SECURITY.md` | Security model and threat model |
| `SECURITY.md` | Vulnerability disclosure policy |
| `docs/XMLPULLPARSER_MIGRATION_PLAN.md` | Plan for migrating XML parsing to XmlPullParser |

---

## Summary

iwo Mail Client is an offline-first Android mail client focused on on-premise Microsoft Exchange, especially Exchange 2007 SP1. The app uses Jetpack Compose, Room, DataStore, WorkManager, OkHttp and Conscrypt. Exchange support is implemented through EAS/WBXML and EWS/SOAP with Basic/NTLM authentication. IMAP/POP3 is a beta JavaMail path for basic mail reading.

The architecture is layered as:

```text
Compose UI → Repositories → EAS/EWS/IMAP/POP3 protocol services → Room/DataStore/Network → Background services
```

The main constraints are: no OAuth/Modern Auth, beta IMAP/POP3, no S/MIME, and part of the XML parsing remains on regular expressions until the XmlPullParser migration is finished.
