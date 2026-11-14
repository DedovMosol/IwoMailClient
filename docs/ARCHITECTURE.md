[English version](ARCHITECTURE_EN.md)

# Архитектура проекта

**iwo Mail Client** — Android-клиент для Microsoft Exchange (EAS/EWS) с beta-поддержкой IMAP/POP3.

**Версия:** 1.6.3b
**Пакет:** `com.dedovmosol.iwomail`
**Gradle root:** `IwoMailClient`
**Android-модуль:** `:app`

---

## 1. Состояние проекта v1.6.3b

| Область | Значение |
|---------|----------|
| Application ID / namespace | `com.dedovmosol.iwomail` |
| Версия | `versionName = 1.6.3b`, `versionCode = 26` |
| SDK | `minSdk = 26`, `compileSdk = 36`, `targetSdk = 36` |
| JVM target | Java/Kotlin `17` |
| Kotlin | `1.9.22` (расширение компилятора Compose `1.5.8`) |
| AGP | `8.7.3` |
| Инструменты сборки | Gradle `8.12`, KSP `1.9.22-1.0.17`, Kover `0.7.6` |
| UI | Jetpack Compose (BOM `2024.06.00`) + Material 3, Navigation Compose `2.7.6`, Coil `2.5.0` |
| БД | Room `2.6.1`, `MailDatabase` версии `51` (13 entity), экспорт схем включён (`app/schemas`) |
| Сеть и фон | OkHttp `4.12.0`, Conscrypt `2.5.2`, WorkManager `2.9.0`, DataStore `1.1.1`, Glance `1.1.1`, JavaMail (`android-mail`) `1.6.7` |
| Безопасность | `security-crypto` `1.1.0-alpha06` (EncryptedSharedPreferences), `biometric` `1.1.0` |
| Архитектуры APK | `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` + универсальный APK |
| Тесты | Unit-тесты в `app/src/test`: 184 файла `.kt`, 1601 аннотация `@Test` (подсчёт по исходникам); каталога `androidTest` нет |
| Опубликованный релиз | `update.json` → `1.6.2` (`versionCode` 25); `1.6.3b` — beta-сборка |
| Модель работы | Offline-first: Room + Flow → UI, фоновая синхронизация обновляет БД |

Основной production-сценарий — on-premise Exchange, особенно Exchange 2007 SP1/SP2. IMAP/POP3 реализованы как beta-клиенты для чтения (список папок и последние письма; IMAP дополнительно создаёт, переименовывает и удаляет папки) и не дают паритета с функциями Exchange.

---

## 2. Слои приложения

```text
MainActivity (FragmentActivity) / MailApplication
  ├─ старт приложения, Conscrypt, блокировка приложения (AppLockManager), каналы уведомлений, разрешения
  ├─ обработка mailto/share-интентов и deep link'ов iwomail://email, iwomail://widget
  └─ Compose-контент + AppNavigation; LockScreen поверх навигации, пока приложение заблокировано

UI-слой
  ├─ экраны Jetpack Compose
  ├─ навигация, тема, локализация
  ├─ подпакеты экранов: calendar, compose, contacts, emaildetail, emaillist
  └─ общие компоненты, анимации, собственные иконки, rich-text виджеты

Domain (domain/)
  └─ логика без UI: черновики, отправка, цепочки писем, флаги, отписка, отчёты о прочтении, настройки почты

Слой репозиториев
  ├─ AccountRepository
  ├─ MailRepository
  ├─ CalendarRepository / TaskRepository / NoteRepository / ContactRepository / CategoryColorRepository
  ├─ SettingsRepository
  ├─ AccountServerHealthRepository
  ├─ EmailSyncService / EmailOperationsService / FolderSyncService / AppFileCleanupService
  └─ FolderSyncGate (один EAS Sync на папку) / FlagWriteTracker (оптимистичная запись флагов) / PendingMailChanges (очередь правок без связи)

Слой протоколов
  ├─ фасад EasClient
  ├─ EasTransport: EAS WBXML + EWS SOAP + Provision
  ├─ EAS-сервисы по областям: почта, папки, контакты, календарь, задачи, заметки, черновики, вложения
  ├─ EwsClient + NtlmAuthenticator
  └─ ImapClient / Pop3Client (beta, JavaMail)

Слой хранения и системных интеграций
  ├─ Room: аккаунты, папки, письма, вложения, контакты, группы, подписи, заметки, календарь, задачи, цвета категорий, очередь правок писем
  ├─ DataStore: настройки UI, контрольные точки синхронизации и уведомлений, настройки обновлений
  ├─ data/security: PasswordStorage, EncryptedStoreProvider, AppLockManager, SecurityTelemetry
  ├─ HttpClientProvider: общий OkHttp, TLS, mTLS, закрепление сертификатов, Rfc6125HostnameVerifier
  ├─ FileProvider + интеграция с MediaStore/Downloads
  └─ Glance-виджет + динамические ярлыки Android и deep link'и

Фоновый слой
  ├─ PushService: foreground-сервис EAS Ping (Direct Push)
  ├─ SyncWorker: периодическая и ручная синхронизация, очистка, проверка уведомлений
  ├─ OutboxWorker: отправка из офлайн-очереди
  ├─ PendingMailChangesWorker: отправка звёзд и «прочитано», сделанных без связи
  ├─ ScheduledSendReceiver + ScheduledEmailWorker: отложенная отправка
  ├─ CalendarReminderReceiver / TaskReminderReceiver / FlagReminderReceiver / RescheduleRemindersWorker
  └─ BootReceiver / SyncAlarmReceiver / PushRestartWorker / ServiceWatchdogReceiver
```

---

## 2.1. Модель состояния UI (MVVM, внедряется поэтапно)

Исторически экраны держат состояние прямо в `@Composable` через `remember`/`rememberSaveable`/`LaunchedEffect`, без ViewModel. Это требует ручных защит от пересоздания при повороте (флаги `*Loaded`/`*Initialized`) и смешивает логику представления с отрисовкой.

Поверх этого вводится слой **`androidx.lifecycle.ViewModel` + неизменяемый `UiState` + `StateFlow`**, экран за экраном. Он затрагивает только слой представления: протокол EAS/EWS и совместимость с Exchange 2007 SP1/SP2 не меняются.

```text
Composable ──collectAsStateWithLifecycle──> StateFlow<UiState>  (ViewModel)
Composable ──вызовы событий (vm.setX())───> ViewModel ──> Repository (RepositoryProvider)
```

Принципы:
- **UiState** — `data class` с неизменяемыми полями, единый источник данных экрана.
- **ViewModel** переживает поворот: состояние сохраняется без ручных флагов и лишних чтений из БД.
- Корутины запускаются в `viewModelScope` (он живёт дольше композиции), поэтому записи в БД и сетевые операции не отменяются при повороте.
- Зависимости берутся из `RepositoryProvider` через `ViewModelProvider.Factory` (`provideFactory`); аргументы навигации передаются в фабрику. `RepositoryProvider` остаётся механизмом DI.
- **Одноразовые события** (тосты, звук, навигация, пасхалка) не входят в состояние. Они передаются через `Channel(Channel.BUFFERED)` → `receiveAsFlow()`, который UI собирает в `LaunchedEffect`. ViewModel не зависит от языка и ресурсов: она отправляет семантическое событие, локализация выполняется в UI.
- **Тестируемость.** Репозитории и `CoroutineDispatcher` передаются через конструктор; фабрика подставляет боевые реализации, юнит-тест — моки. Системные эффекты `SyncCleanupViewModel` (push/перепланирование синхронизации) спрятаны за интерфейсом `SyncEffects`, поэтому тесты идут на JUnit + MockK.

**Подключено к экранам** (все ViewModel расширяют `ViewModel` и создаются через `viewModel(factory = …)`):

- `SyncCleanupScreen` ↔ `SyncCleanupViewModel`/`SyncCleanupUiState` — настройки синхронизации и очистки аккаунта. Зависимости: `AccountRepository` + `SettingsRepository` + `SyncEffects`. Операции `set*` выполняются по принципу fire-and-forget. Тест: `SyncCleanupViewModelTest`.
- `SearchScreen` ↔ `SearchViewModel`/`SearchUiState` + `SearchEvent` — поиск, выделение, пакетные операции (удалить, прочитано, звезда). Запрос от 2 символов запускается из UI с задержкой 300 мс (`LaunchedEffect(query)`), предыдущий поиск в ViewModel отменяется. Результаты живут в ViewModel и переживают поворот; после смерти процесса поиск сбрасывается. Тест: `SearchViewModelTest`.
- `NotesScreen` ↔ `NotesViewModel`/`NotesUiState` + `NotesEvent` — заметки, корзина, выделение, поиск, автосинхронизация с троттлингом синхронизации корзины. Источник данных реактивный: `collectLatest(activeAccount)` + `combine`; флаг `isInitialLoadDone` вынесен в состояние. Окончательное удаление, восстановление и очистка корзины с прогрессом — тонкие `suspend`-функции ViewModel, которые вызывает `DeletionController` (`ui/components/DeletionProgressBar.kt`). Тест: `NotesViewModelTest`.
- `TasksScreen` ↔ `TasksViewModel`/`TasksUiState` + `TasksEvent` — задачи, корзина, фильтр `TaskFilter` (7 значений: `ALL`, `TODAY`, `ACTIVE`, `COMPLETED`, `HIGH_PRIORITY`, `OVERDUE`, `DELETED`), два независимых набора выделения, поиск, автосинхронизация. `TaskRepository` даёт только одиночные `delete/restore/permanent`, поэтому пакетные `restoreTasks`/`deleteTasksPermanently` (с `onProgress`) выполняют цикл внутри ViewModel. `initialFilter` передаётся в фабрику. Тест: `TasksViewModelTest`.
- `UserFoldersScreen` ↔ `UserFoldersViewModel`/`UserFoldersUiState` + `UserFoldersEvent` — пользовательские папки (в списке только EAS-типы `1` и `12`, сортировка по имени), выделение, создание, переименование, удаление и пакетное удаление с прогрессом в состоянии. `MailRepository` даёт только одиночные операции с папками (`FolderSyncService` сериализует их по аккаунту), поэтому пакетное удаление — последовательный цикл в ViewModel. Операции выполняются в `viewModelScope` и переживают поворот. Тест: `UserFoldersViewModelTest`.
- `EmailListScreen` ↔ `EmailListViewModel`/`EmailListUiState` + `EmailListEvent` — список писем папки, «Избранного» (`favorites`) и «Сегодня» (`TODAY_ALL`), фильтры (`MailFilter` из 5 значений и `EmailDateFilter` из 5 значений), выделение, синхронизация и пакетные операции: в корзину, перенос, восстановление, в спам, прочитано, флаги, удаление черновиков, окончательное удаление. Текущая папка и список папок для диалога переноса берутся из одного реактивного источника (`collectLatest(activeAccount)` + `combine`). Автосинхронизация выполняется только для «Черновиков» и один раз на экземпляр ViewModel (флаг `draftsSynced`). Проверка сети (`NetworkMonitor`), звук удаления и локализация сообщений остаются в UI. Тест: `EmailListViewModelTest`.
- `EmailDetailScreen` ↔ `EmailDetailViewModel`/`EmailDetailUiState` + `EmailDetailEvent` (в ViewModel вынесено **ядро** экрана) — письмо, вложения и папки одним потоком `collectLatest(activeAccount)` + `combine`, из которого выводятся признаки текущей папки (корзина, отправленные, черновики). Inline-картинки загружаются реактивно и хранятся в состоянии, поэтому поворот не запускает повторную загрузку. Операции (обновление, удаление в корзину, перенос, восстановление, «непрочитано», флаг, ответ на запрос отчёта о прочтении и его откладывание, отписка, сохранение `.eml`, окончательное удаление) работают в `viewModelScope`; бизнес-логика делегирована классу `EmailDetailActions` (`ui/screens/emaildetail/`). В Composable остаются WebView и отрисовка тела, launcher'ы и файловый ввод-вывод вложений, разбор iCal и приглашений на встречи. Тест: `EmailDetailViewModelTest`.
- `ScheduledMailScreen` ↔ `ScheduledMailViewModel` + `ScheduledMailEvent` — список запланированных писем (`StateFlow`, строится из `ScheduledSend.pending` и обновляется по `ScheduledSend.changes`), «отправить сейчас», перенос и отмена в `viewModelScope`. Получает `Application` и `CoroutineDispatcher`. Тест: `ScheduledMailViewModelTest` (Robolectric).

**Не подключено:** `ComposeViewModel` + `ComposeUiState`/`ComposeEvent` (`ui/screens/compose/`) существует и покрыт `ComposeViewModelTest`, но `ComposeScreen` его не использует (в `ComposeScreen.kt` и `AppNavigation.kt` обращений к нему нет): экран создания письма держит состояние в Composable.

Зависимости: `lifecycle-viewmodel-ktx`, `lifecycle-viewmodel-compose`, `lifecycle-runtime-compose` (`2.7.0`).

---

## 3. Пакетная структура

```text
com.dedovmosol.iwomail/
├── MainActivity.kt
├── MailApplication.kt
│
├── ui/
│   ├── MainScreen.kt
│   ├── MainScreenDrawer.kt
│   ├── Localization.kt
│   ├── navigation/AppNavigation.kt
│   ├── screens/
│   │   ├── SetupScreen.kt / VerificationScreen.kt / OnboardingScreen.kt / AddAnotherAccountScreen.kt
│   │   ├── LockScreen.kt — разблокировка паролем/отпечатком
│   │   ├── EmailListScreen.kt / EmailDetailScreen.kt / ComposeScreen.kt / ComposeUtils.kt
│   │   ├── ContactsScreen.kt / CalendarScreen.kt / NotesScreen.kt / TasksScreen.kt
│   │   ├── SettingsScreen.kt (корень настроек) / SettingsScaffold.kt
│   │   ├── SettingsAccountsScreen.kt / SettingsMailScreen.kt (почта + уведомления) / SettingsSecurityScreen.kt
│   │   ├── MailSettingsSection.kt / MailAlertsSettingsSection.kt
│   │   ├── AccountSettingsScreen.kt / PersonalizationScreen.kt / SyncCleanupScreen.kt
│   │   ├── UserFoldersScreen.kt / UpdatesScreen.kt / AboutScreen.kt / SearchScreen.kt
│   │   ├── ScheduleSendDialog.kt / ScheduleSendTimes.kt / ScheduledMailScreen.kt / ScheduledEmailWorker.kt
│   │   ├── *ViewModel.kt — EmailList, EmailDetail, Notes, Tasks, Search, SyncCleanup, UserFolders, ScheduledMail
│   │   ├── calendar/ — AgendaView, MonthView, диалоги событий, вложения, UI цветов категорий, верхняя панель выделения
│   │   ├── compose/ — ComposeViewModel/UiState/Event (не подключены к ComposeScreen), модели, текстовые/HTML-хелперы, встроенные вложения
│   │   ├── contacts/ — диалоги контактов, списки и утилиты
│   │   ├── emaildetail/ — секция вложений, inline-вложения, EmailDetailActions
│   │   └── emaillist/ — SwipeableEmailRow, SwipeRules, ThreadRows, имена в строках, превью изображений
│   ├── components/ — HtmlBodyView, RichTextEditor, SendController, FlagMenu, EmailPrinter,
│   │                 WebViewRenderer (+ RendererRecovery), TouchDirectionLock, баннеры, диалоги, DeletionProgressBar
│   ├── theme/ — Theme, AppIcons, CustomTextToolbar
│   └── utils/ — анимации, цвета аватаров, форматирование дат, ContextExtensions, LargeStateSaver, AppHaptics
│
├── domain/ — AttachmentLoader, DraftSaver, EditorInlineBudget, EmailThreads, FlagPresets, MailAlerts, MailPreferences,
│             OutgoingMail, ReadReceipts, RepositoryDraftStore, SendFailures, SentFolder, Unsubscribe
│
├── data/
│   ├── database/ — MailDatabase (Room, entity, миграции), DAO (Daos.kt, *Dao.kt), EmailFlags.kt,
│   │               PendingEmailChangeEntity.kt (очередь правок писем без связи), PendingPimChangeEntity.kt (очередь правок
│   │               задач и заметок без связи), NotificationEmailSummary.kt
│   ├── model/ — AccountServerHealth.kt (состояние здоровья сервера)
│   ├── security/ — PasswordStorage, EncryptedStoreProvider, AppLockManager, SecurityTelemetry
│   └── repository/ — репозитории, сервисы синхронизации и операций, RepositoryProvider, кэши, хелперы
│                     (в т.ч. FolderSyncGate.kt, FlagWriteTracker.kt, PendingMailChanges.kt, CategoryColorRepository.kt,
│                     RepositoryErrors.kt, EndpointClientCache.kt, CertificatePins.kt)
│
├── eas/ — протокольный слой EAS/EWS и Exchange-сервисы (в т.ч. MailFlag.kt, WbxmlParser.kt, ListUnsubscribe.kt, RssItems.kt)
├── imap/ImapClient.kt
├── pop3/Pop3Client.kt
├── shared/ — общие интерфейсы, парсеры и конвертеры JavaMail (MailClient, MailMessageParser, MessageToEntityConverter)
├── smtp/ — пустой каталог без исходников (SMTP-транспорта в коде нет)
├── network/ — HttpClientProvider, NetworkMonitor, ConnectionFailure, Rfc6125HostnameVerifier
├── sync/ — PushService, воркеры, ресиверы, уведомления, напоминания:
│           AppForegroundTracker, BackgroundWork, InitialSyncController, PingFailurePolicy, PushRestartPolicy,
│           ScheduledSend/ScheduledSendReceiver, FlagReminderReceiver, PendingMailChangesWorker, ServerHealthNotifier,
│           MailNotificationChannels, SendNotifications, NotificationHelper, SyncHelper
├── update/UpdateChecker.kt
├── util/ — утилиты дат/HTML/email/MIME/ICS/звука/идентификаторов удалённых писем, RemoteImages, EmlFiles,
│           EmailPrintDocument, AppShortcuts, AppCoroutines
└── widget/ — Glance-виджет (MailWidget, MailWidgetReceiver) и WidgetConfigActivity
```

---

## 4. Данные и хранилище

### Room

`MailDatabase` содержит 13 entity-классов и 14 абстрактных DAO-аксессоров (`accountDao`, `emailDao`, `folderDao`, `signatureDao`, `attachmentDao`, `contactDao`, `contactGroupDao`, `noteDao`, `calendarEventDao`, `taskDao`, `syncDao`, `categoryColorDao`, `pendingEmailChangeDao`, `pendingPimChangeDao`):

- **AccountEntity:** тип аккаунта, URL и альтернативный URL, режим синхронизации, метаданные сертификатов (отдельный пин для альтернативного URL), режим черновиков, интервалы синхронизации и очистки на уровне аккаунта.
- **FolderEntity:** папки Exchange/IMAP/POP3 и SyncKey каждой папки.
- **EmailEntity:** метаданные и тело письма, прочитано/важность, признак вложений, поля MDN, класс сообщения встречи, Internet Message-ID, `bcc` и `replacesServerId` черновика (черновик, заменяющий серверный, отредактированный офлайн), флаг последующих действий (`flagged`, `flagCompleted`, `flagStartDate`, `flagDueDate`, `flagReminder`), `listUnsubscribe`/`listUnsubscribeOneClick`, `requestReadReceipt`/`requestDeliveryReceipt` черновика, `conversationId` (EAS 14.0+).
- **AttachmentEntity:** вложения писем, состояние локальной загрузки, метаданные inline CID.
- **ContactEntity / ContactGroupEntity:** локальные контакты и контакты Exchange, группы, избранное, счётчики использования.
- **SignatureEntity:** HTML-подписи.
- **NoteEntity:** заметки Exchange с локальным флагом корзины.
- **CalendarEventEntity:** события календаря, встречи, поля повторения, исключения, JSON вложений, онлайн-ссылки, идентификаторы запросов на встречу.
- **TaskEntity:** поля задачи, сроки, приоритет, напоминания, владелец/исполнитель, локальный флаг корзины.
- **CategoryColorEntity** (`category_colors`, первичный ключ `accountId` + `nameKey`): цвета категорий Outlook для каждого аккаунта (`serverColor` из Master Category List на Exchange 2010+, `userColor` задаётся в приложении).
- **PendingPimChangeEntity** (`pending_pim_changes`, версия БД 51): правки задач и заметок без связи — `pendingUpdate` и/или `pendingTrash`, `kind` (TASK/NOTE), `serverSubject`, `version`. Внешний ключ только на `accounts` (каскад), по той же причине, что и у очереди писем.
- **PendingEmailChangeEntity** (`pending_email_changes`, версия БД 50): изменения писем, сделанные без связи и ещё не принятые сервером, — `flag` и/или `read`. Само значение не хранится: отправляется текущее из `emails`, поэтому несколько правок без связи уходят одним итогом. Внешнего ключа на `emails` нет намеренно: часть путей перезаписывает письма через REPLACE (DELETE + INSERT), и каскад стёр бы ожидающую правку. Внешний ключ на `accounts` — с каскадным удалением. Вместо UPSERT используется `INSERT OR REPLACE … COALESCE`, потому что SQLite на Android 8 — версии 3.18 и UPSERT не знает.

Текущая версия БД — **51**. Зарегистрированные миграции покрывают `23→51` (`MailDatabase.ALL_MIGRATIONS`, 28 миграций, по одной на версию). При отсутствии миграции для обновления БД пересоздаётся только в **debug**-сборках (`fallbackToDestructiveMigration()` под `BuildConfig.DEBUG`); в release это даёт явный сбой при открытии БД. Понижение версии (установка более старого APK) всегда пересоздаёт БД (`fallbackToDestructiveMigrationOnDowngrade()`), серверные данные синхронизируются заново.

Горячие пути виджета используют отдельные лёгкие проекции вместо полных entity:

| Сценарий | Проекция DAO / форма запроса |
|----------|------------------------------|
| Последние непрочитанные письма «Входящих» | `WidgetRecentEmailSummary` без `EmailEntity.body` |
| Текущее/ближайшее событие календаря | `WidgetCalendarEventSummary` без тела, участников и JSON вложений |
| Заголовок ближайшей задачи | `WidgetTaskSummary` без тела задачи |

Миграция 41→42 (версия БД 42) добавила индексы для путей домашнего виджета: `emails(read, dateReceived)`, `folders(type)`, `tasks(complete, isDeleted, dueDate, subject)`, `calendar_events(isDeleted, startTime, endTime)` и `calendar_events(isDeleted, endTime, startTime)`.

### DataStore

`SettingsRepository` хранит настройки UI, режим черновиков по умолчанию, настройки проверки обновлений, контрольные точки синхронизации и уведомлений, а также метки времени очистки и синхронизации не-почтовых данных по аккаунтам.

### Защищённое хранилище

`AccountRepository` хранит пароли через `data/security/PasswordStorage`, который получает хранилище от `EncryptedStoreProvider`: по умолчанию `EncryptedSharedPreferences`. Если Android Keystore недоступен или повреждён, используется `ObfuscatedSharedPreferences` (XOR с ключом, выведенным через PBKDF2; см. раздел 8) вместо открытого текста. Хеш пароля блокировки приложения (`AppLockManager`) использует тот же провайдер.

---

## 5. Архитектура протоколов Exchange

### EasClient

`EasClient` — фасад, который используют репозитории. Он владеет стабильным `DeviceId` (выводится из имени пользователя и суффикса аккаунта), общим клиентом OkHttp, `EasTransport`, `EasVersionDetector`, EAS-сервисами по областям и `EwsClient`.

### EasTransport

`EasTransport` отвечает за генерацию и разбор WBXML, обработку `PolicyKey`, повтор после HTTP `449` через Provision (двухфазный, под `provisionMutex`), выполнение EWS-запросов (сначала Basic, затем NTLM) и общую нормализацию ошибок.

`EasVersionDetector` выбирает наибольшую общую версию из `14.1`, `14.0`, `12.1`, `12.0`; Exchange 2007 SP1 отдаёт `2.5, 12.0, 12.1`, поэтому для него выбирается `12.1`.

### EWS

EWS-запросы используют SOAP и по умолчанию `RequestServerVersion="Exchange2007_SP1"` (`EwsClient.VERSION_2007`). EWS применяется там, где EAS 12.x неполон или ненадёжен: заметки, многие пути задач, серверные черновики, детали календаря/встреч/исключений/вложений и отдельные запасные пути удаления.

Рукопожатие NTLM (Type1 → Type2 → Type3) выполняется на отдельном клиенте OkHttp с `ConnectionPool(1)` и сериализуется блокировкой (`ntlmLock`): Type1 и Type3 идут по одному TCP-соединению и не перемежаются с параллельным трафиком EAS/EWS, потому что NTLM аутентифицирует соединение, а не запрос.

Извлечение тела из MIME (`MimeHtmlProcessor.extractHtmlFromMime`) делит письмо по границам multipart по RFC 2046 с рекурсией во вложенные части; запасной вариант на регулярных выражениях покрывает одночастный MIME.

### IMAP/POP3 (beta)

`ImapClient` и `Pop3Client` реализуют `shared.MailClient` (`connect`, `disconnect`, `getFolders`, `getEmails`) через JavaMail. Папки открываются только на чтение (`Folder.READ_ONLY`), читаются последние `limit` писем. IMAP берёт признаки прочитано/звезда из флагов `SEEN`/`FLAGGED` и дополнительно умеет создавать, переименовывать и удалять папки. POP3 работает только с INBOX и всегда отдаёт `read=false`, `flagged=false`. Исходящий сервер (`outgoingServer`/`outgoingPort`) только сохраняется в `AccountEntity`: SMTP-транспорта в коде нет, пустой каталог `smtp/` исходников не содержит.

---

## 6. Основные runtime-сценарии

### Запуск

1. `MailApplication.onCreate()` устанавливает Conscrypt, динамически регистрирует `ServiceWatchdogReceiver` и запускает `AppForegroundTracker` (`ProcessLifecycleOwner`).
2. `AppLockManager.init()` определяет состояние блокировки (холодный старт с заданным паролем = заблокировано); приложение блокируется снова, когда весь процесс уходит в фон (короткие переходы в системные выборщики не считаются).
3. Создаются каналы уведомлений: синхронизация, статус синхронизации, напоминания календаря и задач, исходящие, отложенная отправка, обновления, статус соединения. Каналы почты создаются по аккаунтам (`MailNotificationChannels`: группа каналов на аккаунт с каналами «новая почта» и «важные отправители»).
4. Удаляются устаревшие временные файлы (в т.ч. файлы `LargeStateSaver` старше 24 часов в `cacheDir`) и старые вложения; один раз выполняется очистка дубликатов писем; досылается очередь отправки прежних версий; если в `pending_email_changes` что-то осталось, ставится `PendingMailChangesWorker`.
5. Планируется периодическая синхронизация.
6. `PushService` запускается, если есть хотя бы один Exchange-аккаунт в режиме Push; наблюдается состояние сервера для уведомлений о потере связи (`ServerHealthNotifier`); динамические ярлыки приложения поддерживаются в актуальном состоянии.
7. `MainActivity` обрабатывает разрешения и внешние интенты, затем запускает Compose-навигацию.

### Настройка аккаунта

1. `SetupScreen` собирает данные аккаунта.
2. Exchange-аккаунты проходят `VerificationScreen`.
3. Проверка подключается к серверу и может отправить тестовое письмо.
4. Пароли передаются в памяти через `VerificationSecrets` и затем сохраняются `AccountRepository`.
5. Аккаунты IMAP/POP3 — beta, с упрощённой проверкой.
6. Серверные сертификаты, самоподписанные сертификаты, клиентские `.p12/.pfx` и метаданные пинов обрабатываются через `HttpClientProvider` и поля аккаунта.

### Синхронизация почты

1. `FolderSyncService` синхронизирует папки и сериализует операции с папками по аккаунту (`Mutex` на аккаунт).
2. `EmailSyncService` синхронизирует системные и пользовательские папки полной и инкрементальной стратегиями.
3. `MailRepository` предоставляет методы для UI и делегирует тяжёлую логику сервисам.
4. `EmailOperationsService` выполняет перемещение, удаление, окончательное удаление, «прочитано», флаги и сценарии отчётов о прочтении (Exchange сам отправляет отчёт при пометке прочитанным; отказ идёт через EWS `SuppressReadReceipt` до пометки).
5. `OutboxWorker` повторяет отправку офлайн-писем, когда появляется сеть. Каждое письмо в очереди несёт стабильный `ClientId`/`Message-ID`, одинаковый для всех повторов: на EAS 14+ сервер отбрасывает повтор с тем же `ClientId` после потерянного ответа ([MS-ASCMD] `SendMail`), на Exchange 2007 (EAS 12.1, `SendMail` с сырым MIME без `ClientId`) стабильный Message-ID служит лишь best-effort идентификатором.

#### Один EAS Sync-запрос на папку (`FolderSyncGate`)

SyncKey ([MS-ASCMD] Sync) образует цепочку: каждый ответ выдаёт следующий ключ. Два запроса с одним ключом (цикл синхронизации и изменение звезды/«прочитано») разводят ключи клиента и сервера: следующий запрос получает Status 3, и папка синхронизируется заново с нуля.

`FolderSyncGate.withFolder(folderId)` — общий на процесс `Mutex` на каждую папку (`SyncWorker` и `PushService` создают разные экземпляры сервисов, поэтому шлюз — `object`). Через него идут:

- `EmailSyncService.syncEmailsEas`: каждая итерация цикла (запрос, обработка батча локальной функцией `processSyncBatch` и сохранение SyncKey), а также начальный запрос с `SyncKey=0`;
- `EmailOperationsService`: запись флага (`setFlag`/`toggleFlag`/`retryPendingFlag`), `markAsRead` и `markAsReadBatch` (через `sendReadToServer`), пакетное окончательное удаление (`deleteBatchUnderGate`);
- `MailRepository.deleteDraftViaEas` и путь повторной инициализации ключа при удалении черновика из «Черновиков».

Внутри шлюза SyncKey перечитывается из БД, поэтому цикл продолжается с ключа, продвинутого локальным изменением. Мьютекс не реентерабельный: внутри блока шлюз той же папки брать нельзя.

Отдельной полной синхронизации «Отправленных» нет. После отправки `refreshSentFolder` делает до трёх обычных синхронизаций папки; устаревший ключ (Status 3/12) обычная синхронизация сама исправляет полной ресинхронизацией через шлюз и сверкой `reconcileSentAfterFullResync`. При другой ошибке (нет связи, сбой сервера) попытки прекращаются: полная синхронизация без связи только сбросила бы ключ.

Вне шлюза остаётся только проверка нового аккаунта (`VerificationScreen.findTestEmail`): она работает с временным `EasClient` до сохранения аккаунта, ничего не пишет в БД, и других синхронизаций этого аккаунта ещё нет.

#### Оптимистичная запись флагов (`FlagWriteTracker.shared`)

Изменение звезды или флага сразу пишется в БД, затем уходит на сервер как Sync Change.

- Каждое нажатие получает номер (ticket); на сервер отправляется только последнее нажатие, и только его итог записывается в БД. Ответы на более ранние нажатия не перещёлкивают звезду.
- Отказ сервера откатывает флаг к последнему флагу, **подтверждённому сервером**, а не к снимку до нажатия (снимок мог быть оптимистичным значением предыдущего нажатия).
- Нет связи или нет ответа — локальное значение остаётся.
- Если локальное значение ждёт отправки в очереди (`pending_email_changes`), а запись об этом письме после перезапуска процесса забыта, трекер помечает флаг как `unconfirmed` и берёт за последнее подтверждённое предполагаемое серверное значение (`serverGuess`, противоположное локальному), чтобы откат не оставил отклонённый флаг.
- Ответ синхронизации, запрошенный до записи или во время неё, не перетирает локальный флаг: `applyServerFlag` сравнивает время запроса (`requestedAt`) со временем последней записи, а пока запись идёт, лишь запоминает серверный флаг как подтверждённый.
- Записи без активных отправок удаляются из трекера через 10 минут простоя.
- Порядок блокировок: `FolderSyncGate`, затем замок трекера. Серверная часть выполняется в `NonCancellable`, поэтому уход с экрана не обрывает запрос на полпути.
- Отказ возвращает `RepositoryErrors.FLAG_REJECTED`; `EmailListViewModel` и `EmailDetailViewModel` превращают его в событие ошибки, а UI показывает локализованный тост.

#### Правки без связи (`pending_email_changes` → `PendingMailChangesWorker`)

Откатывается только явный отказ сервера в самом изменении (`isChangeRejected`): `Change Status=N` или Status коллекции 4/6/7/8. Устаревший SyncKey (3), изменение иерархии папок (12), сбой сервера (5), HTTP-ошибки и отсутствие связи оставляют локальное значение, помечают его в `pending_email_changes` и возвращают успех: звезда не пропадает, письмо, прочитанное без связи, не становится снова непрочитанным.

- `PendingMailChangesWorker` — уникальная работа (`ExistingWorkPolicy.KEEP`) с условием `NetworkType.CONNECTED`, экспоненциальным backoff от 30 с и не более чем 20 попытками. Отправка идёт тем же путём, что и нажатие (`EmailOperationsService.retryPendingFlag`/`retryPendingRead` через `PendingMailChanges.flush`, который подхватывает и правки, появившиеся во время прохода).
- `KEEP` выбран намеренно: сбой отправки внутри воркера ничего не ставит заново, поэтому цикла без паузы нет; остаток уходит повтором с нарастающей паузой. После 20 неудачных попыток очередь остаётся в БД, а `enqueueIfPending` вызывается из `SyncWorker` и `MailApplication`.
- Пока поле ждёт отправки, синхронизация его не перезаписывает: `EmailDao.updateReadStatusFromServer` и `updateFlagFromServer` (через `EmailFlags.saveServerFlag`) содержат условие `NOT EXISTS` по очереди.
- При смене ServerId письма (EAS 12.1 после `SyncKey=0`) запись очереди переезжает вместе с ним: `PendingEmailChangeDao.rename`.

#### Окончательное удаление

1. Перед EAS Sync Delete папка синхронизируется обычной `syncEmails` (`syncAndResolve`), которая обрабатывает серверные изменения, а письма заново находятся по содержимому (`resolveEmailIds`): на EAS 12.1 ServerId могут смениться после `SyncKey=0`.
2. Письма читаются лёгкой проекцией `EmailServerRef` (`EmailDao.getServerRefs`, без тел; запросы пачками по 500 из-за лимита SQLite в 999 переменных).
3. Пакетный Delete выполняется под `FolderSyncGate` с ключом из БД (`deleteBatchUnderGate`); Delete идёт с `GetChanges=0`, новый ключ сохраняется.
4. При `INVALID_SYNCKEY` или `DELETE_NOT_APPLIED` выполняется ещё одна синхронизация и один повтор.
5. Поэлементный запасной вариант через EWS запускается, если EAS не удалил письма (или ключа нет вовсе — только на Exchange 2007). Сбои уровня соединения (`ConnectionFailure.connectionLevel`) его пропускают: иначе вышло бы N таймаутов подряд без шанса на успех, итог покажет ошибка `DELETE_FAILED`.
6. На Exchange 2007 `EasEmailService.deleteEmailsPermanentlyBatch` при неудаче возвращает `DELETE_NOT_APPLIED` и не сбрасывает состояние синхронизации запросом `Sync(SyncKey=0)`.
7. Удаление черновика (`MailRepository.deleteDraftViaEas`) тоже идёт под шлюзом и сохраняет вернувшийся ключ. Цикл синхронизации для обновления ключа здесь не запускается: он потребил бы Add нового черновика без обработки.

#### Определение «сирот» после полной пересинхронизации

Используются лёгкие проекции DAO, чтобы не загружать полные `EmailEntity` для больших папок:

| Сценарий | Проекция |
|----------|----------|
| Сверка «Отправленных» после полной пересинхронизации (`reconcileSentAfterFullResync`) | только `List<String>` идентификаторов |
| Сверка обычной папки после полной пересинхронизации (`reconcileGenericFolderAfterFullResync`) | только `List<String>` идентификаторов |
| Сопоставление ServerId по содержимому после сброса `SyncKey=0` (`resolveEmailIds`) | `EmailDedupInfo` |

Сверка «Черновиков» (`reconcileDraftsAfterFullResync`) работает с полными `EmailEntity`, так как нужна миграция тела и `bodyType`.

### Календарь, задачи, заметки, контакты

1. Репозитории владеют локальным состоянием Room и операциями UI.
2. EAS/EWS-сервисы владеют деталями протокола.
3. Напоминания календаря и задач планируются через ресиверы и воркеры Android.
4. Локальные флаги `isDeleted` и `DeletedIdsTracker` не дают удалённым элементам «воскреснуть», пока состояние Exchange догоняет клиент.
5. Вложения календаря хранятся как JSON-метаданные в `CalendarEventEntity.attachments`; байты файлов скачиваются только по требованию.
6. Серии повторяющихся событий хранят метаданные вложений в мастер-событии/исключении, а виртуальные вхождения используют их повторно, не дублируя данные.
7. Окончательное удаление события календаря требует успеха на сервере: локальное удаление и очистка корзины выполняются только после подтверждённого удаления через EAS/EWS.
8. Удаление встречи участником отправляет `MeetingResponse` или EWS `DeclineItem` до удаления, чтобы Calendar Repair Assistant не вернул встречу.

#### Правки задач и заметок без связи (`pending_pim_changes`)

Изменение задачи («выполнено», тема, сроки) или заметки и перенос в корзину без связи сохраняются на устройстве и ставятся в очередь `pending_pim_changes`; отправляет их тот же `PendingMailChangesWorker` (одна уникальная работа, `NetworkType.CONNECTED`, экспоненциальная пауза).

- **Когда откладывать** (`OfflineWrites`): сети нет — к серверу не обращаемся вовсе (без таймаута и лишней батареи); запрос не дошёл — метка `[NET:…]` уровня соединения, HTTP 5xx (`isConnectionLevelError`) или сеть пропала во время запроса (путь EWS на Exchange 2007 при обрыве возвращает ошибку без метки). Отказ сервера — ошибка, как раньше.
- **Значение не хранится**: отправляется текущее состояние задачи или заметки, несколько правок уходят одним итогом. `serverSubject` — тема до первой правки без связи: EWS на Exchange 2007 находит задачу по теме. `version` растёт с каждой правкой, отметка снимается только при той же версии — правка во время отправки не теряется.
- **Порядок**: правка со связью за правкой той же записи, ждущей отправки, тоже встаёт в очередь — иначе сервер получил бы старую версию последней.
- **Протокол выбирается при отправке**: те же `EasClient.updateTask`/`deleteTask`/`updateNote`/`deleteNote`, что и со связью (EAS 14+, EWS на Exchange 2007).
- **Синхронизация** не перетирает запись с ожидающей правкой, не считает её дублем и не возвращает из корзины. Удаление на сервере по-прежнему сильнее. После отправки — одна синхронизация на аккаунт и вид, а не на каждую запись: она подтягивает новые ServerId (перенос в Deleted Items, UpdateItem на Exchange 2007).
- **Корзина**: восстановление до отправки — только на устройстве, сервер не трогается. Окончательное удаление, очистка корзины и сброс («Сбросить и синхронизировать») сначала отправляют очередь; без связи — ошибка `PENDING_CHANGES_NOT_SENT`, данные не теряются.
- Назначение задачи (письмо исполнителю) без связи не откладывается — это ошибка.
- UI: событие `SavedOffline` закрывает диалог и показывает «Сохранено на устройстве. Отправится на сервер, когда появится связь».
- Создание задач и заметок без связи пока не поддержано.

### Уведомления

1. `PushService` получает изменения по EAS Ping.
2. `SyncWorker` тоже проверяет уведомления при периодической и ручной синхронизации.
3. Оба пути используют `NotificationHelper.notificationMutex`, чтобы не создавать дублей уведомлений.
4. Запросы для уведомлений используют лёгкие проекции (`NotificationEmailSummary`) и ограниченные по размеру пакеты действий (показ до 20 писем, до 25 писем в действии «прочитано»).
5. Уведомления о почте идут в каналы по аккаунтам (`MailNotificationChannels`); звук, важность и «Не беспокоить» настраиваются по каждому аккаунту в системных настройках. Тихие часы и важные отправители (`domain/MailAlerts`, глобальные настройки) делают письмо бесшумным в тихие часы, если отправитель не из списка важных.
6. `ServerHealthNotifier` показывает по одному уведомлению на аккаунт при потере связи с сервером; напоминания по флагам доставляет `FlagReminderReceiver`.

### Виджет рабочего стола

1. `MailWidget` — Glance `GlanceAppWidget` с `SizeMode.Responsive` (три фиксированных размера), чтобы не ронять лаунчер: `SizeMode.Exact` строит `RemoteViews` с картой размеров, которая вызывает падение лаунчера HyperOS 2.0.
2. Данные виджета загружаются на `Dispatchers.IO` из Room и DataStore через `applicationContext`.
3. Запросы последней почты, ближайшей задачи и события календаря используют проекции и индексы, рассчитанные на виджет (см. раздел 4).
4. `updateMailWidget(context)` сериализует `MailWidget().updateAll()` процессным мьютексом (`widgetUpdateMutex`), чтобы пути синхронизации, уведомлений, аккаунтов и персонализации не обновляли виджет параллельно.
5. Нажатия идут через deep link'и/extras в `MainActivity`; ручная синхронизация использует `SyncAlarmReceiver.ACTION_SYNC_NOW` и делегируется `SyncWorker`. У каждого кликабельного элемента свой **`data` URI** (`iwomail://widget/…`, `iwomail://email/{id}`), потому что `PendingIntent.filterEquals` игнорирует extras и интенты одинаковой формы иначе делили бы одну цель нажатия.
6. **Содержимое зависит от ширины.** `Row` в Glance — горизонтальный `LinearLayout` без переноса, поэтому всё, что шире виджета, обрезается. Нижняя строка действий на узких размерах рисует меньше элементов (порог `size.width >= 300.dp`: 4 аватара аккаунтов против 2, метка последней синхронизации только на широком виджете), а метка занимает `defaultWeight()` с `maxLines = 1` и усекается, не вытесняя кнопки. Время в списке писем форматируется через `isSameLocalDay`: сегодня — время, иначе — дата.

### Обновления и откат

1. `UpdateChecker` читает `update.json` из GitHub.
2. URL APK выбирается по ABI устройства с запасным универсальным APK.
3. Скачивание идёт общими клиентами OkHttp с длинными таймаутами (канал обновлений использует системные CA и `MODERN_TLS`).
4. Установка идёт через `FileProvider`.
5. Подготовка отката копирует APK в `Downloads/iwomail rollback` (имя `iwomail-rollback-v<версия>.apk`) через MediaStore, после чего пользователь удаляет текущую версию и устанавливает сохранённую.

---

## 7. Фоновые компоненты

| Компонент | Назначение |
|-----------|------------|
| `PushService` | Foreground-сервис Direct Push (`foregroundServiceType="dataSync"`): адаптивный heartbeat EAS Ping (старт 480 с, границы 120–1800 с, шаг 60 с; при Ping `Status=5` принимается `HeartbeatInterval` сервера в этих границах по [MS-ASCMD], иначе heartbeat уменьшается вдвое), задачи по аккаунтам; реакцию на серии сбоев задаёт `PingFailurePolicy` |
| `SyncWorker` | Периодическая и ручная синхронизация, проверка уведомлений, автоочистка, проверка работоспособности `PushService`, постановка `PendingMailChangesWorker` (`enqueueIfPending`); параллельный запуск пропускается (`doWorkMutex.tryLock()`) |
| `OutboxWorker` | Отправка офлайн-очереди (уникальная работа `outbox_send`, до 10 попыток) |
| `PushRestartWorker` | Перезапуск Push после сбоев |
| `BootReceiver` | Запуск синхронизации и Push после перезагрузки или обновления пакета |
| `SyncAlarmReceiver` | Запасной вариант на `AlarmManager` для агрессивных прошивок OEM; обрабатывает и ручную синхронизацию (`ACTION_SYNC_NOW`) |
| `ServiceWatchdogReceiver` | Сторожевой ресивер `PushService` на события экрана, разблокировки и питания. **Регистрируется динамически в `MailApplication`** (`ContextCompat.registerReceiver` + `RECEIVER_NOT_EXPORTED`): `SCREEN_ON`/`USER_PRESENT`/`POWER_CONNECTED` — неявные широковещательные сообщения, которые на API 26+ не доставляются ресиверам из манифеста. Перезапуск идёт через `PushService.requestRestart`: точный будильник + `getForegroundService` + запасной вариант на WorkManager, под ограничителем частоты `PushRestartPolicy` (circuit breaker с экспоненциальной паузой; при размыкании восстановление отдаётся `SyncWorker`) |
| `MailNotificationActionReceiver` | Действия в уведомлениях о почте (пометка прочитанным делегируется `MarkEmailReadWorker`) |
| `CalendarReminderReceiver` | Действия напоминаний календаря (напоминание, прочитано, отложить на 5 минут) |
| `TaskReminderReceiver` | Действия напоминаний задач (завершение ставит `MarkTaskCompleteWorker`) |
| `FlagReminderReceiver` | Напоминания по помеченным письмам (`tasks:ReminderTime`); будильники только для ближайших 50 напоминаний (`MAX_SCHEDULED`), в момент срабатывания флаг перепроверяется по БД |
| `ScheduledSendReceiver` | Точный будильник отложенного письма: запускает отправку в срок (`ScheduledSend`) |
| `ScheduledEmailWorker` | Отправка отложенного письма (`ui/screens`, имя класса сохранено для уже поставленных задач); он же страховка WorkManager к точному будильнику; временные ошибки повторяются (до 8 попыток), иначе письмо попадает в «Черновики» с уведомлением |
| `MarkEmailReadWorker` | Уникальная работа пометки письма прочитанным (до 3 повторов) |
| `MarkTaskCompleteWorker` | Завершение задачи из уведомления; ошибка сервера повторяется (до 10 попыток, экспоненциальный backoff), а не проглатывается |
| `PendingMailChangesWorker` | Отправка звёзд и «прочитано», сделанных без связи (`pending_email_changes`), когда появляется сеть |
| `RescheduleRemindersWorker` | Перестроение будильников напоминаний календаря, задач и флагов после перезагрузки или обновления |
| `MailWidgetReceiver` | Ресивер Glance-виджета (`widget/MailWidget.kt`) |

В манифесте объявлены `PushService`, `SystemForegroundService` WorkManager (`dataSync`), `FileProvider`, ресиверы `BootReceiver`, `SyncAlarmReceiver`, `CalendarReminderReceiver`, `TaskReminderReceiver`, `ScheduledSendReceiver`, `FlagReminderReceiver`, `MailNotificationActionReceiver`, `MailWidgetReceiver` и активности `MainActivity`, `WidgetConfigActivity`. Воркеры WorkManager в манифесте не объявляются. Экспортированы только `BootReceiver` и `MailWidgetReceiver` (среди ресиверов), остальные ресиверы — `exported="false"`.

---

## 8. Безопасность и сеть

`HttpClientProvider` централизует создание клиентов OkHttp, чтобы не допускать утечек пулов соединений и потоков.

Поддерживаемые сетевые режимы и режимы безопасности:

- обычное доверие системе: системные корневые сертификаты, а для Exchange-клиентов дополнительно сертификаты, установленные пользователем (`AndroidCAStore`), — это нужно самоподписанным Exchange 2007 SP2;
- режим «принимать любые сертификаты» для устаревших/самоподписанных окружений;
- собственный серверный сертификат (`certificatePath`);
- клиентский сертификат PKCS#12 с mTLS;
- закрепление сертификата (certificate pinning) с сохранённым SHA-256 и метаданными (отдельный пин для альтернативного URL); пин проверяется после стандартной валидации;
- провайдер Conscrypt для совместимости со старым TLS; Exchange-клиенты используют `ConnectionSpec.COMPATIBLE_TLS` (TLS 1.0–1.3 в одном рукопожатии), канал обновлений — `MODERN_TLS` и только системные корни (`getSystemTrustOnlyClient`).

Проверка имени хоста: при импортированном серверном сертификате (`certificatePath`) клиент использует `Rfc6125HostnameVerifier` (SAN dNSName/iPAddress по RFC 6125, откат на CN только если в сертификате нет SAN — типично для самоподписанных сертификатов Exchange 2007); режим «принимать любые сертификаты» отключает проверку по выбору пользователя; в остальных случаях действует стандартный верификатор OkHttp.

Открытый трафик запрещён глобально (`res/xml/network_security_config.xml`: `cleartextTrafficPermitted="false"`, доверие только системным сертификатам в `base-config`). Поэтому Exchange (EAS через OkHttp) работает только по HTTPS: экран настройки не предлагает HTTP для Exchange-аккаунтов и принудительно ставит `useSSL=true` при сохранении. IMAP/POP3 идут через JavaMail, а не через `HttpClientProvider`: в свойствах сессии заданы `ssl.trust=*` и `ssl.checkserveridentity=false`, то есть сертификат сервера не проверяется, а порт без SSL (143/110) остаётся доступным.

`AccountRepository` кэширует `EasClient` по аккаунту (`EndpointClientCache`) и сбрасывает кэш при изменении сертификата или настроек сервера. Для ошибок уровня соединения поддержан переход на альтернативный URL Exchange с последующей пробой и возвратом на основной.

### Отображение тела письма (WebView)

HTML тел писем очищается перед загрузкой в `WebView`:

- `HtmlUtils.sanitizeEmailHtml` удаляет:
  - блоки `<script>` и одиночные теги;
  - контейнеры плагинов `<iframe>`, `<object>`, `<embed>`, `<applet>` (парные и самозакрывающиеся формы);
  - редиректы `<meta http-equiv="refresh">`;
  - встроенные обработчики событий `on*="..."`/`on*='...'`/без кавычек;
  - URI `javascript:` и `data:text/html` в атрибутах `href`/`src`/`action`/`formaction`/`xlink:href`.
- Очистка вызывается до показа: `EmailDetailScreen` (просмотр и печать) и `EventDetailDialog`; `RichTextEditor` дополнительно очищает вставляемый HTML через `stripDangerousTags`.
- `HtmlBodyView` (`ui/components`, используется `EmailDetailScreen` и календарным `EventDetailDialog`) загружает очищенный HTML через `loadDataWithBaseURL(null, ...)`; `EmailPrinter` делает то же для печати (в отдельном WebView без JavaScript, без доступа к файлам). Нулевой baseURL блокирует кросс-доменные запросы, кражу cookie/localStorage и service worker'ы и дополняет блок-лист на уровне тегов. В `HtmlBodyView` отключены `allowFileAccess`, `allowContentAccess`, `domStorageEnabled`; переходы по ссылкам перехватываются (`mailto` → `onMailto`, `tel` → набор номера, остальное → внешний браузер).
- **Жесты в просмотрщике.** WebView `HtmlBodyView` имеет высоту своего содержимого и не прокручивается вертикально; вертикальную прокрутку выполняет родительский `Column.verticalScroll`. `TouchDirectionLock` решает, кому принадлежит жест, один раз за жест, после выхода за системный порог касания (`touchSlop`): горизонтальное движение и масштабирование (второй палец) отдаются WebView (`requestDisallowInterceptTouchEvent(true)`), вертикальное — экрану. `overScrollMode` всегда `OVER_SCROLL_NEVER` (нет растяжения края Android 12+ при вертикальном дрейфе). Скрипт замера высоты (`MEASURE_CONTENT_JS`) учитывает и высоту `documentElement.getBoundingClientRect()`: поля последнего блока проходят сквозь `body`, но не сквозь `html`, и без этого WebView оказывался бы на несколько пикселей ниже страницы и прокручивался внутри.
- **Смерть процесса рендерера.** Каждый WebView (`HtmlBodyView`, `RichTextEditor`, `EmailPrinter`) переопределяет `onRenderProcessGone` и возвращает `true` (иначе система завершает всё приложение). Общие помощники в `WebViewRenderer.kt`: `WebViewRenderer` (`markGone`/`isGone` через `R.id.webview_renderer_gone`, `release`, `crashed`) и `RendererRecovery` (пересоздать WebView после первого падения; второе падение на том же содержимом останавливает попытки; выгрузка рендерера системой ради памяти падением не считается). Запасной вариант просмотрщика после второго падения — `HtmlBodyTextFallback`: простой выделяемый текст письма, извлекаемый вне главного потока. Запасной вариант редактора — сообщение вместо редактора (`isContentUnavailable`), текст остаётся в `latestHtml` и по-прежнему отправляется и сохраняется. Для печати ожидающий WebView уничтожается.
- **Удалённое содержимое.** Картинки и шрифты из интернета по умолчанию заблокированы (трекинговые пиксели): CSP из `RemoteImages.contentSecurityPolicy` разрешает только изображения `data:`/`cid:`/`blob:` и шрифты `data:`, пока удалённое содержимое не разрешено; дополнительно WebView ставит `blockNetworkImage`/`blockNetworkLoads`. Разрешить можно для конкретного письма («Показать»), для доверенных отправителей или всегда (настройка).
- **CSP (глубокая защита).** Оба WebView внедряют `Content-Security-Policy` с `script-src 'nonce-<случайное значение>'` (без `'unsafe-inline'`): просмотрщик (`HtmlBodyView`) и редактор (`RichTextEditor`, собственный скрипт которого помечен nonce; вызовы `evaluateJavascript` и `@JavascriptInterface` выполняются вне CSP). Это блокирует внедрённые inline-обработчики (`onerror`/`onload`) и URI `javascript:`, даже если обход регулярных выражений удался, в том числе на пути **вставки** в редакторе. Редактор дополнительно очищает внедряемый HTML дважды: на стороне Kotlin `stripDangerousTags` (делегирует `sanitizeEmailHtml`) перед `evaluateJavascript` и на стороне JS `sanitizeHtml` (отсоединённый `div`, удаление `on*`/`javascript:`) перед записью в `innerHTML`.

### Очистка заголовков исходящего MIME

При сборке сырого MIME для `SendMail`/`SmartForward` и приглашений на встречи значения адресных заголовков (`From`/`To`/`Cc`/`Bcc`) проходят через `stripHeaderCrlf` (единая функция в `EasXmlTemplates.kt`), которая удаляет одиночные CR/LF. Иначе внедрённый CR/LF в теле заголовка (RFC 5322 §2.2) позволил бы добавить произвольные заголовки (`\r\nBcc: …`). UI создания письма уже нормализует получателей, но протокольный слой на это не полагается. Собственное MDN-сообщение клиент не собирает: отчёты о прочтении отправляет Exchange (см. `domain/ReadReceipts.kt`). Покрыто `EasMimeHeaderSanitizeTest`.

Заголовок `Subject` кодируется как RFC 2047 encoded-word и при большой длине разбивается на несколько слов, разделённых CRLF и пробелом (`encodeMimeHeaderText` на основе `mimeEncodedWord` и `chunkByUtf8Bytes` в `EasXmlTemplates.kt`; тот же кодировщик использует сборка приглашений на встречи в `EasClient`), чтобы строка заголовка не превышала предел RFC 5322 в 998 октетов; многобайтовые символы UTF-8 между словами не разрезаются. Покрыто `EasMimeSubjectEncodingTest`.

### Хранение паролей

Пароли хранит `data/security/PasswordStorage` через `EncryptedSharedPreferences` (ключи AES256-SIV, значения AES256-GCM, мастер-ключ в Android Keystore), которое создаёт `EncryptedStoreProvider`. Если Keystore недоступен или повреждён, провайдер переключается на `ObfuscatedSharedPreferences`: значения XOR-ятся с ключом, выведенным PBKDF2-HMAC-SHA256 (10 000 итераций, статическая соль) из `ANDROID_ID`. Это обфускация, а не шифрование. Переключение фиксирует `SecurityTelemetry` и один раз показывает пользователю тост. Режим fail-closed (`SecurityTelemetry.setFailClosedMode`, отказ сохранять пароли в запасное хранилище) есть в коде, но переключателя в настройках нет, поэтому он выключен. Массивы символов паролей обнуляются (`fill('\u0000')`) после использования в `HttpClientProvider`.

Пароль блокировки приложения (`AppLockManager`) хранится только как хеш PBKDF2-HMAC-SHA256 (600 000 итераций, случайная соль 16 байт, формат `v1:iterations:b64salt:b64hash`, сравнение за постоянное время). Разблокировка отпечатком использует `BiometricPrompt` (`BIOMETRIC_WEAK`), поэтому `MainActivity` наследует `FragmentActivity`.

---

## 9. Совместимость с Exchange 2007 SP1

Exchange 2007 SP1 отдаёт EAS 12.1. Приложение считает поведение Exchange 2007 первоклассным ограничением:

| Функция | Основной путь | Запасной вариант / детали |
|---------|---------------|---------------------------|
| Синхронизация, отправка, перемещение и флаги почты | EAS | EWS для отдельных путей окончательного удаления и ошибок |
| Синхронизация папок и CRUD | EAS FolderSync | Сериализация по аккаунту для защиты состояния SyncKey |
| Синхронизация содержимого папки и локальные изменения | EAS Sync | Один Sync-запрос на папку за раз (`FolderSyncGate`), SyncKey перечитывается из БД под шлюзом |
| Флаги последующих действий | EAS Sync Change `<Flag>` ([MS-ASEMAIL], EAS 12.0+) | Снятый флаг = пустой `<Flag/>`; флаг без дат, отклонённый со Status 6, повторяется один раз с датами «сегодня» |
| Цепочки писем | `email2:ConversationId` на EAS 14.0+ | Exchange 2007 его не отдаёт: цепочка по теме (только если в цепочке есть ответ или пересылка) |
| Отчёты о прочтении | Сервер отправляет их при пометке прочитанным (EAS/EWS) | Отказ: EWS `SuppressReadReceipt` до пометки прочитанным |
| Цвета категорий | Master Category List (Exchange 2010+) | Exchange 2007: стандартные имена Outlook или детерминированный цвет по имени; ручное назначение приоритетнее |
| Direct Push | EAS Ping | Адаптивный heartbeat; при `Status=5` принимается `HeartbeatInterval` сервера |
| Контакты | EAS + поиск по GAL | Контакты Exchange только для чтения (Sync читает лишь Add, кнопки правки и удаления есть только у личных); поддержаны и локальные контакты |
| Заметки | EWS на Exchange 2007 | Класс Notes в EAS появился в 14.0+ |
| Задачи | EAS/EWS в зависимости от версии и пути | EWS для пробелов EAS в задачах Exchange 2007 |
| Синхронизация календаря | Основной путь EAS | EWS дополняет детали, вложения и пути встреч |
| Вложения календаря | EWS CreateAttachment/GetAttachment | Нужны EWS ItemId, ItemId мастера серии и последовательность RootItemChangeKey |
| Удаление событий календаря | EAS Sync Delete / EWS DeleteItem | Догоняющий SyncKey, локальное удаление после подтверждения сервера, DeclineItem/MeetingResponse для участников |
| Черновики | Серверные черновики через EWS или локальный режим | Двойное тело для inline-картинок |

Правила совместимости, зафиксированные в коде:

- Исходящий EWS SOAP использует объявленные префиксы пространств имён и `Exchange2007_SP1`.
- Разбор ответов EWS должен допускать XML-элементы с префиксами и без них.
- Цепочки EAS Sync календаря не должны навязывать несогласованный `FilterType`.
- EAS-операции, которым нужен актуальный SyncKey, при необходимости выполняют догоняющую синхронизацию перед отправкой команд.
- Удаление встречи различает пути участника, организатора и не-встречи, чтобы Calendar Repair Assistant не вернул её.
- Окончательное удаление и очистка корзины календаря не удаляют локальные записи, которые есть на сервере, пока серверное удаление не удалось.
- Вложения повторяющихся событий используют ссылки на метаданные и разрешение ItemId мастера через EWS вместо дублирования файлов на каждое вхождение.
- Sync-запросы одной папки (цикл синхронизации и локальные изменения флага/«прочитано») никогда не идут параллельно с одним SyncKey: они проходят через `FolderSyncGate`, иначе следующий запрос получит Status 3 и папка синхронизируется заново.
- Флаги писем (`eas/MailFlag.kt`, `EasEmailService.setFlag`): при каждом изменении пишется весь `<Flag>`, потому что отсутствующий дочерний элемент сервер удаляет ([MS-ASEMAIL], неявные удаления); снятый флаг отправляется как пустой `<Flag/>` ([MS-ASEMAIL] Flag: «SHOULD be included as an empty container element»); четыре элемента дат (`tasks:StartDate`/`DueDate`/`UtcStartDate`/`UtcDueDate`) отправляются все вместе или не отправляются. Часть серверов Exchange отклоняет активный флаг без дат со Sync Change Status 6; тогда запись повторяется один раз с датами «сегодня» (`MailFlags.dueToday`, как быстрый флаг Outlook). Новый SyncKey из ответа сохраняется даже при отклонении самого изменения (`FlagWrite.syncKey`: сервер выдаёт новый ключ и сообщает в `Responses` только о неудавшихся изменениях).
- Кодирование WBXML (`eas/WbxmlParser.kt`): пространство имён пустого элемента (`<Flag xmlns="Email"/>`) действует только на него самого и не переходит на следующие за ним элементы.

---

## 10. Ключевые проектные решения

### Ручной DI

Вместо Hilt/Dagger приложение использует `RepositoryProvider`: минимальный набор зависимостей, простой жизненный цикл и отладка, общие репозитории-синглтоны и централизованное создание клиентов в `AccountRepository`.

### Offline-first

UI читает Room/Flow, сетевые операции обновляют локальную БД. Приложение остаётся рабочим при нестабильном соединении с Exchange, а состояние UI во время долгих синхронизаций предсказуемо.

### Декомпозиция сервисов

Крупная протокольная логика разделена по специализированным сервисам: `EasTransport` отвечает за транспорт, Provision и аутентификацию; сервисы по областям — за почту, папки, календарь, задачи, заметки, черновики, контакты и вложения; сервисы уровня репозиториев — за синхронизацию и операции.

### Модель тела черновика

Черновики с inline-картинками хранят два представления тела:

| Представление | Назначение |
|---------------|------------|
| Серверное тело с `cid:` | Совместимо с отображением в Outlook/Exchange |
| Локальное тело с `data:image/...base64` | Мгновенный показ и редактирование в приложении |

Серверный режим сохраняет черновики через EWS (с вложениями — одним MIME-запросом `CreateItem`). Локальный режим хранит черновики только в Room (идентификаторы вида `local_draft_…`) и помечен как beta.

### Защиты от гонок

- `SyncWorker` не запускается параллельно сам с собой: `doWorkMutex.tryLock()` пропускает второй запуск.
- `PushService` сериализует синхронизацию по аккаунтам (`accountSyncMutexes`).
- Операции с папками сериализуются по аккаунту (`FolderSyncService`) для защиты состояния SyncKey.
- EAS Sync-запросы сериализуются по папкам (`FolderSyncGate`); оптимистичные записи флагов отслеживает `FlagWriteTracker` (порядок блокировок: шлюз папки, затем трекер).
- Репозитории календаря, задач и заметок используют блокировки синхронизации по аккаунту; удаление, восстановление, окончательное удаление и очистка корзины календаря берут ту же блокировку, что и синхронизация календаря.
- `EasTransport` выполняет Provision под `provisionMutex`; NTLM-рукопожатие `EwsClient` идёт под `ntlmLock`.
- Показ уведомлений использует общий мьютекс для путей Push и воркеров (`NotificationHelper.notificationMutex`).
- Отложенная отправка защищена блокировкой на каталог письма (`ScheduledSend.lock`): будильник, страховка и отмена не отправят письмо дважды.
- Обновления Glance-виджета сериализуются через `widgetUpdateMutex`.

### Сохранение состояния UI при смене конфигурации

Compose-экраны сохраняют редактируемое пользователем состояние при повороте и смерти процесса и не дают загрузчикам перезаписать его:

- Редактируемые поля используют `rememberSaveable`. Крупные тела используют `createLargeStringSaver` (`ui/utils/LargeStateSaver.kt`): строки длиннее 10 000 символов пишутся в файл экземпляра в `cacheDir` (`compose_state_<tag>_<uuid>.tmp`), а в сохранённое состояние попадает только путь. Всё состояние Activity уходит одной транзакцией Binder с общим на процесс буфером около 1 МБ; больший объём приводит к падению с `TransactionTooLargeException` при сворачивании. Файл удаляется при восстановлении; файлы старше 24 часов (экран закрыт без восстановления) удаляются при старте приложения (`MailApplication.cleanupStaleTempFiles`). Используется в `ComposeScreen` (тело и тело черновика), `CreateEventDialog` и `RichTextEditor`.
- Одноразовые загрузчики защищены от повторного запуска при смене конфигурации флагом `rememberSaveable`, например `replyLoaded` / `forwardLoaded` / `signatureInitialized` (`ComposeScreen`) и `datesInitialized` (диалог создания задачи в `TasksScreen`); эффект сразу выходит, если флаг установлен. `TasksScreen` и `NotesScreen` берут признак `isInitialLoadDone` из состояния своих ViewModel.
- Состояние `RichTextEditorController` (`latestHtml` и параметры форматирования) сохраняется собственным `Saver`; HTML редактора попадает в сохранённое состояние один раз (поле `latestHtml` через `createLargeStringSaver`).
- `RichTextEditor` очищает и нарезает крупный HTML вне главного потока (`setHtmlAsync`: `Dispatchers.Default`, куски по 200 000 символов, которые страница читает через `@JavascriptInterface` вместо одного огромного `evaluateJavascript`). Новый вызов отбрасывает результат прежнего; признак `isPreparingHtml` не даёт `awaitCurrentHtml` вернуть пустой текст, пока HTML ещё готовится; при `OutOfMemoryError` показывается сообщение вместо падения.
- При смерти рендерера в редакторе (`onRenderProcessGone`) WebView пересоздаётся один раз; после второго падения редактор заменяется сообщением, а текст остаётся в `controller.latestHtml` и сохраняется/отправляется оттуда.

### Обработка исключений в корутинах (устойчивость к падениям)

`viewModelScope` использует `SupervisorJob` без `CoroutineExceptionHandler`: любое неперехваченное исключение в `viewModelScope.launch` **роняет приложение**. Поэтому запуски корутин в ViewModel следуют обязательному защитному шаблону:

```kotlin
viewModelScope.launch {
    _uiState.update { it.copy(isLoading = true) }
    try {
        val result = withContext(ioDispatcher) { repo.operation() }
        when (result) {
            is EasResult.Success -> sendEvent(Event.Success)
            is EasResult.Error -> sendEvent(Event.Error(result.message))
        }
    } catch (e: CancellationException) {
        throw e  // кооперативная отмена должна распространяться
    } catch (e: Exception) {
        sendEvent(Event.Error(e.message ?: "Unknown error"))
    } finally {
        _uiState.update { it.copy(isLoading = false) }
    }
}
```

Правила:
- **`CancellationException` всегда пробрасывается дальше**: так требует кооперативная отмена корутин.
- **Остальные исключения перехватываются** (`RuntimeException`, `SQLiteException`, `IllegalArgumentException`, сетевые ошибки) и превращаются в события `Error` (тост) вместо падения процесса.
- **Флаги загрузки сбрасываются в `finally`** (`isDeleting`, `isMoving`, `isRestoring`, `isLoadingBody` и т. п.), поэтому UI не застревает в состоянии загрузки.
- **Выделение очищается в `finally`** (пакетные операции `EmailListViewModel`), в одной точке вместо дублирования в `try` и `catch`.

Шаблон применён в `EmailListViewModel`, `EmailDetailViewModel`, `SearchViewModel`, `NotesViewModel`, `TasksViewModel`, `UserFoldersViewModel`. `SyncCleanupViewModel` не имеет канала ошибок (операции `set*` — fire-and-forget), поэтому вместо дублирования `try/catch` использует один приватный помощник `launchSafe { }` с теми же ветками `catch(CancellationException){throw}` / `catch(Exception){ Log.e }`. `ScheduledMailViewModel` запускает операции в `viewModelScope` без этой обёртки. Перехват исключений в ViewModel покрыт unit-тестами `*ViewModelTest`.

Долгоживущие реактивные запуски **наблюдения** (подписки на потоки Room по аккаунту, папкам и письмам) в `EmailListViewModel`, `EmailDetailViewModel`, `NotesViewModel`, `TasksViewModel`, `UserFoldersViewModel` и `ComposeViewModel` дополнительно передают `loggingExceptionHandler(tag)` в `viewModelScope.launch(handler) { … }`: ошибка потока Room в коллекторе логируется, а не роняет процесс.

**Экраны без MVVM**, которые запускают работу из `rememberCoroutineScope()`, используют `rememberSafeScope()` (`ui/components/ComposableUtils.kt`): безопасный по отношению к падениям scope на `SupervisorJob` (сбой одной операции не отменяет соседей) + `CoroutineExceptionHandler` (необработанное исключение логируется, а не уходит в процесс). Его жизненный цикл совпадает с `rememberCoroutineScope` (supervisor — дочерний `Job` базового scope). Помощник `rememberSyncScope()` получил такой же обработчик.

То же верно для долгоживущих **не-UI** scope: `MailApplication`, сервисы Push и синхронизации, ресиверы будильников и загрузки, `InitialSyncController`, `SendController` и часть репозиториев создают scope через `supervisedScope(dispatcher, tag)` (`util/AppCoroutines.kt`) — `SupervisorJob` + `CoroutineExceptionHandler` вместо голого `CoroutineScope(SupervisorJob() + dispatcher)`. Неперехваченная ошибка одной фоновой задачи (включая `OutOfMemoryError`, который `catch(Exception)` не ловит) логируется, а не роняет процесс. `GlobalScope` и `runBlocking` в коде не используются (упоминаются только в комментариях).

**Очистка в `onDispose` с учётом смены конфигурации.** Composable'ы, которые освобождают ресурсы в `onDispose` (`SetupScreen` удаляет осиротевшие файлы сертификатов, `AppNavigation` очищает `VerificationSecrets` на маршруте проверки, `AboutScreen` и `SearchScreen` останавливают проигрыватель пасхалки), защищают очистку проверкой `context.findActivity()?.isChangingConfigurations` (`ui/utils/ContextExtensions.kt`, один общий `tailrec`-помощник). `MainActivity` объявляет `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboard|keyboardHidden|navigation"`, поэтому поворот не пересоздаёт Activity, но другие изменения (тема/`uiMode`, язык, масштаб шрифта, плотность) пересоздают её и уничтожают композицию; без проверки очистка разрушила бы ресурсы, на которые ещё ссылается состояние `rememberSaveable` после пересоздания. Нужен именно `findActivity()`, а не прямое приведение `as? Activity`: `LocalContext.current` часто оказывается `ContextThemeWrapper`, и прямое приведение вернуло бы `null`, молча обесценив проверку.

---

## 11. Текущие ограничения

- OAuth 2.0 / Modern Auth не реализованы.
- Office 365 / Exchange Online не является основной целью без Basic Auth.
- IMAP/POP3 — beta: только чтение, без групповой функциональности Exchange и без SMTP-транспорта.
- EAS 16.x не является текущей целью оптимизации (`EasVersionDetector` согласует версии `14.1`, `14.0`, `12.1`, `12.0`).
- Разбор XML смешанный: через `XmlPullParser` (`EasXmlParser`, `EasSyncStream`; обработка DOCTYPE отключена) разбираются ответы Sync календаря, контактов, заметок и задач, поиск по GAL, ответ MoveItems и Provision. Многие пути EWS/SOAP и EAS (в частности, в сервисах почты, черновиков и задач) всё ещё извлекают значения регулярными выражениями или подстроками (см. `docs/XMLPULLPARSER_MIGRATION_PLAN.md`).
- S/MIME-подпись и шифрование не реализованы.

---

## 12. Сборка и ресурсы

Рекомендуемый путь production-сборки — Android Studio с JDK 17. Примеры командной строки ниже вспомогательные и требуют корректно заданного `JAVA_HOME`.

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

В Windows:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

Unit-тесты запускаются командой `./gradlew testDebugUnitTest` (она же выполняется в CI, `.github/workflows/ci.yml`; в Windows есть `run-tests.bat`). Покрытие считает Kover.

| Каталог | Содержимое |
|---------|------------|
| `res/drawable/` | собственные векторные иконки, иконки файлов |
| `res/layout/` | макеты предпросмотра и загрузки виджета |
| `res/raw/` | звуки отправки/получения/удаления и аудио пасхалки |
| `res/xml/` | правила резервного копирования, правила извлечения данных, пути FileProvider, описание виджета, конфигурация сетевой безопасности (ярлыки приложения динамические, `util/AppShortcuts.kt`) |
| `res/values/` | строки и ресурсы темы по умолчанию |
| `res/values-ru/` | русские строки |

---

## 13. Карта документации

| Файл | Назначение |
|------|------------|
| `README.md` | Обзор проекта на русском |
| `README_EN.md` | Обзор проекта на английском |
| `docs/ARCHITECTURE.md` | Этот документ (русская версия) |
| `docs/ARCHITECTURE_EN.md` | Архитектура проекта (английская версия) |
| `docs/CHANGELOG_RU.md` | История изменений на русском |
| `docs/CHANGELOG_EN.md` | История изменений на английском |
| `docs/PRIVACY_POLICY.md` | Политика конфиденциальности |
| `docs/SECURITY.md` | Модель безопасности и модель угроз |
| `SECURITY.md` | Политика сообщения об уязвимостях |
| `docs/XMLPULLPARSER_MIGRATION_PLAN.md` | План миграции разбора XML на XmlPullParser |

---

## Краткое резюме

iwo Mail Client — offline-first Android-клиент почты, ориентированный на on-premise Microsoft Exchange, особенно Exchange 2007 SP1. Приложение использует Jetpack Compose, Room, DataStore, WorkManager, OkHttp и Conscrypt. Поддержка Exchange реализована через EAS/WBXML и EWS/SOAP с аутентификацией Basic/NTLM. IMAP/POP3 — beta-путь на JavaMail для базового чтения почты.

Архитектура слоёв:

```text
Compose UI → Репозитории → протокольные сервисы EAS/EWS/IMAP/POP3 → Room/DataStore/Сеть → Фоновые сервисы
```

Основные ограничения: нет OAuth/Modern Auth, IMAP/POP3 в статусе beta, нет S/MIME, часть разбора XML остаётся на регулярных выражениях до завершения миграции на XmlPullParser.
