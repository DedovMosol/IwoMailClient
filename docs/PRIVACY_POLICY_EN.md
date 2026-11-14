# Privacy Policy

**Last updated:** 14 November 2026

English: this file. Russian: [PRIVACY_POLICY_RU.md](PRIVACY_POLICY_RU.md).

## Introduction

iwo Mail Client ("the App") is developed by DedovMosol. This Privacy Policy explains how we handle your information when you use our application.

## Data Collection

**We do NOT collect, store, or transmit any personal data to external servers.**

The App operates entirely on your device. All your data remains on your device and is transmitted only to the mail servers you configure (Exchange ActiveSync / EWS, IMAP, POP3).

## Data Stored Locally

The following data is stored only on your device:

- Email account credentials (encrypted)
- Client certificates (.p12/.pfx files with passwords, encrypted) for mTLS authentication
- Email messages and attachments (cached locally)
- Drafts (stored locally and/or on the mail server)
- Scheduled (deferred) emails awaiting send time
- Downloaded update / rollback APK files and other app-managed local files (until deleted by you or auto-cleanup)
- HTML signatures with formatting
- Contacts (personal and from corporate address book)
- Calendar events and reminders
- Notes
- Tasks and task reminders
- Server certificates (if configured)
- Certificate pinning data (SHA-256 hashes for MITM protection)
- App settings and preferences
- Sync history, sync checkpoints, and notification metadata used for deduplication/reliable delivery
- Widget display data

## Data Transmission

The App connects only to:

- Mail servers you configure (Exchange ActiveSync, Exchange Web Services, IMAP, POP3)
- GitHub (only for checking app updates and rollbacks via `https://raw.githubusercontent.com/DedovMosol/IwoMailClient/main/update.json`)
- No third-party analytics or advertising services
- No personal data is sent to the developer

## Permissions

The App requests the following permissions:

- **Internet** — to connect to mail servers and check for updates
- **Network state** — to check network connectivity before sync
- **Notifications** — to notify about new emails, calendar events, task reminders, and available app updates
- **Boot completed** — to start background sync after device restart
- **Foreground service** — for reliable background synchronization (including Direct Push — a persistent connection to the Exchange server for instant email delivery)
- **Exact alarms** — for scheduled (deferred) email sending, calendar reminders, and task reminders
- **Battery optimization exemption** — to ensure reliable background sync
- **Vibration** — for haptic feedback in the app
- **Install packages** — to install app updates downloaded from GitHub
- **Remove packages** — to uninstall a previous/old app version during rollback or the `com.iwo.iwomail` → `com.dedovmosol.iwomail` package migration, only user confirmation triggers it

## Security

- Account passwords are stored encrypted using Android Keystore
- Client certificate passwords (.p12/.pfx) are stored encrypted using Android Keystore
- All connections to mail servers use TLS encryption by default (HTTP fallback is available for legacy servers)
- Client certificates enable mutual TLS (mTLS) authentication for enhanced security
- Certificate Pinning (Public Key Pinning) protection against MITM attacks — binds to server's public key (SHA-256 hash)
- No data is shared with third parties, including the developer
- The app has no backend servers — all data stays on your device

## Interaction with Other Apps

The App can receive data from other applications via Android's standard sharing mechanism:

- **mailto: links** — the App can be selected as the default email client to handle mailto: links from browsers and other apps
- **Share intent** — other apps can share text, files, and attachments to compose a new email
- **iwomail:// URIs** — internal URI scheme used only by the home screen widget for in-app navigation (search, calendar, email, compose), no external data is involved
- No data received from other apps is transmitted anywhere except to the mail server you configured when sending the email

## Updates

The app can check for updates and rollbacks by connecting to GitHub:

- Update information is fetched from `https://raw.githubusercontent.com/DedovMosol/IwoMailClient/main/update.json`
- Only version information and changelog are downloaded
- APK files are downloaded directly from GitHub releases
- No personal data is sent during update checks
- Push notifications are sent when a new version is available (can be disabled in Android notification settings)
- Updates are optional and require user confirmation

## Changes to This Policy

We may update this Privacy Policy from time to time. Changes will be posted in the app repository.

## Contact

If you have questions about this Privacy Policy:

- Email: andreyid@outlook.com
- Telegram: [@i_wantout](https://t.me/i_wantout)

---

© 2025-2026 DedovMosol
