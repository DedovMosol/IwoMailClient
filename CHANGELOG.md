# Changelog

All notable changes to iwo Mail Client will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.6.3b] - 2026-08-13

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
  - See `L7_COMPLETION_REPORT.md` for detailed implementation notes

### Changed

- **N-4: Optimized `parseEwsDateTime` timezone handling**
  - Replaced `SimpleDateFormat` with `java.time.Instant.parse()` for ISO 8601 parsing
  - Simplified logic: removed regex fractional seconds stripping and conditional branching
  - Code reduced from 20 lines to 12 lines (40% reduction)
  - Improved compliance with DRY/KISS/SOLID/YAGNI/SOC principles
  - All existing tests pass without modification
  - See `N4_VERIFICATION_REPORT.md` for detailed analysis

### Architecture

- Extracted password storage logic into `data/security` package
- Improved separation of concerns: storage, telemetry, and business logic now independent
- Reduced `AccountRepository` complexity by removing 58 lines of inline crypto code
- Applied SOLID principles: Single Responsibility, Dependency Inversion

### Documentation

- Updated `docs/AUDIT.md` with L-7 completion details
- Created `L7_COMPLETION_REPORT.md` with comprehensive implementation documentation
- Created `N4_VERIFICATION_REPORT.md` with timezone parsing verification
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
