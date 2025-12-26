# iwo Mail Client - API Documentation

**Version:** 1.0.6c (26.12.2025)

## What's New in v1.0.6c (compared to v1.0.5c)

### New Features
- **Read receipt request (MDN)** — request notification when recipient opens email
- **Delivery receipt request (DSN)** — confirmation that email was delivered to server
- **Font size customization** — small/medium/large in settings
- **Individual sync settings** — each account has its own sync mode and interval
- **Account card settings** — sync mode (Push/Scheduled) and interval directly in UI

### Battery Optimization
- **Sync mode selection** — Push or Scheduled for Exchange accounts
- **Night mode battery saving** — 23:00-7:00 sync every 60 min
- **Adaptive heartbeat** — auto-increase Ping interval (5→15 min)
- **NetworkCallback** — smart stop/resume on network changes
- **Sync debounce** — protection from duplicate requests
- **Smart sync logic** — automatic disable of periodic sync for Push accounts

### Bug Fixes
- Fixed vibration when selecting emails
- Fixed search highlight on all devices
- Fixed scroll on "Select all"
- Fixed notifications — fixed IDs, no spam

### Technical Improvements
- Database v9 — added syncIntervalMinutes field to AccountEntity
- SyncWorker.getMinSyncInterval() — centralized function for getting sync interval
- SyncMode.getDisplayName() — sync mode localization
- ScaledAlertDialog — proper font scaling in all dialogs
- Code cleanup — removed unused methods from SettingsRepository

## What's New in v1.0.5c (compared to v1.0.5b)

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

## What's New in v1.0.5b (compared to v1.0.4b)

### UI Modernization
- **Gradient TopAppBar** — purple-blue gradient applied to all screens
- **Unified folder cards** — vibrant gradients in consistent style
- **Gmail-style avatars** — stable colors for senders based on name hash
- **Pulsating animation** — "Support Developer" button with smooth pulse effect
- **Haptic feedback** — vibration when selecting emails
- **"Tips" section** — collapsible section with beta features info
- **Empty trash** — full trash cleanup function with confirmation dialog

### Sync Improvements
- **Reliable background sync** — AlarmManager fallback works even when app is killed
- **SyncAlarmReceiver** — now syncs directly, not relying only on WorkManager
- **Auto-translate notification** — foreground notification translates when language changes

### Bug Fixes
- **Fixed setup screen bug** — no more false navigation to setup on app launch
- **Improved auto-sync** — reactive sync based on active account state
- **Notification navigation** — back button from email goes to Inbox, not main screen
- **Section state preservation** — expanded sections don't collapse during sync

### Known Limitations
- IMAP and POP3 are in beta mode
- Full multi-account support coming in future versions

## What's New in v1.0.4b (compared to v1.0.3b)

### Memory Optimization
- **SettingsRepository singleton** — now creates one instance for the entire app instead of creating new one on each access
- **EasClient caching** — client is cached per account, repeated requests use existing instance
- **Fixed memory leak in SyncAlarmReceiver** — coroutine scope is now local with mandatory cancel() in finally block

### Battery Optimization
- **Removed sync duplication** — when server doesn't support Direct Push, sync no longer runs twice (PushService + SyncWorker)

### Production Optimizations
- **Removed all Log.d() calls** — debug logs removed from PushService, SyncWorker, SyncAlarmReceiver
- **Optimized ProGuard rules** — added rules for JavaMail, BouncyCastle, Security Crypto

### UI Improvements
- **Improved "Move to" dialog** — from system folders shows only user folders, from user folders shows all folders
- **Fixed crash when opening email from notification** — app no longer crashes when pressing "back" on locked screen
- **IMAP/POP3 marked as beta** — in account setup interface

## Architecture Overview

The application supports multiple protocols for working with mail servers:

```
┌─────────────────────────────────────────────────────────────┐
│                      UI Layer                                │
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
        │                   │
        ▼                   ▼
┌───────────────┐   ┌───────────────┐
│  WbxmlParser  │   │  SmtpClient   │
│ (EAS Binary)  │   │   (Send)      │
└───────────────┘   └───────────────┘
        │
        ▼
┌───────────────────────────────────────────────────────────┐
│              Background Services                           │
│  PushService (Direct Push) ←→ SyncWorker (WorkManager)    │
│                    BootReceiver                            │
└───────────────────────────────────────────────────────────┘
```

---

## Background Sync

### PushService (Direct Push)

Foreground Service for instant notifications about new emails via Exchange Direct Push (Ping command).

**Features:**
- Automatic restart when app is closed
- Adaptive heartbeat (8-28 minutes)
- Automatic folder sync on first start
- Smart notifications (single email → opens it, multiple → "Unread" filter)
- Heads-up notifications with high priority

```kotlin
// Start service
PushService.start(context)

// Stop service
PushService.stop(context)
```

### SyncWorker (WorkManager)

Periodic background sync via WorkManager.

```kotlin
// Schedule sync
SyncWorker.schedule(
    context,
    intervalMinutes = 15,
    wifiOnly = false
)

// Immediate sync
SyncWorker.syncNow(context)

// Cancel sync
SyncWorker.cancel(context)
```

### SyncAlarmReceiver

BroadcastReceiver for reliable sync via AlarmManager. Works even when PushService is killed by system (Xiaomi/MIUI).

```kotlin
// Automatically scheduled by PushService when app is swiped away
// Triggers sync and restarts PushService
```

### SettingsRepository - Notification Tracking

```kotlin
// Last notification check time - used to determine new emails
val lastNotificationCheckTime: Flow<Long>
suspend fun setLastNotificationCheckTime(timeMillis: Long)
fun getLastNotificationCheckTimeSync(): Long
```

### EmailDao - New Email Detection

```kotlin
// Get unread emails received after specified time (for notifications)
suspend fun getNewUnreadEmails(accountId: Long, afterTime: Long): List<EmailEntity>
```

---

## Localization

The app supports two languages:
- 🇷🇺 Russian 
- 🇬🇧 English (default)

### Localization.kt
```kotlin
enum class AppLanguage(val code: String, val displayName: String) {
    RUSSIAN("ru", "🇷🇺 Русский"),
    ENGLISH("en", "🇬🇧 English")
}

object Strings {
    // Folder names
    val inbox: String @Composable get() = if (isRussian()) "Входящие" else "Inbox"
    val drafts: String @Composable get() = if (isRussian()) "Черновики" else "Drafts"
    val sent: String @Composable get() = if (isRussian()) "Отправленные" else "Sent"
    val trash: String @Composable get() = if (isRussian()) "Удалённые" else "Trash"
    val spam: String @Composable get() = if (isRussian()) "Спам" else "Spam"
    
    // Actions
    val movedToTrash: String @Composable get() = if (isRussian()) "Перемещено в корзину" else "Moved to trash"
    val deletedPermanently: String @Composable get() = if (isRussian()) "Удалено окончательно" else "Deleted permanently"
    val restored: String @Composable get() = if (isRussian()) "Восстановлено" else "Restored"
    
    // ComposeScreen
    val scheduleSend: String @Composable get() = if (isRussian()) "Запланировать отправку" else "Schedule send"
    val tomorrowMorning: String @Composable get() = if (isRussian()) "Завтра утром" else "Tomorrow morning"
    val selectDateTime: String @Composable get() = if (isRussian()) "Выбрать дату и время" else "Pick date & time"
    
    // Localize folder name by type
    @Composable
    fun getFolderName(type: Int, originalName: String): String
}
```

### NotificationStrings
Localization for notifications and Toast (outside Compose context):
```kotlin
object NotificationStrings {
    fun getNewMailTitle(isRussian: Boolean) = 
        if (isRussian) "Новая почта" else "New mail"
    
    fun getMovedToTrash(isRussian: Boolean) =
        if (isRussian) "Перемещено в корзину" else "Moved to trash"
    
    fun getRestored(isRussian: Boolean) =
        if (isRussian) "Восстановлено" else "Restored"
    
    fun localizeError(errorCode: String, isRussian: Boolean): String
}
```

---

## MailRepository API

### Sync
```kotlin
suspend fun syncFolders(accountId: Long): EasResult<Unit>
suspend fun syncEmails(accountId: Long, folderId: String): EasResult<Int>
```

### Move and Delete Emails
```kotlin
// Move to any folder
suspend fun moveEmails(emailIds: List<String>, targetFolderId: String): EasResult<Int>

// Move to spam
suspend fun moveToSpam(emailIds: List<String>): EasResult<Int>

// Move to trash or permanent delete
suspend fun moveToTrash(emailIds: List<String>): EasResult<Int>

// Restore from trash to original folder
suspend fun restoreFromTrash(emailIds: List<String>): EasResult<Int>

// Permanent delete
suspend fun deleteEmailsPermanently(emailIds: List<String>): EasResult<Int>
```

### Folder Management
```kotlin
suspend fun createFolder(accountId: Long, folderName: String): EasResult<Unit>
suspend fun deleteFolder(accountId: Long, folderId: String): EasResult<Unit>
suspend fun renameFolder(accountId: Long, folderId: String, newName: String): EasResult<Unit>
```

---

## Database

### EmailEntity
```kotlin
data class EmailEntity(
    val id: String,
    val accountId: Long,
    val folderId: String,
    val serverId: String,
    val from: String,
    val fromName: String,
    val to: String,
    val cc: String,
    val subject: String,
    val preview: String,
    val body: String,
    val bodyType: Int,
    val dateReceived: Long,
    val read: Boolean,
    val flagged: Boolean,
    val importance: Int,
    val hasAttachments: Boolean,
    val originalFolderId: String? // Original folder before moving to trash
)
```

---

## Error Codes

### HTTP
| Code | Description |
|------|-------------|
| 200 | OK |
| 401 | Unauthorized |
| 403 | Forbidden |
| 449 | Retry (Provision) |
| 500 | Server Error |

### EAS Status
| Status | Description |
|--------|-------------|
| 1 | Success |
| 3 | Invalid SyncKey |
| 12 | Object not found |
| 141 | Device not provisioned |
