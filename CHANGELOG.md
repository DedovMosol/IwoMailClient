# Changelog

All notable changes to iwo Mail Client will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.6.3b] - 2026-08-13

### Added

- **Hardening audit of foreground-aware push (2026-08-25)** — meticulous self-audit of `3c4e641` found & fixed 3 defects before they shipped:
  - **Race condition in `PushService.syncAccount`** (real bug, introduced by adding the second concurrent caller): the per-account debounce checks at entry but writes the timestamp at EXIT — a check-then-act window. The new foreground-resume sync races the ping loop on the same account; the loser's folder gets «Синхронизация уже выполняется» → misclassified as failure → **full resync with sync-key reset during an active sync = the exact Status=3 data-loss path the InitialSyncController guard was built to prevent** (server-id instability on Exchange 2007 SP1/SP2). Fix: per-account `Mutex` with `tryLock()` — loser returns immediately, never queues, never blocks (documented semantics: «Tries to lock, returning false if already locked»); mutex held for the whole account sync including the HWM/checkAndNotify tail, so two concurrent syncs of ONE account are now impossible; debounce keeps the cross-account anti-stampede role.
  - **SOC cleanup**: `NotificationHelper` decision rebuilt as a single decision point `AppForegroundTracker.shouldShowNewMailNotification(notificationsEnabled, appInForeground)` with REAL inputs (removed the misleading hardcoded `true` argument and the pre-gating that duplicated the policy).
  - **Documentation**: KDoc referenced a renamed function (`shouldSuppressNewMailNotification` → `shouldShowNewMailNotification`).
  - **Best-practice verification (internet docs)**: `Mutex.tryLock` non-suspending semantics confirmed from kotlinx.coroutines docs; `ProcessLifecycleOwner` ON_STOP ~700 ms delay (config-change safety) confirmed from developer.android.com — the tracker cannot false-fire on Activity rotation.
  - Verification: `--rerun-tasks` compile 0 warnings / 0 errors, 616 tests green, `assembleDebug` + `assembleRelease`.
- **Foreground-aware mail notifications & instant inbox delivery (2026-08-25)** — release goal «мгновенное получение сообщений при открытом клиенте» (без FCM — Exchange 2007 on-prem его не шлёт):
  - Новый `AppForegroundTracker` на официальном паттерне `ProcessLifecycleOwner` + `DefaultLifecycleObserver` (androidx `lifecycle-process`): единый источник истины о видимости приложения, потокобезопасный `StateFlow`, идемпотентная инициализация из `Application.onCreate`.
  - Подавление системных уведомлений при открытом клиенте (практика Gmail/Outlook): новое письмо уже мгновенно появляется в списке через реактивный Room Flow — дубль в шторке шум. HWM всё равно продвигается и письма помечаются показанными, поэтому при возврате в фон «догоняющее» уведомление о них не всплывает.
  - `PushService`: немедленная догоняющая инкрементальная синхронизация при выходе приложения из фона — страховка от убитого системой сервиса и переключения сети. Нагрузка под контролем существующих защит (30-с дебаунс на аккаунт, гвард `InitialSyncController`, бюджет 600с). Совместимость с Exchange 2007 SP1/SP2 без изменений: тот же инкрементальный Sync по синхронизационным ключам.
  - 6 новых тестов: 4 — чистая логика решения `shouldShowNewMailNotification` (полная таблица истинности), 2 — интеграция подавления в `checkAndNotifyNewMail` (foreground подавляет + продвигает HWM + помечает показанными; фон уведомляет как раньше).
  - **Верификация**: 616 юнит-тестов зелёные, полная перекомпиляция `--rerun-tasks` — 0 предупреждений / 0 ошибок.

- **Round 4 compiler-warning cleanup (2026-08-25)** — all 101 pre-existing Kotlin warnings eliminated per DRY/KISS/SOLID/SOC/YAGNI; full `--rerun-tasks` rebuild now reports **0 warnings / 0 errors**:
  - **Signature-replacement hardening**: the 4 duplicated `Regex.escapeReplacement(...)` call sites collapsed into a single tested helper `replaceSignatureHtml()` in `ComposeTextUtils` (DRY); 7 new regression tests cover `$`, `\`, `$1`/`$99` group-reference patterns and html-mode signatures.
  - **NTLM/EWS cascade completed (YAGNI/KISS)**: removed ~200 dead `ewsUrl`/`authHeader` references across 12 files — `EasTransport`/`EwsClient` own the URL via constructor; the 5 service `Deps` interfaces, 4 calendar sub-services and `EasClient` no longer thread dead parameters or bridge them with `{ _, _, _ -> }` adapters. `tryBasicAuthEws` is now private in `EasTransport` (single DRY entry `executeEwsWithAuth`).
  - **Sync-loop guard extracted for testability**: `EmailSyncService.shouldContinueSync(sameKeyCount, emptyDataCount)` companion helper (DRY single source of truth) guards both loop-termination paths; 5 new regression tests cover same-syncKey ×5 and empty-data ×5 forced-stop boundaries. `PackageInfo.versionCode` → `PackageInfoCompat.getLongVersionCode()`. **Verification**: 610 unit tests green, `assembleDebug` + `assembleRelease` clean.
  - Deprecated/unsafe API fixes: deprecated `AlertDialog` → `BasicAlertDialog`, deprecated `Locale("ru")` → `Locale.Builder`, unnecessary `!!`/safe-calls/elvis removed, name shadowing and duplicate labels resolved, `@OptIn(ExperimentalCoroutinesApi)` added for Ping resume.


- **Theme mode selection: light / dark / follow system**
  - New `SettingsRepository.ThemeMode` enum (SYSTEM / LIGHT / DARK) with localized display names (RU + EN)
  - Persisted via DataStore key `theme_mode`, thread-safe `AtomicReference` cache + Flow
  - `MainActivity` resolves dark theme from mode + system state; status/navigation bar icon contrast follows the app theme (official `enableEdgeToEdge` + `SystemBarStyle` pattern)
  - "Personalization" screen: theme mode row + selection dialog (radio list with icons)
  - Onboarding: mode choice cards on the theme selection page (first launch)
  - DRY: single icon mapping helper shared by dialog and list row
  - 12 new tests: `ThemeModeTest` (JVM, parsing/localization/roundtrip) + `SettingsRepositoryThemeModeTest` (Robolectric, real DataStore persistence, sync cache, app-restart survival)

### Fixed

- **Settings cache race eliminated (theme / color theme / day themes)**
  - Setter writes now guard their cache keys: the background DataStore collector no longer overwrites a value the setter just wrote (async echo of own writes). Single-writer invariant per official DataStore constraint — no new locks, no perf impact.
  - `getThemeModeSync()` cold-cache fallback: deterministic one-shot DataStore read instead of returning the default, so first launch after restart shows the saved mode immediately.
- **DataStore 1.0.0 → 1.1.1**: official fix for the "Unable to rename" write failure on Windows (`File.renameTo` cannot replace an existing file; 1.1.1 uses atomic move).
- **`ObfuscatedEditor.putString` returned the delegate instead of `this`**: chained `putString().putString()` writes stored the second secret UNOBFUSCATED. Now every chained write is obfuscated (SharedPreferences.Editor contract restored).
- **Test isolation for security singletons**: `PasswordStorage` / `SecurityTelemetry` gained `@VisibleForTesting resetForTesting()` — fail-closed state and stale Application state no longer leak across test classes.
- **`AttachmentLoader` URI resolution now injectable (DIP)**: prod default stays `FileProvider`; tests use a deterministic resolver, so attachment loading is testable without a registered manifest provider.
- **`ComposeViewModel` nav args typed as `String` (was `Long`)**
- **Round 3 audit fixes (2026-08-24)** — top-practice verification (DRY/KISS/SOLID/SOC/YAGNI):
  - **Crash fix**: `Regex.replace(HTML_SIGNATURE_REGEX, newSignatureHtml)` without `Regex.escapeReplacement` — `$` and `\` in replacement string are special chars (Kotlin docs). Signature containing `$` would crash or corrupt. Fixed in 4 places (ComposeScreen ×2, ComposeViewModel ×2).
  - **ANR risk removed**: `getThemeModeSync()` used `runBlocking` on main thread for cold DataStore read. Now reads cache only (consistent with 24 other Sync getters).
  - **DRY**: removed 3 duplicate local functions (`normalizeRecipients`, `extractQueryPart`, `replaceLastRecipient`) from `ComposeScreen`; imports from shared `ComposeTextUtils`.
  - **DRY**: removed `isRussian()` duplicate in `ComposeViewModel`; now uses shared `isRussianLanguage()` from `Localization`.
  - **Test coverage**: 8 new tests for 7 previously uncovered public VM methods (`setImportance`, `setRequestReadReceipt`, `setRequestDeliveryReceipt`, `removeAttachment`, `addGroupsFromPicker`, `dismissDiscardDialog`, `saveDraftAndExit`).
  - **Verification**: 598 tests / 0 failures / 0 errors / 41 suites, `assembleDebug` — 5 APK.
- **Email ID type handling**: real `EmailEntity.id` is a composite `accountId_serverId` string; `Long.toString()` could never match a real email, silently breaking reply/forward/draft prefill.

### Security

- **L-7: Enhanced password storage fallback mechanism**
  - Migrated from inline `ObfuscatedSharedPreferences` to dedicated `PasswordStorage` class
  - Upgraded obfuscation from simple SHA-256 to PBKDF2-HMAC-SHA256 (10,000 iterations) for defense-in-depth
  - Added `SecurityTelemetry` for tracking insecure storage usage and user notification
  - Implemented Toast notification on first fallback to insecure storage
  - Added optional fail-closed mode (disabled by default) via `SecurityTelemetry.setFailClosedMode()`
  - Exposed security status to UI: `AccountRepository.isUsingInsecurePasswordStorage()`
  - Follows OWASP Password Storage Cheat Sheet and Android Security Best Practices
  - Added localized security warning strings (English + Russian)
  - 39 comprehensive unit tests for `PasswordStorage`, `SecurityTelemetry`, and obfuscation

### Changed

- **N-4: Optimized `parseEwsDateTime` timezone handling**
  - Replaced `SimpleDateFormat` with `java.time.Instant.parse()` for ISO 8601 parsing
  - Simplified logic: removed regex fractional seconds stripping and conditional branching
  - Code reduced from 20 lines to 12 lines (40% reduction)
  - Improved compliance with DRY/KISS/SOLID/YAGNI/SOC principles
  - All existing tests pass without modification

### Changed

- **ComposeScreen MVVM migration — Stage 3: business logic moved to `ComposeViewModel` (CS-16)**
  - Recipient validation & normalization live in the VM (`isValidRecipientList` + shared `ComposeTextUtils.normalizeRecipients` — DRY, single source for old screen and VM); group tokens `[GroupName]` preserved
  - Real autocomplete search in VM: groups → local contacts → email history (200 ms debounce, CS-7) + GAL (extra 500 ms network debounce); dedup across sources, own address excluded
  - `sendEmail` in VM: attachment budget checked BEFORE reading bytes (CS-1/CS-2), recipient validation events, scheduled send via `scheduleEmail`, correct `PendingEmail` with group-token expansion, `applicationContext` only (no Activity leak, CS-5)
  - `saveDraft` in VM: same budget gate, inline `data:`URL → `cid:` + ContentId conversion (Outlook/Word compatibility, Exchange 2007 SP1), delete+create for drafts with attachments (EWS UpdateItem cannot remove attachments), anti-resurrection guard + record verification
  - Duplicate-recipient handling moved to VM state (`PendingDuplicateAddition` + confirm/dismiss)
  - Delta-detection on back press (discard dialog) and active-account sync on exit
  - DAO access only through repositories (new wrappers: `searchEmailHistory`, `getContactsByGroupList`, `incrementUseCountByEmail`, `getSignaturesForAccount`, `insertDraftRecord`, `updateEmailBody`)
  - 31 new tests in `ComposeViewModelTest` (validation, dedup, debounce, budget, send, draft, delta-detect, crash-resistance) + 12 pure-function tests in `ComposeTextUtilsTest`
  - **Self-audit hardening (parity re-verification against the reference `ComposeScreen`):** reply/forward/draft loading re-audited and fixed — localized quote labels & headers (RU/EN), `toField = null` in reply quotes, recipient normalization in reply/draft, attachment client created from the *email/draft owner account* (multi-account), draft lazy body load (`loadEmailBody`) + `refreshAttachmentMetadata` + `cid:` resolution with EAS/EWS routing (`fetchInlineImages` / `fetchInlineImagesEws` for long EWS ItemIds, Exchange 2007 SP1/SP2) and persistence of the resolved body, draft attachments routed through `downloadDraftAttachment` (EAS ItemOperations vs EWS GetItem), downloaded files stored in `filesDir/<source>_attachments` (survives cache wipe, covered by `file_paths.xml`), share-intent attachments cleared only after consumption and no longer override reply/forward/draft mode, mailto applies with subject/body only. +18 tests in `ComposeViewModelTest`, +1 in `AttachmentLoaderTest`
  - **Self-audit round 2 (second pass, incl. auditing round-1 fixes):** full error localization — all 11 hardcoded English `ComposeEvent.Error` strings replaced with RU/EN helpers via `getLanguageSync()` (release requirement #2 "полноценная локализация всех ошибок"); attachment-load errors show a clean localized message to the user, raw details go to the log (SOC). Fixed mailto losing the account signature (`applyMailtoIntent` rebuilt body on top of the signature — now signature preserved below the shared body, as in the reference). Fixed send validation accepting recipients only in `to` — now any of to/cc/bcc gates sending (reference parity). Moved `createEasClient` off the Main dispatcher in all three mode loaders (DB + keystore reads were on the UI thread — FPS). Removed dead `ComposeEvent.PlaySendSound` (sound already plays in `SendProgressBar` via `SoundPlayer` — YAGNI). +10 tests in `ComposeViewModelTest` (RU/EN error localization, mailto+signature, cc-only send, no-recipients block, localized attachment error); 5 compiler warnings removed

### Fixed

- **`SendController` stuck `isSending` after send failure**: added `onError` callback (invoked on client-creation failure and server errors); previously the compose UI stayed permanently blocked after a failed send.
- **Build environment: Gradle daemon pinned to JDK 17** (`org.gradle.java.home` in user `gradle.properties`): Robolectric 4.11.1's bundled ASM does not support Java 23 class files (major version 67) — all 5 Robolectric suites failed with `NoClassDefFoundError: android/webkit/RoboCookieManager`. Baseline 517 tests are green again.

### Architecture

- Extracted password storage logic into `data/security` package
- Improved separation of concerns: storage, telemetry, and business logic now independent
- Reduced `AccountRepository` complexity by removing 58 lines of inline crypto code
- Applied SOLID principles: Single Responsibility, Dependency Inversion

### Documentation

- Updated `docs/AUDIT.md` with L-7 completion details
- Updated `README.md` password security section

### Testing

- Added `PasswordStorageTest.kt` (13 tests)
- Added `SecurityTelemetryTest.kt` (11 tests)
- Added `ObfuscatedStorageTest.kt` (15 tests)
- All tests follow Robolectric + Truth best practices
- Total: 39 new unit tests for security improvements

---

## [1.6.3b] - 2026-07-01

### Fixed

- **M-1**: Added crash resistance to ViewModel mutation functions (Notes, Search, Tasks, UserFolders, SyncCleanup)
- **N-12**: Fixed dead `ServiceWatchdogReceiver` on API 26+ by migrating to dynamic registration
- **N-2**: Added pre-check for attachment size before loading into memory (10 MB limit)
- **N-10**: Added try-catch around `PushService.startForeground` to prevent crashes on Android 12+
- **N-11**: Implemented stable `ClientId` for `OutboxWorker` to prevent duplicate sends on retry
- **L-3**: Added CSP to `RichTextEditor` and improved `stripDangerousTags` to remove `on*=` handlers
- **N-7**: Fixed race condition in `EasClient` cache lifecycle by not removing per-account Mutex
- **N-13**: Forced HTTPS for Exchange accounts (removed non-working HTTP option)
- **N-1**: Added CRLF stripping to all MIME header fields to prevent header injection
- **N-3**: Implemented RFC 2047 folding for long Subject headers (max 75 octets per encoded-word)
- **N-5**: Unified inline image extraction logic by delegating to `MimeHtmlProcessor` (DRY)
- **N-8**: Migrated all legacy screens from `collectAsState` to `collectAsStateWithLifecycle`
- **L-1**: Removed dead `clearAllEasClientCache` method
- **L-2**: Removed dead `RepositoryProvider.clear` method
- **N-9**: Removed dead `EmailDao.getEmailsByFolder` method
- **M-2**: Migrated direct `ContactRepository` instantiations to `RepositoryProvider`
- **L-4**: Fixed `deleteDuplicateEmails` grouping key to use canonical `internetMessageId`
- **N-15**: Migrated `PushService.easClientCache` to `ConcurrentHashMap` for thread safety

### Added

- Comprehensive unit tests for all fixes (15+ test files)
- `rememberSafeScope()` for crash-safe coroutine scopes in Composables
- `supervisedScope()` utility for long-lived scopes with exception handling
- `loggingExceptionHandler()` for ViewModel reactive observe-launch patterns

### Documentation

- Updated `docs/AUDIT.md` with completion status for Stages 1, 2, and partial Stage 3
- Added detailed fix descriptions and internet verification sources

---

## [1.6.3a] - 2026-06-29

### Added

- Initial stable release
- Exchange ActiveSync 12.0/12.1/14.0/14.1 support
- EWS support for calendar, tasks, notes, and fallback scenarios
- Basic Auth and NTLMv2 authentication
- Direct Push via EAS Ping
- Room database with 42 migrations
- Jetpack Compose UI with Material 3
- Multi-account support
- IMAP/POP3 beta support
- Certificate pinning
- Widget and shortcuts
- Auto-update system

---

## Version Format

**Format:** MAJOR.MINOR.PATCH[a-z]

- **MAJOR**: Incompatible API changes or major architecture refactoring
- **MINOR**: New features in backward-compatible manner
- **PATCH**: Backward-compatible bug fixes
- **[a-z]**: Pre-release suffix (a=alpha, b=beta, rc=release candidate)

Current version **1.6.3b** indicates:
- Major version 1 (stable API)
- Minor version 6 (feature set 6)
- Patch version 3 (3rd bugfix release)
- Beta status (b)
