# iwo Mail Client

🇷🇺 [Русская версия](README.md)

Android mail client with Microsoft Exchange Server 2007+ (ActiveSync), IMAP and POP3 support.

**Version:** 1.0.8  
**Developer:** DedovMosol  
**Telegram:** [@i_wantout](https://t.me/i_wantout)  
**Email:** andreyid@outlook.com

## 🌟 Features

- 📧 **Exchange ActiveSync** — full EAS 2.5-14.1 support
- 📬 **IMAP/POP3** — works with any mail server (beta)
- 🔒 **Exchange 2007 compatibility** — TLS 1.0/1.1 support via Conscrypt
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
| Multiple accounts | ✅ |
| Push notifications (Direct Push) | ✅ |
| Background sync | ✅ |
| Dark theme | ✅ |
| Color themes (7 colors) | ✅ |
| Account signature | ✅ |
| Read/delivery receipt request (MDN/DSN) | ✅ |

## 🆕 What's New in v1.0.8

- **Deferred deletion** — empty trash with progress bar and cancel option
- **Auto-empty trash** — automatically delete old emails after 3/5/7/14/30 days
- **Animation settings** — toggle to enable/disable UI animations (enabled by default)
- **"View changelog" button** — link to changelog on GitHub
- **Privacy policy link** — in app settings
- **Server certificate selection** — support for corporate self-signed certificates

📋 Full changelog: [CHANGELOG_EN.md](CHANGELOG_EN.md)

## 📋 Requirements

- Android 8.0+ (API 26)
- Target SDK: 35 (Android 15)

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
