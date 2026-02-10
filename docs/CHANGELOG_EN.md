# Changelog

## v1.6.1 (08.02.2026) — Package rename → "com.dedovmosol.iwomail", reinstall required

### New
- Rich Text editor and HTML signatures
- Inline images — full cycle: send, view, reply, forward, drafts (via EWS CreateAttachment + cid:)
- User folders — dedicated management screen
- Update checker with push notification
- Client certificates (.p12/.pfx) for mTLS, Certificate Pinning

### Improvements
- Clickable links, emails and phone numbers in email body
- Email suggestions with partial match search
- Multi-select — bulk delete in trash, notes, calendar
- Material3 DatePicker/TimePicker, unified scrollbars (Outlook-style)
- Calendar/tasks/notes — updated filters and full-width search
- Full error localization (EN/RU)

### Fixes
- **Drafts** — server-side save of formatting, inline images and attachments
- **Reply/Forward** — correct addresses, attachments and inline images
- **Sync** — read status propagation to server, reentrancy protection, system folder auto-recovery
- **Deletion** — batch delete, fixed email "resurrection"
- **"Today" card** — correct count of all emails for the day
- **Stability** — markAsRead conflict handling, connection leaks, PushService crash (Android 12+), ANR SyncAlarmReceiver
- **UI** — double-tap protection, HTML entity decoding

### Performance
- Refactoring: 15 services, 6 repositories
- N+1 query optimization (10-100x faster), caching, regex pre-compilation
- PushService — battery savings, Chinese ROM protection

---

## v1.6.0 (08.01.2026)

### New Features
- **Onboarding Screen** вЂ” welcome slides with app features on first launch, language and animations selection
- **Redesigned Widget** вЂ” new design with search, calendar, accounts and unread counters
- **Today Statistics Card** on main screen вЂ” emails, events and tasks for today
- **Updates Screen** вЂ” check for updates with auto-check interval settings
- **About Screen** вЂ” all app information in one place
- **Sync & Cleanup Screen** вЂ” sync and auto-cleanup management per account
- **Per-account settings** вЂ” night mode and Battery Saver ignore now configurable per account
- **Offline mode:**
  - "No network" banner вЂ” shown at top of screen when offline
  - Send queue вЂ” emails without attachments are saved and sent automatically when network returns
  - Email body caching вЂ” last 7 emails in each folder are prefetched for offline access
  - Network check before sync вЂ” "No network" toast instead of waiting for error
  - Network check before downloading attachments

### Improvements
- Simplified settings screens вЂ” sections moved to separate screens
- Removed "Tips" and "About" sections from main screen
- Widget updates after sync completion
- Scrollbar in move email dialog

### Fixes
- Reliable calendar/notes/tasks counters loading on startup
- Counters don't decrease during sync
- Fixed crash when deleting emails from server folders
- Empty folder icon centering
- SelectionTopBar colors match theme

---

## v1.5.2 (07.01.2026)

### New Features
- App Shortcuts вЂ” quick actions when holding app icon (Compose, Inbox, Search, Sync)
- "Compose" button on search screen

### Improvements
- Sender names taken from contacts (priority), then from email history

### Exchange 2007 Fixes
- Task sync вЂ” uses EWS instead of EAS (EAS returns empty response)
- Task creation вЂ” uses EWS for all tasks (EAS doesn't work on Exchange 2007)
- Task deletion from server now works correctly

### Fixes
- Fixed task/event/note duplication when creating on Exchange 2007
- Folder counters not updating after first sync
- Fixed description duplication in calendar events and tasks

---

## v1.5.1 (05.01.2026)

### New Features
- **Undo send** вЂ” 3 seconds to cancel after pressing "Send" (green progress bar)

### Improvements
- **Account switch in compose** вЂ” when selecting different sender, their signature and contacts are automatically loaded
- **Architecture-specific updates** вЂ” downloads APK for your architecture (arm64, arm32, x86)
- **Deletion progress bar** вЂ” moved to bottom of screen, doesn't overlap content
- **Task reminders** вЂ” added reminder scheduling when creating and editing tasks

### Fixes
- Fixed ComposeScreen reopening on screen rotation after mailto:
- Fixed button display in "Support developer" dialog in landscape mode
- Fixed draft save prompt when changing sender
- Fixed navigation to setup screen after deleting last account

### Bug Fixes from v1.5.0
- Fixed scheduled sync вЂ” interval now applies correctly
- Fixed calendar event deletion from server
- Fixed meeting response вЂ” uses correct account
- Fixed duplicate attendees in calendar events
- Fixed meeting invitation sending
- Fixed body text duplication when accepting meeting invitation
- Fixed opening email from notification and account switching
- Fixed tasks loading on main screen

### Exchange 2007 Fixes
- **Tasks with dates** вЂ” fixed task creation with dates on Exchange 2007:
  - Uses EWS instead of EAS for creating tasks with dates
  - Due date and body are saved to server
  - Start date is saved locally only (Exchange 2007 limitation)
  - Tasks without dates are created via EAS as before
- **Calendar event creation** вЂ” updated EWS request format to match official Microsoft documentation
- **Meetings with attendees** вЂ” invitations are now sent through Exchange (not as separate email)
- **Task sync** вЂ” fixed WBXML parsing (added Code Page 9 for Tasks)
- **Task/calendar body** вЂ” added BodyPreference to sync requests

### Note
вљ пёЏ **Calendar and Tasks are in beta mode** вЂ” bugs and instability may occur

---

## v1.5.0 (03.01.2026)

### New Features
- **Check for updates** вЂ” button in settings to check and install new versions
- **Task assignment** вЂ” ability to assign tasks to other users with email notification
- **Meeting invitations** вЂ” send and receive calendar event invitations (iCalendar)
- **Default email app** вЂ” handles mailto: links and Share from other apps
- **Clickable contacts** вЂ” emails and phones in calendar, notes and tasks are now clickable

### Improvements
- **Battery optimization** вЂ” instant Battery Saver response via BroadcastReceiver, increased PushService heartbeat
- **Sync after send** вЂ” "Sent" folder updates automatically
- **Self-filtering in contacts** вЂ” own email not shown in organization list
- **Change credentials** вЂ” new menu item in account settings
- Memory and performance optimizations (singleton repositories, DB batching, indexes)

### Fixes
- Fixed potential crashes and memory leaks
- Fixed infinite sync when switching accounts
- Fixed attachments and drafts preservation on screen rotation
- Duplicate protection in calendar, tasks and email suggestions

---

## v1.4.2 (01.01.2026)

### New Features
- **Tasks** вЂ” sync and manage of personal tasks from the server
  - View, create, edit and delete tasks
  - Filters: all, active, completed, high priority, overdue
  - Task search
  - Mark complete with server sync
  - Priority and due date
  - Automatic background sync
  - Notifications
  - Date and time picker for start date and due date

### Improvements
- **Memory optimization** вЂ” repositories on main screen are now cached
- **Network optimization** вЂ” tasks folder ID caching (fewer HTTP requests)
- **Battery optimization** вЂ” tasks sync respects interval (not on every run)
- **Draft editing** вЂ” fixed saving changes to server drafts

### Fixes
- Battery saver indicator now updates when mode changes
- Removed large font size option from settings
- Fixed drafts badge on main screen (was showing even without drafts)

---

## v1.4.1 (31.12.2025)

### New Features
- **Server-side drafts** вЂ” drafts now sync with Exchange server via EWS (previously stored locally only)
  - Creating draft saves to server
  - Sync drafts from server
  - Editing draft updates on server
- **High priority when sending** вЂ” ability to mark email as important when sending (recipient will see red exclamation mark)

### Improvements
- **Compact dialogs** вЂ” notes and calendar event dialogs are now compact, sized by content
- **Dynamic folder card height** вЂ” text no longer truncated on small screens
- **Regex optimization** вЂ” precompiled and cached regex patterns in all key files

### Bug Fixes
- **Draft deletion** вЂ” drafts are deleted immediately without moving to trash
- **Mixed deletion** вЂ” correct handling when deleting emails from different folders simultaneously
- **Drafts folder display** вЂ” fixed crash when deleting emails, drafts now use standard Flow instead of manual loading
- **Blind carbon copy (BCC)** вЂ” fixed sending emails with BCC (previously BCC was not working)
- **Quote localization** вЂ” text "Original message", "From", "Date", "Subject" in replies and forwards is now localized
- **Drafts sync** вЂ” full resync on every update, deletions from Outlook now reflected
- **Calendar sync** вЂ” fixed UI flickering and rescheduling all reminders on every sync
- **Notes sync** вЂ” fixed UI flickering during sync
- **Contacts sync** вЂ” fixed UI flickering during sync
- **Localization** вЂ” all hardcoded strings are now localized
- **DatePicker crash** вЂ” fixed crash when selecting scheduled send date
- **WBXML parser crash** вЂ” fixed crash when encoding zero value
- **SSL with self-signed certificates** вЂ” fixed security policy negotiation error when acceptAllCerts enabled
- **EWS authentication** вЂ” added Basic auth fallback for servers not supporting NTLM

---

## v1.4.0 (31.12.2025)

### New Features
- **Create calendar events** вЂ” create events directly from the app with Exchange server sync
- **Edit events** вЂ” change title, time, location, description, reminder and busy status
- **Delete events** вЂ” delete events from Exchange server
- **Event reminders** вЂ” push notifications before event start time
- **Create notes** вЂ” create notes with server sync
- **Edit notes** вЂ” change note title and body
- **Delete notes** вЂ” delete notes from server

### Improvements
- **Auto-scroll** вЂ” list scrolls to top after creating note or event
- **Performance optimization** вЂ” precompiled regex patterns

### Bug Fixes
- **Draft saving** вЂ” email body now saves correctly
- **Memory leak** вЂ” fixed CoroutineScope leak in MainScreen
- **Battery optimization** вЂ” increased PushService restart timeout from 5 to 30 sec to prevent aggressive restart cycles

### Technical Details
- Calendar: EAS Sync Add/Change/Delete for all Exchange versions (2007+)
- Notes Exchange 2010+: EAS Sync Add/Change/Delete
- Notes Exchange 2007: EWS CreateItem/UpdateItem/DeleteItem with NTLMv2
- Reminders: AlarmManager with setExactAndAllowWhileIdle for precise timing

---

## v1.3.2 (30.12.2025)

### New Features
- **"Important" filter** вЂ” new filter for high priority emails (Importance = High)
- **Priority icon** вЂ” high priority emails marked with red exclamation mark in list

### Bug Fixes
- **Sync deletions** вЂ” emails deleted or moved on server (Outlook) now removed from local database during sync
- **Email body loading** вЂ” added fallback to plain text (Type=1) if HTML returns empty result
- **Initial sync for multiple accounts** вЂ” each account now syncs separately, notification shown for each
- **Parallel folder sync** вЂ” folders now sync in parallel instead of sequentially
- **Auto-scroll during sync** вЂ” email list now auto-scrolls to top when new emails appear during sync
- **Attachments on screen rotation** вЂ” attachments no longer disappear when rotating screen in email detail
- **Quoted-printable decoding** вЂ” fixed Cyrillic decoding in emails with quoted-printable encoding
- **Sync cancellation on account switch** вЂ” when quickly switching accounts, previous sync is properly cancelled
- **Email body loading timeout** вЂ” added 25 sec total timeout for triple fallback (MIME в†’ HTML в†’ Plain)
- **Delete emails on server error** вЂ” if server doesn't respond, emails are deleted locally

### Improvements
- **Notes for Exchange 2007** вЂ” added notes sync via EWS with NTLMv2 for servers without EAS 14.1+ support
- **Local drafts notice** вЂ” Drafts folder now shows info that drafts are stored locally

---

## v1.3.1 (29.12.2025)

### Bug Fixes
- **Bitmap memory leak** вЂ” images in event descriptions now released when leaving screen
- **Unclosed HTTP connections** вЂ” connections when loading images now properly closed
- **Regex optimization** вЂ” link parsing patterns compiled once instead of on every call
- **SoundPlayer race condition** вЂ” added synchronization to prevent race conditions
- **Cache cleanup on account deletion** вЂ” EasClient and heartbeat now cleared when deleting account
- **Heartbeat persistence** вЂ” adaptive heartbeat saved between PushService restarts
- **Local contacts deletion** вЂ” fixed contact deletion (list now updates properly)
- **Avatars in Sent folder** вЂ” now shows first letter/digit of recipient name
- **Duplicate sender email** вЂ” if name equals email, shows only email without duplication
- **Bulk contacts deletion** вЂ” fixed deleting multiple contacts at once
- **Initial sync** вЂ” calendar, notes and contacts now sync on first launch
- **Pull-to-refresh in empty folder** вЂ” animated envelope now displays at top center
- **Email list updates** вЂ” fixed automatic list updates during background sync

### Improvements
- **Copy contacts from organization** вЂ” added button to bulk copy selected contacts to personal
- **Settings text** вЂ” clarified "Ignore battery saver when syncing" text
- **Clickable recipient** вЂ” "To" field in email is now clickable, opens compose with pre-filled address
- **FAB icon in Sent folder** вЂ” changed from pencil to email icon
- **Expanded sections reset** вЂ” "Tips" and "About" sections collapse when navigating to folder
- **Navigation drawer** вЂ” Contacts, Notes and Calendar moved to side menu under Favorites
- **Real-time list updates** вЂ” emails in folder update automatically during background sync
- **Clickable emails in calendar** вЂ” organizer and attendee emails are clickable, open compose screen
- **Clickable location URLs** вЂ” if event location contains URL, it's clickable and opens in browser
- **Contact dialog** вЂ” removed close button, dialog closes by tapping outside

---

## v1.3.0 (29.12.2025)

### New Features
- **Exchange Notes** вЂ” sync and view notes from Exchange Notes folder (read-only)
- **Exchange Calendar** вЂ” sync and view calendar events (read-only) with three display modes:
  - Agenda вЂ” events list grouped by dates
  - Month вЂ” calendar grid with dots on days with events
  - Year вЂ” clicking month header opens view with all 12 months
- **Notes and Calendar sync settings** вЂ” in account settings choose interval: disabled, daily, weekly, biweekly, monthly
- **Automatic sync** вЂ” notes and calendar sync in background together with contacts
- **Multiple contact selection**
- **Favorite contacts** вЂ” mark contacts with star and filter by favorites
- "Notes" and "Calendar" cards on home screen
- Search in notes and events
- Detailed event view (time, location, organizer, attendees)
- Contact counters in "Personal" and "Organization" tabs
- Updated "About" section вЂ” added contacts, notes and calendar features

### Improvements
- **Year in event dates** вЂ” group headers show year (Friday, December 25, 2020)
- **Past events indicator** вЂ” green checkmark and muted colors for completed events
- **Clickable links in events** вЂ” URLs in event descriptions are clickable
- **Images in events** вЂ” images from event descriptions are loaded and displayed
- **Days with events highlighted** вЂ” in year view, days with events shown in blue
- **Improved email scroll** вЂ” WebView no longer blocks parent container scroll
- **Inline images** вЂ” not shown in attachments list, displayed in email body
- **Avatar color palette fixes** вЂ” 32 colors everywhere

---

## v1.2.0 (29.12.2025)

### New Features
- **Rebranding** вЂ” app renamed to "iwo Mail Client"
- **Background GAL contacts sync** вЂ” contacts from organization address book now automatically download in background and save to local database
- **Contacts sync interval setting** вЂ” in account settings choose: disabled, daily, weekly, biweekly, monthly (default вЂ” daily)
- **Manual contacts sync** вЂ” "Sync" button on "Organization" tab in contacts
- **Multiple signatures** вЂ” up to 5 signatures per account with selection when composing email
- **Auto cleanup** вЂ” individual settings per account: trash, drafts, spam (never, 3, 7, 14, 30, 60 days)
- **Contact picker for composing** вЂ” contact selection button next to To/Cc/Bcc fields with two tabs (Personal and Organization), search and multiple selection

### Improvements
- **Audio optimization** вЂ” MP3в†’OGG conversion (~45 KB savings)
- **Sent folder** вЂ” now shows recipient with "To:" prefix instead of sender
- **"Write more" button** вЂ” in Sent folder instead of "Reply" for continuing conversation
- **HTML cleanup in quotes** вЂ” when replying and forwarding, HTML tags are removed, clean text is shown
- **Exchange 2007 compatibility** вЂ” improved XML request format for EAS 12.x (BodyPreference with TruncationSize)
- **GAL loading by letters** вЂ” for servers not supporting wildcard "*", contacts are loaded by alphabet letters (A-Z, Рђ-РЇ)
- **Account settings** вЂ” moved to separate screen
- **Fixed delete dialog texts** вЂ” for drafts and trash shows "will be deleted permanently", for other folders "will be moved to deleted"
- **Localized authorization errors** вЂ” 401, timeout and other errors now shown in selected language

### Bug Fixes
- **Contacts in picker dialog** вЂ” now shows synced contacts from database
- **Repeated GAL loading** вЂ” contacts no longer reload on every visit to "Organization" tab
- **Fixed recipient duplication** вЂ” email view no longer duplicates name and email

---

## v1.1.2 (28.12.2025)

### New Features
- Interface personalization screen

### Bug Fixes
- Contact export (FileProvider)
- Test email on verification deleted automatically
- Dialog buttons вЂ” vertical layout
- Contacts folder hidden from list
- GAL search when returning to contacts screen
- Back swipe in compose shows save dialog
- Focus preserved on screen rotation
- Drafts counter updates immediately after saving
- Local drafts deletion works correctly
- Save draft dialog preserved on screen rotation
- Sync not interrupted on navigation

### Improvements
- Dialogs вЂ” compact design, animations
- Sync time вЂ” date+time format, only after completion
- Updated "Tips" section
- User avatar uses account color from settings
- Drafts saved locally and deleted immediately (not to trash)
- Drafts folder not synced with server
- Save draft dialog in Material Design style

---

## v1.1.1 (27.12.2025)

### Bug Fixes
- **Clipboard button order** вЂ” Cut в†’ Copy в†’ Paste
- **Contact menu** вЂ” added "Edit" and "Delete" to three-dot menu
- **Contact dialog buttons** вЂ” "Cancel" and "Save" moved to opposite sides
- **Sync on screen rotation** вЂ” sync no longer interrupts when orientation changes
- **Contact details dialog** вЂ” redesigned, action buttons at bottom
- **Trash cleanup on navigation** вЂ” deletion continues even when leaving trash folder
- **Compact edit dialog** вЂ” fields grouped in rows
- **Certificate text** вЂ” added warning "(less secure)"
- **Notifications on first launch** вЂ” not shown for old emails
- **Folder order on main screen** вЂ” Inbox/Sent, Drafts/Trash, Favorites/Contacts

### Improvements
- **Battery Saver setting text** вЂ” reworded to "Frequent sync"
- **Push notification tip** вЂ” clarified it's Push mode only
- **Contacts card** вЂ” added to main screen (light blue)

---

## v1.1.0 (27.12.2025)

### New Features
- **Contact groups** вЂ” organize contacts into folders:
  - Create, rename and delete groups
  - Move contacts between groups
  - Filter contacts by groups (chips)
  - View contacts without group
- **Battery saver mode** вЂ” app automatically detects when Android enables battery saver:
  - Increases sync interval to 60 minutes
  - Shows indicator on main screen
  - "Ignore battery saver" setting for those who need mail urgently

### Bug Fixes
- **Empty trash** вЂ” now shows real deletion progress (X / Y emails) instead of fake timer
- **User avatar color** вЂ” avatar in search and settings now changes with app color theme

### Changes
- **Exchange 2003 not supported** вЂ” removed EAS 2.5 support (Exchange 2003). Supported versions: EAS 12.0-14.1 (Exchange 2007+).

---

## v1.0.9 (27.12.2025)

### New Features
- **Contacts** вЂ” full contact management system:
  - Personal contacts with add, edit and delete
  - Corporate address book (GAL) search
  - Export contacts to vCard (.vcf) and CSV
  - Import contacts from vCard and CSV
  - Alphabetical grouping and search
  - Add GAL contacts to personal
  - Integration with email composition
- **Email autocomplete** вЂ” when typing in "To" field, suggestions appear from local contacts, email history, and GAL

### Improvements
- **Background sync** вЂ” all folders sync in background on app launch
- **Styled dialogs** вЂ” unified gradient design for all dialogs
- **Certificate indicator** вЂ” account settings now show if server certificate is used
- **Certificate management** вЂ” view info, export, replace, or remove certificate
- **Developer link** вЂ” "DedovMosol" in settings is now clickable and leads to GitHub

### Bug Fixes
- **Deletion progress bar** вЂ” fixed progress bar display when emptying trash
- **Certificate selection** вЂ” now only certificate files can be selected
- **Account deletion** вЂ” deleting account now fully cleans up local data
- **User avatar color** вЂ” avatar in search and settings now changes color with app theme

---

## v1.0.8 (27.12.2025)

### New Features
- **Deferred trash deletion** вЂ” when emptying trash, a progress bar with cancel button is shown
- **Auto-empty trash** вЂ” automatically delete old emails from trash after 3/5/7/14/30 days (default 30 days)
- **Animation settings** вЂ” toggle in settings to enable/disable UI animations
- **Extended folder card animations** вЂ” icon pulse, slight wobble, scale animation on press
- **Server certificate selection** вЂ” ability to specify certificate file for corporate servers
- **Delete sound** вЂ” audio notification when deleting emails and folders

### UI Improvements
- **"View changelog" button** вЂ” animated button with link to changelog on GitHub
- **Privacy policy link** вЂ” added in settings under "About" section
- **Receipt checkboxes in menu** вЂ” "Request read/delivery receipt" moved to dropdown menu
- **Card appearance animation** вЂ” staggered animation when loading main screen
- **Unread badge pulse** вЂ” animated unread email counter
- **Deletion progress bar** вЂ” appears at top of screen above all content

### Bug Fixes
- **certificatePath in all clients** вЂ” certificate now correctly passed when sending emails
- **EasClient cache clear on account update** вЂ” account settings changes apply immediately
- **Notifications for multiple accounts** вЂ” each account now shows separate notification

---

## v1.0.7 (27.12.2025)

### New Features
- **Email mismatch dialog** вЂ” informative dialog shown during Exchange account verification instead of error
- **Color themes** вЂ” choose from 7 color themes: purple, blue, red, yellow, orange, green, pink
- **Daily themes** вЂ” ability to assign different colors for each day of the week
- **Animated logo** вЂ” envelope icon in "About" section with pulse and wobble animation
- **Settings grouping** вЂ” settings divided into sections: Appearance, Sync, About
- **Account signature** вЂ” ability to set signature for each account
- **Send/receive sounds** вЂ” audio notification when sending and receiving emails

### Bug Fixes
- **Fixed sender address in MDN/DSN** вЂ” read and delivery receipts now sent to correct email
- **Fixed time input fields** вЂ” in schedule send dialog you can now properly edit hours/minutes/seconds
- **Auto-activate new account** вЂ” when adding second account it automatically becomes active
- **Preserve settings on 401 error** вЂ” on authorization error email, server, color are preserved

### Security
- **Disabled ADB backup** вЂ” `allowBackup="false"` prevents data extraction via ADB

---

## v1.0.6c (26.12.2025)

### New Features
- **Email verification when adding Exchange account** вЂ” verifies that entered email matches actual email on server
- **"Verifying account" screen** вЂ” animated verification screen like in Outlook
- **Read receipt request (MDN)** вЂ” checkbox when sending email to request read notification
- **Delivery receipt request (DSN)** вЂ” checkbox to confirm email delivery to recipient's server
- **Font size customization** вЂ” small/medium/large selection in app settings
- **Individual sync settings** вЂ” each account has its own sync mode and interval
- **Account card settings** вЂ” sync mode (Push/Scheduled) and interval directly in UI

### Battery Optimization
- **Sync mode selection** вЂ” for Exchange accounts choose Push or Scheduled
- **Night mode battery saving** вЂ” from 23:00 to 7:00 sync every 60 minutes
- **Adaptive heartbeat** вЂ” automatic Ping interval increase from 5 to 15 minutes on stable connection
- **Smart network stop** вЂ” PushService pauses when no internet
- **Sync debounce** вЂ” protection from duplicate sync requests
- **Smart sync logic** вЂ” automatic disable of periodic sync for Push accounts

### Bug Fixes
- **Fixed vibration** вЂ” haptic feedback when selecting emails works correctly
- **Fixed search highlight** вЂ” found letters highlighting works on all devices
- **Fixed scroll on "Select all"** вЂ” automatic scroll to top of list
- **Fixed notifications** вЂ” fixed IDs, no spam
- **Fixed default language** вЂ” now Russian for new installations

---

## v1.0.5c (26.12.2025)

### Notification Fixes
- Fixed notifications on lock screen вЂ” added `VISIBILITY_PUBLIC`
- Notification channels now recreated on app launch to apply new settings
- Added `CATEGORY_EMAIL` for proper system handling

### Email Move Fixes
- Fixed restore from trash bug вЂ” email now returns to the folder it was deleted from
- `originalFolderId` correctly updates when moving between folders

### Compatibility Improvements
- Improved TLS compatibility on different devices вЂ” removed deprecated SSLv3
- Added TLSv1.3 support
- Improved SSL/TLS error handling вЂ” fallback to OkHttp defaults
- Added sync timeouts (60 sec total, 30 sec per folder)

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

---

## v1.0.4b (25.12.2025)

### Performance and Memory Optimization
- SettingsRepository is now singleton вЂ” eliminated multiple instance creation
- EasClient cached per account вЂ” resource savings on repeated requests
- Fixed memory leak in SyncAlarmReceiver вЂ” local coroutine scope with cancel() in finally
- Removed sync duplication when Direct Push not supported by server
- Removed all Log.d() calls for production build вЂ” reduced APK size
- Optimized ProGuard rules for R8 minification

### UI Improvements
- Improved "Move to" dialog logic
- Fixed crash when opening email from notification on locked screen
- IMAP and POP3 marked as "beta" in account setup interface

---

## v1.0.3c (24.12.2025)

### Critical Fixes
- Fixed critical notification issue вЂ” notifications now arrive for all new emails
- Fixed foreground notification navigation вЂ” now opens main screen
- Improved new email detection logic вЂ” uses last check time
- Added SyncAlarmReceiver for reliable sync on Xiaomi/MIUI

---

## v1.0.3b (24.12.2025)

### Notification Improvements
- Improved new email notifications вЂ” now show sender name and subject
- Clicking notification with single email opens that email directly
- Added localization for special Exchange folders (Tasks, Calendar, Contacts)
- Added permission requests on first launch (battery, exact alarms)
- Improved background sync reliability on Xiaomi/MIUI (AlarmManager fallback)
- Foreground notification now localized based on app settings

---

## v1.0.3a (23.12.2025)

### Localization
- Fixed interface localization вЂ” all strings now correctly translate
- Fixed hardcoded Russian strings in ComposeScreen, SearchScreen, EmailListScreen
- System folder names now localized (Inbox/Р’С…РѕРґСЏС‰РёРµ, Sent/РћС‚РїСЂР°РІР»РµРЅРЅС‹Рµ, etc.)
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
