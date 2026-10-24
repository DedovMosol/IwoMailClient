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

- **Primary:** `EncryptedSharedPreferences` (AES-256-GCM for values, AES-256-SIV
  for keys) with a master key from the hardware-backed **Android KeyStore**
  (`EncryptedStoreProvider`).
- **Fallback:** if KeyStore / `EncryptedSharedPreferences` initialization fails
  (seen on some custom ROMs and after certain device migrations),
  the app degrades to `ObfuscatedSharedPreferences` — values obfuscated with a
  PBKDF2-HMAC-SHA256 key derived from `ANDROID_ID`. This is **obfuscation, not
  encryption**; it is recorded via `SecurityTelemetry` and surfaced through
  `isUsingInsecureStorage()`.
- **Fail-closed option:** when enabled, the app refuses to persist a password
  rather than write it to the obfuscated fallback.
- **App lock** (optional): PBKDF2-HMAC-SHA256, 600 000 iterations (OWASP 2023
  guidance), salted; only the derived hash is stored.

## Transport security

- **No cleartext.** `network_security_config.xml` sets
  `cleartextTrafficPermitted="false"`; HTTP is auto-upgraded/forbidden.
- **Trust anchors: system CAs only.** User-installed CAs are intentionally not
  trusted, reducing the MITM surface (Android 7+ best practice).
- **TLS versions:** modern TLS by default; a bundled Conscrypt provider enables
  TLS 1.0 **only** where required to reach legacy Exchange 2007 SP1 servers
  (`MODERN_TLS` + `COMPATIBLE_TLS`).
- **Certificate pinning** (`pinnedCertInfo`) and **client certificates / mTLS**
  (`clientCertificatePath`) are supported for self-signed or
  certificate-authenticated deployments.
- **`acceptAllCerts` is opt-in and off by default.** It exists for dev/test and
  self-signed servers at the user's explicit choice; it uses a separate HTTP
  client so it never weakens validation for other accounts.
- **Authentication:** NTLMv2 (preferred) with Basic fallback for EWS; Basic over
  TLS for EAS. Credentials are only ever presented to the configured host.

## Local data

- `android:allowBackup="false"` — app data (including the credential store and
  the message database) is excluded from `adb backup` / cloud backup.
- Mail, contacts, calendar, tasks and notes are cached in a local Room database
  in the app's private storage.

## Rooted / compromised devices

- **The app does not detect or block rooted devices.** This is deliberate: a
  FOSS mail client should not deny service to users who control their own
  device, and root detection is trivially bypassed anyway.
- **It does not crash on them:** if the hardware KeyStore is unavailable (common
  on custom ROMs), credential storage degrades gracefully to the obfuscated
  fallback instead of failing.
- **Inherent limitation:** on a rooted device, any process with root can read
  another app's private storage and, depending on the ROM, weaken KeyStore
  guarantees. No on-device measure can fully prevent local secret extraction
  once root is granted to a malicious actor — this is a platform property, not
  an app defect. Hardware-backed keys raise the bar but do not remove it. Treat
  credentials stored on a rooted, untrusted device as potentially exposed.

## Untrusted input

- Server responses (WBXML/EAS, SOAP/EWS) are parsed with a hardened
  `XmlPullParser`: DOCTYPE processing and external entities are disabled
  (no XXE), and a truncated WBXML/SOAP document is rejected rather than treated
  as a complete (authoritative) result, so a dropped connection cannot trigger
  deletion of local data.

## Out of scope

- Protection against a rooted device under an attacker's control (see above).
- Server-side security and Exchange account policy enforcement.
- Network-level adversaries who also control a trusted system CA.
