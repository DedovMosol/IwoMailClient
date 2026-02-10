# iwo Mail Client

🇷🇺 [Русская версия](README.md)

Android mail client with Microsoft Exchange Server 2007+ (ActiveSync), IMAP and POP3 support.

**Version:** 1.6.1  
**Developer:** DedovMosol  
**Telegram:** [@i_wantout](https://t.me/i_wantout)  
**Email:** andreyid@outlook.com

## 🌟 Features

- 📧 **Exchange ActiveSync** — EAS 12.0-14.1 support (Exchange 2007+). Tested on Exchange 2007 SP1
- 🔄 **EWS for Exchange 2007** — notes sync and creation via EWS with NTLMv2 (fallback for EAS 12.x)
- 📬 **IMAP/POP3** — works with any mail server (beta)
- 📱 **Android 8.0 - 16** — works on all Android versions from Oreo to the latest
- 🔒 **Exchange 2007 compatibility** — TLS 1.0-1.3 support via Conscrypt
- 🔐 **Server certificates** — self-signed certificate support for corporate servers
- 🌍 **Two languages** — 🇷🇺 Russian / 🇬🇧 English
- 🎨 **Material Design 3** — modern interface with color themes
- 🔔 **Push notifications** — instant notifications for new emails (Direct Push)

## 📱 Capabilities

- ✅ **Mail** — sync, send with attachments, search, filters, favorites, folder management
- ✅ **Contacts** — personal and GAL, groups, import/export (vCard, CSV)
- ✅ **Calendar** — events with reminders, invite attendees
- ✅ **Notes** — create, edit, sync
- ✅ **Tasks** — create, edit, sync
- ✅ **Notifications** — Push (Direct Push), background sync, night mode
- ✅ **Interface** — dark/light theme, 7 color schemes, personalization
- ✅ **Multiple accounts** — with individual signatures and settings
- ✅ **MDN/DSN** — read and delivery receipt requests

## 🆕 What's New in v1.6.1

- Rich Text editor, HTML signatures, folder management
- Client certificates (mTLS), Certificate Pinning
- Fixed: reply/forward (files, inline images), read status sync, deletion, system folders
- Sync reentrancy protection, extended markAsRead error handling
- Refactoring: 15 services, 6 repositories, performance improvements

📋 Full changelog: [CHANGELOG_EN.md](docs/CHANGELOG_EN.md)

---

## ⚠️ Important: Package Rename

**Version 1.6.1 changed the package name** from `com.iwo.iwomail` to `com.dedovmosol.iwomail`.

**This means:**
- ❌ APK update **is impossible** — Android treats it as a different app
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

## 🖥️ Supported Servers

| Server | Status |
|--------|--------|
| Exchange 2007 SP1/SP2 | ✅ Stable |
| Exchange 2010/2013+ | ⚠️ Needs testing |
| Office 365 | ⚠️ Requires OAuth |
| IMAP/POP3 servers | ⚠️ Beta |

## 💡 Known Limitations

- **Office 365 / Modern Auth** — OAuth 2.0 not yet supported (Basic Auth only)
- **IMAP/POP3** — beta version, may be unstable
- **EAS 16.0+** (Exchange 2016+) — not tested, possible issues
- **S/MIME signatures** — not supported
- **Calendar** — no recurring events support

## 📊 Tech Stack

**Language & Frameworks:**
- Kotlin 1.9.24
- Jetpack Compose — UI
- Coroutines + Flow — async
- Material Design 3 — design

**Storage:**
- Room Database — local DB
- DataStore — settings
- SQLCipher — encryption (optional)

**Network & Protocols:**
- OkHttp 4.12.0 — HTTP client
- Conscrypt — TLS 1.0-1.3 (Exchange 2007 support)
- EAS 12.0-14.1 — ActiveSync
- EWS (NTLM) — Exchange Web Services
- JavaMail — IMAP/POP3/SMTP

**Security:**
- Certificate Pinning (Public Key Pinning)
- SSL/TLS mutual authentication (mTLS)
- Self-signed certificates support

**Other:**
- WorkManager — background sync
- Hilt — dependency injection
- Coil — image loading

## 🔧 Build

```bash
./gradlew assembleDebug    # Debug
./gradlew assembleRelease  # Release
```

## 📖 Documentation

- [Changelog](CHANGELOG_EN.md)
- [Project Architecture](docs/ARCHITECTURE.md)
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
  Jetpack Compose — 23 screens, 7 components
  Navigation, Theme, Localization
    ↓
Repository Layer
  AccountRepository, MailRepository, CalendarRepository,
  ContactRepository, NoteRepository, TaskRepository, SettingsRepository
  + EmailSyncService, EmailOperationsService, FolderSyncService
    ↓
Protocol Layer
  EAS/EWS — EasClient (Email, Calendar, Tasks, Notes, Drafts, Contacts, Attachment)
  IMAP — ImapClient  |  POP3 — Pop3Client  |  SMTP — SmtpClient
    ↓
Database Layer                    Network Layer
  Room — 7 DAOs, 6 Entities        HttpClientProvider, NetworkMonitor
  MailDatabase                      RetryUtils, NtlmAuthenticator
    ↓
Background Services
  PushService, SyncWorker, OutboxWorker
  BootReceiver, SyncAlarmReceiver, PushRestartWorker
  CalendarReminderReceiver, TaskReminderReceiver
```

## 📄 License

MIT License

---

© 2025-2026 DedovMosol
