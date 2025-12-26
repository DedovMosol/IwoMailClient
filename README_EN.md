# iwo Mail Client

🇷🇺 [Русская версия](README.md)

Android mail client with Microsoft Exchange Server 2007+ (ActiveSync), IMAP and POP3 support.

**Version:** 1.0.6c  
**Developer:** DedovMosol  
**Telegram:** [@i_wantout](https://t.me/i_wantout)  
**Email:** andreyid@outlook.com

## 🌟 Features

- 📧 **Exchange ActiveSync** — full EAS 2.5-14.1 support
- 📬 **IMAP/POP3** — works with any mail server
- 🔒 **Exchange 2007 compatibility** — TLS 1.0/1.1 support via Conscrypt
- 🌍 **Two languages** — 🇷🇺 Russian / 🇬🇧 English
- 🎨 **Material Design 3** — modern interface
- 🔔 **Push notifications** — instant notifications for new emails (Direct Push)
- 📱 **High refresh rate** — 120Hz display support

## 📱 Capabilities

| Feature | Status |
|---------|--------|
| Folder and email sync | ✅ |
| Full email loading (pagination) | ✅ |
| Send emails with attachments | ✅ |
| Download attachments | ✅ |
| Email search | ✅ |
| Filters (unread, starred, with attachments) | ✅ |
| Date filters (today, week, month, year) | ✅ |
| Favorite emails | ✅ |
| Move to spam/trash | ✅ |
| Permanent deletion from trash | ✅ |
| Restore emails from trash | ✅ |
| Create/delete/rename folders | ✅ |
| Multiple accounts | ✅ |
| Background sync (WorkManager) | ✅ |
| Push notifications (Direct Push) | ✅ |
| Auto-start after reboot | ✅ |
| Dark theme | ✅ |
| Last sync time | ✅ |
| Folder cleanup recommendations | ✅ |
| Forward emails | ✅ |
| Move emails between folders | ✅ |

## 📋 Version History

### v1.0.6c (26.12.2025)
**New Features:**
- Email verification when adding Exchange account — verifies that entered email matches actual email on server
- "Verifying account" screen — animated verification screen like in Outlook
- Read receipt request (MDN) — request notification when recipient opens email
- Delivery receipt request (DSN) — confirmation that email was delivered to server
- Font size customization — small/medium/large in settings
- Individual sync settings — each account has its own sync mode and interval
- Account card settings — sync mode (Push/Scheduled) and interval directly in UI

**Battery Optimization:**
- Sync mode selection for Exchange — Push or Scheduled
- Night mode battery saving — 23:00-7:00 sync every 60 min instead of configured interval
- Adaptive heartbeat — auto-increase Ping interval (5→15 min) on stable connection
- Smart network stop — PushService pauses when no network
- Sync debounce — protection from duplicate sync requests
- Smart sync logic — automatic disable of periodic sync for Push accounts

**Bug Fixes:**
- Fixed vibration when selecting emails
- Fixed search highlight on all devices
- Fixed scroll on "Select all" — auto-scroll to top
- Fixed notifications — fixed IDs, no spam
- Fixed default language — now Russian for new installations

**Technical Improvements:**
- VerificationScreen — new email verification screen with animation
- fetchOneEmailForVerification — method to fetch 1 email for quick verification
- NetworkCallback — instant resume when network appears
- Database v9 — added syncIntervalMinutes field to AccountEntity
- SyncWorker.getMinSyncInterval() — centralized function for getting sync interval
- Code cleanup — removed unused methods from SettingsRepository

### v1.0.5c (25.12.2025)
**Notification fixes:**
- Fixed notifications on lock screen — added `VISIBILITY_PUBLIC`
- Notification channels now recreated on app launch to apply new settings
- Added `CATEGORY_EMAIL` for proper system handling
- New mail channel now has `lockscreenVisibility = PUBLIC`

**Email move fixes:**
- Fixed restore from trash bug — email now returns to the folder it was deleted from, not the original one
- `originalFolderId` correctly updates when moving between folders

**Navigation fixes:**
- Fixed opening email from notification after swiping app away
- Added email existence check before opening
- If email not found — opens Inbox with "Unread" filter

**Compatibility improvements:**
- Improved TLS compatibility on different devices — removed deprecated SSLv3
- Added TLSv1.3 support
- Improved SSL/TLS error handling — fallback to OkHttp defaults
- Added sync timeouts (60 sec total, 30 sec per folder)

### v1.0.5b (25.12.2024)
**UI Modernization:**
- Gradient TopAppBar on all screens (purple-blue gradient)
- Folder cards in unified style with vibrant gradients
- Gmail-style avatar colors for email senders
- Pulsating animation for "Support Developer" button
- Haptic feedback (vibration) when selecting emails
- Added "Tips" section with beta features info
- Empty trash functionality with confirmation dialog

**Sync improvements:**
- Improved background sync reliability (AlarmManager fallback)
- SyncAlarmReceiver now syncs directly, not relying only on WorkManager
- Foreground notification auto-translates when language changes

**Bug fixes:**
- Fixed false navigation to setup screen on app launch
- Improved auto-sync logic on app startup
- Back button from notification email now goes to Inbox
- Expanded sections state preserved during sync

**⚠️ Known limitations:**
- IMAP and POP3 are in beta mode
- Full multi-account support coming in future versions

### v1.0.4b (25.12.2024)
**Performance and memory optimization:**
- SettingsRepository is now singleton — eliminated multiple instance creation
- EasClient cached per account — resource savings on repeated requests
- Fixed memory leak in SyncAlarmReceiver — local coroutine scope with cancel() in finally
- Removed sync duplication when Direct Push not supported by server
- Removed all Log.d() calls for production build — reduced APK size and improved performance
- Optimized ProGuard rules for R8 minification

**UI improvements:**
- Improved "Move to" dialog logic: from system folders shows only user folders, from user folders shows all folders
- Fixed crash when opening email from notification on locked screen
- IMAP and POP3 marked as "beta" in account setup interface

### v1.0.3c
- Fixed critical notification issue — notifications now arrive for all new emails, not just the first one
- Fixed foreground notification navigation — now opens main screen instead of last email
- Improved new email detection logic — uses last check time instead of ID comparison
- Added SyncAlarmReceiver for reliable sync on Xiaomi/MIUI even when app is killed by system

### v1.0.3b
- Improved new email notifications — now show sender name and subject
- Clicking notification with single email opens that email directly
- Added localization for special Exchange folders (Tasks, Calendar, Contacts)
- Added permission requests on first launch (battery, exact alarms) with explanations
- Improved background sync reliability on Xiaomi/MIUI (AlarmManager fallback)
- Foreground notification now localized based on app settings

### v1.0.3a
- Fixed interface localization — all strings now correctly translate when language is selected
- Fixed hardcoded Russian strings in ComposeScreen, SearchScreen, EmailListScreen
- System folder names now localized (Inbox/Входящие, Sent/Отправленные, etc.)
- Fixed crash on first account addition (added try-catch and wait timeout)
- Language now loads synchronously on app start

### v1.0.2a
- Fixed Push notifications (automatic fallback to periodic sync if server doesn't support Direct Push)
- Added confirmation dialog for email deletion
- Delete button now correctly moves emails to trash (not local deletion)
- Implemented "Forward" email function
- Implemented "Move" email to another folder from detail view
- Removed non-functional "Archive" button
- Fixed infinite loading of old emails (added 30 sec timeout)
- "Favorites" card now centered on main screen
- Added colon after "Tip of the day:"
- Reduced Push notification logs
- Added internet connection check when adding account
- Added restore emails from trash to original folder
- Full interface localization (RU/EN)

### v1.0.1a
- Replaced statistics with last sync time
- Added tip of the day for folder cleanup (>1000 emails)
- Fixed instant folder counter updates on move/delete
- Fixed sync cancellation on UI interaction
- Default language changed to English
- Added language switch button on account setup screen
- Hidden system folders in move dialog
- In Spam folder "To spam" button replaced with "Delete permanently"
- Improved donation dialog (removed SBP, improved alignment)
- Developer email and Telegram now clickable
- Improved input field UX in ComposeScreen (autofocus)

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

## 🔔 Notifications

- **Push notifications** — instant notifications via Exchange Direct Push
- **Periodic sync** — configurable interval (1-30 minutes) or automatic fallback if Direct Push not supported
- **Smart notifications** — single new email opens it directly, multiple emails open inbox with "Unread" filter
- **Heads-up notifications** — high priority with sound and vibration

## 🗑️ Delete and Restore Emails

- **From any folder** — moves to trash (remembers original folder)
- **From trash** — permanently deletes from server
- **Restore** — returns email to original folder
- **Localized messages** — "Moved to trash" / "Deleted permanently" / "Restored"

## 📎 Attachments

- **Send** — up to 7 MB (Exchange limit)
- **Download** — via ItemOperations or GetAttachment
- **Size check** — warning when limit exceeded

## 🔧 Build

```bash
# Debug
./gradlew assembleDebug

# Release
./gradlew assembleRelease
```

## 📖 Documentation

Detailed technical documentation: [docs/API_DOCUMENTATION_EN.md](docs/API_DOCUMENTATION_EN.md)

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      UI Layer (Compose)                      │
│  MainScreen → EmailListScreen → EmailDetailScreen           │
│  SettingsScreen, SetupScreen, SearchScreen, ComposeScreen   │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                   Repository Layer                           │
│  AccountRepository ←→ MailRepository ←→ SettingsRepository  │
└─────────────────────────────────────────────────────────────┘
                            │
        ┌───────────────────┼───────────────────┐
        ▼                   ▼                   ▼
┌───────────────┐   ┌───────────────┐   ┌───────────────┐
│   EasClient   │   │  ImapClient   │   │  Pop3Client   │
│  (Exchange)   │   │   (IMAP)      │   │   (POP3)      │
└───────────────┘   └───────────────┘   └───────────────┘
        │
        ▼
┌───────────────────────────────────────────────────────────┐
│              Background Services                           │
│  PushService (Direct Push) ←→ SyncWorker (WorkManager)    │
│                    BootReceiver                            │
└───────────────────────────────────────────────────────────┘
```

## 📄 License

MIT License

---

© 2025 DedovMosol
