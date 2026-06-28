# iwo Mail Client

🇷🇺 [Русская версия](README.md)

Android mail client focused on Microsoft Exchange Server 2007 SP1+ through Exchange ActiveSync and EWS. IMAP and POP3 are available as beta mail-only paths.

**Version:** 1.6.3b
**Developer:** DedovMosol
**Telegram:** [@i_wantout](https://t.me/i_wantout)
**Email:** andreyid@outlook.com

## 🌟 Features

- 📧 **Exchange ActiveSync** — EAS 12.0-14.1 support (Exchange 2007+). Tested on Exchange 2007 SP1 (EAS 12.1)
- 🔄 **EWS for Exchange 2007** — calendar, tasks, notes, drafts, calendar attachments and meeting responses via EWS with NTLMv2 (fallback for EAS 12.x)
- 📬 **IMAP/POP3** — beta mail-only support through JavaMail
- 📱 **Android 8.0 - 16** — works on all Android versions from Oreo to the latest
- 🔒 **Exchange 2007 compatibility** — TLS 1.0-1.3 support via Conscrypt
- 🔐 **Server certificates** — self-signed certificate support for corporate servers
- 🔑 **Client certificates** — mTLS authentication via PKCS#12 (.p12/.pfx) for corporate environments with mutual TLS verification
- 🌍 **Two languages** — 🇷🇺 Russian / 🇬🇧 English
- 🎨 **Material Design 3** — modern interface with color themes
- 🔔 **Push notifications** — instant notifications for new emails (Direct Push)

## 📱 Capabilities

- ✅ **Mail** — sync, send with attachments, search, filters, favorites, folder management, drafts, scheduled send, send group users
- ✅ **Contacts** — personal and GAL, groups, import/export (vCard, CSV)
- ✅ **Calendar** — recurring events, attendee management, attachments, online meeting links, local trash and server-confirmed permanent delete
- ✅ **Notes** — create, edit, sync
- ✅ **Tasks** — create, edit, assign, reminders, sync
- ✅ **Notifications** — Push (Direct Push), background sync, night mode
- ✅ **Auto-cleanup** — configurable Trash/Drafts/Spam cleanup per account (interval-based or disabled via “Never”)
- ✅ **Widget** — home screen widget with quick access to emails, search, calendar, tasks, and compose; widget data uses lightweight Room projections without loading heavy email/event bodies
- ✅ **Interface** — dark/light theme, 4 color schemes, personalization
- ✅ **Multiple accounts** — with individual signatures and settings
- ✅ **MDN/DSN** — read and delivery receipt requests

📋 Changelog: [CHANGELOG_EN.md](docs/CHANGELOG_EN.md)

---

## ⚠️ Important: Package Rename

**Version 1.6.1 changed the package name** from `com.iwo.iwomail` to `com.dedovmosol.iwomail`.

**This means:**
- ❌ APK update old versions **is impossible** — Android treats it as a different app
- ⚠️ Full **reinstallation required** — uninstall old version, install new one
- 💾 Data will be lost — export contacts/settings before updating
- ✅ Future versions will update normally

**How to update:**
1. Export contacts (vCard/CSV) in the app
2. Uninstall old version `com.iwo.iwomail`
3. Install new version `com.dedovmosol.iwomail`
4. Set up accounts again
5. Import contacts

---

## 📋 Requirements

| Parameter | Minimum | Recommended |
|-----------|---------|-------------|
| Android | 8.0+ (API 26) | — |
| RAM | 2 GB | 4+ GB |
| Storage | 50 MB | 100+ MB |
| CPU | ARMv7 / x86 | ARM64 / x86_64 |

- Target SDK: 36 (Android 16)
- Supported architectures: armeabi-v7a, arm64-v8a, x86, x86_64
- Build JDK: JDK 17 recommended (JDK 11 minimum for Android Gradle Plugin 8.7.3)

## 🖥️ Supported Servers

| Server | Status |
|--------|--------|
| Exchange 2007 SP1/SP2 | ✅ Stable |
| Exchange 2010/2013+ | ⚠️ Needs testing |
| Office 365 | ⚠️ Requires OAuth |
| IMAP/POP3 servers | ⚠️ Beta |

## 📅 Calendar and Attachments

- **DRY recurring attachments** — calendar attachments are stored locally as JSON metadata (`fileReference`, name, size), not duplicated file bytes for every occurrence.
- **Exchange 2007 SP1** — calendar attachment upload/fetch uses EWS `CreateAttachment`/`GetItem`; recurring series resolve the master ItemId.
- **Permanent delete** — local DB deletion happens only after successful server-side deletion; calendar mutations are serialized with per-account sync locks.
- **CRA resurrection prevention** — attendee meetings are declined through `MeetingResponse` or EWS `DeclineItem` before deletion; if the original meeting request cannot be found, local deletion is blocked.
- **Preview cache** — attachment previews use `cacheDir/calendar_preview`, stable names derived from `fileReference`, and delayed cleanup designed to avoid races with external viewers.

## 🧩 Widget and Performance

- **Lightweight DAO projections** — the widget reads only displayed fields for recent unread emails, the next task and the next calendar event.
- **Correct unread logic** — the widget's new-mail list is limited to Inbox and `read = 0`.
- **Race protection** — `updateMailWidget()` serializes `GlanceAppWidget.updateAll()` through a shared mutex and uses `applicationContext`.
- **Room v42** — hot widget paths are indexed for unread Inbox, folder type lookup, active tasks, current calendar events and upcoming calendar events.

## 💡 Known Limitations

- **Office 365 / Modern Auth** — OAuth 2.0 not yet supported (Basic Auth only)
- **IMAP/POP3** — beta version, may be unstable
- **EAS 16.0+** (Exchange 2016+) — not tested, possible issues
- **S/MIME signatures** — not supported

## 📊 Tech Stack

**Language & Frameworks:**
- Kotlin 1.9.22
- Jetpack Compose — UI
- Coroutines + Flow — async
- Material Design 3 — design

**Storage:**
- Room Database — local DB
- Room schema version: `MailDatabase` v42
- DataStore — settings and per-account sync/notification checkpoints

**Network & Protocols:**
- OkHttp 4.12.0 — HTTP client
- Conscrypt — TLS 1.0-1.3 (Exchange 2007 support)
- EAS 12.0-14.1 — ActiveSync
- EWS (NTLM) — Exchange Web Services
- JavaMail (com.sun.mail) — IMAP/POP3

**Security:**
- Certificate Pinning (Public Key Pinning)
- SSL/TLS mutual authentication (mTLS)
- Self-signed certificates support
- XSS protection for email bodies: sanitizer blocks `<script>`, plugin containers (`iframe`/`object`/`embed`/`applet`), event handlers, `meta http-equiv="refresh"`, `javascript:` and `data:text/html` URIs in URL-context attributes. Combined with `loadDataWithBaseURL(null, ...)` for cross-origin isolation in WebView.
- `EncryptedSharedPreferences` for passwords with obfuscated fallback when Android Keystore is unavailable

**Other:**
- WorkManager — background sync
- Manual DI (RepositoryProvider) — dependency injection
- Coil — image loading

## 🔧 Build

Recommended production build path is Android Studio with JDK 17. The CLI commands below are a supplemental option when `JAVA_HOME` is configured correctly.

```bash
./gradlew assembleDebug    # Debug
./gradlew assembleRelease  # Release
```

## 📖 Documentation

- [Changelog](docs/CHANGELOG_EN.md)
- [Project Architecture](docs/ARCHITECTURE.md)
- [XmlPullParser migration plan](docs/XMLPULLPARSER_MIGRATION_PLAN.md)
- [Privacy Policy](docs/PRIVACY_POLICY.md)

## 🤝 Contributing

This project is open for improvements! Here's how you can help:

**🐛 Report a bug:**
- Telegram: [@i_wantout](https://t.me/i_wantout)
- Email: andreyid@outlook.com
- [GitHub Issues](https://github.com/DedovMosol/IwoMailClient/issues)

**💡 Suggest an improvement:**
- Message on Telegram with your idea
- Or create an Issue on GitHub

**❓ Ask a question:**
- About Exchange 2007/2010/2013 setup
- About sync issues
- Any technical questions

**🔧 Pull Requests:**
- Bug fixes are welcome
- New features are welcome
- Follow existing code style
- Write clear commit messages

## 🏗️ Architecture

```
UI Layer
  Jetpack Compose — 22 screens, 8 components
  Navigation, Theme, Localization
    ↓
Repository Layer
  AccountRepository, MailRepository, CalendarRepository,
  ContactRepository, NoteRepository, TaskRepository, SettingsRepository
  + EmailSyncService, EmailOperationsService, FolderSyncService
    ↓
Protocol Layer
  EAS/EWS — EasClient (Email, Calendar, Tasks, Notes, Drafts, Contacts, Attachment)
  IMAP — ImapClient  |  POP3 — Pop3Client
    ↓
Database Layer                    Network Layer
  Room — 11 DAOs, 10 Entities      HttpClientProvider, NetworkMonitor
  MailDatabase (v42)                NtlmAuthenticator
    ↓
Background Services
  PushService, SyncWorker, OutboxWorker
  BootReceiver, SyncAlarmReceiver, PushRestartWorker
  ServiceWatchdogReceiver, ScheduledEmailWorker
  NotificationHelper, MailNotificationActionReceiver
  CalendarReminderReceiver, TaskReminderReceiver
  MarkEmailReadWorker, MarkTaskCompleteWorker
```

## 📄 License

MIT License

---

© 2025-2026 DedovMosol
