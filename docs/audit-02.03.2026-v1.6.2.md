# IWO Mail — Security & Reliability Audit

**Date:** 02.03.2026
**Version:** 1.6.2
**Target server:** Microsoft Exchange 2007 SP1 (EAS / EWS)
**Codebase:** ~71,000 lines Kotlin, 112 files

---

## Summary

| Severity | Count | Description |
|----------|-------|-------------|
| CRITICAL | 11 | Data loss, crashes, coroutine leaks, connection pool exhaustion |
| HIGH | 31 | Race conditions, thread safety, OOM risks, security |
| MEDIUM | 28 | Non-atomic operations, missing error handling, DRY violations |
| LOW | 17 | Dead code, logging, performance, minor UX |
| **Total** | **87** | |

---

## CRITICAL Findings

### C-01. MailDatabase singleton double-init
**File:** `MailDatabase.kt:173-194`
**Category:** Singleton thread-safety

`getInstance()` is missing the inner null-check inside the `synchronized` block (standard DCL pattern). If two threads (e.g., `SyncWorker` + `PushService` at boot) both see `INSTANCE == null`, each enters the synchronized block sequentially and builds a **separate** `RoomDatabase` instance. Two open SQLite connections to the same file cause WAL checkpoint corruption, `SQLiteDatabaseLockedException`, and inconsistent reads.

**Fix:** Add inner null-check:
```kotlin
fun getInstance(context: Context): MailDatabase {
    return INSTANCE ?: synchronized(this) {
        INSTANCE ?: Room.databaseBuilder(...)
            .build().also { INSTANCE = it }
    }
}
```

---

### C-02. OutboxWorker data loss on concurrent enqueue
**File:** `OutboxWorker.kt:141-196`
**Category:** Race condition / data loss

`doWork()` reads the outbox file under lock, processes emails **without** the lock for potentially minutes (network calls), then writes back only failed emails under lock. If `enqueue()` is called from the UI thread during processing, the new email is written by `enqueue()` but then **overwritten and lost** when `doWork()` saves `failedEmails`.

**Fix:** After processing, re-read the current file under lock, merge in any new entries added since the snapshot, then save.

---

### C-03. PushService missing notificationMutex
**File:** `PushService.kt:887-909`
**Category:** Race condition / duplicate notifications

`SyncWorker` correctly uses `PushService.notificationMutex.withLock { ... }` before showing notifications. However, `PushService.syncAccount()` does NOT use the mutex. When both process the same account concurrently, duplicate notifications are shown.

**Fix:** Wrap the PushService notification path in the same `notificationMutex.withLock`.

---

### C-04. SyncKey race condition across concurrent email operations
**File:** `EmailOperationsService.kt` (throughout)
**Category:** Race condition / data loss

`markAsRead`, `toggleFlag`, `moveEmails`, `deleteEmailsPermanently` all read `folder.syncKey`, use it for an EAS Sync command, and write back the new syncKey — without any per-folder lock. If PushService or SyncWorker trigger `syncEmails` concurrently, both threads consume the same syncKey. One succeeds; the other gets `INVALID_SYNCKEY`. The recovery logic (reset from "0") consumes pending server changes, which can cause silently dropped emails.

**Fix:** Introduce a per-folder `Mutex` that serializes all syncKey-consuming operations.

---

### C-05. CancellationException swallowed across multiple services
**Files:** `EasAttachmentService.kt:121`, `EasNotesService.kt:679`, `EasDraftsService.kt:375`, `EasEmailService.kt:232`, `TaskRepository.kt:468,522,580`, `ContactRepository.kt:155,206,275`, `FolderSyncService.kt:296,327`
**Category:** Coroutine leaks

All `catch (e: Exception)` blocks in `suspend` functions silently catch `CancellationException`, breaking structured concurrency. When a coroutine is cancelled, the cancellation is swallowed and the function returns a normal error instead of propagating. This causes coroutine leaks and prevents proper cleanup.

**Fix:** Add `if (e is kotlinx.coroutines.CancellationException) throw e` as the first line in every `catch (e: Exception)` block inside a `suspend` function.

---

### C-06. Response body leak in EasAttachmentService and EasEmailService
**Files:** `EasAttachmentService.kt:109,285,579`, `EasEmailService.kt:193,222`
**Category:** Connection pool exhaustion

HTTP `Response` objects from `deps.executeRequest()` are never closed with `.use {}`. OkHttp `Response.body` holds a connection from the pool; if not closed, the connection pool is exhausted and network calls eventually hang.

**Fix:** Wrap every `deps.executeRequest()` call in `response.use { ... }`.

---

### C-07. WbxmlParser infinite loop on EOF
**File:** `WbxmlParser.kt:122-130`
**Category:** App hang

If the stream reaches EOF in `readMultiByteInt`, `input.read()` returns `-1`. The value `-1 and 0x80 = 0x80 != 0`, so the loop never breaks. This hangs the parser thread on malformed or truncated WBXML data.

**Fix:** Add `if (b == -1) throw IOException("Unexpected EOF")` after `input.read()`.

---

### C-08. WbxmlParser opaque read may not read all bytes
**File:** `WbxmlParser.kt:79-82`
**Category:** Data corruption

`InputStream.read(bytes)` does NOT guarantee it reads all `length` bytes in one call. On a slow network stream, it may read fewer bytes, corrupting parsed data.

**Fix:** Replace `input.read(bytes)` with `DataInputStream(input).readFully(bytes)`.

---

### C-09. InitialSyncController coroutine scope leak
**File:** `MainScreen.kt:96`
**Category:** Memory leak

`syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)` is never cancelled. This scope outlives any Activity/Composable lifecycle. Jobs in `syncJobs` can hold references to `Context` objects passed to `startSyncIfNeeded`.

**Fix:** Tie `syncScope` to `Application` lifecycle or use `ProcessLifecycleOwner.get().lifecycleScope`.

---

### C-10. ComposeScreen double-back crash
**File:** `ComposeScreen.kt:638-657`
**Category:** Crash

`handleBackNavigation` launches a coroutine with `delay(100)` then calls `onBackClick()`. Rapid back-presses launch multiple coroutines, each calling `navController.popBackStack()`, which can pop past the intended destination or crash if back stack is empty.

**Fix:** Add a `var backNavigationInProgress` guard flag.

---

### C-11. No ViewModel layer — business logic in Composables
**Files:** All screens
**Category:** Architecture / memory leak

All state is managed directly in Composables with `remember`/`rememberSaveable`. Long-running operations (sync, send) are tied to `rememberCoroutineScope` which is cancelled on navigation. State management is scattered across hundreds of `var x by` declarations.

**Impact:** Operations can be interrupted by configuration changes; complex state is hard to test and debug.

**Fix:** Introduce ViewModels per screen. Move repository calls, sync logic, and complex state into ViewModels with `viewModelScope`.

---

## HIGH Findings

### H-01. PushService no foregroundServiceType for Android 14+
**File:** `PushService.kt:331`

`startForeground(NOTIFICATION_ID, notification)` does not specify `foregroundServiceType`. On Android 14+ (API 34), this throws `MissingForegroundServiceTypeException` and crashes. SyncWorker already handles this correctly.

**Fix:** Add `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` for API >= Q.

---

### H-02. NtlmAuthenticator ArrayIndexOutOfBoundsException
**File:** `NtlmAuthenticator.kt:84`

`type2Message.copyOfRange(24, 32)` without checking `type2Message.size >= 32`. A malicious or buggy server sending a truncated Type 2 message crashes the app.

**Fix:** Add bounds check before access.

---

### H-03. Thread safety — cached folder IDs without @Volatile
**Files:** `EasNotesService.kt:46`, `EasDraftsService.kt:61`, `EasTasksService.kt:46`, `EasCalendarService.kt:70`

`cachedNotesFolderId`, `cachedDraftsFolderId`, etc. are plain `var` accessed from `Dispatchers.IO`. Without `@Volatile`, one thread may write but another sees stale `null`.

**Fix:** Mark all cached folder IDs as `@Volatile`.

---

### H-04. WbxmlParser currentCodePage not thread-safe
**File:** `WbxmlParser.kt:30`

`currentCodePage` is instance state mutated during `parse()` and `generate()`. If two coroutines share the same `WbxmlParser` instance (as `EasClient` does), concurrent calls corrupt each other's code page tracking.

**Fix:** Create a new `WbxmlParser` instance per call, or make `currentCodePage` a local parameter.

---

### H-05. Regex on enormous XML responses — OOM risk
**Files:** `EasEmailService.kt`, `EasNotesService.kt`, `EasCalendarService.kt`

Patterns like `"<Add>(.*?)</Add>".toRegex(RegexOption.DOT_MATCHES_ALL)` run on the full XML response string. For large sync responses (10+ MB), the regex engine creates excessive intermediate objects.

**Fix:** Use a streaming XML parser or `indexOf`-based splitting before applying regex.

---

### H-06. EmailSyncService.prefsInitialized non-volatile flag
**File:** `EmailSyncService.kt:62`

`prefsInitialized` is a plain `Boolean` without `@Volatile`. The JVM may cache the value in a CPU register; another thread may not see the update.

**Fix:** Mark as `@Volatile` or make `initFromPrefs` synchronized.

---

### H-07. EmailSyncService.syncSentFull non-atomic activeSyncs guard
**File:** `EmailSyncService.kt:395-405`

Check-then-act pattern on `activeSyncs[folderId]` is not atomic. Two threads can both pass the check and run concurrent syncs.

**Fix:** Use `putIfAbsent` like `syncEmailsEas`.

---

### H-08. PushService accountPingJobs inconsistent synchronization
**File:** `PushService.kt:563,567`

`accountPingJobs` is a `synchronizedMap` but access in `startPingForAccount` is not wrapped in `synchronized`. Jobs can be lost or leaked.

**Fix:** Wrap access in `synchronized(accountPingJobs)`.

---

### H-09. TLS 1.0/1.1 enabled globally
**File:** `HttpClientProvider.kt:574`

`preferredProtocols` includes `"TLSv1.1"` and `"TLSv1"` which have known vulnerabilities (POODLE, BEAST). These are enabled for all accounts, not just legacy Exchange 2007 SP1 servers.

**Fix:** Make TLS version list per-account. Default to TLS 1.2+.

---

### H-10. ConnectionSpec order exposes weak ciphers
**File:** `HttpClientProvider.kt:289-293`

`COMPATIBLE_TLS` (weak ciphers) is listed before `MODERN_TLS`. OkHttp tries specs in order. `CLEARTEXT` is also included, allowing unencrypted HTTP.

**Fix:** Reorder to `MODERN_TLS, COMPATIBLE_TLS`. Remove `CLEARTEXT` in production.

---

### H-11. CalendarRepository.initFromPrefs TOCTOU race
**File:** `CalendarRepository.kt:44-52`

`prefsInitialized` check-then-act is not synchronized. Two concurrent callers can both see `false`.

**Fix:** Use `@Volatile` + synchronized block or `lazy` initializer.

---

### H-12. MailRepository.initCacheFromDb TOCTOU race
**File:** `MailRepository.kt:71-73`

Same pattern: `cacheInitialized` is checked and set without synchronization.

**Fix:** Make `@Volatile` and wrap in synchronized block.

---

### H-13. NoteRepository.restoreNote deletes before confirming replacement
**File:** `NoteRepository.kt:86-103`

Old note is deleted at line 88. If `syncNotes` then fails, the note is lost with no recovery.

**Fix:** Delete old note only after confirming new one was created.

---

### H-14. NoteRepository.deleteNote finds note by subject+body
**File:** `NoteRepository.kt:403-425`

After syncing, the code finds the note by `subject == note.subject && body == note.body`. Two notes with identical content cause wrong deletion.

**Fix:** Match by ID first, fall back to content-matching.

---

### H-15. CalendarRepository.updateAttendeeStatus finds event by subject only
**File:** `CalendarRepository.kt:1394-1396`

`events.find { it.subject.equals(meetingSubject, ignoreCase = true) }` — multiple meetings with the same subject cause wrong attendee update.

**Fix:** Accept event ID or serverId as parameter.

---

### H-16. Non-atomic batch soft-deletes in CalendarRepository
**File:** `CalendarRepository.kt:803-824`

`deleteEventsWithProgress` calls `softDelete` per event without a transaction. Crash mid-loop leaves inconsistent state.

**Fix:** Wrap in `database.withTransaction`.

---

### H-17. DRY violation — retry pattern duplicated 10+ times
**Files:** `NoteRepository.kt`, `TaskRepository.kt`

Identical retry patterns (check error → delay 1s → retry) copy-pasted across ~10 methods.

**Fix:** Extract a shared `withRetry` helper.

---

### H-18. DRY violation — AllDayEvent UTC normalization duplicated 3 times
**File:** `CalendarRepository.kt:182-197, 421-436, 612-627`

Identical 15-line blocks for all-day event time conversion.

**Fix:** Extract to `normalizeAllDayTimes()` helper.

---

### H-19. FoldersCache unbounded growth
**File:** `MainScreen.kt:73-89`

Singleton `ConcurrentHashMap` with no eviction. Stale entries accumulate as accounts are added/removed.

**Fix:** Add `clearAccount()` on account deletion.

---

### H-20. VerificationSecrets race condition
**File:** `AppNavigation.kt:40-49`

Global `@Volatile var password` can be cleared by callbacks before `VerificationScreen` reads it.

**Fix:** Move secrets into `SavedStateHandle`.

---

### H-21. ShareIntentData URI lifecycle
**File:** `AppNavigation.kt:59-66`

`ShareIntentData.attachments` holds `android.net.Uri` references globally. Process death between set and read loses URIs.

**Fix:** Persist share intent URIs immediately in `MainActivity.onNewIntent()`.

---

### H-22. Busy-wait polling for NavController initialization
**File:** `AppNavigation.kt:398-401, 441-444, 503-508`

`while (navController.currentBackStackEntry == null && w < 2000) { delay(50) }` — On slow devices, 2 seconds may not suffice.

**Fix:** Use `navController.currentBackStackEntryFlow.first()` for reactive waiting.

---

### H-23. Silent exception swallowing in navigation retry
**File:** `AppNavigation.kt:403-424, 509-520, 591-598`

Navigation failures caught with `catch (_: Exception) { }` and retried after 500ms. If both attempts fail, user gets no feedback and intent is lost.

**Fix:** Log, show Toast/Snackbar on second failure.

---

### H-24. CalendarScreen expandRecurringForRange blocks main thread
**File:** `CalendarScreen.kt:89-128`

Expensive recurring event expansion runs in `remember` (main thread). Hundreds of recurring events over 365 days causes jank.

**Fix:** Move to `withContext(Dispatchers.Default)` inside `LaunchedEffect`.

---

### H-25. EmailListScreen auto-scroll fights user scrolling
**File:** `EmailListScreen.kt:1117-1124`

`animateScrollToItem(0)` fires on every `emails.size` change during sync. Users actively reading mail are interrupted.

**Fix:** Only auto-scroll if `listState.firstVisibleItemIndex == 0`.

---

### H-26. EmailListScreen imagePreviewCache grows unbounded
**File:** `EmailListScreen.kt:1319,1343`

Entries added but never removed. Navigating between folders accumulates stale entries.

**Fix:** Clear when `folderId` changes, or limit size.

---

### H-27. SearchScreen parallel search coroutines
**File:** `SearchScreen.kt:134-159`

`LaunchedEffect(query)` cancels itself on change, but `scope.launch` inside it is NOT cancelled. Multiple searches run simultaneously.

**Fix:** Track and cancel the Job explicitly.

---

### H-28. ComposeScreen share intent URIs invalid after process death
**File:** `ComposeScreen.kt:344-373`

`rememberSaveable` persists URI strings, but content URI permissions granted by `FLAG_GRANT_READ_URI_PERMISSION` are lost after process death. Reading throws `SecurityException`.

**Fix:** Copy attachment bytes to internal storage immediately.

---

### H-29. ComposeScreen temp file leak
**File:** `ComposeScreen.kt:62-80`

`createLargeStringSaver` temp files not cleaned on process kill.

**Fix:** Clean stale `compose_state_*.tmp` on app start.

---

### H-30. AccountRepository.clearEasClientCache removes lock while held
**File:** `AccountRepository.kt:436-439`

`easClientLocks.remove(accountId)` can remove a `Mutex` that another coroutine is waiting on.

**Fix:** Stop removing locks (lightweight), or only on account deletion.

---

### H-31. MailWidget sequential blocking DB queries
**File:** `MailWidget.kt:57-128`

8+ synchronous DB queries during widget update. If DB is locked, widget update blocks.

**Fix:** Combine into a single `@Transaction` DAO method.

---

## MEDIUM Findings

### M-01. CalendarRepository.confirmServerDeletions non-atomic filter+remove
**File:** `CalendarRepository.kt:86-91`

**Fix:** Use `deletedServerIds.removeIf { it !in serverReturnedIds }`.

### M-02. MailRepository.repairXmlEntities raw SQL outside transaction
**File:** `MailRepository.kt:885-937`

Eight `execSQL` calls without transaction. Crash between repairs leaves partial state.

**Fix:** Wrap in `beginTransaction/setTransactionSuccessful/endTransaction`.

### M-03. ContactRepository imports not in transaction
**File:** `ContactRepository.kt:598-640, 645-690`

Contacts inserted one by one. Large imports are slow and non-atomic.

**Fix:** Collect all, then `insertAll` in transaction.

### M-04. EmailOperationsService.moveEmails uses firstEmail.accountId for all
**File:** `EmailOperationsService.kt:599`

No explicit check that all emails belong to the same account.

### M-05. CalendarRepository.createEvent time mismatch between server and DB
**File:** `CalendarRepository.kt:199-212`

For all-day events, server receives original times but DB stores UTC-normalized. Sync may create duplicates.

### M-06. SettingsRepository.cacheScope init race
**File:** `SettingsRepository.kt:45,83-127`

Async init means sync getters may return defaults on first access.

### M-07. RecurrenceHelper.generateMonthlyRelative lacks fast-forward
**File:** `RecurrenceHelper.kt:367-383`

Iterates from `seriesStart` to `rangeStart` without optimization. Old recurring events loop unnecessarily.

### M-08. SimpleDateFormat not thread-safe (instance field)
**File:** `EasCalendarService.kt:73-75`

`easDateFormat` stored as instance field, shared across coroutines.

**Fix:** Create locally per method or use `DateTimeFormatter`.

### M-09. XML injection via user-controlled input
**Files:** `EasAttachmentService.kt:408-414`, various EAS services

`fileReference`, `collectionId`, `serverId` interpolated into XML without escaping.

**Fix:** Apply `escapeXml()` to all values.

### M-10. Inconsistent EWS success validation
**Files:** `EasNotesService.kt:977-981`, `EasTasksService.kt:990-994`

Batch deletes use `hasSuccess || hasNoError || hasNotFound` (too permissive).

**Fix:** Use `(hasSuccess && hasNoError) || hasNotFound`.

### M-11. Unbounded MIME StringBuilder
**Files:** `EasAttachmentService.kt:148-255`, `EasEmailService.kt:1284-1357`

Base64-encoded attachments can require 20+ MB heap per attachment.

**Fix:** Stream directly to `ByteArrayOutputStream`.

### M-12. WbxmlParser duplicate dead code
**File:** `WbxmlParser.kt:293-302`

`writeMultiByteInt` has the `value == 0` check duplicated.

### M-13. WbxmlParser attribute parsing potential infinite loop
**File:** `WbxmlParser.kt:92-98`

No EOF check in attribute read loop.

### M-14. PushService.stopSelf() from IO dispatcher
**File:** `PushService.kt:536`

Should be called from main thread.

### M-15. Unordered set trimming for shown notifications
**Files:** `SyncWorker.kt:64`, `PushService.kt:939`

`Set.toList().takeLast(500)` — no ordering guarantee, may keep old entries.

### M-16. Room IN-clause SQLite bind variable limit
**File:** `Daos.kt:276,279`

`getExistingIds(ids: List<String>)` — SQLite limit of 999 bind variables. Full resync of >999 emails will throw.

**Fix:** Chunk input into groups of 900.

### M-17. goAsync() timeout risk in BroadcastReceivers
**Files:** `CalendarReminderReceiver.kt:40-78`, `TaskReminderReceiver.kt:47-59`

DB access in `goAsync()` can hang past 30s deadline, causing ANR.

**Fix:** Add `withTimeoutOrNull(25_000)`.

### M-18. MailApplication.cleanupDuplicateEmails on every cold start
**File:** `MailApplication.kt:81-94`

Full table scan with GROUP BY on every startup.

**Fix:** Track with SharedPreferences, run only after migrations.

### M-19. EmailDao.insertAll uses REPLACE — cascades delete attachments
**File:** `Daos.kt:269-270`

`REPLACE` is `DELETE + INSERT`, triggering `ForeignKey.CASCADE` on attachments.

**Fix:** Audit all call sites; prefer `insertAllIgnore` + explicit updates.

### M-20. PushService.scheduleRestart without SCHEDULE_EXACT_ALARM check
**File:** `PushService.kt:420`

On Android 12+, `setExactAndAllowWhileIdle` requires permission check.

### M-21. AppNavigation openInboxUnread has no intent-ID guard
**File:** `AppNavigation.kt:493-524`

Simple boolean — recomposition can trigger duplicate navigation.

**Fix:** Add counter guard like `lastHandledEmailIntentId`.

### M-22. CalendarScreen auto-sync race with isSyncing
**File:** `CalendarScreen.kt:176-190`

Rapid account switches can prevent sync of new account.

### M-23. ContactsScreen auto-sync on isEmpty keys
**File:** `ContactsScreen.kt:110-122`

LaunchedEffect restarts when contacts go from empty to non-empty.

### M-24. ContactsScreen selection TransactionTooLargeException
**File:** `ContactsScreen.kt:125-136`

`ArrayList<String>` in Bundle has 1MB limit. Thousands of selections can exceed it.

### M-25. TasksScreen deleted filter always triggers sync
**File:** `TasksScreen.kt:172-188`

No throttle on filter change to DELETED.

### M-26. NotesScreen tab change to Deleted always syncs
**File:** `NotesScreen.kt:138-151`

Same issue.

### M-27. NoteRepository.syncNotes side-effect inside mapNotNull
**File:** `NoteRepository.kt:900`

`noteDao.delete()` called as side-effect inside mapping lambda.

### M-28. CalendarScreen renamedOccurrenceIds O(n*m) performance
**File:** `CalendarScreen.kt:327-346`

Groups + find loop is expensive for large datasets.

---

## LOW Findings

### L-01. Logging sensitive data in production
**Files:** `EasNotesService.kt`, `EasTasksService.kt`, `EasDraftsService.kt`

`Log.d()` outputs EWS ItemIds, server URLs, response previews.

### L-02. EasClient.detectEasVersion uses synchronous execute()
**File:** `EasClient.kt:338`

Not cancellation-aware. Should use `executeRequest()`.

### L-03. Ping creates new OkHttpClient per call
**File:** `EasClient.kt:1377-1381`

Unnecessary object churn.

### L-04. Password stored as plain String
**File:** `NtlmAuthenticator.kt:16`

Consider `CharArray` and zeroing after use.

### L-05. WbxmlParser input.available() unreliable
**File:** `WbxmlParser.kt:61`

`available()` provides only an estimate.

### L-06. WbxmlParser SimpleXmlParser fragile parsing
**File:** `WbxmlParser.kt:333-395`

Does not handle CDATA, comments, or `>` in attributes.

### L-07. ReDoS risk in extractBodyFromMime
**File:** `EasEmailService.kt:1524-1526`

`"^[A-Za-z0-9+/=\\s]+$"` regex runs on entire MIME string.

### L-08. TaskRepository unused variables
**File:** `TaskRepository.kt:241,264`

`requestDuration` and `retryDuration` computed but never used.

### L-09. ContactRepository.parseCSVLine loses escaped quotes
**File:** `ContactRepository.kt:730-748`

Doubled quotes `""` parsed as toggles.

### L-10. RecurrenceHelper.exceptionsToJson manual JSON construction
**File:** `RecurrenceHelper.kt:134-149`

Doesn't escape all unicode control characters.

### L-11. SettingsRepository duplicated cache-update logic
**File:** `SettingsRepository.kt:83-127`

Init block and collect block contain identical code for 20+ values.

### L-12. FolderSyncService.syncFoldersEas N*2 queries for folder counts
**File:** `FolderSyncService.kt:240-243`

Per-folder `getCountByFolder + getUnreadCount` instead of batch.

### L-13. CalendarRepository.syncCalendar recomputes existingEventsMap twice
**File:** `CalendarRepository.kt:1086,1161`

### L-14. SyncWorker new-email ordering by String ID
**File:** `SyncWorker.kt:388`

Lexicographic comparison. Use `dateReceived`.

### L-15. SyncWorker.Result.retry() no-op on PeriodicWork
**File:** `SyncWorker.kt:299`

### L-16. hashCode collisions for PendingIntent request codes
**Files:** `CalendarReminderReceiver.kt:92`, `TaskReminderReceiver.kt:75`

### L-17. OutboxWorker missing POST_NOTIFICATIONS check
**File:** `OutboxWorker.kt:231-259`

On Android 13+, notification silently fails.

---

## Exchange 2007 SP1 Specific Notes

1. **RequestServerVersion** — `deleteSingleOccurrenceEws` correctly sets `Exchange2007_SP1`. Verify all other EWS SOAP envelopes also include this header.
2. **EAS protocol version** — `Semaphore(2)` for parallel sync is correct to avoid throttling on older servers.
3. **CalendarView vs FindItem** — Exchange 2007 SP1 `FindItem` with `CalendarView` does NOT return `Attachments` collection; current `supplementAttachmentsViaEws` GetItem fallback is correct.
4. **TLS compatibility** — Exchange 2007 SP1 may require TLS 1.0/1.1 but should only be enabled per-account, not globally.
5. **WBXML parsing** — Exchange 2007 SP1 sends WBXML for EAS commands; the infinite loop and partial read bugs (C-07, C-08) are especially dangerous here.

---

## Recommended Priority Order

### Immediate (before release)
1. C-01 — MailDatabase double-init (data corruption)
2. C-02 — OutboxWorker data loss
3. C-07 + C-08 — WbxmlParser hangs and data corruption
4. C-04 — SyncKey race condition
5. H-01 — PushService crash on Android 14+

### Short-term (next sprint)
6. C-03 — Notification mutex
7. C-05 — CancellationException handling sweep
8. C-06 — Response body leaks
9. H-02 — NtlmAuthenticator bounds check
10. H-03 + H-04 — Thread safety for cached state and WbxmlParser

### Medium-term (planned)
11. C-09, C-10, C-11 — Architecture (ViewModel layer)
12. H-05 — Replace regex parsing with streaming XML
13. H-09, H-10 — TLS/security hardening
14. M-16 — SQLite bind variable limit
15. M-19 — REPLACE cascade issue
