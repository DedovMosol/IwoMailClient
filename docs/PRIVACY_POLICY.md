# Privacy Policy / Политика конфиденциальности

**Last updated / Последнее обновление:** February 6, 2026

---

## English

### Introduction

iwo Mail Client ("the App") is developed by DedovMosol. This Privacy Policy explains how we handle your information when you use our application.

### Data Collection

**We do NOT collect, store, or transmit any personal data to external servers.**

The App operates entirely on your device. All your data remains on your device and is transmitted only to the mail servers you configure (Exchange, IMAP, POP3).

### Data Stored Locally

The following data is stored only on your device:
- Email account credentials (encrypted)
- Client certificates (.p12/.pfx files with passwords, encrypted) for mTLS authentication
- Email messages and attachments (cached locally)
- HTML signatures with formatting
- Contacts (personal and from corporate address book)
- Calendar events and reminders
- Notes
- Tasks and task reminders
- Server certificates (if configured)
- Certificate pinning data (SHA-256 hashes for MITM protection)
- App settings and preferences
- Sync history
- Widget display data

### Data Transmission

The App connects only to:
- Mail servers you configure (Exchange ActiveSync, IMAP, POP3)
- GitHub (only for checking app updates via `https://raw.githubusercontent.com/DedovMosol/IwoMailClient/main/update.json`)
- No third-party analytics or advertising services
- No personal data is sent to the developer

### Permissions

The App requests the following permissions:
- **Internet** — to connect to mail servers and check for updates
- **Network state** — to check network connectivity before sync
- **Notifications** — to notify about new emails, calendar events, task reminders, and available app updates
- **Boot completed** — to start background sync after device restart
- **Foreground service** — for reliable background synchronization
- **Exact alarms** — for scheduled email sending and reminders
- **Battery optimization exemption** — to ensure reliable background sync
- **Install packages** — to install app updates downloaded from GitHub

### Security

- Account passwords are stored encrypted using Android Keystore
- Client certificate passwords (.p12/.pfx) are stored encrypted using Android Keystore
- All connections to mail servers use TLS encryption by default (HTTP fallback is available for legacy servers)
- Client certificates enable mutual TLS (mTLS) authentication for enhanced security
- Certificate Pinning (Public Key Pinning) protection against MITM attacks — binds to server's public key (SHA-256 hash)
- No data is shared with third parties, including the developer
- The app has no backend servers — all data stays on your device

### Updates

The app can check for updates by connecting to GitHub:
- Update information is fetched from `https://raw.githubusercontent.com/DedovMosol/IwoMailClient/main/update.json`
- Only version information and changelog are downloaded
- APK files are downloaded directly from GitHub releases
- No personal data is sent during update checks
- Push notifications are sent when a new version is available (can be disabled in Android notification settings)
- Updates are optional and require user confirmation

### Changes to This Policy

We may update this Privacy Policy from time to time. Changes will be posted in the app repository.

### Contact

If you have questions about this Privacy Policy:
- Email: andreyid@outlook.com
- Telegram: [@i_wantout](https://t.me/i_wantout)

---

## Русский

### Введение

iwo Mail Client ("Приложение") разработано DedovMosol. Настоящая Политика конфиденциальности объясняет, как мы обрабатываем вашу информацию при использовании приложения.

### Сбор данных

**Мы НЕ собираем, не храним и не передаём персональные данные на внешние серверы.**

Приложение работает полностью на вашем устройстве. Все ваши данные остаются на устройстве и передаются только на почтовые серверы, которые вы настроите (Exchange, IMAP, POP3).

### Данные, хранящиеся локально

На вашем устройстве хранятся только:
- Учётные данные почтовых аккаунтов (в зашифрованном виде)
- Клиентские сертификаты (.p12/.pfx файлы с паролями, зашифрованы) для mTLS аутентификации
- Письма и вложения (локальный кэш)
- HTML-подписи с форматированием
- Контакты (личные и из корпоративной адресной книги)
- События календаря и напоминания
- Заметки
- Задачи и напоминания о задачах
- Сертификаты серверов (если настроены)
- Данные закрепления сертификатов (SHA-256 хэши для защиты от MITM)
- Настройки приложения
- История синхронизации
- Данные для отображения виджета

### Передача данных

Приложение подключается только к:
- Почтовым серверам, которые вы настроите (Exchange ActiveSync, IMAP, POP3)
- GitHub (только для проверки обновлений через `https://raw.githubusercontent.com/DedovMosol/IwoMailClient/main/update.json`)
- Никаких сторонних сервисов аналитики или рекламы
- Никакие персональные данные не отправляются разработчику

### Разрешения

Приложение запрашивает следующие разрешения:
- **Интернет** — для подключения к почтовым серверам и проверки обновлений
- **Состояние сети** — для проверки подключения перед синхронизацией
- **Уведомления** — для оповещения о новых письмах, событиях календаря, напоминаниях о задачах и доступных обновлениях приложения
- **Автозапуск** — для запуска фоновой синхронизации после перезагрузки
- **Фоновая служба** — для надёжной фоновой синхронизации
- **Точные будильники** — для отложенной отправки писем и напоминаний
- **Исключение оптимизации батареи** — для надёжной фоновой синхронизации
- **Установка пакетов** — для установки обновлений, скачанных с GitHub

### Безопасность

- Пароли аккаунтов хранятся в зашифрованном виде с использованием Android Keystore
- Пароли клиентских сертификатов (.p12/.pfx) хранятся в зашифрованном виде с использованием Android Keystore
- Все соединения с почтовыми серверами по умолчанию используют шифрование TLS (для устаревших серверов доступен HTTP)
- Клиентские сертификаты обеспечивают взаимную TLS (mTLS) аутентификацию для повышенной безопасности
- Защита Certificate Pinning (Public Key Pinning) от MITM атак — привязка к публичному ключу сервера (SHA-256 хэш)
- Данные не передаются третьим лицам, включая разработчика
- Приложение не имеет собственных серверов — все данные остаются на вашем устройстве

### Обновления

Приложение может проверять наличие обновлений, подключаясь к GitHub:
- Информация об обновлениях загружается с `https://raw.githubusercontent.com/DedovMosol/IwoMailClient/main/update.json`
- Загружается только информация о версии и список изменений
- APK файлы скачиваются напрямую с GitHub releases
- Никакие персональные данные не отправляются при проверке обновлений
- При обнаружении новой версии отправляется push-уведомление (можно отключить в настройках уведомлений Android)
- Обновления опциональны и требуют подтверждения пользователя

### Изменения политики

Мы можем обновлять эту Политику конфиденциальности. Изменения будут опубликованы в репозитории приложения.

### Контакты

Если у вас есть вопросы по данной Политике конфиденциальности:
- Email: andreyid@outlook.com
- Telegram: [@i_wantout](https://t.me/i_wantout)

---

© 2025-2026 DedovMosol
