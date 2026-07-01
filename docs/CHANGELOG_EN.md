# Changelog

## v1.6.3b

### Architecture — MVVM adoption (incremental)
- Introduced a `ViewModel` + immutable `UiState` + `StateFlow` layer to eliminate state-loss bugs on configuration changes (rotation)
- `SyncCleanupScreen` → `SyncCleanupViewModel`: sync & cleanup settings survive rotation without manual flags
- `SearchScreen` → `SearchViewModel`: search results, selection and batch operations live in the ViewModel; removed the fragile `rememberSaveable` + DB re-query dance (eliminates `TransactionTooLargeException` risk)
- `NotesScreen` → `NotesViewModel`: notes/trash/selection/search and auto-sync live in the ViewModel; removed the `dataLoaded` flag that could trigger redundant syncs on rotation/account switch; progress-bar operations (permanent delete/restore/empty trash) are encapsulated behind suspend methods
- `TasksScreen` → `TasksViewModel`: tasks/trash/filter/selection/search and auto-sync live in the ViewModel; removed the `dataLoaded` flag; batch restore and permanent-delete (with progress bar) moved out of the Composable into ViewModel suspend wrappers
- `UserFoldersScreen` → `UserFoldersViewModel`: user folder list (filter/sort), selection, create/rename/delete and batch-delete with progress live in the ViewModel; long-running operations moved out of `rememberSyncScope` (which was cancelled on rotation) into `viewModelScope` — they now survive rotation
- `EmailListScreen` → `EmailListViewModel`: email list (folder/favorites/"Today"), filters, selection, sync and batch operations (to trash/spam, move, restore, read/unread, flags) live in the ViewModel; operations moved out of `rememberCoroutineScope` (which was cancelled on rotation) into `viewModelScope` — they are no longer interrupted by rotation; Drafts auto-sync now runs exactly once (no redundant sync or races on rotation); the current folder and the move-dialog folder list are derived from a single reactive stream
- `EmailDetailScreen` → `EmailDetailViewModel` (screen core): the email/attachments/folders, body and inline-image loading, and operations (refresh, delete to trash, move, restore, read/unread, flag, MDN) live in the ViewModel; operations moved out of `rememberCoroutineScope` (which was cancelled on rotation — aborting delete/move) into `viewModelScope`; inline images are cached in state — eliminating their repeated network reload on every rotation; UI-only concerns (WebView, attachments, iCal/tasks, meeting invitations) remain in the Composable
- One-shot UI events (toasts, sound, easter egg) moved to an event channel (`Channel` + `receiveAsFlow`) — the ViewModel stays independent of localization/resources
- Dependencies are constructor-injected (DIP): system push/sync side-effects are hidden behind a `SyncEffects` interface

### Stability & security (code audit)
- Fixed crashes when changing sync/cleanup settings and in notes/tasks/folders/search mutations: mandatory exception handling with proper loading/selection flag reset (M-1)
- Fixed a Push service crash on Android 12+ (`ForegroundServiceStartNotAllowedException`): safe foreground start + graceful stop (N-10)
- Restored the Push watchdog on Android 8+: the screen/unlock/charging receiver is now registered dynamically (a manifest receiver never fired → Push degradation) (N-12)
- Prevented OOM when sending large attachments: total size is checked BEFORE reading into memory (10 MB limit + notification) (N-2)
- Email editor: Content-Security-Policy + a single HTML sanitizer strip inline `on*`/`javascript:` handlers (including on paste) (L-3)
- Fixed an EAS-client cache race during failover to an alternate server (N-7)
- Outgoing de-duplication: a stable `ClientId`/`Message-ID` prevents duplicate sends on queue retry (N-11)
- MIME header-injection protection: addresses and Message-IDs across all message-building paths (send, meeting invitations, MDN read receipts) are stripped of CR/LF via a single sanitizer — most important for MDN, whose values come from the incoming message (N-1)
- Correct sending of long subjects: the Subject is folded per RFC 2047 (multiple encoded-words) so the header line stays within limits and isn't rejected by strict gateways; a single encoder is shared across all message-building paths (N-3)
- Safer email de-duplication: the duplicate-cleanup key now uses the canonical Message-ID — distinct emails that share subject/sender/timestamp are no longer deleted (L-4)
- Exchange over HTTPS only: removed the non-functional plaintext-HTTP option when setting up an Exchange account (cleartext was blocked by the system security policy); SSL is now forced for Exchange; IMAP/POP3 unaffected (N-13)
- Unified inline-image parsing: removed duplicated MIME extraction logic (single source) and added protection against maliciously deep-nested MIME (N-5)
- Battery: all screens switched to lifecycle-aware state collection (`collectAsStateWithLifecycle`) — flow collection pauses while a screen is not visible, cutting background CPU/battery use (N-8)
- Crash resilience: screen operations (deleting accounts/contacts/events, settings, etc.) can no longer crash the app on a DB/I-O error — the failure is isolated and logged (UI-1)
- Attachments: removed a faulty fallback that saved the entire email instead of the attachment when the direct download failed (corrupt file) (PB-1)
- Stability: opening heavy emails with large attachments/images no longer crashes the app on low memory — the email is shown without inline images (PB-2)
- Crash resilience (cont.): background app, push/sync, alarm and receiver operations can no longer crash the process on an uncaught error — a single crash-safe scope isolates and logs the failure
- Crash resilience (reactive screens): observing emails/folders/notes/tasks can no longer crash the app on a database error — the failure is isolated and logged (CR-2)
- Calendar: correct timezone handling when parsing event/task times that carry an offset instead of UTC — fixes a possible time shift (N-4)
- Stability: inline images are not built from excessively large emails — out-of-memory prevention (in addition to the open-path guard) (PB-2)
- Fixed a potential race/`ConcurrentModificationException` in the Push service's EAS-client cache — switched to `ConcurrentHashMap` with atomic operations (N-15)
- Dead-code cleanup and DRY: removed unused `clearAllEasClientCache`/`RepositoryProvider.clear`/`getEmailsByFolder`; `ContactRepository` is now always obtained via the provider (L-1, L-2, N-9, M-2)

### Widget (home screen)
- Fixed bottom-row clipping on a narrow widget: the sync button and avatars no longer run off the edge — avatar count and size adapt to width, and the last-sync label is shown on the wide widget (W-1)
- The last-sync time no longer looks like "today" for older syncs: a date is added for non-today syncs (W-2)
- Recent emails in the widget: today's mail now shows the time (previously always the full date); minor render optimization — the date formatter is no longer recreated inside the loop (W-3)
- Internal widget cleanup (KISS): removed unused event fields that were computed on every render (W-4)

### UX — haptic feedback and accessibility
- Added haptic feedback when tapping the star (flag) in the email list and on the email detail screen — consistent with long-press/selection
- Dynamic accessibility labels for the star: "Add to favorites" / "Remove from favorites" depending on state (previously a static "Favorites" label)
- Accessibility label for the attachment icon in the list row (previously `null` — TalkBack did not announce it)
- Fixed accessibility label for the "star selected" button in SearchScreen: `Strings.star` ("Star") instead of `Strings.favorites` ("Favorites")
- DRY: removed duplicate `Strings.unstar` (identical to `Strings.removeFromFavorites`); sole usage replaced with `Strings.removeFromFavorites`

### Tests
- Added ViewModel unit tests (`SearchViewModelTest`, `SyncCleanupViewModelTest`, `NotesViewModelTest`, `TasksViewModelTest`, `UserFoldersViewModelTest`, `EmailListViewModelTest`, `EmailDetailViewModelTest`) on plain JUnit + MockK, no Robolectric
- Widget formatting unit tests (`MailWidgetFormatTest`: today→time else→date, midnight boundary, same day-of-year across years)


## v1.6.2 (27.02.2026)

- Redesigned UI: new elements, custom file icons, extra scrollbars, updated widget
- Full `Save` and `Save as` actions for all attachments
- Improved multi-select: drag selection and batch operations
- Stronger email validation before sending
- Calendar: event attachments, recurring events, local trash
- Contacts: group support when composing + duplicate warnings
- Drafts: local save mode (switchable in settings)
- Exchange 2007 SP1: fixed loading of all tasks, calendar events, and folder sync
- Smoother onboarding slide transitions
- Significant performance improvements
- Bug fixes, reduced memory leaks and battery usage, improved stability, security level

### Security — extended XSS protection for email bodies
- `sanitizeEmailHtml` now blocks plugin containers: `<iframe>`, `<object>`, `<embed>`, `<applet>` (opening/closing tags and inner content)
- Blocked `<meta http-equiv="refresh">` — protection against JS-based meta redirects
- Blocked `data:text/html` in `href/src/action/formaction/xlink:href` — protection against inline HTML execution
- Added `xlink:href` to JS-URI attribute list — protection against SVG-based XSS
- Implementation without third-party dependencies (regex-based, idempotent, no ReDoS)
- Complements the existing `loadDataWithBaseURL(null, ...)` in `EmailDetailScreen` (null-baseURL blocks cross-origin requests and cookie/localStorage exfiltration)

### Performance — memory optimization for large-folder sync
- `EmailSyncService.syncSentFull` — orphan detection uses lightweight `EmailDedupInfo` projection (6 fields instead of ~30) instead of full `EmailEntity`
- `EmailSyncService.reconcileSentAfterFullResync` — switched to id-only projection `getEmailIdsByFolder`, unified with `reconcileGenericFolderAfterFullResync` (DRY)
- `EmailOperationsService.resolveEmailIds` — content-matching during ServerId migration after SyncKey=0 reset now uses lightweight projection
- Expected impact: for Sent folders with 10k+ emails — peak memory during orphan detection reduced ~4-5x

### Email detail — Sent Items and screen rotations
- EAS body loading no longer starts normal email viewing with a large MIME (`Type=4`) request: HTML/plain body is fetched first, and MIME is used only as a small header fragment where safe.
- For Sent Items on Exchange 2007 SP1, initial MIME-header loading is disabled to prevent emails with large attachments from blocking message opening or leaving an endless loading indicator.
- Attachment metadata refresh and automatic inline-image loading now have timeout and size limits; large attachments no longer trigger hidden full-MIME loading while opening the email body.
- Fixed empty body for some emails in Sent Items on Exchange 2007 SP1: the EWS fallback `fetchEmailBodyViaEws` now additionally searches by `item:DateTimeSent` (the semantically correct field for Sent), uses `IgnoreCaseAndNonSpacingCharacters` for subject matching, and adds a 10-minute window for clock-drift / indexing-lag cases on the server.
- On ambiguous EWS matches (multiple candidates with the same subject) the fallback no longer bails out — it retries the next, more specific restriction; the existing cached body is never overwritten with an empty server response.
- After a manual email refresh, if the server did not return the body, a clear toast is shown instead of a misleading "Email refreshed".
- Removed the redundant `forceReload` of the email body on every `EmailDetailScreen` recomposition. Rotating the screen no longer reloads the body — `LaunchedEffect(emailId)` only fetches when the local body is empty.

### Widget — stability and performance
- `MailWidget` now uses `applicationContext` and serializes `GlanceAppWidget.updateAll()` through a shared mutex to avoid concurrent updates from sync/UI/account paths.
- Recent widget emails are limited to Inbox and `read = 0`; already-read messages are no longer shown as new.
- Recent email loading now uses `WidgetRecentEmailSummary` without loading heavy `EmailEntity.body`.
- The next task and calendar event are loaded through `WidgetTaskSummary` / `WidgetCalendarEventSummary` without body, attendees or attachment JSON payloads.
- `MailDatabase` was updated to v42 with hot widget indexes: `emails(read, dateReceived)`, `folders(type)`, `tasks(complete, isDeleted, dueDate, subject)`, `calendar_events(isDeleted, startTime, endTime)`, `calendar_events(isDeleted, endTime, startTime)`.

### Calendar — recurring attachments and safe deletion
- Recurring event attachments follow DRY: the local DB stores JSON metadata and occurrences do not duplicate file bytes.
- Exchange 2007 SP1 recurring series use the EWS master ItemId for calendar attachment upload/fetch.
- `deleteEventPermanently` and `emptyCalendarTrash` delete locally only after confirmed server-side delete; server errors no longer cause local-only permanent deletion.
- Calendar delete/restore operations are serialized with the per-account sync mutex so sync cannot overwrite `isDeleted` or resurrect stale records.
- Attendee meetings send `MeetingResponse`/EWS `DeclineItem` before deletion; if the original meeting request cannot be found, the operation returns an error without local deletion.
- Calendar attachment preview cache uses stable `fileReference`-based names, age-based cleanup and delayed `path + lastModified` cleanup to avoid races with external viewers.

### Compose and editors — preserving input across screen rotation
- `ComposeScreen`: reply (`replyToEmailId`) and forward (`forwardEmailId`) no longer re-run their loaders on rotation — added `rememberSaveable` flags `replyLoaded`/`forwardLoaded`. Previously rotation overwrote the email body (signature + quote), and forward additionally reset the "To" field, attachments (with re-download) and the SmartForward source.
- `ComposeScreen`: a new email's signature is initialized once (`signatureInitialized`) — a user-deleted signature is no longer restored or duplicated on rotation.
- `RichTextEditor`: restore after rotation prefers the freshest `controller.latestHtml`, and the parent `body` is reconciled with the recovered content. Fixes loss of typed text (where only the signature remained); also fixes the HTML-signature editor in settings.
- `CreateEventDialog` (calendar): event start/end date and time are no longer reset on rotation — added a `datesInitialized` guard (mirroring the task dialog).

## v1.6.1 (09.02.2026) — Package rename → "com.dedovmosol.iwomail", reinstall required

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
- Calendar/tasks — updated filters and full-width search
- Full error localization (EN/RU)

### Fixes
- Drafts — server-side save of formatting, inline images and attachments
- Reply/Forward — correct addresses, attachments and inline images
- Sync — read status propagation to server, reentrancy protection, system folder auto-recovery
- Deletion — batch delete, fixed email "resurrection"
- "Today" card — correct count of all emails for the day
- Stability — markAsRead conflict handling, connection leaks, PushService crash (Android 12+), ANR SyncAlarmReceiver
- UI — double-tap protection, HTML entity decoding

### Performance
- Refactoring: 15 services, 6 repositories
- N+1 query optimization, caching, regex pre-compilation
- PushService — battery savings, Chinese ROM protection

---

## v1.6.0 (08.01.2026)

### New Features
- Onboarding Screen — welcome slides with app features on first launch, language and animations selection
- Redesigned Widget — new design with search, calendar, accounts and unread counters
- Today Statistics Card on main screen — emails, events and tasks for today
- Updates Screen — check for updates with auto-check interval settings
- About Screen — all app information in one place
- Sync & Cleanup Screen — sync and auto-cleanup management per account
- Per-account settings — night mode and Battery Saver ignore now configurable per account
- Offline mode:
  - "No network" banner — shown at top of screen when offline
  - Send queue — emails without attachments are saved and sent automatically when network returns
  - Email body caching — last 7 emails in each folder are prefetched for offline access
  - Network check before sync — "No network" toast instead of waiting for error
  - Network check before downloading attachments

### Improvements
- Simplified settings screens — sections moved to separate screens
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
- App Shortcuts — quick actions when holding app icon (Compose, Inbox, Search, Sync)
- "Compose" button on search screen

### Improvements
- Sender names taken from contacts (priority), then from email history

### Exchange 2007 Fixes
- Task sync — uses EWS instead of EAS (EAS returns empty response)
- Task creation — uses EWS for all tasks (EAS doesn't work on Exchange 2007)
- Task deletion from server now works correctly

### Fixes
- Fixed task/event/note duplication when creating on Exchange 2007
- Folder counters not updating after first sync
- Fixed description duplication in calendar events and tasks

---

## v1.5.1 (05.01.2026)

### New Features
- **Undo send** — 3 seconds to cancel after pressing "Send" (green progress bar)

### Improvements
- **Account switch in compose** — when selecting different sender, their signature and contacts are automatically loaded
- **Architecture-specific updates** — downloads APK for your architecture (arm64, arm32, x86)
- **Deletion progress bar** — moved to bottom of screen, doesn't overlap content
- **Task reminders** — added reminder scheduling when creating and editing tasks

### Fixes
- Fixed ComposeScreen reopening on screen rotation after mailto:
- Fixed button display in "Support developer" dialog in landscape mode
- Fixed draft save prompt when changing sender
- Fixed navigation to setup screen after deleting last account

### Bug Fixes from v1.5.0
- Fixed scheduled sync — interval now applies correctly
- Fixed calendar event deletion from server
- Fixed meeting response — uses correct account
- Fixed duplicate attendees in calendar events
- Fixed meeting invitation sending
- Fixed body text duplication when accepting meeting invitation
- Fixed opening email from notification and account switching
- Fixed tasks loading on main screen

### Exchange 2007 Fixes
- **Tasks with dates** — fixed task creation with dates on Exchange 2007:
  - Uses EWS instead of EAS for creating tasks with dates
  - Due date and body are saved to server
  - Start date is saved locally only (Exchange 2007 limitation)
  - Tasks without dates are created via EAS as before
- **Calendar event creation** — updated EWS request format to match official Microsoft documentation
- **Meetings with attendees** — invitations are now sent through Exchange (not as separate email)
- **Task sync** — fixed WBXML parsing (added Code Page 9 for Tasks)
- **Task/calendar body** — added BodyPreference to sync requests

### Note
⚠️ **Calendar and Tasks are in beta mode** — bugs and instability may occur

---

## v1.5.0 (03.01.2026)

### New Features
- **Check for updates** — button in settings to check and install new versions
- **Task assignment** — ability to assign tasks to other users with email notification
- **Meeting invitations** — send and receive calendar event invitations (iCalendar)
- **Default email app** — handles mailto: links and Share from other apps
- **Clickable contacts** — emails and phones in calendar, notes and tasks are now clickable

### Improvements
- **Battery optimization** — instant Battery Saver response via BroadcastReceiver, increased PushService heartbeat
- **Sync after send** — "Sent" folder updates automatically
- **Self-filtering in contacts** — own email not shown in organization list
- **Change credentials** — new menu item in account settings
- Memory and performance optimizations (singleton repositories, DB batching, indexes)

### Fixes
- Fixed potential crashes and memory leaks
- Fixed infinite sync when switching accounts
- Fixed attachments and drafts preservation on screen rotation
- Duplicate protection in calendar, tasks and email suggestions

---

## v1.4.2 (01.01.2026)

### New Features
- **Tasks** — sync and manage of personal tasks from the server
  - View, create, edit and delete tasks
  - Filters: all, active, completed, high priority, overdue
  - Task search
  - Mark complete with server sync
  - Priority and due date
  - Automatic background sync
  - Notifications
  - Date and time picker for start date and due date

### Improvements
- **Memory optimization** — repositories on main screen are now cached
- **Network optimization** — tasks folder ID caching (fewer HTTP requests)
- **Battery optimization** — tasks sync respects interval (not on every run)
- **Draft editing** — fixed saving changes to server drafts

### Fixes
- Battery saver indicator now updates when mode changes
- Removed large font size option from settings
- Fixed drafts badge on main screen (was showing even without drafts)

---

## v1.4.1 (31.12.2025)

### New Features
- **Server-side drafts** — drafts now sync with Exchange server via EWS (previously stored locally only)
  - Creating draft saves to server
  - Sync drafts from server
  - Editing draft updates on server
- **High priority when sending** — ability to mark email as important when sending (recipient will see red exclamation mark)

### Improvements
- **Compact dialogs** — notes and calendar event dialogs are now compact, sized by content
- **Dynamic folder card height** — text no longer truncated on small screens
- **Regex optimization** — precompiled and cached regex patterns in all key files

### Bug Fixes
- **Draft deletion** — drafts are deleted immediately without moving to trash
- **Mixed deletion** — correct handling when deleting emails from different folders simultaneously
- **Drafts folder display** — fixed crash when deleting emails, drafts now use standard Flow instead of manual loading
- **Blind carbon copy (BCC)** — fixed sending emails with BCC (previously BCC was not working)
- **Quote localization** — text "Original message", "From", "Date", "Subject" in replies and forwards is now localized
- **Drafts sync** — full resync on every update, deletions from Outlook now reflected
- **Calendar sync** — fixed UI flickering and rescheduling all reminders on every sync
- **Notes sync** — fixed UI flickering during sync
- **Contacts sync** — fixed UI flickering during sync
- **Localization** — all hardcoded strings are now localized
- **DatePicker crash** — fixed crash when selecting scheduled send date
- **WBXML parser crash** — fixed crash when encoding zero value
- **SSL with self-signed certificates** — fixed security policy negotiation error when acceptAllCerts enabled
- **EWS authentication** — added Basic auth fallback for servers not supporting NTLM

---

## v1.4.0 (31.12.2025)

### New Features
- **Create calendar events** — create events directly from the app with Exchange server sync
- **Edit events** — change title, time, location, description, reminder and busy status
- **Delete events** — delete events from Exchange server
- **Event reminders** — push notifications before event start time
- **Create notes** — create notes with server sync
- **Edit notes** — change note title and body
- **Delete notes** — delete notes from server

### Improvements
- **Auto-scroll** — list scrolls to top after creating note or event
- **Performance optimization** — precompiled regex patterns

### Bug Fixes
- **Draft saving** — email body now saves correctly
- **Memory leak** — fixed CoroutineScope leak in MainScreen
- **Battery optimization** — increased PushService restart timeout from 5 to 30 sec to prevent aggressive restart cycles

### Technical Details
- Calendar: EAS Sync Add/Change/Delete for all Exchange versions (2007+)
- Notes Exchange 2010+: EAS Sync Add/Change/Delete
- Notes Exchange 2007: EWS CreateItem/UpdateItem/DeleteItem with NTLMv2
- Reminders: AlarmManager with setExactAndAllowWhileIdle for precise timing

---

## v1.3.2 (30.12.2025)

### New Features
- **"Important" filter** — new filter for high priority emails (Importance = High)
- **Priority icon** — high priority emails marked with red exclamation mark in list

### Bug Fixes
- **Sync deletions** — emails deleted or moved on server (Outlook) now removed from local database during sync
- **Email body loading** — added fallback to plain text (Type=1) if HTML returns empty result
- **Initial sync for multiple accounts** — each account now syncs separately, notification shown for each
- **Parallel folder sync** — folders now sync in parallel instead of sequentially
- **Auto-scroll during sync** — email list now auto-scrolls to top when new emails appear during sync
- **Attachments on screen rotation** — attachments no longer disappear when rotating screen in email detail
- **Quoted-printable decoding** — fixed Cyrillic decoding in emails with quoted-printable encoding
- **Sync cancellation on account switch** — when quickly switching accounts, previous sync is properly cancelled
- **Email body loading timeout** — added 25 sec total timeout for triple fallback (MIME → HTML → Plain)
- **Delete emails on server error** — if server doesn't respond, emails are deleted locally

### Improvements
- **Notes for Exchange 2007** — added notes sync via EWS with NTLMv2 for servers without EAS 14.1+ support
- **Local drafts notice** — Drafts folder now shows info that drafts are stored locally

---

## v1.3.1 (29.12.2025)

### Bug Fixes
- **Bitmap memory leak** — images in event descriptions now released when leaving screen
- **Unclosed HTTP connections** — connections when loading images now properly closed
- **Regex optimization** — link parsing patterns compiled once instead of on every call
- **SoundPlayer race condition** — added synchronization to prevent race conditions
- **Cache cleanup on account deletion** — EasClient and heartbeat now cleared when deleting account
- **Heartbeat persistence** — adaptive heartbeat saved between PushService restarts
- **Local contacts deletion** — fixed contact deletion (list now updates properly)
- **Avatars in Sent folder** — now shows first letter/digit of recipient name
- **Duplicate sender email** — if name equals email, shows only email without duplication
- **Bulk contacts deletion** — fixed deleting multiple contacts at once
- **Initial sync** — calendar, notes and contacts now sync on first launch
- **Pull-to-refresh in empty folder** — animated envelope now displays at top center
- **Email list updates** — fixed automatic list updates during background sync

### Improvements
- **Copy contacts from organization** — added button to bulk copy selected contacts to personal
- **Settings text** — clarified "Ignore battery saver when syncing" text
- **Clickable recipient** — "To" field in email is now clickable, opens compose with pre-filled address
- **FAB icon in Sent folder** — changed from pencil to email icon
- **Expanded sections reset** — "Tips" and "About" sections collapse when navigating to folder
- **Navigation drawer** — Contacts, Notes and Calendar moved to side menu under Favorites
- **Real-time list updates** — emails in folder update automatically during background sync
- **Clickable emails in calendar** — organizer and attendee emails are clickable, open compose screen
- **Clickable location URLs** — if event location contains URL, it's clickable and opens in browser
- **Contact dialog** — removed close button, dialog closes by tapping outside

---

## v1.3.0 (29.12.2025)

### New Features
- **Exchange Notes** — sync and view notes from Exchange Notes folder (read-only)
- **Exchange Calendar** — sync and view calendar events (read-only) with three display modes:
  - Agenda — events list grouped by dates
  - Month — calendar grid with dots on days with events
  - Year — clicking month header opens view with all 12 months
- **Notes and Calendar sync settings** — in account settings choose interval: disabled, daily, weekly, biweekly, monthly
- **Automatic sync** — notes and calendar sync in background together with contacts
- **Multiple contact selection**
- **Favorite contacts** — mark contacts with star and filter by favorites
- "Notes" and "Calendar" cards on home screen
- Search in notes and events
- Detailed event view (time, location, organizer, attendees)
- Contact counters in "Personal" and "Organization" tabs
- Updated "About" section — added contacts, notes and calendar features

### Improvements
- **Year in event dates** — group headers show year (Friday, December 25, 2020)
- **Past events indicator** — green checkmark and muted colors for completed events
- **Clickable links in events** — URLs in event descriptions are clickable
- **Images in events** — images from event descriptions are loaded and displayed
- **Days with events highlighted** — in year view, days with events shown in blue
- **Improved email scroll** — WebView no longer blocks parent container scroll
- **Inline images** — not shown in attachments list, displayed in email body
- **Avatar color palette fixes** — 32 colors everywhere

---

## v1.2.0 (29.12.2025)

### New Features
- **Rebranding** — app renamed to "iwo Mail Client"
- **Background GAL contacts sync** — contacts from organization address book now automatically download in background and save to local database
- **Contacts sync interval setting** — in account settings choose: disabled, daily, weekly, biweekly, monthly (default — daily)
- **Manual contacts sync** — "Sync" button on "Organization" tab in contacts
- **Multiple signatures** — up to 5 signatures per account with selection when composing email
- **Auto cleanup** — individual settings per account: trash, drafts, spam (never, 3, 7, 14, 30, 60 days)
- **Contact picker for composing** — contact selection button next to To/Cc/Bcc fields with two tabs (Personal and Organization), search and multiple selection

### Improvements
- **Audio optimization** — MP3→OGG conversion (~45 KB savings)
- **Sent folder** — now shows recipient with "To:" prefix instead of sender
- **"Write more" button** — in Sent folder instead of "Reply" for continuing conversation
- **HTML cleanup in quotes** — when replying and forwarding, HTML tags are removed, clean text is shown
- **Exchange 2007 compatibility** — improved XML request format for EAS 12.x (BodyPreference with TruncationSize)
- **GAL loading by letters** — for servers not supporting wildcard "*", contacts are loaded by alphabet letters (A-Z)
- **Account settings** — moved to separate screen
- **Fixed delete dialog texts** — for drafts and trash shows "will be deleted permanently", for other folders "will be moved to deleted"
- **Localized authorization errors** — 401, timeout and other errors now shown in selected language

### Bug Fixes
- **Contacts in picker dialog** — now shows synced contacts from database
- **Repeated GAL loading** — contacts no longer reload on every visit to "Organization" tab
- **Fixed recipient duplication** — email view no longer duplicates name and email

---

## v1.1.2 (28.12.2025)

### New Features
- Interface personalization screen

### Bug Fixes
- Contact export (FileProvider)
- Test email on verification deleted automatically
- Dialog buttons — vertical layout
- Contacts folder hidden from list
- GAL search when returning to contacts screen
- Back swipe in compose shows save dialog
- Focus preserved on screen rotation
- Drafts counter updates immediately after saving
- Local drafts deletion works correctly
- Save draft dialog preserved on screen rotation
- Sync not interrupted on navigation

### Improvements
- Dialogs — compact design, animations
- Sync time — date+time format, only after completion
- Updated "Tips" section
- User avatar uses account color from settings
- Drafts saved locally and deleted immediately (not to trash)
- Drafts folder not synced with server
- Save draft dialog in Material Design style

---

## v1.1.1 (27.12.2025)

### Bug Fixes
- **Clipboard button order** — Cut → Copy → Paste
- **Contact menu** — added "Edit" and "Delete" to three-dot menu
- **Contact dialog buttons** — "Cancel" and "Save" moved to opposite sides
- **Sync on screen rotation** — sync no longer interrupts when orientation changes
- **Contact details dialog** — redesigned, action buttons at bottom
- **Trash cleanup on navigation** — deletion continues even when leaving trash folder
- **Compact edit dialog** — fields grouped in rows
- **Certificate text** — added warning "(less secure)"
- **Notifications on first launch** — not shown for old emails
- **Folder order on main screen** — Inbox/Sent, Drafts/Trash, Favorites/Contacts

### Improvements
- **Battery Saver setting text** — reworded to "Frequent sync"
- **Push notification tip** — clarified it's Push mode only
- **Contacts card** — added to main screen (light blue)

---

## v1.1.0 (27.12.2025)

### New Features
- **Contact groups** — organize contacts into folders:
  - Create, rename and delete groups
  - Move contacts between groups
  - Filter contacts by groups (chips)
  - View contacts without group
- **Battery saver mode** — app automatically detects when Android enables battery saver:
  - Increases sync interval to 60 minutes
  - Shows indicator on main screen
  - "Ignore battery saver" setting for those who need mail urgently

### Bug Fixes
- **Empty trash** — now shows real deletion progress (X / Y emails) instead of fake timer
- **User avatar color** — avatar in search and settings now changes with app color theme

### Changes
- **Exchange 2003 not supported** — removed EAS 2.5 support (Exchange 2003). Supported versions: EAS 12.0-14.1 (Exchange 2007+).

---

## v1.0.9 (27.12.2025)

### New Features
- **Contacts** — full contact management system:
  - Personal contacts with add, edit and delete
  - Corporate address book (GAL) search
  - Export contacts to vCard (.vcf) and CSV
  - Import contacts from vCard and CSV
  - Alphabetical grouping and search
  - Add GAL contacts to personal
  - Integration with email composition
- **Email autocomplete** — when typing in "To" field, suggestions appear from local contacts, email history, and GAL

### Improvements
- **Background sync** — all folders sync in background on app launch
- **Styled dialogs** — unified gradient design for all dialogs
- **Certificate indicator** — account settings now show if server certificate is used
- **Certificate management** — view info, export, replace, or remove certificate
- **Developer link** — "DedovMosol" in settings is now clickable and leads to GitHub

### Bug Fixes
- **Deletion progress bar** — fixed progress bar display when emptying trash
- **Certificate selection** — now only certificate files can be selected
- **Account deletion** — deleting account now fully cleans up local data

---

## v1.0.8 (27.12.2025)

### New Features
- **Deferred trash deletion** — when emptying trash, a progress bar with cancel button is shown
- **Auto-empty trash** — automatically delete old emails from trash after 3/5/7/14/30 days (default 30 days)
- **Animation settings** — toggle in settings to enable/disable UI animations
- **Extended folder card animations** — icon pulse, slight wobble, scale animation on press
- **Server certificate selection** — ability to specify certificate file for corporate servers
- **Delete sound** — audio notification when deleting emails and folders

### UI Improvements
- **"View changelog" button** — animated button with link to changelog on GitHub
- **Privacy policy link** — added in settings under "About" section
- **Receipt checkboxes in menu** — "Request read/delivery receipt" moved to dropdown menu
- **Card appearance animation** — staggered animation when loading main screen
- **Unread badge pulse** — animated unread email counter
- **Deletion progress bar** — appears at top of screen above all content

### Bug Fixes
- **certificatePath in all clients** — certificate now correctly passed when sending emails
- **EasClient cache clear on account update** — account settings changes apply immediately
- **Notifications for multiple accounts** — each account now shows separate notification

---

## v1.0.7 (27.12.2025)

### New Features
- **Email mismatch dialog** — informative dialog shown during Exchange account verification instead of error
- **Color themes** — choose from 7 color themes: purple, blue, red, yellow, orange, green, pink
- **Daily themes** — ability to assign different colors for each day of the week
- **Animated logo** — envelope icon in "About" section with pulse and wobble animation
- **Settings grouping** — settings divided into sections: Appearance, Sync, About
- **Account signature** — ability to set signature for each account
- **Send/receive sounds** — audio notification when sending and receiving emails

### Bug Fixes
- **Fixed sender address in MDN/DSN** — read and delivery receipts now sent to correct email
- **Fixed time input fields** — in schedule send dialog you can now properly edit hours/minutes/seconds
- **Auto-activate new account** — when adding second account it automatically becomes active
- **Preserve settings on 401 error** — on authorization error email, server, color are preserved

### Security
- **Disabled ADB backup** — `allowBackup="false"` prevents data extraction via ADB

---

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
- **Sync mode selection** — for Exchange accounts choose Push or Scheduled
- **Night mode battery saving** — from 23:00 to 7:00 sync every 60 minutes
- **Adaptive heartbeat** — automatic Ping interval increase from 5 to 15 minutes on stable connection
- **Smart network stop** — PushService pauses when no internet
- **Sync debounce** — protection from duplicate sync requests
- **Smart sync logic** — automatic disable of periodic sync for Push accounts

### Bug Fixes
- **Fixed vibration** — haptic feedback when selecting emails works correctly
- **Fixed search highlight** — found letters highlighting works on all devices
- **Fixed scroll on "Select all"** — automatic scroll to top of list
- **Fixed notifications** — fixed IDs, no spam
- **Fixed default language** — now Russian for new installations

---

## v1.0.5c (25.12.2025)

### Notification Fixes
- Fixed notifications on lock screen — added `VISIBILITY_PUBLIC`
- Notification channels now recreated on app launch to apply new settings
- Added `CATEGORY_EMAIL` for proper system handling
- New emails channel now has `lockscreenVisibility = PUBLIC`

### Email Move Fixes
- Fixed restore from trash bug — email now returns to the folder it was deleted from
- `originalFolderId` correctly updates when moving between folders

### Navigation Fixes
- Fixed bug opening email from notification after swiping away app
- Added check for email existence before opening
- If email not found — opens Inbox with "Unread" filter

### Compatibility Improvements
- Improved TLS compatibility on different devices — removed deprecated SSLv3
- Added TLSv1.3 support
- Improved SSL/TLS error handling — fallback to OkHttp defaults
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
- SettingsRepository is now singleton — eliminated multiple instance creation
- EasClient cached per account — resource savings on repeated requests
- Fixed memory leak in SyncAlarmReceiver — local coroutine scope with cancel() in finally
- Removed sync duplication when Direct Push not supported by server
- Removed all Log.d() calls for production build — reduced APK size
- Optimized ProGuard rules for R8 minification

### UI Improvements
- Improved "Move to" dialog logic
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
- System folder names now localized (Inbox/Sent, etc.)
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
- Fixed instant folder counter updates on main screen

### Bug Fixes
- Fixed email list not updating after sync
- Fixed folder counters showing incorrect values
- Improved sync reliability
