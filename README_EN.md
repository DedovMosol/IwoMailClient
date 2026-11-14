<p align="center">
  <img src="docs/banner.png" alt="iwo Mail Client — Exchange ActiveSync (EAS 12.0, 12.1, 14.0, 14.1), EWS, Android" width="860">
</p>

<p align="center">
  <a href="https://github.com/DedovMosol/IwoMailClient/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/DedovMosol/IwoMailClient/actions/workflows/ci.yml/badge.svg"></a>
  <a href="https://github.com/DedovMosol/IwoMailClient/releases"><img alt="Release" src="https://img.shields.io/github/v/release/DedovMosol/IwoMailClient?label=release&color=7DC42A"></a>
  <a href="https://github.com/DedovMosol/IwoMailClient/releases"><img alt="Downloads" src="https://img.shields.io/github/downloads/DedovMosol/IwoMailClient/total?label=downloads&color=0078D4"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-GPLv3-7DC42A.svg"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android-23272E">
  <img alt="Min API" src="https://img.shields.io/badge/API-26%2B-7DC42A">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white">
</p>

<div align="justify">

🇷🇺 **Русская версия:** [README.md](README.md)

Android mail client focused on **Microsoft Exchange Server 2007 SP1+** through **Exchange ActiveSync (EAS)** and **Exchange Web Services (EWS)**. IMAP/POP3 are present as a beta direction with limited functionality.

> **Important:** 🚧 The project is under active development. The next release will be **1.7.0**. It will differ significantly from 1.6.1 and the subsequent beta builds.

**Version:** 1.6.3b (beta, versionCode 26; latest published release — 1.6.1) · **Package:** `com.dedovmosol.iwomail` · **Developer:** DedovMosol · **Telegram:** [@i_wantout](https://t.me/i_wantout) · **Email:** andreyid@outlook.com

## 📖 About

- **Production focus:** Exchange 2007 SP1/SP2 and compatible on-premise Exchange servers.
- **Primary protocol:** EAS 12.0/12.1/14.0/14.1 for mail, folders, contacts, Direct Push and part of the operations.
- **EWS addition:** calendar, tasks, notes, drafts, calendar attachments, meeting responses and fallback scenarios for Exchange 2007.
- **Auth:** Basic Auth, NTLMv2 fallback additionally for EWS operations. The EAS transport works with Basic Auth only. OAuth 2.0 / Modern Auth are not yet implemented.
- **Local model:** offline-first via Room DB, the UI reads data through Flow, background services update the database.

## ✨ Features

- **📧 Mail:** sync of system and user folders, HTML sending, attachments, inline images, CC/BCC, importance, search, filters, favorites, conversation threads (by `ConversationId` on EAS 14.0+, by subject on Exchange 2007), multi-select, batch operations, configurable left/right swipes (to Deleted Items, read/unread, flag).
- **🚩 Flags:** follow-up flag with due date and reminder (`[MS-ASEMAIL] Flag`, as in Outlook); the star is set immediately and reverts to the server value if the server rejects it. A star or read state set while offline is kept locally, queued in `pending_email_changes` and sent to the server later (`PendingMailChangesWorker`).
- **📖 Reading:** remote images are not loaded by default (show for one message, trusted senders, "always load"), unsubscribe from mailing lists (`List-Unsubscribe`, one-click per RFC 8058), print and save as PDF via the system print dialog, save a message as `.eml`, answer read-receipt requests (decline — EWS `SuppressReadReceipt`), Outlook RSS items (`IPM.Post.RSS`) with a link to the article.
- **📝 Drafts:** server mode via Exchange/EWS and a local beta mode, selectable in onboarding and account settings.
- **📤 Sending:** scheduled send (exact alarm + WorkManager) and a scheduled mail screen (send now, reschedule, cancel), outbox queue for offline scenarios, undo send (0/5/10/20/30 s), MDN/DSN requests.
- **👥 Contacts:** local and Exchange contacts, GAL, groups, favorites, vCard/CSV import/export, address autocomplete.
- **🗓 Calendar:** events, meetings, attendees, invitation responses, recurrences, exceptions, reminders, attachments, online links, category colors (Master Category List on Exchange 2010+, standard Outlook colors, manual assignment), local trash, permanent delete only after server confirmation, reset and resync.
- **✅ Tasks:** active/completed/important/overdue tasks, due dates, priorities, assignment, reminders, trash and restore, reset and resync; edits and moves to trash made offline are kept on the device and sent when the connection is back (`pending_pim_changes`).
- **🗒 Notes:** Exchange Notes sync, create/edit, trash and restore, reset and resync; offline edits and trash work as for tasks.
- **🔔 Notifications:** Direct Push via EAS Ping, WorkManager sync, AlarmManager fallback, notifications for mail, calendar, tasks, flags, updates and outbox, a calendar/task/flag reminder tap switches to that account and opens the item, separate notification channels per account (sound and importance in system settings), quiet hours and VIP senders (their mail still sounds during quiet hours), server connection-loss notification.
- **🔐 App lock:** password (only a PBKDF2-HMAC-SHA256 hash is stored, 600,000 iterations) and fingerprint; locks on cold start and when the app goes to the background.
- **🎨 Interface:** Jetpack Compose, Material 3, RU/EN localization, light/dark/system theme, color schemes, daily themes, custom file icons, drag selection; settings split into Accounts, Mail, Notifications, Personalization, Security, About.
- **📊 Widget & shortcuts:** Glance home-screen widget, quick access to mail, search, calendar, tasks and compose; widget data is read via lightweight Room projections without loading heavy mail/event bodies.
- **⬆️ Updates:** `update.json` check on GitHub, APK selection by ABI, download, install and rollback preparation.

<details>
<summary><b>🗓 Calendar and attachments — how it works</b></summary>

- **DRY for recurring events:** calendar attachments are stored as JSON metadata (`fileReference`, name, size), not as file copies for each occurrence.
- **Exchange 2007 SP1:** calendar attachment upload/fetch goes through EWS `CreateAttachment`/`GetItem`; recurring series use the master ItemId.
- **Permanent delete:** local DB deletion happens only after successful server-side deletion; operations are serialized with calendar sync via a per-account mutex.
- **CRA resurrection prevention:** attendee meetings are declined through `MeetingResponse`/EWS `DeclineItem` before deletion; if the original meeting request cannot be found, local deletion is not performed.
- **Meeting invitations:** on Exchange 2010/2013 (EAS 14.x) meetings with attendees are created and edited through EWS `CreateItem`/`UpdateItem` so the server sends invitations/updates; a plain appointment without attendees stays on EAS.
- **Preview cache:** attachment previews use a temporary `cacheDir/calendar_preview` directory, stable names derived from `fileReference`, and delayed cleanup without races with external viewers.

</details>

<details>
<summary><b>🧩 Widget and performance</b></summary>

- **Lightweight DAO projections:** the widget reads only displayed fields for recent unread emails, the next task and the next calendar event.
- **Correct unread logic:** the widget's new-mail list is filtered by Inbox and `read = 0`.
- **Race protection:** `updateMailWidget()` serializes `GlanceAppWidget.updateAll()` through a shared mutex and uses `applicationContext`.
- **Width-adaptive layout:** the bottom action row adapts to widget width (a Glance `Row` never wraps its content) — narrow sizes show fewer avatars and hide the last-sync label so the buttons aren't clipped; avatars scale with the widget.
- **Room indexes (migration 41→42):** indexes for hot widget queries: unread Inbox, folder type, active tasks, current and upcoming calendar events. The current schema version is v54.

</details>

## 📸 Screenshots

| | |
|:-:|:-:|
| <img src="screenshots/1.jpg" width="230"><br>**Home** — daily summary and section tiles | <img src="screenshots/2.jpg" width="230"><br>**Navigation drawer** — folders and sections |
| <img src="screenshots/3.jpg" width="230"><br>**Account settings** | <img src="screenshots/4.jpg" width="230"><br>**Inbox** |
| <img src="screenshots/5.jpg" width="230"><br>**Message view** — HTML rendering | <img src="screenshots/6.jpg" width="230"><br>**Compose** |
| <img src="screenshots/7.jpg" width="230"><br>**Tasks** — filters, priorities, due dates | <img src="screenshots/8.jpg" width="230"><br>**Notes** |
| <img src="screenshots/10.jpg" width="230"><br>**Calendar (event list)** | <img src="screenshots/9.jpg" width="230"><br>**Calendar event view** |

## ⬇️ Install

1. Open the [**Releases**](https://github.com/DedovMosol/IwoMailClient/releases) page and grab the latest version.
2. Download the APK for your device architecture (or `universal` if unsure):

   | ABI | For which devices |
   |-----|-------------------|
   | `arm64-v8a` | most modern phones (recommended) |
   | `armeabi-v7a` | older 32-bit ARM devices |
   | `x86_64` / `x86` | emulators and x86 tablets |
   | `universal` | a single APK for all architectures |

3. Allow installation from this source and install the APK.
4. On first launch, go through onboarding and add an Exchange account.

> The built-in updater checks `update.json` in the repository, picks an APK by ABI and prepares a rollback.

## 🖥 Requirements

| Parameter | Minimum | Recommended |
|-----------|---------|-------------|
| Android | 8.0+ (API 26) | — |
| RAM | 2 GB | 4+ GB |
| Storage | 50 MB | 100+ MB |
| CPU/ABI | armeabi-v7a / x86 | arm64-v8a / x86_64 |

- **Compile SDK:** 36 · **Target SDK:** 36 · **Java/Kotlin target:** JVM 17
- **APK:** universal + ABI splits for `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`
- **Build:** JDK 17 required (the minimum for Android Gradle Plugin 8.x)

## 🔌 Supported servers

| Server | Status |
|--------|--------|
| Exchange 2007 SP1/SP2 | ✅ Stable primary scenario |
| Exchange 2010/2013/2016 on-premise | 🟡 Architecturally supported via EAS/EWS, needs verification on the specific infrastructure |
| Office 365 / Exchange Online | 🟠 Limited: requires OAuth/Modern Auth, which is not yet implemented |
| IMAP/POP3 | 🧪 Beta: read/sync via JavaMail, without full parity with Exchange |

## 🔒 Security and compatibility

- **Conscrypt:** added for TLS 1.0/1.1 and compatibility with old Exchange 2007 installations.
- **Server certificates:** system, user and self-signed certificate support.
- **mTLS:** PKCS#12 client certificates (`.p12`/`.pfx`) with KeyManager caching.
- **Certificate Pinning:** stores SHA-256, CN/O and certificate dates per account; the alternate server has its own pin.
- **Hostname verification:** for an imported server certificate — `Rfc6125HostnameVerifier` (SAN dNSName/iPAddress, CN only when there is no SAN).
- **No cleartext:** `network_security_config.xml` forbids HTTP (`cleartextTrafficPermitted="false"`).
- **Passwords:** `EncryptedSharedPreferences` (Android KeyStore, AES256-SIV/AES256-GCM) for the primary path; when the Keystore is unavailable — a fallback `ObfuscatedSharedPreferences`: XOR with a key derived by PBKDF2-HMAC-SHA256 from `ANDROID_ID` (obfuscation, not encryption). The user gets a one-time warning; a fail-closed mode exists in code (`SecurityTelemetry`) but has no settings switch.
- **Alternate URL:** primary and backup Exchange URL with fallback on network errors and later auto-switchback.
- **Email body XSS protection:** `sanitizeEmailHtml` blocks `<script>`, plugin containers (`iframe`/`object`/`embed`/`applet`), event handlers, `meta http-equiv="refresh"`, `javascript:` and `data:text/html` URIs in all URL-context attributes; combined with a nonce CSP and `loadDataWithBaseURL(null, ...)` in the WebView for isolation from cross-origin context.

## ⚠️ Known limitations

- **OAuth 2.0 / Modern Auth:** not supported.
- **Office 365:** typically does not work without Basic Auth.
- **NTLM for EAS:** the EAS transport supports Basic Auth only (NTLMv2 applies to EWS operations only); the `/Microsoft-Server-ActiveSync` virtual directory must allow Basic Auth.
- **IMAP/POP3:** beta implementation, does not cover calendar, contacts, tasks, notes, Direct Push and EWS features. There is no SMTP sending: the outgoing server address is only stored in the account.
- **Offline queue:** message star and read state, message moves (including to trash), task and note edits and moves to trash. Creating tasks and notes, the calendar and folder operations are still server-first and are not deferred without a connection.
- **Exchange contacts:** read-only. They come from the server's Contacts folder (EAS Sync); edit and delete them in Outlook or OWA. In the app an Exchange contact can be copied to personal contacts, which are editable.
- **S/MIME:** signing and encryption not implemented.
- **EAS 16.x:** explicitly not a target version of the project; the code's main range is EAS 12.x-14.1.

## 🧱 Tech stack

| Category | Technologies |
|-----------|------------|
| Language | Kotlin 1.9.22, Java 17 |
| Build | Gradle 8.12, AGP 8.7.3, KSP 1.9.22-1.0.17, Kover 0.7.6, minSdk 26, targetSdk 36 |
| UI | Jetpack Compose, Compose BOM 2024.06.00, Material 3, Navigation 2.7.6, Lifecycle/ViewModel 2.7.0 |
| Async | Coroutines 1.7.3, Flow |
| Storage | Room 2.6.1 (`MailDatabase` v54), DataStore Preferences 1.1.1 |
| Network | OkHttp 4.12.0, Conscrypt 2.5.2 |
| Protocols | EAS, EWS, JavaMail IMAP/POP3 (`android-mail` 1.6.7) |
| Background | WorkManager 2.9.0, Foreground Service, AlarmManager |
| Security | AndroidX Security Crypto 1.1.0-alpha06, Biometric 1.1.0 (+ Fragment 1.8.6), TLS/mTLS, Certificate Pinning |
| UI extras | Coil 2.5.0, Glance AppWidget 1.1.1 |
| Tests | JUnit 4.13.2, MockK 1.13.8, Truth 1.1.5, Robolectric 4.12.2 |
| DI | Manual DI via `RepositoryProvider` |

## 🏗 Architecture at a glance

```text
UI Layer
  Jetpack Compose, Navigation, Theme, Localization
  MainScreen, Setup/Verification, Mail, Compose, Contacts, Calendar, Notes, Tasks, Settings, Lock, Updates
    ↓
Repository Layer
  AccountRepository, MailRepository, CalendarRepository, ContactRepository,
  NoteRepository, TaskRepository, SettingsRepository, AccountServerHealthRepository
  EmailSyncService, EmailOperationsService, FolderSyncService, AppFileCleanupService
    ↓
Protocol Layer
  EasClient facade
  EasTransport + EAS services + EWS client + IMAP/POP3 beta clients
    ↓
Persistence / Network
  Room MailDatabase v54, DataStore
  HttpClientProvider, NetworkMonitor, NtlmAuthenticator
    ↓
Background
  PushService, SyncWorker, OutboxWorker, PendingMailChangesWorker, scheduled send, reminders, notifications, watchdogs
```

Details: [Project Architecture](docs/ARCHITECTURE_EN.md) · [RU](docs/ARCHITECTURE.md)

## 🛠 Build from source

Recommended production build path is Android Studio with JDK 17. The CLI commands below are a supplemental option when `JAVA_HOME` is configured correctly.

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

On Windows:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

## 📚 Documentation

<p align="center">
  <a href="docs/CHANGELOG_EN.md"><img alt="Changelog" src="https://img.shields.io/badge/Changelog-EN-0366D6"></a>
  <a href="docs/CHANGELOG_RU.md"><img alt="История изменений" src="https://img.shields.io/badge/Changelog-RU-2EA44F"></a>
  <a href="docs/ARCHITECTURE_EN.md"><img alt="Architecture project in detail" src="https://img.shields.io/badge/Architecture-EN_detailed-FB8C00"></a>
  <a href="docs/ARCHITECTURE.md"><img alt="Архитектура проекта подробно" src="https://img.shields.io/badge/Архитектура_проекта-подробно-E36209"></a>
</p>
<p align="center">
  <a href="docs/SECURITY.md"><img alt="Security model" src="https://img.shields.io/badge/Security-6F42C1"></a>
  <a href="docs/SECURITY.md#reporting-a-vulnerability"><img alt="Report a vulnerability" src="https://img.shields.io/badge/Report_a_vulnerability-D73A49"></a>
  <a href="docs/XMLPULLPARSER_MIGRATION_PLAN.md"><img alt="XML parser plan" src="https://img.shields.io/badge/XML_parser-plan-6A737D"></a>
</p>
<p align="center">
  <a href="docs/PRIVACY_POLICY_EN.md"><img alt="Privacy Policy" src="https://img.shields.io/badge/Privacy_Policy-EN-2188FF"></a>
  <a href="docs/PRIVACY_POLICY_RU.md"><img alt="Политика конфиденциальности" src="https://img.shields.io/badge/Политика_конфиденциальности-RU-7DC42A"></a>
</p>

## ♻️ Important: package rename

Version 1.6.1 changed the package from `com.iwo.iwomail` to `com.dedovmosol.iwomail`.

- Updating old APKs as a normal update is impossible: Android treats it as a different app.
- Reinstallation required: uninstall the old version, install the new one and re-add accounts.
- Before moving from the old package, export local contacts/data.

## 💬 Feedback

- **Telegram:** [@i_wantout](https://t.me/i_wantout)
- **Email:** andreyid@outlook.com
- **Issues:** [GitHub Issues](https://github.com/DedovMosol/IwoMailClient/issues)

## ⚖️ License

Licensed under the [GNU General Public License v3.0](LICENSE). Releases made before the switch to GPLv3 were published under MIT and remain under it.

---

© 2025-2026 DedovMosol

</div>
