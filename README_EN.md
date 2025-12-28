# iwo Mail Client

🇷🇺 [Русская версия](README.md)

Android mail client with Microsoft Exchange Server 2007+ (ActiveSync), IMAP and POP3 support.

**Version:** 1.1.2  
**Developer:** DedovMosol  
**Telegram:** [@i_wantout](https://t.me/i_wantout)  
**Email:** andreyid@outlook.com

## 🌟 Features

- 📧 **Exchange ActiveSync** — full EAS 12.0-14.1 support (Exchange 2007+)
- 📬 **IMAP/POP3** — works with any mail server (beta)
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
| Email autocomplete | ✅ |
| Multiple accounts | ✅ |
| Push notifications (Direct Push) | ✅ |
| Background sync | ✅ |
| Dark theme | ✅ |
| Color themes (7 colors) | ✅ |
| Interface personalization | ✅ |
| Account signature | ✅ |
| Read/delivery receipt request (MDN/DSN) | ✅ |

## 🆕 What's New in v1.1.2

- **Personalization screen** — appearance settings moved to separate screen
- **Account avatar** — uses selected color everywhere in the app
- **Drafts** — saved locally and deleted immediately
- **Bug fixes** — contact export, dialogs on screen rotation

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
