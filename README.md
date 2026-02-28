# iwo Mail Client

🇬🇧 [English version](README_EN.md)

Почтовый клиент для Android с поддержкой Microsoft Exchange Server 2007+ (ActiveSync/EWS), IMAP и POP3.

**Версия:** 1.6.2  
**Разработчик:** DedovMosol  
**Telegram:** [@i_wantout](https://t.me/i_wantout)  
**Email:** andreyid@outlook.com

## 🌟 Особенности

- 📧 **Exchange ActiveSync** — поддержка EAS 12.0-14.1 (Exchange 2007+). Протестировано на Exchange 2007 SP1 (EAS 12.1)
- 🔄 **EWS для Exchange 2007** — календарь, задачи, заметки, черновики через EWS с NTLMv2 (fallback для EAS 12.x)
- 📬 **IMAP/POP3** — работа с любыми почтовыми серверами (скоро ожидается)
- 📱 **Android 8.0 - 16** — работает на данных версиях Android
- 🔒 **Совместимость с Exchange 2007** — поддержка TLS 1.0/1.1 через Conscrypt
- 🔐 **Сертификаты сервера** — поддержка самоподписанных сертификатов для корпоративных серверов
- 🔑 **Клиентские сертификаты** — mTLS аутентификация через PKCS#12 (.p12/.pfx) для корпоративных сред с двусторонней проверкой
- 🌍 **Два языка** — 🇷🇺 Русский / 🇬🇧 English
- 🎨 **Material Design 3** — современный интерфейс с цветовыми темами
- 🔔 **Push-уведомления** — мгновенные уведомления о новых письмах (Direct Push)

## 📱 Возможности

- ✅ **Почта** — синхронизация, отправка с вложениями, поиск, фильтры, избранное, управление папками, черновики, отложенная отправка, отправка группе пользователей
- ✅ **Контакты** — личные и GAL, группы, импорт/экспорт (vCard, CSV)
- ✅ **Календарь** — поддержка повторяющихся событий, вложений и ссылок на онлайн-встречи
- ✅ **Заметки** — создание, редактирование, синхронизация
- ✅ **Задачи** — создание, редактирование, синхронизация
- ✅ **Уведомления** — Push (Direct Push), фоновая синхронизация, ночной режим
- ✅ **Автоочистка** — настраиваемая очистка Trash/Drafts/Spam для каждого аккаунта (по интервалу или отключение через «Никогда»)
- ✅ **Интерфейс** — тёмная/светлая тема, 4 цветовые схемы, персонализация
- ✅ **Несколько аккаунтов** — с индивидуальными подписями и настройками
- ✅ **MDN/DSN** — запрос отчёта о прочтении и доставке

📋 История изменений: [CHANGELOG_RU.md](docs/CHANGELOG_RU.md)

---

## ⚠️ Важно: Переименование пакета

**Версия 1.6.1 изменила имя пакета** с `com.iwo.iwomail` на `com.dedovmosol.iwomail`.

**Это означает:**
- ❌ Обновление старых версий через APK **невозможно** — Android воспринимает это как другое приложение
- ⚠️ Требуется **полная переустановка** — удалить старую версию, установить новую
- 💾 Данные будут утеряны — экспортируйте контакты/настройки перед обновлением
- ✅ В будущих версиях обновления будут работать штатно

**Как обновиться:**
1. Экспортировать контакты (vCard/CSV) в приложении
2. Удалить старую версию `com.iwo.iwomail`
3. Установить новую версию `com.dedovmosol.iwomail`
4. Настроить аккаунты заново
5. Импортировать контакты

---

## 📋 Требования

| Параметр | Минимум | Рекомендуется |
|----------|---------|---------------|
| Android | 8.0+ (API 26) | — |
| Оперативная память | 2 ГБ | 4+ ГБ |
| Свободное место | 50 МБ | 100+ МБ |
| Процессор | ARMv7 / x86 | ARM64 / x86_64 |

- Target SDK: 36 (Android 16)
- Поддерживаемые архитектуры: armeabi-v7a, arm64-v8a, x86, x86_64

## 🖥️ Поддерживаемые серверы

| Сервер | Статус |
|--------|--------|
| Exchange 2007 SP1/SP2 | ✅ Стабильно работает |
| Exchange 2010/2013+ | ⚠️ Требует тестирования |
| Office 365 | ⚠️ Требует OAuth |
| IMAP/POP3 серверы | ⚠️ Beta |

## 💡 Известные ограничения

- **Office 365 / Modern Auth** — OAuth 2.0 пока не поддерживается (только Basic Auth)
- **IMAP/POP3** — в beta-версии, может работать нестабильно
- **EAS 16.0+** (Exchange 2016+) — не протестировано, возможны проблемы
- **S/MIME подписи** — не поддерживается

## 📊 Технологический стек

**Язык и фреймворки:**
- Kotlin 1.9.22
- Jetpack Compose — UI
- Coroutines + Flow — асинхронность
- Material Design 3 — дизайн

**Хранилище:**
- Room Database — локальная БД
- DataStore — настройки

**Сеть и протоколы:**
- OkHttp 4.12.0 — HTTP клиент
- Conscrypt — TLS 1.0-1.3 (Exchange 2007 support)
- EAS 12.0-14.1 — ActiveSync
- EWS (NTLM) — Exchange Web Services
- JavaMail (com.sun.mail) — IMAP/POP3/SMTP

**Безопасность:**
- Certificate Pinning (Public Key Pinning)
- SSL/TLS mutual authentication (mTLS)
- Self-signed certificates support

**Другое:**
- WorkManager — фоновая синхронизация
- Manual DI (RepositoryProvider) — dependency injection
- Coil — загрузка изображений

## 🔧 Сборка

```bash
./gradlew assembleDebug    # Debug
./gradlew assembleRelease  # Release
```

## 📖 Документация

- [История изменений](docs/CHANGELOG_RU.md)
- [Архитектура проекта](docs/ARCHITECTURE.md)
- [Политика конфиденциальности](docs/PRIVACY_POLICY.md)

## 🤝 Вклад в проект

Этот проект открыт для улучшений! Вот как вы можете помочь:

**🐛 Сообщить о баге:**
- Telegram: [@i_wantout](https://t.me/i_wantout)
- Email: andreyid@outlook.com
- [GitHub Issues](https://github.com/DedovMosol/IwoMailClient/issues)

**💡 Предложить улучшение:**
- Напишите в Telegram с описанием идеи
- Или создайте Issue на GitHub

**❓ Задать вопрос:**
- По настройке Exchange 2007/2010/2013
- По проблемам синхронизации
- По любым техническим вопросам

**🔧 Pull Requests:**
- Приветствуются исправления багов
- Приветствуются новые возможности
- Следуйте существующему code style
- Пишите понятные commit messages

## 🏗️ Архитектура

```
UI Layer
  Jetpack Compose — 21 экран, 8 компонентов
  Navigation, Theme, Localization
    ↓
Repository Layer
  AccountRepository, MailRepository, CalendarRepository,
  ContactRepository, NoteRepository, TaskRepository, SettingsRepository
  + EmailSyncService, EmailOperationsService, FolderSyncService
    ↓
Protocol Layer
  EAS/EWS — EasClient (Email, Calendar, Tasks, Notes, Drafts, Contacts, Attachment)
  IMAP — ImapClient  |  POP3 — Pop3Client  |  SMTP — SmtpClient
    ↓
Database Layer                    Network Layer
  Room — 10 DAO, 10 Entity         HttpClientProvider, NetworkMonitor
  MailDatabase (v34)                RetryUtils, NtlmAuthenticator
    ↓
Background Services
  PushService, SyncWorker, OutboxWorker
  BootReceiver, SyncAlarmReceiver, PushRestartWorker
  ServiceWatchdogReceiver, ScheduledEmailWorker
  CalendarReminderReceiver, TaskReminderReceiver
  MarkEmailReadWorker, MarkTaskCompleteWorker
```

## 📄 Лицензия

MIT License

---

© 2025-2026 DedovMosol
