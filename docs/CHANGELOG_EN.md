# Changelog

The changelog is kept in two languages: the English version is this file, the Russian one is [CHANGELOG_RU.md](CHANGELOG_RU.md), an entry is added to both files in the same commit.
Version: MAJOR.MINOR.PATCH plus a pre-release letter (a — alpha, b — beta, rc — release candidate).

## v1.7.0 (unreleased)

### Mail and threads
- An interrupted full Inbox sync resumes reconciliation from the saved page, including after a restart. In a 987-message test mailbox, renumbering 14 items no longer leaves 1,001 local rows after reconciliation completes. Incomplete pages do not authorize removal, and distinct messages are not merged just because their subjects match.
- Folder list preparation runs off the main thread. The initial Room read is distinguished from an empty folder. Onboarding page content is visible immediately instead of waiting for staggered sections. The background-work prompt centers its explanation and checks Android’s actual exemption state on return from Settings.
- HTML mail checks every `srcset` image candidate, even if the first image is embedded. After consent, network-path URLs such as `//cdn.example/...` use HTTPS, plain HTTP stays blocked. Missing CID images are fetched separately using the message owner’s mailbox, with a 256 KB per-image and 512 KB total budget. A large adjacent PDF is not downloaded to display a logo, substitution leaves the stored message body unchanged.
- “Threads only” hides single messages in mail lists and search. An exact reply-header link can attach a message without a `ConversationId` to one unambiguous server conversation. An entry from the other folder may be visible as context in Unread, selecting the thread header or Select all does not add that context to delete or move actions.
- Mail lists and search share a tonal “To start / To end of list” button that uses displayed rows, including expanded threads. It hides during selection and when the list fits, its touch target is 48 dp.
- On EAS 14+ messages in a thread are ordered by `ConversationIndex`: the original first, then the replies. On Exchange 2007, which has no such field, the order is by date, newest first.
- Without a ConversationId (EAS 12.x) a reply joins by subject only if it has a reply prefix with a colon and exactly one original message has that subject. SV, VS, Enc, Od, YNT, Doorst, RES and REF are also recognized, and single-letter R: and I: are not treated as replies.
- A Message-ID link wins over the subject, and all ids from In-Reply-To and References are used. In-Reply-To comes from MIME when the body is loaded, on Exchange 2007 from EWS GetItem (`item:InReplyTo`), and is stored in `emails.inReplyTo`.
- An Inbox thread shows replies from Sent and vice versa, drafts are not included. The peer folder is read only when conversations are on, and search groups results the same way.
- A collapsed thread shows a summary of the latest message. An expanded thread shows a slim "Collapse conversation" bar and every message, newest first, and in selection mode a tap on the bar selects the thread.
- In Sent and Drafts the row and avatar show the recipient from To, with an empty To the first one from Cc, then from Bcc. Search shows the same recipients instead of the sender.
- Multi-select works by dragging across the list, and selected messages are processed in batches. In scrollable folders (Inbox, Sent, Drafts) the list's own scrolling took the gesture after the long press and only one message got selected: now after a long press the finger drives the selection, while before it scrolling works as usual. A sync during the drag no longer clears the selection or ends the gesture: selection keeps to the last prepared list, while move and delete still check the current one. A drag can start on the avatar, and new mail above the start row does not shift the selection. Auto-scroll stops at the edge of the list instead of running every frame for nothing. Re-emitting the same messages and writing the folder SyncKey no longer rebuild the list.
- Fixed a crash when opening a screen from the widget or at startup ("Reading a state that was created after the snapshot was taken"): sync status is exposed through a `StateFlow` instead of Compose state of an object that was first created from a background thread during a frame.
- The "Starred" filter is called "Favorites", like the "Favorites" folder. "Important senders" are removed: the star on messages means only Favorites, quiet hours silence every notification, and the stored list and the "Important senders" system channel are deleted on update.
- With fast taps on the star only the last one goes to the server, and on a rejection the star returns to the last server-confirmed value, also after a restart. A sync response requested before the tap does not overwrite it, and a server rejection is shown as a toast.
- Move and delete to trash send MoveItems at once, and a folder Sync runs only on Status=1 (stale ServerId on EAS 12.1). Rows disappear from the list at once and come back if the server refused or moved nothing, after a ServerId change messages are matched by the snapshot contents, so a move does not hit another message.
- Permanent deletion processes folder changes and rechecks the messages against the confirmation snapshot. If a full sync can have reassigned ServerIds, especially on Exchange 2007, select the messages again. EWS deletion by subject is no longer a fallback, and a partial response removes only acknowledged items. Move to Trash from Search no longer permanently deletes messages. Deleting from the message screen closes it once, after the toast.
- If the server answers Status 6 when the body is loaded, the message is not deleted or hidden. The screen syncs the folder once and closes only if the message is really gone from the server (Exchange 2007 answers this way during a full resync).
- The EAS body is requested as HTML or text without the full MIME, and Sent on Exchange 2007 skips the preliminary MIME header load, so messages with large attachments open without endless loading. The body is not reloaded on rotation.
- For an empty body in Sent on Exchange 2007 the EWS request also searches by `item:DateTimeSent`, compares the subject case-insensitively and allows up to 10 minutes of clock difference. If the server returns no body after a refresh, a toast says so.
- HTML is extracted from MIME by splitting parts on the boundary (RFC 2046), so a message is no longer cut at the first `--` in Outlook conditional comments or in `-----Original Message-----`.
- In the message viewer the gesture owner is decided once after the system touch slop (`TouchDirectionLock`): horizontal scrolling and zoom belong to the WebView, vertical scrolling to the screen. The WebView height counts `documentElement`, and there is no edge effect.
- The HTML sanitizer runs in linear time, so a poison message does not hang the screen. It strips `<iframe>`, `<object>`, `<embed>`, `<applet>`, `<meta http-equiv="refresh">`, `data:text/html` and `javascript:` in `xlink:href`, and links in the body are found in the background with a bounded regular expression.
- HTML mail opens links only on a user tap: `http`/`https` in an external web-link handler, `tel:` in the dialer, and `mailto:` in the composer. Automatic navigation and frame navigation are ignored. Forms and input fields are removed from the HTML.

### Drafts and sending
- An empty successful SendMail response still confirms delivery to Exchange. A nonempty HTML page from a gateway does not. An empty response to an incremental folder read retains the current SyncKey.
- Cancelling scheduled mail retains its files until draft recovery succeeds. A damaged job cannot be sent with an empty body or missing attachment. New jobs record the attachment count and expected file lengths.
- A short text part in a large base64-wrapped MIME message can be read without materializing an adjacent large attachment. Preview and file downloads use the message owner’s account rather than the currently active account. Cancelling an EWS download interrupts the network call while waiting for headers as well as while reading content.
- If Exchange confirms draft creation without returning an item ID, the client keeps the local body and attachments. Sync neither removes this copy based on its subject or a nearby timestamp nor uploads it again. Editing it does not delete a server draft identified only by its subject. Both copies can remain visible in this case.
- `mailto:` links from a message or another app populate To, Cc, Bcc, subject and body, `+` in an address stays a plus. Attachments received via Android Share must use external `content://` URIs, file names are sanitized before copying.
- The compose screen saves drafts through `DraftSaver`. An edit of only text, subject, recipients or importance goes through EWS UpdateItem, and the draft keeps its id and server attachments, while any other change creates a new version and then deletes the previous ones by id.
- Offline, a draft edit is saved on the device (`local_draft_d_`) with the list of server versions it replaces. Sync first uploads it and only then deletes the old versions, and if the server refuses the new version, the old one stays. A setting turns on a mode in which drafts are saved only on the device.
- Drafts sync without `TruncationSize`, and a truncated or plain-text EAS body is not used as the draft body. Opening a server draft refreshes the body by exact id, and an empty draft is not saved while the editor prepares HTML.
- Closing a draft without edits no longer asks "Save changes?". The body used to be compared as a string with the HTML the editor returns re-serialized, so an Exchange body (`<html>`/`<head>` wrappers, unquoted attributes, CRLF, `&#39;`) "changed" just by opening it (verified in Chromium 157). Body edits are now marked by the editor itself (typing, paste, formatting, image, signature change), and the mark survives rotation. A load interrupted by rotation starts over instead of leaving fields half filled. A calendar event description is likewise not sent re-encoded when it was not edited.
- Bcc is stored locally. The app reads an unknown Bcc of an Outlook draft (EAS before 16.0 does not send it) over EWS from the previous version, and if that fails the previous version is not deleted, emptied To, Cc and Bcc are cleared with `DeleteItemField`, and importance is kept.
- Attachments the editor did not load are downloaded at save time. If a download fails while the server is reachable, the save fails instead of losing the attachment, and offline the attachment is carried by reference.
- An attachment counts as inline if it has `isInline` or a Content-ID, and the rule is the same in sync and reconcile, so a picture is not duplicated in the body and the file list. Pictures of a reopened draft are mapped back to their `cid:`, and text edits go through UpdateItem again.
- A draft update by subject requires a unique match. Deletion does not look up the subject: it uses a known EWS ItemId directly, and the EAS path requires a confirmed selection in Drafts. An older server version may remain if all that is known about it is a historical EAS ID. A draft whose attachments were all removed is recreated, and after sending the client tries to remove the original server draft by ID.
- Device drafts are uploaded with attachments, Bcc and importance, and a double tap on Save starts one save. Scheduled send keeps the body in a file because WorkManager `Data` is limited to 10 KB.
- Send and draft save run in `RetainScopeViewModel`: rotation does not interrupt them, they do not run at the same time, Back does not close the screen while they run, and the Send button does not get stuck after an error.
- The total size of files and inline pictures (10 MB limit) is checked before the bytes are read into memory, on send and on draft save. Addresses are validated before sending, and sending is allowed if any of To, Cc or Bcc is filled.
- A long subject is encoded per RFC 2047 as several encoded-words. Addresses and Message-ID are stripped of CR/LF on every message build path, including read receipts. A stable `ClientId` is used to deduplicate retries on EAS 14.x. On EAS 12.x the command sends MIME without a `ClientId`, so a stable `Message-ID` alone does not guarantee server-side deduplication.
- Editor text over 10 000 characters is saved to a file (`LargeStateSaver`), so minimizing a draft with pictures does not cause `TransactionTooLargeException`, and the body survives process death. Typed text, a removed signature and a loaded reply or forward are not reset on rotation.
- When the WebView renderer dies, the editor is recreated once, a second crash shows a message, and the text can still be sent and saved. Large HTML is processed off the main thread, out-of-memory on a huge body does not crash the app, and the Content-Security-Policy and sanitizer strip `on*` and `javascript:`, also on paste.
- The reply and forward quote is labeled in the UI language ("From", "Date", "Subject", "To"). A signature with `$` and `\` is inserted intact, and mailto keeps the signature below the passed text. Recipient suggestions search groups, contacts, address history and the GAL after a typing pause.

### Attachments
- Attachments are streamed to a file instead of memory: ItemOperations Fetch with `MS-ASAcceptMultiPart: T`, on EAS 12.x GetAttachment as POST, then EWS GetAttachment with streamed base64 decoding. Open, save, share and drag work with the file, 30 MB was verified over EWS, and Exchange 2007 serves attachments over EAS up to its limit (Status 11 above it).
- The EAS streaming response is read and closed in the same coroutine context, so leaving the screen during a download leaves no open connection. Cancelling stops the download and deletes the temporary `.part` files.
- A response cut inside EWS `Content` is an error, not a shorter file. An empty attachment confirmed by EWS is saved as an empty file instead of a "not found" error.
- Save and Save as… are available for every attachment, and the file choice survives rotation. On Android 10+ Save writes to Downloads through MediaStore, on Android 8–9 it opens the system dialog, and if the write fails the empty document in the chosen folder is deleted.
- The fallback that saved the whole message instead of the attachment on error is removed. Base64 in MIME attachments uses CRLF line breaks (RFC 2045).
- Share copies files in the background, up to 10 MB, and a toast reports skipped ones. Temporary reply, forward and draft attachment files are deleted by age, and files that drafts point to are kept and stored in `filesDir`.

### Calendar
- A meeting with attendees on Exchange 2010/2013 is created and changed through EWS with `SendToAllAndSaveCopy`, so attendees get invitations and updates (over EAS 14.x the server does not send them). The item is found by its previous subject and start, and a series is addressed by its master item.
- When editing a meeting the attendee field is filled in, and attendee names and responses are sent back. An attendee's edit mails nobody and keeps `MeetingStatus`, which is read by bit flags, and the "Invitation sent" toast appears only if the organizer added attendees.
- Recurring exceptions on Exchange 2007 are supplemented through EWS: FindItem is requested without `item:Body` and `calendar:OriginalStart`, which come from GetItem, and an incomplete response changes nothing. The supplement does not overwrite an occurrence edit made during sync, and an edit of one occurrence is merged into the fresh database row inside a transaction (PIM-28).
- Events support attachments, recurrence and a local trash. Attachments of recurring events are stored as metadata without copies of the bytes, on Exchange 2007 they are loaded by the series master ItemId, and the preview cache is cleaned by age.
- Daily and weekly series with COUNT are computed correctly, also in Monday-first locales. Month and year navigation works, and the time field does not crash on "9999" and keeps the cursor at the end.
- An event is deleted locally only after the server confirms, and an attendee deleting a meeting first sends a decline (`MeetingResponse` or EWS `DeclineItem`). Delete and restore run under the same lock as sync, so deleted events do not come back.
- A time with an offset instead of UTC is parsed with `Instant.parse` without a shift. The date and time in the new event dialog are not reset on rotation.

### Tasks, notes, contacts
- In GAL CSV exports, values that look like spreadsheet formulas are marked as text, ordinary phone numbers are preserved. In vCard exports, line breaks in fields are escaped rather than starting a new entry.
- Tasks and notes can be created, edited and moved to trash offline: the change is saved on the device, queued in `pending_pim_changes`, and a toast says it will be sent when the connection is back. It is sent the same way as online (EAS on Exchange 2010+, EWS on 2007), and assigning a task offline is an error.
- Several edits of one item go out as one result, an edit made during the send is not lost, and sync does not overwrite a queued edit. Restoring from trash before the send happens only on the device, and permanent delete, empty trash and reset send the queue first.
- An incomplete server response (HTTP error, dropped connection, truncated WBXML or SOAP, page limit) does not delete local tasks, notes and contacts. Contact pages are parsed whole, and a failure before the first contact is returned as an error.
- Restore and permanent delete of a task from trash on Exchange 2010/2013 find its copy through EWS, and a refused `DeleteItem` is returned as an error. "Done" from a task reminder is retried up to 10 times on a server error, and EWS note categories are read.
- The Calendar, Tasks and Notes menus have "Reset and resync": the account's local copy is erased and loaded from the server again, and the server is not touched.
- When composing, a contact group can be picked, and adding an address that is already listed shows a warning.

### Sync and offline
- Star, read state and moves made offline apply on the device at once, are queued in `pending_email_changes` and sent by `PendingMailChangesWorker` when the network is back, also after a restart. They are rolled back only on an explicit server rejection, and a stale SyncKey, server errors and timeouts are retried later.
- After a SyncKey reset Exchange 2007 numbers the messages of a folder again, and a new message shifts the numbers of the others (verified on the server). Because of this a message could open with another message's body, a message with an attachment could close with "Email deleted on server", and the star could be rejected. Fixed as follows:
  - a full resync runs only when the sync state is broken (Status 3/12), not on "sync already running" or a server error,
  - the star and read state wait for a sync when there is no key and do not reset the key themselves,
  - Status 8 on a flag is no longer treated as a rejection,
  - a row whose number the server gave to another message is rewritten with that message, and the body moves to its own message,
  - a body is written only if the subject and date in the server's answer match the message, otherwise the folder gets a full resync,
  - the message screen whose row disappeared or changed opens the same message under its new number and shows "deleted on server" only if the message is gone.
- A full resync (SyncKey=0) does not delete a message with a pending move and does not duplicate it in Inbox. The move is sent from the original folder, and if the message is already in the target folder the queue is cleared.
- `FolderSyncGate` lets only one Sync request per folder run at a time, and the SyncKey is re-read from the database inside the gate. There is no separate full sync of Sent: the regular sync fixes a stale key, and the key is not reset while offline.
- Opening Inbox, Sent and user folders runs a quiet incremental sync, at most once every 30 seconds per folder and without a refresh indicator. Trash is not synced automatically, and Drafts are synced once.
- Push with no push accounts does not restart in a loop, and the restart worker does not retry forever. On Android 15 the start after boot goes through WorkManager, and the watchdog is registered dynamically and works on Android 8+.
- When the app returns to the foreground, push runs a catch-up sync. A per-account lock (`Mutex.tryLock`) rules out two concurrent syncs of one account and a full resync caused by their race.
- A sync timeout switches to the backup server only if the current one does not answer over TCP, and does not cause a full resync. An intranet-only network counts as connected, the reachability probe fits DNS into its timeout and uses the system HTTP proxy, and `host:port` in the server address wins over the account port (IPv6 in square brackets).
- Folder auto-cleanup is off by default. The Trash and Spam candidate snapshots store the folder's EAS sync epoch, so a key reset that can reassign ServerIds restarts the waiting period. Moving the clock backwards also restarts it. Local draft age is still checked only when candidates are selected.
- Reminders use an exact alarm, and without permission a while-idle alarm or a plain one. The reminder worker retries and stops after 5 failures.

### Exchange 2007–2013 compatibility (EAS and EWS)
- On Exchange 2007 SP1 all tasks and calendar events load, and folder sync is fixed. The EAS `DeviceId` and EWS dates are formatted with `Locale.US`. In languages with non-Latin digits (Persian, Arabic, Bengali and others) requests no longer break, and the server does not see a new device when the phone language changes.
- NTLM for EWS reads the 401 response to the end and runs the handshake on a separate client with `ConnectionPool(1)`, so parallel requests do not take over the connection being authenticated and there are no random 401s. An EWS SOAP Fault is returned as an error, not an empty body.
- `DeviceInformation` in Provision is sent only on EAS 14.1, and on 12.x and 14.0 device information goes through the Settings command. The retry after HTTP 449 sends a correctly built request body.
- A cleared flag is sent as an empty `<Flag/>` ([MS-ASEMAIL]), a flag without dates is retried with today's dates after Status 6, and the new SyncKey is saved even when the Change is rejected. The WBXML encoder no longer carries the namespace of an empty element over to the following elements.
- On Ping Status 5 the `HeartbeatInterval` proposed by the server is accepted within 120–1800 seconds. Exchange accounts work only over HTTPS, and the broken HTTP option is removed.

### Server trust and TLS
- If the system does not trust the certificate, adding an account shows a dialog: issued to and by, validity, names, SHA-256 and SHA-1, and the system validation verdict. The certificate is fetched by a handshake without credentials and is shown even if the server requires a client certificate.
- "Trust" pins the server's public key, after which only that key is accepted, without name and expiry checks. If the system already trusts the certificate (CA and host name), the dialog offers "use system check" instead of pinning.
- Account settings without a certificate file offer "Trust the certificate from the server", but not for accounts that pass system validation. The backup server has its own pinned key, and a changed certificate is written to the slot of the server where the change was seen.
- An SSL setup error in pinned or imported-certificate mode fails the connection instead of falling back to system trust without a host name check.
- An imported certificate is accepted when the leaf key matches and it is within validity, otherwise the chain is checked by PKIX with it as the anchor, then by the system store. A chain that merely contains the imported certificate is no longer accepted.
- `Rfc6125HostnameVerifier` checks host names through public APIs. For DNS names it checks the CN only if the SAN contains no DNS names. An IP address requires a matching `iPAddress` in the SAN. Wildcards are allowed only in the leftmost label. An Exchange 2007 self-signed certificate without a SAN can still match a DNS name through its CN.
- IMAP and POP3 without SSL require STARTTLS/STLS and check the host name, pinning works there too, and `socketFactory.fallback=false` keeps JavaMail from bypassing it. TLS 1.0 and 3DES are allowed last in the list for Exchange 2007 on ports 993/995, and errors are read along the whole cause chain.

### App lock and privacy
- If the app lock is enabled but its stored password hash is lost, recovery asks for the mail account password. The check is local if the account password is still stored, otherwise server verification is available only for Exchange. A successful check turns off the lock, set a new app password in Settings.
- The app can be locked with a password and fingerprint. The password is stored as PBKDF2-HMAC-SHA256 with 600 000 iterations and a random salt in `EncryptedSharedPreferences`, the app locks when it goes to the background and on cold start, and typed passwords are not put into the screen's saved state.
- After 5 wrong passwords there are pauses of 30 s, 1 min, 5 min and 15 min counted on `elapsedRealtime`, so changing the clock does not shorten them. The attempt is counted before the check, the lock screen shows a countdown and attempts left, and a successful unlock resets the counter.
- With the lock on, Android 13+ keeps no window snapshot in Recents (`setRecentsScreenshotEnabled`), while screenshots inside the unlocked app still work. Android 8–12 uses FLAG_SECURE during that time.
- Notifications for mail, reminders, sending and connection problems use VISIBILITY_PRIVATE with a contentless version. With the app lock on, the shade shows no content either, and notifications already shown are re-posted without content when the lock is turned on.
- Turning on App Lock hides senders, subjects and previews, as well as event and task titles, even in a running widget session. It does not wait for a Room reload, and late results do not restore hidden details. Counts and the event time can remain visible.
- If Keystore is unavailable, passwords are stored obfuscated with PBKDF2 (10 000 iterations), and the app reports this with a toast. Security has a fail-closed switch: in this mode passwords are not written to obfuscation, and a new mailbox without a stored password is not created.
- `data_extraction_rules.xml` excludes all private storage from device-to-device transfer on Android 12+, including client certificates, attachments and the outbox queue.

### Notifications and widget
- When there is room for avatars, the widget marks the active account with a white ring. Tapping another avatar switches accounts, and tapping the active one opens its mail. Mail, unread count, events, tasks and notes in a new snapshot belong to the active account. Each visible avatar has its own count.
- With app lock enabled, the mail row can show the active mailbox name and unread Inbox count when there is unread mail and room for the row. It contains no subjects, senders or previews. A tap opens the unread Inbox after unlocking. When the count is zero, the widget shows “No new mail” if there is room.
- While the app is open, no new mail notifications are shown, and the message appears in the list at once. These messages do not pop up as notifications after the app goes to the background either.
- A tap on an event, task or flag reminder switches to the right mailbox and opens that item, even if another account was on screen.
- Permission requests are shown one at a time after onboarding (notifications, background work, exact alarms) in the common dialog style, each with its own icon.
- After a push sync the app requests a widget update. Lightweight queries select recent unread Inbox messages without loading their bodies. Calls to `updateAll()` from the app are serialized.
- The Glance layout handles 12 sizes and the system font scale. Two accounts remain selectable at `180×140 dp`, and widening to `250×140 dp` no longer removes search in favor of larger text. Avatar initials use contrasting text colors. Today's messages and sync show the time, while older ones show the date.
- Widget text on the theme color is solid white. Before, it was white at 50–80% opacity with a contrast of 1.8–2.8:1.

### UI and settings
- Settings search opens the specific account or control: automatic update interval, start and end of quiet hours, trusted image senders, refusal of insecure writes, or the theme and scrollbar colours for a selected weekday. Conditional rows appear only when available. Add account opens the form, searching for the update interval does not run a network check. The interval uses the shared choice sheet and child rows use colour accents.
- HyperOS-style settings: the section title on the theme gradient sits in one line with the Back arrow right away, without the large block that took its final look only after scrolling, white cards without dividers sit on a light gray page (dark gray on black in dark theme), and the value is on the right with an arrow. About ten radio button dialogs are replaced by a shared `SettingsChoiceRow`, auto-cleanup is one row per folder, and settings search finds scrollbar color, animations, password change and account settings.
- Every value choice in settings (color theme, theme mode, language, font size, mail, account and sync options) opens a bottom sheet with a sample, name and radio button, like the scrollbar color. A choice inside a bottom sheet (Daily themes) opens a list at the row instead of a second sheet.
- The header of settings and their sections, including account settings and Updates, is one 56 dp bar: the back arrow and the title in one line, with no empty band and no large header.
- Settings cards are larger and almost full width. Section icons are new (Material Symbols Rounded) in filled tiles of their own scrollbar-palette color, row icons inside sections and on the Updates screen are in the same filled tiles, smaller, with a white glyph. The colors do not depend on the theme or wallpaper.
- The bottom of settings explains that signature, drafts, sync, auto-cleanup and certificates are set per account. Section descriptions list what is inside, and the row and the screen are both called Personalization (they were Appearance and Interface personalization). Tapping an account in Accounts and in the navigation drawer opens its mail on the main screen, account settings are behind the gear on the right (in the drawer a tap used to open the settings). Contacts, calendar, notes and tasks can sync "With mail sync", on every mail sync (the global address list at most once a day). Onboarding Organizer icon colors match the app sections.
- Contact tabs are called Local (on the device), Personal (the Exchange Contacts folder) and Global (the organization address book, GAL), the same in the recipient picker. The count is on a second line and names are never cut: on a narrow screen and in the dialog with the fourth Groups tab the tabs share the width by name length, and they scroll only with a very large font. The whole tab is the touch target, at least 48 dp high.
- A message sender can be saved to local contacts: "Save sender to contacts" in the message menu and in the folder selection menu (all senders of the selected messages at once). The address is also taken from the Exchange 2007 form `"Name" <address>`, existing contacts and your own address are not duplicated, and a double tap does not create two contacts. Sent, Drafts and Outbox have no such item: the sender there is you.
- Settings search finds only rows that are on screen now: Change password and fingerprint while the app lock is on, Configure daily themes while daily themes are on. It also finds each account's settings (credentials, signature, draft mode, sync and cleanup, certificates, delete, notification sound) according to the account type and opens the account's screen at that row, searching by an account address shows its settings.
- About is removed from the navigation drawer: it is in settings.
- In the Background work and Exact notifications dialogs, Later is on the left and Continue on the right.
- Without dynamic color, containers and surfaces follow the chosen theme instead of the M3 purple scheme. The "Server impersonation protection" card uses theme colors and localized strings, with no emoji.
- The theme mode (light, dark or system) is chosen in Personalization and during onboarding, and system bar icons follow the app theme.
- The yellow theme is darker (`#A86400`/`#8F4F00`), so white text on bars and buttons reaches a contrast of at least 4.5:1. This text threshold is checked in every theme.
- List selection and the star give a short `VibrationEffect` tick, a long press a stronger pulse, and auto-scroll during selection ticks too. Vibration is marked as touch feedback on every version (`USAGE_TOUCH` on API 33+, `USAGE_ASSISTANCE_SONIFICATION` below), so the system "touch vibration" of HyperOS, One UI and ColorOS scales and mutes it. On Android 8–9 the pulse is 20/35 ms (an ERM motor does not spin up for less), a vibrator without a system tick gets a click, and ticks come at most once per 50 ms. Mail notifications have a new "Haptic feedback" switch.
- The list scrollbar computes its position in pixels: in settings sections made of one or two tall blocks (Personalization, Sync and cleanup, Mail, Security) it now appears and no longer takes half the screen.
- Contacts get the same selection haptics as mail: a tick on toggle and a stronger pulse on long press. A long press followed by drag-select gives one vibration, not two, and starting an attachment drag vibrates too.
- System Back in selection mode clears the selection in mail, search, tasks, notes and contacts. Mail settings have two rows, "Swipe right" and "Swipe left", with the current action, and the filter panel has a "Reset all" button.
- Onboarding slides animate in when they become current or the swipe target, and the neighboring slide is not blank during a swipe. UI elements are updated, with custom file type icons and extra scrollbars.
- The star and the attachment icon have TalkBack labels, and small buttons are enlarged to 48 dp. About 50 repository and EAS error messages, compose screen errors and the calendar empty-trash dialog are shown in the UI language.

### Stability and performance
- The mail list, message viewer, search, tasks, notes, folders and sync settings use screen ViewModels. The composer has no separate `ComposeViewModel`: the screen restores its fields, while `RetainScopeViewModel` keeps sending or saving in progress across rotation. The update available dialog survives rotation too.
- Screen operations, background work, push, alarms and receivers run in guarded scopes: a database or I/O error is logged and does not crash the process.
- If the system WebView is unavailable, the viewer shows plain text, the editor offers Retry, and printing is unavailable. If a running renderer fails, the viewer recreates the WebView once before falling back to text, printing does not retry automatically.
- When memory is short, a heavy message opens without inline pictures, and inline pictures are not built from oversized messages.
- Body processing, invitation detection and iCal parsing are cached and not repeated on every recomposition, and an invisible animation in the message list no longer redraws every frame. Screens collect flows with lifecycle awareness.
- Star, permanent delete, move, the widget and the reconcile after a full resync read light projections without message bodies, and widget queries got indexes.
- Update checks close unsuccessful OkHttp responses. EAS transport and Ping network calls handle coroutine cancellation. The EAS client cache is thread-safe, and the race when switching to the backup server is fixed.
- Duplicate cleanup groups messages by Message-ID and does not delete different messages with the same subject, sender and time.
- The push service on Android 12+ does not crash with `ForegroundServiceStartNotAllowedException`. A firmware failure to open app settings during an update rollback does not crash the process.
- Theme settings are not overwritten by the echo of their own DataStore write, and the saved theme mode shows right after a restart without `runBlocking` on the main thread. DataStore 1.1.1 fixes the "Unable to rename" write error, and every write in a chained `putString` of the obfuscated store is obfuscated. `LargeStateSaver` reads and deletes only its own files in `cacheDir`, and files older than 24 hours are deleted at start.

### Docs and tests
- The license changed from MIT to GNU GPL v3.0, and releases published before the change remain under MIT.
- The security policy with the threat model (`docs/SECURITY_RU.md`, `docs/SECURITY_EN.md`, index `docs/SECURITY.md`) and the privacy policy are kept in Russian and English, and About opens the policy in the UI language.
- The changelog and the architecture description are kept as separate Russian and English files, outdated documents are removed, and `docs/XMLPULLPARSER_MIGRATION_PLAN.md` describes the move of parsers to `EasXmlParser`. The README states the published version 1.6.1, EAS versions 12.0–14.1 and the recommended OS, Android 13+ (minimum 8.0).
- GitHub Actions runs the debug tests, generates the Kover report, checks Android Lint and builds the R8 release. The [local tests](../app/src/test/README.md) include Robolectric, Compose UI and an HTTP-level fake Exchange. Live Exchange, ServerId renumbering and lock-recovery checks are [opt-in](../CONTRIBUTING.md#live-tests-against-a-real-exchange-server).
- CI keeps the release build `mapping.txt` (artifact `release-mapping`, 90 days) to retrace crash stack traces. CI jobs have time limits: a hung test stops the run after 25 minutes instead of holding it for up to 6 hours, and thread dumps go into the report. Database migration tests cover 51 → 55 on frozen schemas of each version.

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
