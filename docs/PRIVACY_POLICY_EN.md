# Privacy Policy

For source version 1.7.0 · updated 20 November 2026 · [Русский](PRIVACY_POLICY_RU.md)

## Who receives data

iwo Mail Client does not route mail through a developer-operated service, include advertising SDKs or automatically upload analytics. This does not mean that data never leaves your device:

- **Your mail server** receives credentials and synchronization requests. For Exchange the app uses EAS and EWS: depending on the operation it exchanges messages, attachments, drafts, contacts, events, tasks and notes. Direct Push connects directly to Exchange. IMAP and POP3 accounts receive mail; there is no SMTP sending. Mail connections use TLS; the client does not fall back to plain HTTP for older Exchange servers.
- **Hosts of remote images and fonts** can receive your IP address, the request time and identifiers embedded in the URL. Received HTML mail blocks these resources by default. You can allow them for the current message, a trusted sender or all messages. The viewer allows remote resources in Sent, Drafts and HTML calendar event descriptions. Displaying an image already embedded in the message does not require a request to the sender’s website.
- **Opening a web link** passes its URL to an external app. If you choose one-click unsubscribe, the client sends a request to the HTTPS URL supplied in the message without the mailbox password; the URL itself may identify your subscription. A `mailto:` link opens the composer.
- **When checking for updates**, the app requests [release metadata](../update.json) from GitHub. The request does not include the mailbox password or mail content. Downloads use the selected APK URL in the metadata. The download host receives ordinary connection information, including your IP address. The app itself displays update notifications; Android asks for confirmation before installation or uninstallation for rollback.

## What is stored on the device

App-private storage holds the Room database with local mail and other account data, settings, signatures, cached attachments, sending queues, sync state and local diagnostic records. A local copy of a message does not replace the one on the server. The app does not separately encrypt the mail database or attachment files.

Mailbox and client-certificate passwords normally use `EncryptedSharedPreferences` with an Android Keystore key. If secure storage is unavailable, the fallback uses **obfuscation, not encryption**. You can refuse new password writes in that mode. Imported certificate files are copied into private storage as supplied. App lock stores a password verifier; Android performs biometric verification. [Lock recovery](SECURITY_EN.md) has different requirements from normal sign-in.

System backup is disabled; on Android 12 and later, extraction rules also exclude private data from device-to-device transfer. These protections do not cover files saved to Downloads, exported contacts or copies passed to another app.

## Permissions and actions you take

Network access is used for mail, remote resources and updates. The foreground service, boot receiver, exact alarms and battery optimization exemption support synchronization, scheduled sending and reminders. The app uses notifications and vibration; biometric permissions support optional fingerprint unlock. Package installation and deletion permissions are used for APK updates and rollback.

Sharing text or files into the app can add them to a draft and then transfer them to the mail server when the draft is saved or sent. Opening an attachment in another app grants access to that file; printing passes the document to Android’s print system. The widget and notifications can display mail details. Enabling app lock hides message content. The large widget still shows mailbox names and unread counts. Tapping one of those rows requires unlocking the app; the row does not give the launcher the subject or sender.

Policy changes are published in the repository. Questions: andreyid@outlook.com · [Telegram](https://t.me/i_wantout).
