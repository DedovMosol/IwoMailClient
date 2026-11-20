# Security

This document is the project's security policy and threat model: **how to report
a vulnerability** and **how the app protects data** (Exchange 2007 SP1 … 2013,
EAS and EWS).

Русский: [SECURITY_RU.md](SECURITY_RU.md)

## Supported versions

Only the latest released version receives security fixes. Please update before
reporting an issue.

| Version | Supported |
|---------|-----------|
| 1.6.x   | yes       |
| < 1.6   | no        |

## Reporting a vulnerability

**Do not open a public GitHub issue for security problems.**

The app handles mail credentials, EAS/EWS traffic and local messages. Report
privately:

- **Email:** andreyid@outlook.com
- **Telegram:** [@i_wantout](https://t.me/i_wantout)

Include affected app and Android versions, steps to reproduce, and impact
(what data or account is exposed). Initial response within a few days. Fixes
ship in the next patch, with credit if you wish.

## Threat model

The app is an Exchange client for **Exchange 2007 SP1 … 2013** speaking
Exchange ActiveSync (EAS, WBXML) and Exchange Web Services (EWS, SOAP).

## Credential storage

Mailbox credentials are stored on the device and used to authenticate to the configured Exchange, IMAP or POP3 server over TLS. An account can also specify an alternate Exchange endpoint.

- **Primary:** `EncryptedSharedPreferences` (AES-256-SIV for keys, AES-256-GCM
  for values) with a master key in the **Android KeyStore** — hardware-backed
  where the device provides it (`EncryptedStoreProvider`).
- **Fallback:** if Keystore / `EncryptedSharedPreferences` initialization fails
  (seen on some custom ROMs and after certain device migrations), the app
  degrades to `ObfuscatedSharedPreferences` — values obfuscated with a
  PBKDF2-HMAC-SHA256 key derived from `ANDROID_ID`. This is **obfuscation, not
  encryption**. `SecurityTelemetry` records it (historical journal). The live
  backend is `PasswordStorage.isUsingInsecureStorage()` and
  `AppLockManager.isUsingInsecureStorage()`.
- **Fail-closed:** when encryption is down, Security settings show a switch
  (off by default). If the user turns it on, the app refuses to write a new
  mailbox password or the app-lock password into the obfuscated store. Existing
  secrets stay. Adding an account that cannot store its password rolls the
  mailbox row back so it is not left without a secret. Toast text follows
  `AppLanguageCode` (loaded in `Application` before `AppLockManager.init`).
- **App lock** (optional): PBKDF2-HMAC-SHA256, 600 000 iterations (OWASP
  guidance), salted; only the derived hash is stored. Cold start locks the app.
  The hash uses the same `EncryptedStoreProvider` as mailbox passwords.

## What happens if the app-lock verifier is lost?

`MainActivity` selects the recovery screen if the session is locked, the lock setting is enabled and `AppLockManager` has no usable verifier. This is **not** a reset for a forgotten password whose verifier still exists. Recovery asks for the active mailbox password (the first mailbox if none is active). `AccountRepository.verifyAccountPassword` checks the stored password locally, or, if none is stored, requires a mailbox access request to succeed at the Exchange endpoint in use. An OPTIONS response alone is not sufficient. Online password recovery is not used for accounts with a client certificate, IMAP or POP3. If verification is unavailable, the mailbox stays locked. Success disables both the app lock and biometric unlock; set the app password again in Settings. If there are no accounts, recovery turns the lock off without asking for a mailbox password.

App lock restricts the UI, not the Room database or background synchronization. With the lock enabled, the widget and notifications hide mail details even when the verifier is missing. Android 13+ disables Recents snapshots; Android 8–12 uses `FLAG_SECURE` while the lock feature is enabled.

## Transport security

- **No cleartext.** `network_security_config.xml` sets
  `cleartextTrafficPermitted="false"`; plain-HTTP connections are refused.
- **Trust anchors.** The update channel (`update.json`, APK) trusts **system
  CAs only**. Exchange connections trust system CAs and, as a fallback,
  **user-installed CAs**: corporate Exchange certificates are often issued by an
  internal CA installed on the device as a user CA. Consequence: a user CA
  installed on the device (by the user, an MDM, or malware the user approved)
  can intercept Exchange traffic. Certificate pinning (below) removes that
  exposure for a given account.
- **TLS versions.** TLS 1.3/1.2 are negotiated whenever the server supports
  them. TLS 1.1/1.0 stay enabled (bundled Conscrypt; Exchange clients offer
  TLS 1.0–1.3 in one handshake with OkHttp `COMPATIBLE_TLS`, no separate
  `MODERN_TLS` attempt) so that Exchange 2007 SP1 remains reachable; this
  applies to every Exchange server, the negotiated version is the highest both
  sides support. The update channel uses `MODERN_TLS` (TLS 1.2+).
- **Explicit key trust:** confirming a fingerprint pins the SHA-256 hash of the server public key (SPKI). This mode checks the key rather than the certificate name or expiry, so verify the first fingerprint with your administrator over an independent channel. An alternate endpoint has its own pin. Importing a server certificate with hostname verification and presenting a client certificate for mTLS are separate mechanisms.
- **IMAP/POP3:** JavaMail uses implicit TLS or requires STARTTLS/STLS before sign-in. Turning off implicit TLS does not allow a plaintext password.
- **`acceptAllCerts` is opt-in and off by default.** It exists for dev/test and
  self-signed servers at the user's explicit choice; it uses a separate HTTP
  client, so it never weakens validation for other accounts.
- **Authentication.** EAS: Basic over TLS. EWS: Basic first, NTLMv2 if the
  Basic attempt fails. On a redirect to another host OkHttp drops the
  `Authorization` header, so credentials are presented only to the server
  addresses configured in the account (the main one and, if set, the alternate
  one).

## Local data

- Mail, contacts, calendar, tasks and notes are cached in a local Room database
  in the app's private storage; downloaded attachments, the outbox queue and
  imported client certificates live in private files.
- **No backup, no device transfer.** `android:allowBackup="false"` turns off
  backup on Android 11 and lower and cloud backup on Android 12+, but on
  Android 12+ it does not stop device-to-device transfers. So
  `data_extraction_rules.xml` excludes all private storage from both cloud
  backup and device transfer.

## Rooted / compromised devices

- **The app does not detect or block rooted devices.** This is deliberate: a
  FOSS mail client should not deny service to users who control their own
  device, and root detection is trivially bypassed anyway.
- **It does not crash on them:** if the Keystore is unavailable (common on
  custom ROMs), credential storage degrades to the obfuscated fallback instead
  of failing.
- **Inherent limitation:** on a rooted device, any process with root can read
  another app's private storage and, depending on the ROM, weaken Keystore
  guarantees. No on-device measure can fully prevent local secret extraction
  once root is granted to a malicious actor — this is a platform property, not
  an app defect. Treat credentials stored on a rooted, untrusted device as
  potentially exposed.

## Untrusted input

- The HTML mail viewer sanitizes markup and uses CSP to forbid form submission and base URL changes. Only a user tap in the main frame can open a browser (`http`/`https`), the dialer (`tel`) or the composer (`mailto`). Automatic and subframe navigation are ignored.
- `EasXmlParser.createParser` and `CalendarCategoryService.parseCategoryListXml` disable DOCTYPE processing. Not all EWS/SOAP paths use these parsers; see the [XML migration status](XMLPULLPARSER_MIGRATION_PLAN.md).
- Sync paths that may delete local data (tasks, notes, contacts, calendar)
  delete only on a complete server snapshot. A truncated, failed or partial
  response is treated as incomplete, so a dropped connection cannot wipe local
  data.

## Out of scope

- Protection against a rooted device under an attacker's control (see above).
- Server-side security and Exchange account policy enforcement.
- Network-level adversaries who control a CA trusted by the device (system or
  user), unless the account uses certificate pinning.
