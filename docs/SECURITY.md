# Security Model

This document describes **how the app protects data and what it does not
protect against** (the threat model). For **reporting a vulnerability**, see
the repository-root [`SECURITY.md`](../SECURITY.md) — that file is the
disclosure policy; this one is the security design.

The app is an Exchange client for **Exchange 2007 SP1 … 2013** speaking
Exchange ActiveSync (EAS, WBXML) and Exchange Web Services (EWS, SOAP).

## Credential storage

Account passwords are stored on-device only; they are never sent anywhere
except to the configured Exchange server over TLS.

- **Primary:** `EncryptedSharedPreferences` (AES-256-SIV for keys, AES-256-GCM
  for values) with a master key in the **Android KeyStore** — hardware-backed
  where the device provides it (`EncryptedStoreProvider`).
- **Fallback:** if KeyStore / `EncryptedSharedPreferences` initialization fails
  (seen on some custom ROMs and after certain device migrations), the app
  degrades to `ObfuscatedSharedPreferences` — values obfuscated with a
  PBKDF2-HMAC-SHA256 key derived from `ANDROID_ID`. This is **obfuscation, not
  encryption**; it is recorded via `SecurityTelemetry` and reported by
  `isUsingInsecureStorage()`.
- **Fail-closed mode** exists in code (`SecurityTelemetry.setFailClosedMode`):
  when on, the app refuses to write a password to the obfuscated fallback. It
  has no settings switch yet, so by default the fallback is used.
- **App lock** (optional): PBKDF2-HMAC-SHA256, 600 000 iterations (OWASP
  guidance), salted; only the derived hash is stored. Cold start locks the app.

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
  them. TLS 1.1/1.0 stay enabled (bundled Conscrypt; OkHttp `MODERN_TLS` with a
  `COMPATIBLE_TLS` fallback) so that Exchange 2007 SP1 remains reachable; this
  applies to every server, the negotiated version is the highest both sides
  support.
- **Certificate pinning** (per-account pinned certificate hash) and **client
  certificates / mTLS** (`clientCertificatePath`) are supported for self-signed
  or certificate-authenticated deployments.
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
- **It does not crash on them:** if the KeyStore is unavailable (common on
  custom ROMs), credential storage degrades to the obfuscated fallback instead
  of failing.
- **Inherent limitation:** on a rooted device, any process with root can read
  another app's private storage and, depending on the ROM, weaken KeyStore
  guarantees. No on-device measure can fully prevent local secret extraction
  once root is granted to a malicious actor — this is a platform property, not
  an app defect. Treat credentials stored on a rooted, untrusted device as
  potentially exposed.

## Untrusted input

- All XML parsing goes through one hardened `XmlPullParser` factory
  (`EasXmlParser.createParser`, DOCTYPE processing disabled); the remaining
  EWS/SOAP handling reads values with regular expressions, which never expand
  entities. Neither path can resolve external entities (no XXE).
- Sync paths that may delete local data (tasks, notes, contacts, calendar)
  delete only on a complete server snapshot. A truncated, failed or partial
  response is treated as incomplete, so a dropped connection cannot wipe local
  data.

## Out of scope

- Protection against a rooted device under an attacker's control (see above).
- Server-side security and Exchange account policy enforcement.
- Network-level adversaries who control a CA trusted by the device (system or
  user), unless the account uses certificate pinning.
