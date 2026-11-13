# Changelog

The changelog is kept in two languages: the English version is this file, the Russian one is [CHANGELOG_RU.md](CHANGELOG_RU.md); an entry is added to both files in the same commit.
Version: MAJOR.MINOR.PATCH plus a pre-release letter (a — alpha, b — beta, rc — release candidate).

## v1.6.3b

### Star (flag) turned itself off, or could not be removed after fast taps (2026-11-12)
- **Wrong state after fast taps**: the rollback after a server rejection restored the snapshot taken before the tap. With several taps in flight, that snapshot was the optimistic value of the previous tap, so the star ended up in a random state. `FlagWriteTracker` now sends only the latest tap, lets only it settle the DB, and rolls back to the last server-confirmed flag
- **Parallel Sync requests with one SyncKey**: the flag Sync Change, `markAsRead` and the folder sync loop sent requests with the same SyncKey in parallel (Status 3, then a full resync). `FolderSyncGate` allows one Sync request per folder at a time; the key is re-read from the DB inside the gate
- **Stale sync response**: a sync response requested before the tap no longer overwrites the new star
- **Protocol**: a cleared flag is an empty `<Flag/>` ([MS-ASEMAIL] Flag); a dateless flag rejected with Status 6 is retried once with today's dates; the new SyncKey is saved even when the Change is rejected. The WBXML encoder no longer applies the namespace of an empty element to the elements after it
- **Leaving the screen and rejections**: the server write is not cancelled by leaving the screen. A rejection shows a toast instead of the star silently going off; Search keeps its stars in line with the DB

### Crash when opening drafts (2026-11-12)
- **`TransactionTooLargeException` on minimise**: the editor HTML was stored twice in the saved-state Bundle (up to 500 000 characters each). For a draft with pictures this exceeded the ~1 MB Binder limit, so the app crashed with `TransactionTooLargeException` when it was minimised. Text over 10 000 characters is now saved to a file per screen instance (`LargeStateSaver`). Files older than 24 h are deleted at start; before, all of them were deleted, so a large body was lost after process death
- **WebView renderer death**: when the WebView renderer died, the whole app was terminated. `onRenderProcessGone` now recreates the editor once; a second crash shows a message instead of the editor, and the text is kept and can still be sent or saved
- **ANR and out-of-memory**: sanitizing and chunking large HTML, and the heavy regex work on the draft body, run off the main thread (ANR). An out-of-memory or stack-overflow error on a huge body no longer crashes the app

### Message viewer, offline star and read state, no lost server changes on delete (2026-11-12)
- **Jerky horizontal scrolling in the message viewer**: the WebView re-decided on every move who owns the gesture, by the offset from the touch point. Panning left and right in one gesture passed through zero on X, vertical drift won, the screen intercepted the gesture and the WebView got `ACTION_CANCEL`. The owner is now decided once per gesture after the system touch slop (`TouchDirectionLock`): horizontal drag and pinch (second finger) belong to the WebView, vertical drag to the screen. The WebView has no edge stretch any more (`OVER_SCROLL_NEVER`), and its height also counts `documentElement` (collapsed margins of the last block), so it is never a few pixels shorter than the page and does not scroll inside while panning
- **Viewer and print no longer kill the app when the WebView renderer dies** (`onRenderProcessGone`): the WebView is recreated; after a second crash on the same message its plain text is shown (selectable). One shared implementation for the viewer, the editor and printing (`WebViewRenderer`, `RendererRecovery`); the viewer WebView is released in `onRelease` (no leak when it is recreated)
- **Star and read state made without a connection are no longer lost**: they stay on the device, go to a queue (`pending_email_changes`, DB v50) and are sent by `PendingMailChangesWorker` when the network is back (also after a restart); sync does not overwrite them meanwhile. Only an explicit rejection of the change by the server rolls it back; an expired SyncKey (Status 3), a server error (5), HTTP errors and timeouts are retried instead. An email read offline no longer becomes unread again, and the "no connection" toast for it is gone
- **Permanent delete no longer drops server changes**: before EAS Delete the folder was "refreshed" by up to 10 Syncs whose data was thrown away while the advanced SyncKey was saved — mail moved into Deleted Items from Outlook and deletions there were lost until a full resync. Now the folder is synced by the regular sync (changes processed), then the batch delete runs under the folder gate with the current key, which is saved afterwards. On Exchange 2007 a failed batch delete no longer resets the server sync state (`Sync` with key `0`, which forced a full resync next time). Deleting a Drafts item over EAS saves the new key and runs under the folder gate
- **Moving emails could move the wrong one**: content matching after a ServerId change (EAS 12.1) paired IDs and snapshots by index; one already deleted email shifted the pairs. It now matches by the snapshot itself
- **"Done" from a task reminder notification**: a server error was swallowed and the task stayed open silently; it is now retried (up to 10 attempts with backoff)
- **Faster star tap**: the email is read by a projection without the body (multi-megabyte for messages with pictures)
- **Permanent delete and move read light projections**: `EmailServerRef` (`EmailDao.getServerRefs`, in chunks of 500) instead of full emails with bodies. If permanent delete loses the connection, emails are no longer retried one by one over EWS: no answer would come anyway
- **Star rollback after a restart**: `FlagWriteTracker` knows that the value in the DB is not yet confirmed by the server (the email is in the queue), so a rejection rolls back to the last server value, not to the unconfirmed one
- **Empty draft is not saved**: while the editor prepares HTML asynchronously (`isPreparingHtml`), `awaitCurrentHtml` returns the last known HTML instead of an empty string
- **Documentation**: the root `CHANGELOG.md` was moved here and into `CHANGELOG_RU.md` and removed; `ARCHITECTURE` is split into `ARCHITECTURE.md` (RU) and `ARCHITECTURE_EN.md`; `COMPOSESCREEN_AUDIT.md`, `COMPOSESCREEN_MVVM_PLAN.md`, `CALENDAR_TASKS_BUGFIX.md` and `EXCHANGE_DELETE_OPTIMIZATION.md` were removed
- **Not done**: there is no offline queue for tasks, notes, calendar, contacts, folders and moves (server-first); editing and deleting Exchange contacts is local-only; `syncSentFull` and `VerificationScreen` call `Sync(0)` outside the folder gate; the horizontal-scroll fix and the WebView recovery are not verified on a device
- **Tests**: 1602 (new: `FlagWriteTrackerTest`, `FolderSyncGateTest`, `OfflineMailChangesTest` on a real Room DB — star/read sent, rejected, offline queue, fast taps, sync does not overwrite, permanent delete keeps new mail; `PendingEmailChangeDaoTest`, migration 49→50, `WebViewGestureAndRecoveryTest`, `LargeStateSaverTest`, editor async HTML and saved state)

### Drafts: shared saving, offline edits, no lost attachments or Bcc (2026-10-26)
- **Commit `90e32e9`**: one `DraftSaver` serves the compose screen and `ComposeViewModel`
- **Update or create**: text, subject, recipients or importance only → EWS UpdateItem: the draft keeps its id, server attachments are untouched, pictures already on the server are sent as their `cid:`. Anything else → the new version is created first, the previous ones are deleted by id afterwards
- **Offline edits**: offline, the edit is saved on the device (`local_draft_d_`) and lists the server versions it replaces (DB v46 `emails.replacesServerId`). Sync uploads it and only then deletes them; meanwhile they are hidden from sync. A server that answers but refuses the new version keeps the old one
- **Attachments**: attachments the editor could not load are downloaded at save time and carried over. If one cannot be downloaded while the server is reachable, the save fails instead of dropping it; offline it is carried by reference, without download attempts
- **What counts as "already in the body"**: an attachment counts as "already in the body" only when its bytes equal a `data:` picture of the body. Files with a Content-ID (synced as inline) are no longer hidden in the editor, reply/forward or the device-draft upload. Reopened drafts map embedded pictures back to their cids, so text edits are UpdateItem again
- **Bcc of Outlook drafts**: unknown Bcc of Outlook drafts (EAS before 16.0 does not send it) is read from the previous version over EWS (EAS ServerId → subject via ItemOperations → unique FindItem → GetItem). If it cannot be read, the previous version is kept rather than deleted
- **Drafts without an id**: drafts the server confirmed without an id are not edited in place; an EWS create without an id while attachments are pending is an error. A save after the record moved (uploaded, migrated to the EAS id) edits the new record instead of creating a duplicate
- **Deletes and bodies**: EWS-id drafts are deleted over EWS on every Exchange version (Basic, then NTLM); non-strict deletes still clean up locally. Truncated (`Truncated`, including server policy) or plain-text EAS bodies are not used as the draft body; a Bcc changed later in Outlook is refreshed
- **Cleanup and device drafts**: draft attachment files that records point to are kept by the 7-day cleanup. A device draft too large for a DB row is stored with `cid:` and image files; pictures removed from a device draft no longer return as attachments
- **Locking and reachability**: per-account draft lock; sync skips upload and deletes after a connection-level failure. The reachability probe resolves DNS within its timeout and uses the system HTTP proxy when one is set
- **Tests**: `DraftSaverTest`, `DraftUploadSyncTest` (Room), `DraftBodiesTest`, `DraftIdsTest`, `DraftSuccessorsTest`, `DraftAttachmentRoleTest`, `InlineAttachmentsTest`, migration 45→46, `EasDraftsServerTest`, `AttachmentLoaderTest`, `AttachmentManagerCleanupTest`. Full suite: 1183 tests, 0 failures

### Drafts: full bodies, Bcc and importance kept, no edits into a wrong draft (2026-10-26)
- **Commit `54ed79f`**
- **Full bodies**: drafts sync without `TruncationSize`; a body reported `Truncated` or too large for a DB row is not stored as the draft body. Opening a server draft refreshes its body by exact id, so the old 10 KB cut can no longer overwrite the full draft
- **Bcc and importance**: UpdateItem clears emptied To/Cc/Bcc with `DeleteItemField`; Bcc is stored locally (DB v45, `null` = unknown) and importance is saved on create, update and MIME create
- **Failures and lookups**: a server failure is no longer reported as saved. Subject lookups act only on a unique match, so update and delete no longer reach another draft with the same or an empty subject
- **Device drafts**: device drafts upload with attachments, Bcc and importance; a double tap on Save starts one save; scheduled send keeps the body in a file (WorkManager `Data` is limited to 10 KB)

### Dual-URL: per-server certificate pins and port in the address (2026-10-26)
- **Commit `f469578`**
- **Certificate pins**: the backup server has its own certificate pin (DB v44); accepting a changed certificate writes to the slot of the server where the change was seen. Certificate warnings update the settings card at once
- **Port in the address**: `host:port` in the server address wins over the account port for probes, requests and labels; IPv6 in brackets
- **Timeouts**: a sync timeout switches servers only when the current one does not answer TCP; no full resync on timeout. Intranet-only networks count as connected

### Imported server certificate is no longer trusted inside a foreign chain (2026-10-26)
- **Commit `aa0075a`**: a chain that merely contained the imported certificate was accepted without signature checks, so it could be appended after an attacker's leaf (MITM while pinning was off). Now: leaf key match plus validity, otherwise PKIX with the imported certificate as the anchor, then the system store. Missing trust managers no longer fail open

### "Reset and resync" for Calendar, Tasks and Notes (2026-10-25)
- **New menu item**: an item in the overflow menu (⋮) of each screen erases the account's local copy and pulls it from the server again. It is meant for "stuck" local records that the regular sync did not remove (incomplete snapshots, the date window, safety heuristics). The server is not touched: this cleans the local cache and does not delete anything on Exchange; a dialog asks for confirmation
- **Technical detail**: the reset also clears the EAS client cache (`clearEasClientCache`), otherwise the notes probe with a cached SyncKey would leave the database empty
- **Tests**: `TaskRepositorySyncTest`/`NoteRepositorySyncTest` — the reset removes "stuck" records even on an INCOMPLETE snapshot and rebuilds from the server response

### Locale-independent EAS DeviceId and EWS dates (2026-10-25)
- **Cause**: `String.format` was called without a `Locale`, so on a device whose default locale uses non-ASCII digits (Persian, several Arabic locales, Bengali, Nepali, Marathi…) the `%d` conversion emitted eastern digits through `DecimalFormatSymbols` and corrupted protocol strings (empirically reproduced on `fa-IR`/`ar-EG`/`bn-BD`/`ne-NP`/`mr-IN`)
- **EAS `DeviceId`** (`androidc%010d`) is carried in the EAS request URI ([MS-ASHTTP] 2.2.1.1.1.4); non-ASCII digits made a malformed query and, worse, a device identity that changed with the phone language — the server then saw a new device (re-provisioning, device-count limits). The generator was also duplicated verbatim in `EasClient` and `AttachmentManager`, which must agree (sync and attachment fetch share one device identity); it is now a single `Locale.US` implementation in `EasClient` reused by both (DRY)
- **EWS dates**: the `FindItem` calendar-exception window (`CalendarExceptionService`) and the recurrence `<StartDate>` (`CalendarDateUtils.localDateFromIso`, used by `CalendarRecurrenceBuilder` for `CreateItem`/`UpdateItem`) were built the same way; non-ASCII digits yield an invalid `xs:date`/`xs:dateTime` that Exchange rejects. All protocol formatters now pass `Locale.US`; `%x` fingerprint/hex output is unaffected (not localized)
- **Tests**: `EasDeviceIdLocaleTest` (DeviceId is pure ASCII and identical across fa-IR/ar-EG/ar-SA/bn-BD/ne-NP/mr-IN) and a `CalendarDateUtilsTest` case for `localDateFromIso` (TZ pinned to UTC to stay machine-independent); both were confirmed to fail against the un-fixed formatter by one-at-a-time mutation, then restored. Full unit suite 943 tests green, lint clean

### Meeting invitations on Exchange 2010/2013 (2026-10-25)
- **Problem**: a meeting with attendees created or edited on Exchange 2010/2013 (EAS 14.x) never notified anyone: per MS-ASCMD, in protocol versions 2.5–14.1 the server does not send invitations/updates on an EAS `Sync` (the client must), and the client didn't. Such meetings now go through EWS `CreateItem`/`UpdateItem` with `SendMeetingInvitationsOrCancellations` — the same EWS path already used for attachments and for Exchange 2007
- **Creating a meeting**: a meeting with attendees → EWS `CreateItem` `SendToAllAndSaveCopy`. A plain appointment (no attendees) still goes through EAS
- **Editing a meeting**: an organizer edit that must notify (meeting changed, or an attendee added) → EWS `UpdateItem` `SendToAllAndSaveCopy`; a private-field edit (reminder/busy status), an attendee's own edit, and an appointment stay on EAS and mail nobody. Routing keyed on `meeting.sendUpdates` (SOC: the repository decides *whether* to notify, the protocol layer decides *how*)
- **Locating the item**: the EWS item is resolved by the **old** subject and start (a moved meeting kept on the server at its old time would otherwise be missed), and a recurring series is addressed by its master item (not an expanded occurrence). On 14.x a notifying edit does not silently fall back to EAS, which would drop the invitations again
- **Tests**: `EasCalendarUpdateMeetingTest` (13, HTTP-level FakeExchange) covers the EAS-vs-EWS routing for both Exchange 2010/2013 and 2007

### Sync completeness and protocol audit (2026-10-24)
- **Principle**: every sync path that deletes local data now acts only on a complete server snapshot (Exchange 2007 SP1 … 2013, EAS 12.1/14.x and EWS)
- **Contacts (EAS)**: a collection `Status` other than 1 (besides 3/12) and an empty page with `MoreAvailable` were taken for the end of the collection, so `ContactRepository` deleted contacts the server had not sent yet — on a first-page error, all Exchange contacts. Pages are parsed atomically; any early exit makes the snapshot non-authoritative; a failure before the first contact is an error. Completeness is carried by value (`ContactsSyncResult`), not by a volatile flag
- **Tasks and notes (EAS + EWS)**: an incomplete snapshot (HTTP error, dropped connection, truncated WBXML/SOAP, page limit) no longer deletes local items; the EAS task sync no longer wipes the local task trash it never reads
- **Task trash on Exchange 2010/2013**: restore and permanent delete addressed the trashed copy by its old Tasks-folder ServerId — restore never worked, and a permanent delete sent `Sync Delete` for a ServerId that can belong to another task after a full resync. The copy is now located via EWS (the single `IPM.Task` with that subject in `deleteditems`). A refused EWS `DeleteItem` is an error instead of `Success(false)`, which the repository took for success
- **Meeting edits**: the attendee field is prefilled; attendees' names and responses are sent back (an EAS 14 `Change` replaces the whole `Attendees` collection); an attendee's edit keeps the server `MeetingStatus` (received bit) and mails nobody; the organizer's EWS update goes out only when the meeting itself changed or attendees were added; the «Invitation sent» toast appears on an edit only when the organizer added attendees; `MeetingStatus` is read by bit flags (values 9–15)
- **EWS note categories** are parsed; the task `OwnerId` field (MS-ASTASK has no Owner) was removed
- **Tests**: a fake Exchange at the HTTP level (`FakeExchange`, real WBXML codec, Basic → NTLM) for EAS 12.1/14.1 and EWS 2007

### No device-to-device transfer of app data (2026-10-24)
- **`data_extraction_rules.xml`**: on Android 12+ `allowBackup="false"` does not stop D2D transfers; `data_extraction_rules.xml` now excludes all private storage. Before, only shared preferences and databases were excluded, so files such as imported client certificates, downloaded attachments and the outbox queue could be transferred. `docs/SECURITY.md` describes the threat model

### License: MIT → GNU GPL v3.0 (2026-10-24)
- **License change**: MIT → GNU GPL v3.0. Releases published before the switch remain available under MIT

### Best-practice audit of `00e05a4` (audit L-5 hostname verification) and `Rfc6125HostnameVerifier` (2026-08-27)
- **Scope**: a meticulous audit of the previous audit-fix commit found and fixed three defects in it (this entry also documents what `00e05a4` itself changed: hostname verification restored for the imported-server-certificate mode, which previously accepted any certificate for any host — an opt-in MITM path)
- **🔴 Internal-API dependency removed**: `00e05a4` had wired the verifier to `okhttp3.internal.tls.OkHostnameVerifier` — an OkHttp internal class with no stability contract. Replaced by a new `Rfc6125HostnameVerifier` built exclusively on public JCA APIs (`X509Certificate`, `HostnameVerifier`, `X500Principal`, `InetAddress`)
- **🔴 Hidden compatibility regression caught by behavioral tests**: the first real run of the verifier against certificates proved that OkHttp's builder-default verifier (the same internal class) performs **no CN fallback** and rejects certificates without a SAN — exactly the shape of the self-signed certificate Exchange 2007 SP1/SP2 installs by default. `Rfc6125HostnameVerifier` implements the full RFC 6125 algorithm: SAN takes precedence; CN fallback only when no SAN entry matches (permitted by RFC 6125 §6.4.4); wildcards only in the leftmost label; IP literals match `iPAddress` SAN entries only. Compatibility preserved; a MITM with a valid certificate for a different hostname is still rejected
- **⚠️ `HttpsURLConnection.getDefaultHostnameVerifier()` deliberately NOT used**: empirically demonstrated that on a JVM it returns a deny-all stub until the https URL handler is loaded — which never happens in an OkHttp-only app; adopting it would have broken every TLS connection. Documented in `docs/AUDIT.md` so the trap is not stepped into again
- **Cache-key fix**: the `getCertificateClient` cache key now includes `accountId`/`serverUrl` (both are consumed by `CertificateChangeDetector`) — previously a certificate-change dialog could be attributed to the wrong account
- **DRY**: the RFC 2253 DN parsing previously duplicated between `extractCertificateInfo` and the new verifier consolidated into one implementation owned by `Rfc6125HostnameVerifier`
- **Tests**: `HttpClientProviderL5Test` rewritten from implementation-name assertions to behavioral ones — 12 tests against 6 real keytool-generated X.509 certificates (checked into `app/src/test/resources/certs/`): CN match/mismatch, SAN match, wildcard, IP SAN, no-CN-no-SAN fail-closed, mode priorities, cache-key isolation, plus a regression guard that main sources reference no `okhttp3.internal.*` class. Full suite: 674 tests, 0 failures, 0 errors. `docs/AUDIT.md` updated: L-5 resolved, M-3 documented as not-a-defect (per-instance NTLM lock, protocol necessity)

### App-lock hardening audit and automated biometric UI tests (2026-08-26)
- **Scope**: a meticulous self-audit of `4835432` found and fixed 7 real defects in the goal-4 feature, then added the missing automated biometric UI test layer
- **🔴 Cold-start gate race fixed (fail-closed)**: `SettingsRepository.getAppLockEnabledSync()` returned `false` while the DataStore cache was empty (~100 ms after cold start) — a user WITH a lock password would see the main screen flash for a moment before the gate engaged. The sync getter is now fail-closed (`true` when unknown), and the gate is additionally a pure tested function `AppLockManager.shouldShowLockScreen(locked, settingEnabled)` (SOC: no inline boolean algebra in `MainActivity`, no soft-lock without a stored hash). The reverse-flag getter `getAppLockBiometricEnabledSync()` stays fail-OPEN (`false`) — an extra biometric *denial* is safe, an extra biometric *grant* is not; both directions documented with rationale
- **🔴 Biometric prompt crash-proofed**: `showBiometricPrompt()` previously called `BiometricPrompt.authenticate()` unconditionally — it throws `IllegalStateException` on finishing/destroyed Activities (re-entrant auth) and could prompt despite the flag being switched off in Settings mid-frame. New pure gate `AppLockManager.canRequestBiometricUnlock(locked, flag, deviceAvailable)` re-checks all three conditions from the fresh DataStore value; the call is additionally wrapped against framework exceptions so a lock-screen tap never crashes the app
- **🟠 Secrets kept out of saved state**: `LockScreen` and `LockPasswordDialog` stored typed passwords in `rememberSaveable` — meaning a process-death snapshot would serialize the plaintext password into the instance-state bundle. All password fields moved to plain `remember` (KISS + security: re-typing after a rare process death is the correct trade)
- **🟡 UX**: the «disable lock» dialog was titled with the generic section name; it now has its own explicit title `appLockDisable`. Settings switches start from a safe `initialValue` instead of the fail-closed sync getter, so there is no visible ON flicker during the DataStore cold read
- **🟡 Dead-code removal (YAGNI)**: 3 unused `Strings.appLockBiometricPrompt*` wrappers deleted — `MainActivity` reads the prompt strings directly from `NotificationStrings` (single source of truth)
- **🧪 Automated biometric UI tests (Robolectric + Compose, no emulator)**: new `LockScreenUiTest` (10 tests): renders title/description/field/button, Unlock disabled while password empty, fingerprint button visibility table (disabled-but-available / enabled-but-unavailable / both → shown), fingerprint tap requests the system prompt WITHOUT unlocking the session, wrong password → error shown + session stays locked, typing clears the error, correct password unlocks the session, simulated BiometricPrompt success unlocks without typing. New gate truth tables in `AppLockManagerTest` (`shouldShowLockScreen` ×4, `canRequestBiometricUnlock` ×8). Tests run the real Compose runtime (`NATIVE` graphics) with the lock hash planted at 1 000 iterations (iterations read from the stored value — no 600k-PBKDF2 burn per test)
- **Infra (verified against official sources)**: Robolectric 4.11.1 → **4.12.2** — Compose tests on a Windows host REQUIRE the native runtime, and Windows x86_64 support shipped only in 4.12 (robolectric#8312, «robolectric-nativeruntime.dll not found»); added `ui-test-junit4` + `ui-test-manifest` (via the existing Compose BOM) to `testImplementation`. `SettingsRepositoryAppLockTest` cold-cache assertion updated to the new fail-closed contract (flag default remains false once read)
- **Verification**: full `--rerun-tasks` compile 0 warnings / 0 errors, all unit tests green, `assembleDebug` + `assembleRelease`. No protocol changes — Exchange 2007 SP1/SP2 compatibility untouched

### Goals 2–6 revision: app lock (password + fingerprint), error localization, widget freshness, draft dedup tests (2026-08-26)
- **Scope**: full audit of release goals 2–6; goals 3 (dark/light theme + onboarding) and 5 (server drafts core) verified already solid in `58bd285`; the rest built/hardened here
- **Goal 4 — App lock with password + fingerprint (was entirely absent)**: new `data/security/AppLockManager` — OWASP Password Storage Cheat Sheet compliant (verified 2026): PBKDF2-HMAC-SHA256, 600 000 iterations, 128-bit random salt per password, constant-time comparison (`MessageDigest.isEqual`), versioned hash format `v1:iter:b64salt:b64hash`, fail-closed parsing. Secret lives in `EncryptedSharedPreferences` via new shared `EncryptedStoreProvider` factory (DRY: `PasswordStorage` pattern reused, no duplicated backend-selection logic); settings flags live in DataStore (SOC: secrets never touch DataStore). `LockScreen` replaces the whole navigation while locked (back handler intercepted, `FLAG_SECURE` blocks screenshots/recents during lock); `MainActivity` inherits `FragmentActivity` — mandatory for stable `androidx.biometric:1.1.0` (verified in library sources), Compose code unchanged. System `BiometricPrompt` with localized title/subtitle/negative button; biometric availability probed via `BiometricManager`. Fail-closed on cold start; process-wide re-lock on background via existing `AppForegroundTracker` subscription (~700 ms ON_STOP delay makes rotation/config-change false-locks impossible — same property verified for notifications). Soft-lock protection: gate requires the password hash to actually exist. Settings: «Security» section with lock switch, change password, fingerprint toggle (disabled with explanation when hardware lacks biometrics); single `LockPasswordDialog` component serves set/change/disable modes (DRY); all PBKDF2 work on `Dispatchers.IO` (no main-thread jank). Permissions: `USE_BIOMETRIC` + legacy `USE_FINGERPRINT` (minSdk 26)
- **Goal 2 — full localization of errors**: ~50 hardcoded Russian repository/EAS error strings now translate to English through new `localizeError` branches (account/folder/calendar/tasks/contacts/notes-not-found, sync errors with details preserved, EAS download policy message, 449 provisioning, system-folder guards, protocol-specific messages); earlier in this pass: `widget_description`/`widget_no_subject` added to `values-ru`, `genericErrorText()` single source of truth for non-composable error fallbacks (5 raw `"Error"` call sites fixed)
- **Goal 6 — widget freshness**: `PushService` now refreshes the widget after every account sync (unread badges, recent mail, sync label) — previously stale until the next periodic update. Coverage confirmed at all 14 update points (SyncWorker, InitialSyncController, BootReceiver, EmailOperationsService, PersonalizationScreen, OnboardingScreen, PushService)
- **Goal 5 — server drafts hardening**: draft-dedup heuristic extracted into pure testable `EmailSyncService.isDraftDuplicate(subject, date, serverDrafts)` + `DRAFT_DEDUP_WINDOW_MS` (120 s window: same subject AND date proximity — guards against duplicates when `createDraftMime` succeeded server-side but ItemId parsing failed)
- **Tests (all real, all green)**: `AppLockManagerTest` (14 tests: PBKDF2 determinism/salt separation, hash round-trip, fail-closed parsing on 6 malformed shapes, full set/check/change/remove cycle, session state, persistence across storage reload, random-salt uniqueness), `SettingsRepositoryAppLockTest` (5 tests: defaults, persistence, flag independence, sync-cache, disable — isolated DataStore per official test pattern), `LocalizationErrorMapTest` (7 tests: EN translation table, RU pass-through, detail preservation, code errors, fallback), 6 new `isDraftDuplicate` tests (window boundary, negative diff, multi-match, empty list)
- **Verification**: full `--rerun-tasks` compile 0 warnings / 0 errors, all unit tests green, `assembleDebug` + `assembleRelease`

### Hardening audit of foreground-aware push (2026-08-25)
- **Scope**: a meticulous self-audit of `3c4e641` found and fixed 3 defects before they shipped
- **Race condition in `PushService.syncAccount`** (real bug, introduced by adding the second concurrent caller): the per-account debounce checks at entry but writes the timestamp at EXIT — a check-then-act window. The new foreground-resume sync races the ping loop on the same account; the loser's folder gets «Синхронизация уже выполняется» → misclassified as failure → **full resync with sync-key reset during an active sync = the exact Status=3 data-loss path the InitialSyncController guard was built to prevent** (server-id instability on Exchange 2007 SP1/SP2). Fix: per-account `Mutex` with `tryLock()` — loser returns immediately, never queues, never blocks (documented semantics: «Tries to lock, returning false if already locked»); mutex held for the whole account sync including the HWM/checkAndNotify tail, so two concurrent syncs of ONE account are now impossible; debounce keeps the cross-account anti-stampede role
- **SOC cleanup**: `NotificationHelper` decision rebuilt as a single decision point `AppForegroundTracker.shouldShowNewMailNotification(notificationsEnabled, appInForeground)` with REAL inputs (removed the misleading hardcoded `true` argument and the pre-gating that duplicated the policy)
- **Documentation**: KDoc referenced a renamed function (`shouldSuppressNewMailNotification` → `shouldShowNewMailNotification`)
- **Best-practice verification (internet docs)**: `Mutex.tryLock` non-suspending semantics confirmed from kotlinx.coroutines docs; `ProcessLifecycleOwner` ON_STOP ~700 ms delay (config-change safety) confirmed from developer.android.com — the tracker cannot false-fire on Activity rotation
- **Verification**: `--rerun-tasks` compile 0 warnings / 0 errors, 616 tests green, `assembleDebug` + `assembleRelease`

### Foreground-aware mail notifications and instant inbox delivery (2026-08-25)
- **Goal**: release goal "instant message delivery while the client is open" (no FCM — on-prem Exchange 2007 does not send it)
- **`AppForegroundTracker`**: new, built on the official `ProcessLifecycleOwner` + `DefaultLifecycleObserver` pattern (androidx `lifecycle-process`): a single source of truth about app visibility, a thread-safe `StateFlow`, idempotent initialization from `Application.onCreate`
- **Notification suppression while the client is open** (Gmail/Outlook practice): a new email already appears instantly in the list through the reactive Room Flow — a duplicate in the notification shade is noise. The HWM still advances and emails are marked as shown, so when the app returns to the background no "catch-up" notification about them pops up
- **`PushService`**: an immediate catch-up incremental sync when the app leaves the background — insurance against a service killed by the system and against network switching. The load stays under the existing protections (30-second per-account debounce, `InitialSyncController` guard, 600 s budget). Exchange 2007 SP1/SP2 compatibility unchanged: the same incremental Sync by synchronization keys
- **6 new tests**: 4 — the pure decision logic `shouldShowNewMailNotification` (full truth table), 2 — integration of suppression in `checkAndNotifyNewMail` (foreground suppresses + advances the HWM + marks as shown; background notifies as before)
- **Verification**: 616 unit tests green, full `--rerun-tasks` recompile — 0 warnings / 0 errors

### Round 4 compiler-warning cleanup (2026-08-25)
- **Result**: all 101 pre-existing Kotlin warnings eliminated per DRY/KISS/SOLID/SOC/YAGNI; full `--rerun-tasks` rebuild now reports **0 warnings / 0 errors**
- **Signature-replacement hardening**: the 4 duplicated `Regex.escapeReplacement(...)` call sites collapsed into a single tested helper `replaceSignatureHtml()` in `ComposeTextUtils` (DRY); 7 new regression tests cover `$`, `\`, `$1`/`$99` group-reference patterns and html-mode signatures
- **NTLM/EWS cascade completed (YAGNI/KISS)**: removed ~200 dead `ewsUrl`/`authHeader` references across 12 files — `EasTransport`/`EwsClient` own the URL via constructor; the 5 service `Deps` interfaces, 4 calendar sub-services and `EasClient` no longer thread dead parameters or bridge them with `{ _, _, _ -> }` adapters. `tryBasicAuthEws` is now private in `EasTransport` (single DRY entry `executeEwsWithAuth`)
- **Sync-loop guard extracted for testability**: `EmailSyncService.shouldContinueSync(sameKeyCount, emptyDataCount)` companion helper (DRY single source of truth) guards both loop-termination paths; 5 new regression tests cover same-syncKey ×5 and empty-data ×5 forced-stop boundaries. `PackageInfo.versionCode` → `PackageInfoCompat.getLongVersionCode()`. **Verification**: 610 unit tests green, `assembleDebug` + `assembleRelease` clean
- **Deprecated/unsafe API fixes**: deprecated `AlertDialog` → `BasicAlertDialog`, deprecated `Locale("ru")` → `Locale.Builder`, unnecessary `!!`/safe-calls/elvis removed, name shadowing and duplicate labels resolved, `@OptIn(ExperimentalCoroutinesApi)` added for Ping resume

### Round 3 audit fixes (2026-08-24)
- **Scope**: top-practice verification (DRY/KISS/SOLID/SOC/YAGNI)
- **Crash fix**: `Regex.replace(HTML_SIGNATURE_REGEX, newSignatureHtml)` without `Regex.escapeReplacement` — `$` and `\` in the replacement string are special chars (Kotlin docs). A signature containing `$` would crash or corrupt. Fixed in 4 places (ComposeScreen ×2, ComposeViewModel ×2)
- **ANR risk removed**: `getThemeModeSync()` used `runBlocking` on the main thread for a cold DataStore read. Now reads the cache only (consistent with 24 other Sync getters)
- **DRY**: removed 3 duplicate local functions (`normalizeRecipients`, `extractQueryPart`, `replaceLastRecipient`) from `ComposeScreen`; imports from the shared `ComposeTextUtils`
- **DRY**: removed the `isRussian()` duplicate in `ComposeViewModel`; it now uses the shared `isRussianLanguage()` from `Localization`
- **Test coverage**: 8 new tests for 7 previously uncovered public VM methods (`setImportance`, `setRequestReadReceipt`, `setRequestDeliveryReceipt`, `removeAttachment`, `addGroupsFromPicker`, `dismissDiscardDialog`, `saveDraftAndExit`)
- **Verification**: 598 tests / 0 failures / 0 errors / 41 suites, `assembleDebug` — 5 APK

### Theme mode selection: light / dark / follow system (2026-08-13)
- **`SettingsRepository.ThemeMode`**: new enum (SYSTEM / LIGHT / DARK) with localized display names (RU + EN)
- **Persistence**: via DataStore key `theme_mode`, thread-safe `AtomicReference` cache + Flow
- **`MainActivity`**: resolves dark theme from mode + system state; status/navigation bar icon contrast follows the app theme (official `enableEdgeToEdge` + `SystemBarStyle` pattern)
- **"Personalization" screen**: theme mode row + selection dialog (radio list with icons)
- **Onboarding**: mode choice cards on the theme selection page (first launch)
- **DRY**: single icon mapping helper shared by dialog and list row
- **Tests**: 12 new tests: `ThemeModeTest` (JVM, parsing/localization/roundtrip) + `SettingsRepositoryThemeModeTest` (Robolectric, real DataStore persistence, sync cache, app-restart survival)

### ComposeScreen MVVM migration — Stage 3: business logic moved to `ComposeViewModel` (CS-16) (2026-08-13)
- **Recipients**: validation & normalization live in the VM (`isValidRecipientList` + shared `ComposeTextUtils.normalizeRecipients` — DRY, single source for old screen and VM); group tokens `[GroupName]` preserved
- **Autocomplete**: real search in the VM: groups → local contacts → email history (200 ms debounce, CS-7) + GAL (extra 500 ms network debounce); dedup across sources, own address excluded
- **`sendEmail` in the VM**: attachment budget checked BEFORE reading bytes (CS-1/CS-2), recipient validation events, scheduled send via `scheduleEmail`, correct `PendingEmail` with group-token expansion, `applicationContext` only (no Activity leak, CS-5)
- **`saveDraft` in the VM**: same budget gate, inline `data:`URL → `cid:` + ContentId conversion (Outlook/Word compatibility, Exchange 2007 SP1), delete+create for drafts with attachments (EWS UpdateItem cannot remove attachments), anti-resurrection guard + record verification
- **Duplicate recipients**: handling moved to VM state (`PendingDuplicateAddition` + confirm/dismiss)
- **Back press**: delta-detection on back press (discard dialog) and active-account sync on exit
- **DAO access**: only through repositories (new wrappers: `searchEmailHistory`, `getContactsByGroupList`, `incrementUseCountByEmail`, `getSignaturesForAccount`, `insertDraftRecord`, `updateEmailBody`)
- **Tests**: 31 new tests in `ComposeViewModelTest` (validation, dedup, debounce, budget, send, draft, delta-detect, crash-resistance) + 12 pure-function tests in `ComposeTextUtilsTest`
- **Self-audit hardening (parity re-verification against the reference `ComposeScreen`)**: reply/forward/draft loading re-audited and fixed — localized quote labels & headers (RU/EN), `toField = null` in reply quotes, recipient normalization in reply/draft, attachment client created from the *email/draft owner account* (multi-account), draft lazy body load (`loadEmailBody`) + `refreshAttachmentMetadata` + `cid:` resolution with EAS/EWS routing (`fetchInlineImages` / `fetchInlineImagesEws` for long EWS ItemIds, Exchange 2007 SP1/SP2) and persistence of the resolved body, draft attachments routed through `downloadDraftAttachment` (EAS ItemOperations vs EWS GetItem), downloaded files stored in `filesDir/<source>_attachments` (survives cache wipe, covered by `file_paths.xml`), share-intent attachments cleared only after consumption and no longer override reply/forward/draft mode, mailto applies with subject/body only. +18 tests in `ComposeViewModelTest`, +1 in `AttachmentLoaderTest`
- **Self-audit round 2 (second pass, incl. auditing round-1 fixes)**: full error localization — all 11 hardcoded English `ComposeEvent.Error` strings replaced with RU/EN helpers via `getLanguageSync()` (release requirement #2 "полноценная локализация всех ошибок"); attachment-load errors show a clean localized message to the user, raw details go to the log (SOC). Fixed mailto losing the account signature (`applyMailtoIntent` rebuilt body on top of the signature — now signature preserved below the shared body, as in the reference). Fixed send validation accepting recipients only in `to` — now any of to/cc/bcc gates sending (reference parity). Moved `createEasClient` off the Main dispatcher in all three mode loaders (DB + keystore reads were on the UI thread — FPS). Removed dead `ComposeEvent.PlaySendSound` (sound already plays in `SendProgressBar` via `SoundPlayer` — YAGNI). +10 tests in `ComposeViewModelTest` (RU/EN error localization, mailto+signature, cc-only send, no-recipients block, localized attachment error); 5 compiler warnings removed

### Settings cache, DataStore, security singletons and test infrastructure (2026-08-13)
- **Settings cache race eliminated (theme / color theme / day themes)**: setter writes now guard their cache keys: the background DataStore collector no longer overwrites a value the setter just wrote (async echo of own writes). Single-writer invariant per official DataStore constraint — no new locks, no perf impact. `getThemeModeSync()` cold-cache fallback: deterministic one-shot DataStore read instead of returning the default, so first launch after restart shows the saved mode immediately
- **DataStore 1.0.0 → 1.1.1**: official fix for the "Unable to rename" write failure on Windows (`File.renameTo` cannot replace an existing file; 1.1.1 uses atomic move)
- **`ObfuscatedEditor.putString` returned the delegate instead of `this`**: chained `putString().putString()` writes stored the second secret UNOBFUSCATED. Now every chained write is obfuscated (SharedPreferences.Editor contract restored)
- **Test isolation for security singletons**: `PasswordStorage` / `SecurityTelemetry` gained `@VisibleForTesting resetForTesting()` — fail-closed state and stale Application state no longer leak across test classes
- **`AttachmentLoader` URI resolution now injectable (DIP)**: prod default stays `FileProvider`; tests use a deterministic resolver, so attachment loading is testable without a registered manifest provider
- **`ComposeViewModel` nav args typed as `String` (was `Long`)**
- **Email ID type handling**: real `EmailEntity.id` is a composite `accountId_serverId` string; `Long.toString()` could never match a real email, silently breaking reply/forward/draft prefill
- **`SendController` stuck `isSending` after send failure**: added an `onError` callback (invoked on client-creation failure and server errors); previously the compose UI stayed permanently blocked after a failed send
- **Build environment: Gradle daemon pinned to JDK 17** (`org.gradle.java.home` in user `gradle.properties`): Robolectric 4.11.1's bundled ASM does not support Java 23 class files (major version 67) — all 5 Robolectric suites failed with `NoClassDefFoundError: android/webkit/RoboCookieManager`. Baseline 517 tests are green again

### Password storage fallback (L-7) and date parsing (N-4): details, architecture, documentation, tests (2026-08-13)
- **L-7: enhanced password storage fallback mechanism**: the short description is in the "Stability & security (code audit)" section; the details are here
  - Migrated from inline `ObfuscatedSharedPreferences` to the dedicated `PasswordStorage` class
  - Upgraded obfuscation from simple SHA-256 to PBKDF2-HMAC-SHA256 (10,000 iterations) for defense-in-depth
  - Added `SecurityTelemetry` for tracking insecure storage usage and user notification
  - Implemented Toast notification on first fallback to insecure storage
  - Added optional fail-closed mode (disabled by default) via `SecurityTelemetry.setFailClosedMode()`
  - Exposed security status to UI: `AccountRepository.isUsingInsecurePasswordStorage()`
  - Follows OWASP Password Storage Cheat Sheet and Android Security Best Practices
  - Added localized security warning strings (English + Russian)
  - 39 comprehensive unit tests for `PasswordStorage`, `SecurityTelemetry`, and obfuscation
- **N-4: optimized `parseEwsDateTime` timezone handling**
  - Replaced `SimpleDateFormat` with `java.time.Instant.parse()` for ISO 8601 parsing
  - Simplified logic: removed regex fractional seconds stripping and conditional branching
  - Code reduced from 20 lines to 12 lines (40% reduction)
  - Improved compliance with DRY/KISS/SOLID/YAGNI/SOC principles
  - All existing tests pass without modification
- **Architecture**:
  - Extracted password storage logic into the `data/security` package
  - Improved separation of concerns: storage, telemetry, and business logic now independent
  - Reduced `AccountRepository` complexity by removing 58 lines of inline crypto code
  - Applied SOLID principles: Single Responsibility, Dependency Inversion
- **Documentation**:
  - Updated `docs/AUDIT.md` with L-7 completion details
  - Updated `README.md` password security section
- **Testing**:
  - Added `PasswordStorageTest.kt` (13 tests)
  - Added `SecurityTelemetryTest.kt` (11 tests)
  - Added `ObfuscatedStorageTest.kt` (15 tests)
  - All tests follow Robolectric + Truth best practices
  - Total: 39 new unit tests for security improvements

### Drafts audit — part 5: unified inline-attachment classification (2026-07-12)
- **Duplicated draft inline images** (D-5): an inline image could show both in the body (via MIME extraction) and as a file attachment in the list. Cause — inconsistent inline inference from ContentId: `reconcileAttachments` inferred it (`isInline || has ContentId`), but the initial sync didn't (strict `isInline`). On Exchange 2007 SP1 the server often doesn't set `IsInline` for draft inline images ([MS](https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/attachments-and-ews-in-exchange): inline attachments are identified by Content-ID, `HasAttachments` doesn't count them), so the attachment synced as `isInline=false` → landed in the file list while the MIME fallback re-inlined it into the body → a duplicate. Introduced a single source of truth `EasAttachment.effectiveIsInline` (`isInline || contentId non-blank`), applied at ALL `AttachmentEntity` creation sites from server data (initial sync ×3, reconcile new+existing); reconcile now also fixes `isInline` when the ContentId is unchanged (for drafts synced by an older version). Sync and reconcile now classify identically. Covered by `EasAttachmentTest`. The MIME inline-extraction path (MS "Option B") was already implemented earlier (`fetchInlineImages`/`fetchInlineImagesEws` + `extractInlineImagesFromMime`) — left untouched
- **Tests**: suite 443 (was 438)

### Server drafts & widget audit — part 4 (2026-07-12)
- **Orphaned draft after send** (D-1): when sending a server draft it was deleted only from the local DB — on the server it stayed orphaned (visible in Outlook/OWA) and "resurrected" on the next Drafts-folder sync. Now `SendProgressBar` calls `mailRepo.deleteDraft` (server EWS delete + local cleanup + `registerDeletedEmail` anti-resurrection) in the background, without delaying the success sound/navigation. DRY — reuses the same method as the re-save path
- **Removing all draft attachments** (D-3): if a draft had attachments and the user removed them all, the code took the `updateDraft` path (EWS `SetItemField`), which per [MS docs](https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/updateitem-operation) cannot modify attachments — the removed attachments stayed on the server draft. The delete+create condition now also covers `email.hasAttachments` (the original draft had attachments), with a correct `hasAttachments` on the new draft. On Exchange 2007 an empty set goes through `createDraftEws` → a valid attachment-free draft
- **Widget theme-color DRY** (W-2): `themeToColor()` hardcoded hex colors, duplicating `AppColorTheme` (Theme.kt) — a drift risk when the palette changes. Reduced to a single source: `AppColorTheme.fromCode(code).gradientStart.toArgb()` (exact round-trip, PURPLE default = the old else branch)
- **Widget formatter tests** (W-3): the "sync time" label logic was extracted into a pure `formatSyncLabel` (no `Context`) + 6 unit tests covering all branches and edges (no sync, <60s, exactly 60s, clock moved backwards, not-today → date, today → "в/at HH:MM"); `formatSyncAgo` delegates
- **Tests**: suite 438 (was 432)

### "Easy wins" audit — part 3: defects hidden under "dead code", cleanup, CI (2026-07-11)
- **Localized quote headers** (A-19): on reply/forward the quote block always showed English `From:/Date:/Subject:/To:` even in the Russian UI — `formatHtmlQuote` hardcoded the labels while the localized `quoteFrom/quoteDate/quoteSubject/quoteTo` were declared but never passed. Labels became parameters (English defaults) and are passed from both call sites. Surfaced while verifying "dead code" — deleting the vars would have hidden the bug. Covered by a RU/EN test + escaping
- **"Reset all" filters button** (A-20): `clearFilters()` existed in the ViewModel and was tested, but there was no UI button (unfinished feature) — added to the filter panel, visible when filters are active
- **Ping Status=5 no new loop**: confirmed the `MIN_HEARTBEAT` floor pre-exists — no regression
- **Dead code removed**: unused `contactRepo`/`focusManager`/`authErrorMsg` (ComposeScreen) + the orphaned `LocalFocusManager` import; 6 leftover toast strings in CalendarScreen (feedback goes through `DeletionController`); `dateTimeFormat` in `TaskEditDialog`. Verified line-by-line that autocomplete/feedback don't break
- **Idiom — redundant `!!`**: `att.contentId!!` ×6 (under the `!isNullOrBlank()` smart-cast guard) and `lastError!!` ×2 (local `var` right after assignment) removed; `existingUri!!` intentionally kept (no smart-cast there — `!!` is required)
- **Nullable `SimpleDateFormat` (DRY)**: `java.lang.ThreadLocal.get()` is a platform type; 12 `.get().format()` in TasksScreen reduced to two DRY helpers `taskFormatDate/taskFormatTime` (safe call + fallback in one place, project convention), parse got a targeted `?.`
- **Deprecated API**: `Divider` → `HorizontalDivider` (EmailDetailScreen); `AlertDialog` → `BasicAlertDialog` ×3 time pickers in TasksScreen (content already has its own `Surface` — no lost background, `ExperimentalMaterial3Api` opted-in globally)
- **CI**: `.github/workflows/ci.yml` (GitHub Actions, JDK 17 Temurin, `testDebugUnitTest` + report artifacts) + Kover 0.7.6 (`koverXmlReportDebug`; 0.7.x chosen deliberately — 0.8/0.9 have variant-resolution regressions on Android+Gradle 8.12, kotlinx-kover#728; compatibility verified locally)
- **Tests**: suite 432 (was 430) — new `formatHtmlQuote` tests (localized labels + escaping)

### "Easy wins" audit — stability, leaks, FPS (2026-07-10)
- **OkHttp connection leak** in update checks: `Response` was not closed on the HTTP-error path (`checkForUpdate`/`checkPreviousVersion`) — the connection was not returned to the pool on every failed auto-check (A-1)
- **Response close race on coroutine cancellation**: `if (isActive) resume(...)` replaced with the atomic `resume(response) { close() }` in the EAS transport and the long-poll Ping (the `AttachmentManager` pattern); removed now-dead imports (A-2)
- **Meeting invitations on EAS 14+**: the retry after Provision (HTTP 449) sent raw MIME bytes with a WBXML Content-Type — the server could not parse the body; the retry now reuses the correctly built request body (A-3)
- **ANR risk on "Share" with attachments**: cross-process `ContentResolver` queries in ComposeScreen moved off the main thread to `Dispatchers.IO` (A-4)
- **Email list FPS**: the envelope refresh indicator with three infinite animations recomposed every frame even while invisible (alpha=0) — now composed only when visible (A-5)
- **Freezes on large emails**: the body pipeline (HTML-from-MIME extraction → entity unescape → Exchange separator cleanup), meeting detection and iCal field parsing ran on every recomposition of the detail screen — extracted into pure `EmailUtils` functions and cached via `remember` (A-6)
- **Provision per MS-ASPROV**: `settings:DeviceInformation` inside Provision is sent only with EAS 14.1 (previously also on 14.0, where Exchange 2010 RTM could reply with a protocol error); on 12.x/14.0 device info is still sent via the Settings command as before (A-7)
- **Dead code**: removed unused `BASE64_DETECT_REGEX` (A-8)
- **Localization**: the calendar empty-trash dialog switched from hardcoded strings to existing `Localization` entries (`emptyCalendarTrashTitle`/`emptyCalendarTrashConfirm`/`trashEmptied`) (A-9)
- **Accessibility**: touch targets < 48dp (error banner close, attachment save menus in mail and calendar) raised to the Material/WCAG minimum; added missing `contentDescription` (A-10)
- **Rollback crash-safety**: the `startActivity` fallback in `requestUninstall` wrapped in try/catch — stripped-down OEM firmwares without the app-details screen can no longer crash the process (A-11)
- **Tests**: `EasProvisioningTest` (pins the 14.1 requirement for DeviceInformation, phase1/phase2, response parsing and validation) + `processEmailBodyForDisplay`/`detectMeetingEmail`/`parseIcalMeetingInfo` tests in `EmailUtilsTest`

### "Easy wins" audit — part 2 (2026-07-10)
- **HTML email truncation at the first `--`** (A-12): MIME part extraction stopped at the first `--` anywhere in the body — on Outlook conditional comments (`<!--[if mso]>`), `<style><!--` and `-----Original Message-----`, dropping the rest of the message. The main path was rewritten as a proper RFC 2046 split by boundary (content-based truncation is now impossible), with the regex fallback kept for single-part MIME; recursive descent into nested multipart. Covered by `MimeHtmlProcessorExtractHtmlTest` (9 tests incl. Outlook comments and the `-- ` signature delimiter)
- **EWS NTLM auth over the connection pool** (A-13): the 401 challenge body is now fully consumed (an unread body made OkHttp discard the connection, so Type3 went out on a new TCP → intermittent 401s, since NTLM authenticates the connection); the handshake was isolated into a dedicated client with `ConnectionPool(1)` so parallel EAS/EWS requests cannot steal the authenticating connection between Type1 and Type3
- **"Save as…" across rotation** (A-14): the attachment id for the system file picker moved to `rememberSaveable` — rotation/process death over the picker no longer silently cancels the save (the object is resolved from the attachment list by id)
- **Auto-update dialog across rotation** (A-15): `UpdateInfo` is persisted via a `listSaver` — the available-update dialog survives rotation instead of disappearing until the next interval check
- **Ping Status=5: server HeartbeatInterval** (A-16): per MS-ASCMD, when the interval is out of range the server returns the boundary allowed value — we now accept it (within our 120–1800 s bounds) immediately, instead of slow convergence by halving
- **Attachment base64 with CRLF** (A-17): base64 lines in MIME attachments are separated by CRLF (RFC 2045), not a bare LF (`Base64.DEFAULT`) — consistent with the email body
- **DRY empty-trash dialogs** (A-18): three identical dialog scaffolds (tasks/notes/calendar) replaced with a shared `EmptyTrashConfirmDialog` — the action specifics stay in each screen's callback
- **DRY MIME Content-Type detection**: the repeated `contains("Content-Type: x") || contains("Content-Type:x")` (6 sites) extracted into `String.hasContentType(prefix)`
- **Tests**: suite 430 (was 421) — new `MimeHtmlProcessorExtractHtmlTest`

### Deferred (future iterations, beyond the "easy wins" scope)
- **HTTP 451 (ActiveSync redirect, `X-MS-Location`)**: correct support needs mutating the server URL, invalidating the lazy `ewsUrl` and persisting to the DB — a half-correct version would leave `ewsUrl` out of sync. Partially covered by the manual dual-URL (`EasClient.probeAlternateUrl`)
- **markAsReadViaEws by subject** (EWS FindItem on `item:Subject` across 4 folders): two emails with the same subject can share a read status — narrow to `InternetMessageId`/date, or enable the fallback only on a confirmed EAS failure
- **Async email body pipeline**: move MIME/HTML parsing (up to 20 MB) into `produceState`/`Dispatchers.Default` (currently under `remember`, but a multi-MB string compare per frame)
- **Protocol-core tests**: `WbxmlParser.parse/generate`, `NtlmAuthenticator` (NTLMv2), calendar recurrence logic — zero coverage; needs a test seam
- **MVVM for `MainScreen`/`ComposeScreen`**: the last large unmigrated screens
- **EWS auth fallback**: already DRY behind deps lambdas; the 6-line wrapper with divergent contracts (`String?` vs `EasResult<String>`) was intentionally not merged
- **Friendly auth toast on send**: send errors currently surface the raw server message; auth errors could be detected and shown as a localized message (low priority, not a bug)
- ✅ **CI** — done in part 3 (GitHub Actions + Kover 0.7.6)

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
- **Centralized password storage:** introduced `PasswordStorage` singleton with two modes: `EncryptedSharedPreferences` (AES256-GCM/SIV via Android Keystore) and XOR obfuscation with PBKDF2-HMAC-SHA256 (10,000 iterations, OWASP 2024) when Keystore is unavailable. `SecurityTelemetry` notifies users of degradation and supports an optional fail-closed mode. `AccountRepository` delegates all password operations to `PasswordStorage`. Defense-in-depth — plaintext eliminated, 39 unit tests (L-7)
- **Calendar/task date parsing optimization:** `parseEwsDateTime` switched from `SimpleDateFormat` to `java.time.Instant.parse()` — simpler, faster, best-practice compliant. Covered by 6 tests (N-4)
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
- Account setup: the selected certificate (server/client) is no longer deleted when rotating the screen mid-setup — self-signed Exchange now sets up correctly (SET-1)
- Account verification: the entered password is no longer reset when rotating the screen during verification — fixes a false "verification session expired, re-enter password" (SET-2)
- Attachments: removed a faulty fallback that saved the entire email instead of the attachment when the direct download failed (corrupt file) (PB-1)
- Stability: opening heavy emails with large attachments/images no longer crashes the app on low memory — the email is shown without inline images (PB-2)
- Crash resilience (cont.): background app, push/sync, alarm and receiver operations can no longer crash the process on an uncaught error — a single crash-safe scope isolates and logs the failure
- Crash resilience (reactive screens): observing emails/folders/notes/tasks can no longer crash the app on a database error — the failure is isolated and logged (CR-2)
- Calendar: correct timezone handling when parsing event/task times that carry an offset instead of UTC — fixes a possible time shift (N-4)
- Stability: inline images are not built from excessively large emails — out-of-memory prevention (in addition to the open-path guard) (PB-2)
- Fixed a potential race/`ConcurrentModificationException` in the Push service's EAS-client cache — switched to `ConcurrentHashMap` with atomic operations (N-15)
- Dead-code cleanup and DRY: removed unused `clearAllEasClientCache`/`RepositoryProvider.clear`/`getEmailsByFolder`; `ContactRepository` is now always obtained via the provider (L-1, L-2, N-9, M-2)

### Widget (home screen)
- Fixed bottom-row clipping on a narrow widget: the compose/sync buttons and avatars no longer run off the edge — avatar count and size adapt to width; on the wide widget the last-sync label is truncated to fit instead of pushing the buttons out (for any locale, including 12-hour time) (W-1)
- The last-sync time no longer looks like "today" for older syncs: a date is shown for non-today syncs (W-2)
- Recent emails in the widget: today's mail now shows the time (previously always the full date); minor render optimization — the date formatter is no longer recreated inside the loop (W-3)
- Internal widget cleanup (KISS): removed unused event fields and a variable that were computed on every render (W-4)

### UX — haptic feedback and accessibility
- Added haptic feedback when tapping the star (flag) in the email list and on the email detail screen — consistent with long-press/selection
- Dynamic accessibility labels for the star: "Add to favorites" / "Remove from favorites" depending on state (previously a static "Favorites" label)
- Accessibility label for the attachment icon in the list row (previously `null` — TalkBack did not announce it)
- Fixed accessibility label for the "star selected" button in SearchScreen: `Strings.star` ("Star") instead of `Strings.favorites` ("Favorites")
- DRY: removed duplicate `Strings.unstar` (identical to `Strings.removeFromFavorites`); sole usage replaced with `Strings.removeFromFavorites`

### Tests
- Added ViewModel unit tests (`SearchViewModelTest`, `SyncCleanupViewModelTest`, `NotesViewModelTest`, `TasksViewModelTest`, `UserFoldersViewModelTest`, `EmailListViewModelTest`, `EmailDetailViewModelTest`) on plain JUnit + MockK, no Robolectric
- Widget formatting unit tests (`MailWidgetFormatTest`: today→time else→date, midnight boundary, same day-of-year across years)
- Expanded attachment-budget tests (`ComposeAttachmentSizeTest`): inline data:URL image size estimation and combined files+inline budget (CS-1/CS-2)
- Unit test for temp attachment-dir cleanup (`AttachmentManagerCleanupTest`): age-based cleanup of reply/forward/draft (CS-11)

### Compose — OOM protection and performance (ComposeScreen audit)
- A single attachment budget (files + inline images in the body) is checked BEFORE reading bytes into memory, both on send and on draft save: fixes OOM when saving a draft with a large attachment and the limit bypass by a "heavy" body with inline images on send (CS-1, CS-2)
- Send: `SendController` no longer retains the screen context after the screen closes — leak window removed (applicationContext is passed) (CS-5)
- Recipient suggestions: added debounce for local search — DB queries (contacts/groups/history) run only after a typing pause instead of on every keystroke (CS-7)
- Send: removed a double `destroy()` of the editor WebView — a single correct teardown before navigation (CS-6)
- Editor: if content hasn't synced yet before send/save, the freshest typed text is used — last characters are not lost (CS-9)
- Cleanup: temporary reply/forward/draft attachment files are removed by age on app startup (CS-11)


### Code audit, stages 1–3: technical form of the fixes, crash-safe scopes and tests (2026-07-01)
- **Note**: the user-facing description of these fixes is in the "Stability & security (code audit)" section above; this section keeps the technical wording with class and method names
- **M-1**: added crash resistance to ViewModel mutation functions (Notes, Search, Tasks, UserFolders, SyncCleanup)
- **N-12**: fixed the dead `ServiceWatchdogReceiver` on API 26+ by migrating to dynamic registration
- **N-2**: added a pre-check for attachment size before loading into memory (10 MB limit)
- **N-10**: added try-catch around `PushService.startForeground` to prevent crashes on Android 12+
- **N-11**: implemented a stable `ClientId` for `OutboxWorker` to prevent duplicate sends on retry
- **L-3**: added CSP to `RichTextEditor` and improved `stripDangerousTags` to remove `on*=` handlers
- **N-7**: fixed a race condition in the `EasClient` cache lifecycle by not removing the per-account Mutex
- **N-13**: forced HTTPS for Exchange accounts (removed the non-working HTTP option)
- **N-1**: added CRLF stripping to all MIME header fields to prevent header injection
- **N-3**: implemented RFC 2047 folding for long Subject headers (max 75 octets per encoded-word)
- **N-5**: unified inline image extraction logic by delegating to `MimeHtmlProcessor` (DRY)
- **N-8**: migrated all legacy screens from `collectAsState` to `collectAsStateWithLifecycle`
- **L-1**: removed the dead `clearAllEasClientCache` method
- **L-2**: removed the dead `RepositoryProvider.clear` method
- **N-9**: removed the dead `EmailDao.getEmailsByFolder` method
- **M-2**: migrated direct `ContactRepository` instantiations to `RepositoryProvider`
- **L-4**: fixed the `deleteDuplicateEmails` grouping key to use the canonical `internetMessageId`
- **N-15**: migrated `PushService.easClientCache` to `ConcurrentHashMap` for thread safety
- **Added**: comprehensive unit tests for all fixes (15+ test files); `rememberSafeScope()` for crash-safe coroutine scopes in Composables; `supervisedScope()` utility for long-lived scopes with exception handling; `loggingExceptionHandler()` for ViewModel reactive observe-launch patterns
- **Documentation**: updated `docs/AUDIT.md` with the completion status of Stages 1, 2, and partial Stage 3; added detailed fix descriptions and internet verification sources

## v1.6.3a (29.06.2026)

- Initial stable release
- Exchange ActiveSync 12.0/12.1/14.0/14.1 support
- EWS support for calendar, tasks, notes, and fallback scenarios
- Basic Auth and NTLMv2 authentication
- Direct Push via EAS Ping
- Room database with 42 migrations
- Jetpack Compose UI with Material 3
- Multi-account support
- IMAP/POP3 beta support
- Certificate pinning
- Widget and shortcuts
- Auto-update system

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
