# iwo Mail Client

🇷🇺 [Русская версия](README.md)

Android mail client with Microsoft Exchange Server 2007+ (ActiveSync), IMAP and POP3 support.

**Version:** 1.4.1  
**Developer:** DedovMosol  
**Telegram:** [@i_wantout](https://t.me/i_wantout)  
**Email:** andreyid@outlook.com

## 🌟 Features

- 📧 **Exchange ActiveSync** — full EAS 12.0-14.1 support (Exchange 2007+)
- 🔄 **EWS for Exchange 2007** — notes sync and creation via EWS with NTLMv2 (fallback for EAS 12.x)
- 📬 **IMAP/POP3** — works with any mail server (beta)
- 📱 **Android 8.0 - 16** — works on all Android versions from Oreo to the latest
- 🔒 **Exchange 2007 compatibility** — TLS 1.0/1.1 support via Conscrypt
- 🔐 **Server certificates** — self-signed certificate support for corporate servers
- 🌍 **Two languages** — 🇷🇺 Russian / 🇬🇧 English
- 🎨 **Material Design 3** — modern interface with color themes
- 🔔 **Push notifications** — instant notifications for new emails (Direct Push)

## 📱 Capabilities

| Feature | Status |
|---------|--------|
| Folder and email sync | ✅ |
| Send emails with attachments | ✅ |
| Download attachments | ✅ |
| Email search | ✅ |
| Filters (unread, starred, with attachments, by date) | ✅ |
| Favorite emails | ✅ |
| Move/delete/restore emails | ✅ |
| Deferred deletion with undo | ✅ |
| Auto-empty trash | ✅ |
| Create/delete/rename folders | ✅ |
| Contacts with import/export | ✅ |
| Contact groups | ✅ |
| Favorite contacts | ✅ |
| Exchange Notes (create/edit/delete) | ✅ |
| Exchange Calendar (create/edit/delete) | ✅ |
| Email autocomplete | ✅ |
| Multiple accounts | ✅ |
| Multiple signatures | ✅ |
| Push notifications (Direct Push) | ✅ |
| Background sync | ✅ |
| Dark theme | ✅ |
| Color themes (7 colors) | ✅ |
| Interface personalization | ✅ |
| Read/delivery receipt request (MDN/DSN) | ✅ |
| Calendar event reminders | ✅ |
| "Important" filter (high priority) | ✅ |
| Night sync mode | ✅ |
| Battery saver mode | ✅ |

## 🆕 What's New in v1.4.1

- **Server-side drafts** — drafts sync with server via EWS
- **Drafts sync** — deletions from Outlook now reflected
- **SSL fix** — fixed error with self-signed certificates
- **EWS authentication** — fallback to Basic auth

📋 Full changelog: [CHANGELOG_EN.md](CHANGELOG_EN.md)

## 📋 Requirements

| Parameter | Minimum | Recommended |
|-----------|---------|-------------|
| Android | 8.0+ (API 26) | — |
| RAM | 2 GB | 4+ GB |
| Storage | 50 MB | 100+ MB |
| CPU | ARMv7 / x86 | ARM64 / x86_64 |

- Target SDK: 35 (Android 15)
- Supported architectures: armeabi-v7a, arm64-v8a, x86, x86_64

## 🖥️ Supported Servers

| Server | Status |
|--------|--------|
| Exchange 2007 SP1+ | ✅ Full support |
| Exchange 2010-2019 | ✅ Works |
| Office 365 | ⚠️ Requires OAuth |
| IMAP/POP3 servers | ⚠️ Beta |

## 🔧 Build

```bash
./gradlew assembleDebug    # Debug
./gradlew assembleRelease  # Release
```

## 📖 Documentation

- [Changelog](CHANGELOG_EN.md)
- [Privacy Policy](PRIVACY_POLICY.md)

## 🏗️ Architecture

```
UI Layer (Compose)
    ↓
Repository Layer (AccountRepository, MailRepository)
    ↓
Protocol Clients (EasClient, ImapClient, Pop3Client)
    ↓
Background Services (PushService, SyncWorker)
```

## 📄 License

MIT License

---

© 2025 DedovMosol
