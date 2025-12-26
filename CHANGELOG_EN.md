# Changelog

## v1.0.6c (26.12.2025)

### New Features
- **Email verification when adding Exchange account** — verifies that entered email matches actual email on server
- **"Verifying account" screen** — animated verification screen like in Outlook
- **Read receipt request (MDN)** — checkbox when sending email to request read notification
- **Delivery receipt request (DSN)** — checkbox to confirm email delivery to recipient's server
- **Font size customization** — small/medium/large selection in app settings
- **Individual sync settings** — each account has its own sync mode and interval
- **Account card settings** — sync mode (Push/Scheduled) and interval directly in UI

### Battery Optimization
- **Sync mode selection** — for Exchange accounts choose Push (instant notifications) or Scheduled (battery saving)
- **Night mode battery saving** — from 23:00 to 7:00 sync every 60 minutes instead of configured interval
- **Adaptive heartbeat** — automatic Ping interval increase from 5 to 15 minutes on stable connection
- **Smart network stop** — PushService pauses when no internet, saves battery
- **Sync debounce** — protection from duplicate sync requests
- **Smart sync logic** — automatic disable of periodic sync for Push accounts

### Bug Fixes
- **Fixed vibration** — haptic feedback when selecting emails works correctly
- **Fixed search highlight** — found letters highlighting works on all devices
- **Fixed scroll on "Select all"** — automatic scroll to top of list
- **Fixed notifications** — fixed IDs, no spam
- **Fixed default language** — now Russian for new installations

### Technical Improvements
- **VerificationScreen** — new email verification screen with animation
- **fetchOneEmailForVerification** — method to fetch 1 email for quick verification (WindowSize=1)
- **NetworkCallback** — instant resume when network becomes available
- **Database v9** — added syncIntervalMinutes field to AccountEntity
- **SyncWorker.getMinSyncInterval()** — centralized function for getting sync interval
- **SyncMode.getDisplayName()** — sync mode localization
- **ScaledAlertDialog** — all dialogs now correctly scale font according to settings
- **Code cleanup** — removed unused methods from SettingsRepository

---

## v1.0.5c (26.12.2025)

### Notification Fixes
- Fixed notifications on lock screen — added `VISIBILITY_PUBLIC`
- Notification channels now recreated on app launch to apply new settings
- Added `CATEGORY_EMAIL` for proper system handling
- New mail channel now has `lockscreenVisibility = PUBLIC`

### Email Move Fixes
- Fixed restore from trash bug — email now returns to the folder it was deleted from
- `originalFolderId` correctly updates when moving between folders

### Navigation Fixes
- Fixed opening email from notification after swiping app away
- Added email existence check before opening
- If email not found — opens Inbox with "Unread" filter

### Compatibility Improvements
- Improved TLS compatibility on different devices — removed deprecated SSLv3
- Added TLSv1.3 support
- Improved SSL/TLS error handling — fallback to OkHttp defaults
- Added sync timeouts (60 sec total, 30 sec per folder)

### Navigation Fixes
- Fixed opening email from notification after swiping app away
- Added email existence check before opening
- If email not found — opens Inbox with "Unread" filter

---

## v1.0.5b (25.12.2025)

### UI Modernization
- Gradient TopAppBar on all screens (purple-blue gradient)
- Folder cards in unified style with vibrant gradients
- Gmail-style avatar colors for email senders
- Pulsating animation for "Support Developer" button
- Haptic feedback (vibration) when selecting emails
- Added "Tips" section with beta features info
- Empty trash functionality with confirmation dialog

### Sync Improvements
- Improved background sync reliability (AlarmManager fallback)
- SyncAlarmReceiver now syncs directly, not relying only on WorkManager
- Foreground notification auto-translates when language changes

### Bug Fixes
- Fixed false navigation to setup screen on app launch
- Improved auto-sync logic on app startup
- Back button from notification email now goes to Inbox
- Expanded sections state preserved during sync

---

## v1.0.4b (25.12.2025)

### Performance and Memory Optimization
- SettingsRepository is now singleton — eliminated multiple instance creation
- EasClient cached per account — resource savings on repeated requests
- Fixed memory leak in SyncAlarmReceiver — local coroutine scope with cancel() in finally
- Removed sync duplication when Direct Push not supported by server
- Removed all Log.d() calls for production build — reduced APK size
- Optimized ProGuard rules for R8 minification

### UI Improvements
- Improved "Move to" dialog logic: from system folders shows only user folders, from user folders shows all folders
- Fixed crash when opening email from notification on locked screen
- IMAP and POP3 marked as "beta" in account setup interface

---

## v1.0.3c (24.12.2025)

### Critical Fixes
- Fixed critical notification issue — notifications now arrive for all new emails
- Fixed foreground notification navigation — now opens main screen
- Improved new email detection logic — uses last check time
- Added SyncAlarmReceiver for reliable sync on Xiaomi/MIUI

---

## v1.0.3b (24.12.2025)

### Notification Improvements
- Improved new email notifications — now show sender name and subject
- Clicking notification with single email opens that email directly
- Added localization for special Exchange folders (Tasks, Calendar, Contacts)
- Added permission requests on first launch (battery, exact alarms)
- Improved background sync reliability on Xiaomi/MIUI (AlarmManager fallback)
- Foreground notification now localized based on app settings

---

## v1.0.3a (23.12.2025)

### Localization
- Fixed interface localization — all strings now correctly translate
- Fixed hardcoded Russian strings in ComposeScreen, SearchScreen, EmailListScreen
- System folder names now localized (Inbox/Входящие, Sent/Отправленные, etc.)
- Fixed crash on first account addition
- Language now loads synchronously on app start

---

## v1.0.2a (22.12.2025)

### New Features
- Implemented "Forward" email function
- Implemented "Move" email to another folder
- Added restore emails from trash to original folder
- Full interface localization (RU/EN)

### Bug Fixes
- Fixed Push notifications (automatic fallback to periodic sync)
- Added confirmation dialog for email deletion
- Delete button now correctly moves emails to trash
- Removed non-functional "Archive" button
- Fixed infinite loading of old emails (added 30 sec timeout)
- "Favorites" card now centered on main screen
- Added internet connection check when adding account

---

## v1.0.1a (21.12.2025)

### UI Improvements
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

---

## v1.0.0 (20.12.2025)

### Initial Release
- Exchange ActiveSync support (EAS 2.5-14.1)
- IMAP and POP3 support (beta)
- Folder and email sync
- Send emails with attachments
- Download attachments
- Email search
- Filters (unread, starred, with attachments)
- Favorite emails
- Move to spam/trash
- Create/delete/rename folders
- Multiple accounts
- Background sync (WorkManager)
- Push notifications (Direct Push)
- Auto-start after reboot
- Dark theme
- Two languages (RU/EN)
