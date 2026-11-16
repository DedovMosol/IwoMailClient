# Ручной аудит 1.6.3b — проверка по коду

Дата проверки: 2026-11-15, углубление 2026-11-16  
Ветка: `main` @ `f7f7cfe`  
Метод: разбор путей в коде + сверка с практиками EAS/EWS/Android. Полевых логов устройства нет. «Подтверждено» = в коде есть механизм, который даёт симптом.

Справочники: [MS-ASCMD](https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-ascmd/), [MS-ASEMAIL](https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-asemail/), [MS-ASAIRS](https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-asairs/), EWS FindItem/GetItem (Exchange 2007_SP1).

**Curl (2026-11-16):** прямые GUID-URL на learn.microsoft.com отдают HTML 404 (страницы Open Specs переезжали). Blob `winprotocoldoc.blob.core.windows.net` — HTTP 409. Живой индекс: [поиск MS-ASEMAIL Flag](https://learn.microsoft.com/en-us/search/?terms=MS-ASEMAIL%20Flag%20FlagStatus). Правила дат флага ниже — из текста протокола, как его цитирует сам код (`MailFlag.kt`, `EasEmailService.setFlag`) и как его описывают разборы [MS-ASEMAIL Flag / Status 6](https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-asemail/).

---

## CRITICAL

### C1. Потеря / «ротация» текста и вложений между письмами

**Симптом (ручной аудит):** тело и/или вложения чужих писем, хаотично, иногда вместе, иногда по отдельности.

**Вердикт: подтверждено как правдоподобный дефект с несколькими независимыми векторами.** Не «рандом Room», а неверное сопоставление элементов.

#### Вектор A — EWS fallback тела по теме (Exchange 2007)

`EmailOperationsService.loadEmailBody`: при пустом ItemOperations и на Status=8 (`OBJECT_NOT_FOUND`) для Exchange 2007 вызывается `EasClient.fetchEmailBodyViaEws(subject, folderType, dateReceived, internetMessageId)`.

EAS ServerId **не** переводится в EWS ItemId. Поиск:

1. `message:InternetMessageId` — безопасно, если id уже есть.
2. Subject + окно даты: 15 с / 120 с / **600 с**; для Sent ещё **1 час**.
3. Для Sent без InternetMessageId — **subject-only** (`subjectOnlyRestriction`).
4. `MaxEntriesReturned="2"`; берётся кандидат, если найден **ровно один**.

Один хит в широком окне или по теме ≠ правильное письмо: серия с одной темой, рассылки, повторные отправки. Тело пишется в `emailDao.updateBody(emailId, bodyContent)` **без сверки InternetMessageId исходного EAS-письма** (id с GetItem сохраняется уже после записи).

Комментарий в коде прямо говорит: «fallback не должен подставлять тело другого письма» — защита неполная.

#### Вектор B — кэш «похожего» тела

`cachedBodyMatchesPreview` + `suspiciousCachedBody`: уже есть эвристика «локальное тело не похоже на preview». Значит, команда уже ловила чужой/устаревший кэш. При неудачном exact reload подозрительное тело **обнуляется** — пользователь видит пустоту (C2).

#### Вектор C — миграция ServerId после SyncKey=0 (EAS 12.1 / Exchange 2007)

`EmailSyncService.deduplicateByContent`: матч `subject|from` + `to` + `dateReceived ±2s` (черновики — fuzzy prefix + ±5s).

При ложном совпадении (две рассылки с одной темой за 2 секунды, повтор «Re: …»):

1. Новый `Add` помечается дубликатом и **не вставляется**.
2. Старая строка **переписывается** на новый `id`/`serverId`:
   `emailDao.insert(oldEmail.copy(id = newEntity.id, serverId = newEntity.serverId))`.
3. Вместе переезжают **тело, preview, вложения, pending flags**.

Это не «прицепить файл» — это **подмена личности письма**. Пользователь открывает новое письмо и читает старое (или наоборот, если следующий sync снова мигрирует). Комментарий в коде: ServerId на EAS 12.1 нестабилен после SyncKey=0 ([MS-ASCMD] §2.2.3.166.8, стабильность обещана с EAS 16.1 — у проекта целевой диапазон 12.x–14.1). Эвристика subject+from+to+±2s для 2007 слишком широкая.

Ложные совпадения также переносят вложения (`attachmentDao.insertAll(... emailId = new)`).

`mergeAttachmentsToTarget` матчит ещё и **только по `displayName`** (`img1.jpg`, `image001.png`) — ротация файлов без тела.

`reconcileAttachments`: индекс локальных вложений — `associateBy { displayName }`. Два `image001.png` в одном письме схлопываются; чужой FileReference пишется на чужое имя (`updateFileReference(emailId, displayName, …)`). Скачивание идёт по FileReference — пользователь видит «тот файл в этом письме».

Практика [MS-ASAIRS]: первичный ключ вложения — **FileReference**, не DisplayName. ContentId — для cid:. Имя файла — подпись для UI.

#### Вектор D — ItemOperations Fetch не тот ServerId

После миграции/MoveItems локальный `serverId` может отстать. Fetch по старому id: пусто или Status=8 → EWS по теме (вектор A) или ошибка C2.

#### Вектор E — парсер Sync

`EasSyncStream` изолирует `ApplicationData` по Add/Change; массовой утечки листьев между письмами в парсере не видно. Основной риск — **после** парсера (дедуп, EWS, merge вложений).

**Exchange 2007–2013:** A/C/D типичны для 2007 SP1 (EAS 12.1, нестабильный ServerId, пустой ItemOperations). 2010/2013 реже, но EWS subject-only для Sent остаётся.

**Что чинить (направление, не объём этого файла):**

- EWS: не писать тело, пока InternetMessageId GetItem не совпал с локальным (если локальный пуст — сохранить id, не тело, пока нет второго сигнала).
- Убрать subject-only для Sent; сузить окно; при >1 кандидате — отказ, не «первый из двух».
- Дедуп: не переносить вложения без `fileReference`/`contentId`; не матчить только по имени файла.
- Не вызывать EWS fallback, если EAS Status=8 на валидном Sent с вложениями без проверки существования через Sync/GetItemEstimate.

---

### C2. «Сервер не вернул текст письма» (входящие и отправленные)

**Симптом:** `Ошибка загрузки: Сервер не вернул текст письма...` (`BodyLoadError.NoBodyFromServer` → `EmailDetailScreen`).

**Вердикт: подтверждено.** Путь реальный, не только UI.

Источники пустого тела:

1. **ItemOperations Fetch** (`fetchItemBody`): HTML Type=2, `TruncationSize=1_048_576`, затем Type=1. Если `Truncated=1` и Data пустой — сохраняется `""` (типичный 2007: не смог отдать HTML).
2. **Status=8 всегда = OBJECT_NOT_FOUND** (`SENTINEL_NOT_FOUND`). На item-level 8 в [MS-ASCMD] ItemOperations — «объект не найден»; на практике 2007 ставит 8 и на Sent с вложениями, и при Fetch параллельно с Sync. Дальше EWS или удаление логики.
3. Fetch **не** под `FolderSyncGate`. Шлюз только для Sync (флаг/прочитано/цикл папки). ItemOperations идёт с тем же устройством параллельно SyncWorker/Push — 2007 часто отвечает пусто/8 на первый Fetch.
4. EWS fallback не нашёл ровно одного кандидата → `EmailBodyResult("")`.
5. `loadBodyInitial`: Success + blank + непустой preview → `NoBodyFromServer`.
6. `refresh()`: после sync тело empty → тот же error.
7. Truncated draft и suspicious cache drop обнуляют body.
8. Письма крупнее 1 МиБ HTML: обрезок без MIME Type=4 на открытии (полный MIME только в `fetchMessageMime` / .eml, TruncationSize 20 МиБ).
9. Sync списка: `TruncationSize>10240` только в шаблоне; в БД для не-черновиков тело **намеренно не пишется** (`body = existing ?: ""`). Открытие всегда идёт в сеть, если локально пусто — это норма, не баг. Баг — когда сеть вернула пусто.

Папки: Inbox/Sent/Drafts/Deleted — EWS distinguished. Пользовательские папки: EWS **нет** → пустой ItemOperations = C2 сразу.

`buildEmailEntities` не кладёт 10 КБ preview в `body` — путаницы «обрезок приняли за полное» на обычной почте нет (только черновики при `!bodyTruncated`).

**Не баг локализации:** строка из `NotificationStrings` / `BodyLoadError.NoBodyFromServer`.

Связь с C1: тот же fallback либо подставляет чужое тело, либо честно оставляет пусто.

---

## HIGH

### H1. Пустой экран «загрузка» при открытии письма; свайп вправо + повтор помогает

**Вердикт: подтверждена гонка UI + повторная загрузка, не обязательно порча БД.**

Факты:

- `EmailDetailScreen`: `email == null` → полноэкранный `CircularProgressIndicator` **без таймаута и без ошибки**.
- `emailFlow.stateIn(..., initialValue = null)`: первый кадр всегда spinner, пока Room не эмитнет.
- `viewModel()` **без `key = emailId`**. Factory зашивает `emailId` в конструктор и **не** читает `SavedStateHandle`. [Android Navigation + ViewModel](https://developer.android.com/topic/libraries/architecture/viewmodel): экземпляр живёт в `NavBackStackEntry`. Маршрут один: `email/{emailId}`. С `launchSingleTop = true` (уведомление/виджет, `AppNavigation.kt` ~542) повторный переход на **другое** письмо может оставить тот же entry и тот же VM со старым id, пока аргумент уже новый. Список писем навигирует **без** `launchSingleTop` (~1003) — с списка чаще новый entry. «Свайп назад + открыть снова» как раз уничтожает entry.
- `HtmlBodyView`: пока `webViewHeight == 0`, у Card **нет высоты** — белая карточка нулевой высоты = «пусто», хотя загрузка внизу. Фон карточки всегда `Color.White` (в тёмной теме вспышка). JS для замера; смерть рендерера → fallback текстом только со второго падения.
- `openEmail()` один раз на инстанс VM: пустое тело → `loadBodyInitial` (30 с). Если Room позже принесёт тело, а fetch ещё идёт, оверлей `isLoadingBody` (около строки 1629) держит «загрузку» поверх.
- `observeData`: `collectLatest` по `activeAccount`; смена аккаунта на время рвёт combine — снова `email` из flow, но папки empty, UI жив.
- `HtmlBodyView`: WebView на `contentKey`; сбой рендерера / нулевая высота выглядит как пустое тело. Back (`popBackStack`) уничтожает VM+WebView; повтор — новый fetch. Совпадает с «свайп вправо помог».
- Параллельно `markAsRead` / `FolderSyncGate` на ту же папку, что ItemOperations Fetch — на 2007 команды EAS сериализуются плохо; первый Fetch пустой, второй после back проходит.

Проверить те же папки: любой `EmailDetailScreen` (поиск, виджет, уведомление) — тот же VM. Не Inbox-only.

**Направление:** `viewModel(key = emailId)`; не крутить fullscreen spinner при `bodyLoadError`; не ставить `isLoadingBody`, если в Room уже есть тело; retry Fetch без ухода с экрана; не блокировать UI на mark-as-read.

---

### H2. «Сервер не принял флаг — оставлено как на сервере» (Избранное)

**Вердикт: подтверждено. Fallback «локально + очередь» уже есть, но не для отказа сервера (Status ≠ 1).**

Цепочка:

- Звезда: `FlagPresets.toggled` → `MailFlag(STATUS_ACTIVE)` **без Start/Due**.
- XML: `<FlagStatus>2` + FlagType, даты **опускаются**. [MS-ASEMAIL] Flag: `StartDate`/`DueDate`/`UtcStartDate`/`UtcDueDate` — **все четыре или ни одной**; Start не позже Due. Нарушение → Sync **Status 6** (conversion error). Код это знает (`MailFlag.kt`, retry `dueToday` только на 6). Звезда шлёт Status=2 **без** четвёрки дат → 6 на части 2007/2010. Outlook «флаг без даты» на сервере всё равно часто хранит сегодняшние даты.
- Retry `MailFlags.dueToday` есть **только** при `changeStatus == CHANGE_STATUS_INVALID` (6) и `dueDate == null`. Другие статусы (5 Object not found, 7, 8, 4) → `applied = null` → `FLAG_REJECTED` → откат звезды.
- `writeFlag`: нет сети / нет клиента / stale key (не reject) → **KeptLocal** + `pendingChanges.markFlag` + `PendingMailChangesWorker`. Это уже запрошенный fallback.
- Явный reject → `Outcome.Rejected`, очередь **очищается**, UI тост `Сервер не принял флаг`.

Почему «постоянно»:

1. Сервер всегда отвергает флаг без дат, а retry dueToday тоже Status 6 (неполный XML / tasks namespace / SyncKey).
2. `Collection Status ≠ 1` бросает exception → в `writeFlagToServer` это Error; `isChangeRejected` может классифицировать как reject.
3. Неверный `serverId` после миграции (C1-C) — Status 8 → reject, не pending.
4. Гонка `FolderSyncGate`: параллельный Sync папки, Change на устаревшем ключе; повтор один раз.

**Направление:** звезду по умолчанию ставить как Outlook «Today» (даты сразу, не ждать Status 6); при любом transient/8 — **не откатывать**, а pending queue; тост только после N неудач; не слать Flag Change пока serverId не подтверждён последним Sync.

---

## MEDIUM

### M1. Поиск: цепочки без стрелки раскрытия

**Вердикт: подтверждено, это UI-разрыв, не баг группировки.**

- Поиск включает `EmailThreads.group` + `MailRow.threaded` при `conversationView`.
- Шапка: клик раскрывает `expandedSearchThreads`, дети с `padding(start = 24.dp)`.
- `SearchResultItem` **не** принимает `thread` / `expanded`, **нет** `AppIcons.ExpandMore` / `ExpandLess`.
- Список писем: `EmailListItem` + `ExpandedThreadBar` со стрелкой (около `EmailListScreen.kt:1536`).

По отправителю цепочки схлопываются в одну строку без affordance «это пачка». Поведение есть, сигнала нет. На 2007 группировка по теме (`EmailThreads`) даёт ложные цепочки — без стрелки ещё хуже.

**Направление:** один ряд (`EmailListItem` / общая ThreadHeader) в списке и поиске. Не плодить второй вид строки.

---

### M2. Настройки выглядят бедно / «паршиво» (эталон `photo/`)

**Вердикт: подтверждено. Это не «чуть не дотянули до HyperOS» — текущий хаб визуально дешёвый относительно и эталона, и Material 3 Settings.**

Эталон (`photo/1.jpg`–`3.jpg`, системные настройки): поиск сразу под заголовком; **уникальный цвет** у каждой иконки в скруглённом квадрате (не круг одного primary); группы-карточки с воздухом; шеврон; статус справа серым («Откл.», имя сети); крупные заголовки секций цветом акцента.

Что в коде (`SettingsScaffold.kt`, `SettingsScreen.kt`):

- Карточки `SettingsGroup` есть (16.dp, `surfaceContainerLow`) — каркас правильный.
- `SettingsLeadingIcon`: **один** `primaryContainer` + **круг** 48.dp на все пункты. Аккаунты, почта, замок — одинаковые пятна. На эталоне и в Gmail/Android Settings у каждого пункта свой glyph-цвет.
- App bar — горизонтальный градиент темы, белый текст. Рядом с системным экраном выглядит как «другая эпоха», не как настройки 2024–2026.
- Нет поиска по пунктам (на эталоне он первый).
- Нет правого статуса (вкл/выкл, email аккаунта в одну линию с шевроном).
- `titleMedium` на хабе vs `titleSmall` на свитчах — плотность скачет; внутренние экраны (`SettingsSwitchRow`) снова серый ListItem на фоне scaffold, не те же карточки.

Практики: [Material 3 lists/settings](https://m3.material.io/components/lists/overview) — группы, 1–2 строки, trailing control, 24.dp иконки в контейнере формы приложения. Android Settings / Gmail: search, per-row color, no gradient chrome. Мы копируем ни систему, ни собственную главную (плитки с градиентами на MainScreen).

Это **редизайн**, не протокол. Отдельная задача. Не мешать с C1/H2. Эталон `photo/` в git не класть (уже в `.gitignore`).

---

## LOW

### L1. Алерт фоновой работы при первом входе не в стиле главной

**Вердикт: подтверждено.**

`MainActivity` → `AppPermissionDialog` → `ScaledAlertDialog` (M3 AlertDialog, иконка battery, «Позже» / outlined «Продолжить»). Главная: градиенты, `BackgroundWorkBanner` (errorContainer-карточка), крупные плитки.

Диалог системно-серый, не «карточка с главной». Баннер на главной ближе к дизайну, чем первый prompt.

**Направление:** тот же визуальный язык, что у баннера/карточек MainScreen (скругление, градиент акцента, иллюстрация), не сырой AlertDialog.

---

## Сводка вердиктов (находка → статус 2026-11-16)

| ID | Находка | Статус |
|----|---------|--------|
| C1 | Ротация тел/вложений | **Исправлено в коде.** EWS: Message-ID или Subject+≤2 мин, ровно 1 хит, сверка GetItem. Вложения по FileReference. Миграция ServerId только при одном хите. InternetMessageId из Sync пишется в Room, если сервер отдал (EAS 14+ чаще, 12.1 редко). |
| C2 | Нет текста с сервера | **Частично.** Status 6 = нет объекта, 8 ≠ удаление. Fetch сериализован со Sync. Пользовательские папки без EWS — по-прежнему пусто. Честный отказ лучше чужого тела. |
| H1 | Пустой спиннер | **Исправлено в коде.** `viewModel(key=emailId)`, без `launchSingleTop` на деталь, ошибка вместо вечной крутилки, FAB только если письмо есть, WebView `heightIn(min=48.dp)`, prefetch `tryWithFolder`. |
| H2 | Звезда отвергнута | **Исправлено.** Звезда и меню «Без срока» = сегодня, четыре даты EAS. |
| M1 | Цепочки в поиске без стрелки | **Исправлено.** SearchResultItem: ExpandMore/ExpandLess как в списке писем. |
| M2 | Настройки | **Частично.** Разные container-цвета и squircle 12.dp у хаба; поиска по пунктам нет. |
| L1 | Permission alert | **Частично.** Иконка в скруглении цветом темы, не голый M3 Icon. |

---

## Углубление (2026-11-16): практики и то, что в первом проходе было слабо)

### Протокол vs клиент (после правок)

| Практика | Сейчас |
|----------|--------|
| Тело: EAS ServerId / RFC Message-ID | FindItem по Message-ID (скобки и без); иначе Subject+15 с/120 с, ровно 1. GetItem: чужое тело только при **другом** ID, отсутствие тега на 2007 не отказ |
| Вложение: FileReference | FileReference → ContentId → уникальное имя; UPDATE по id |
| SyncKey=0 на EAS 12.1 | Миграция только при одном хите ±2 с; >1 — входящий Add отбрасываем |
| ItemOperations ∥ Sync на 2007 | Fetch тела и metadata под `FolderSyncGate`; prefetch — `tryWithFolder` |
| Fetch Status 6 vs 8 | 6 = нет объекта; 8 = Range, не удаление |
| Flag: 4 даты или ни одной | Звезда — сегодня с четырьмя датами; «Без срока» — retry Today при 6 |
| ViewModel детали письма | `viewModel(key = emailId)`, без `launchSingleTop` |
| Settings | Без изменений (M2/L1) |

### Что не нашли (чтобы не охотиться зря)

- Утечка ApplicationData между `<Add>` в `EasSyncStream` — изоляция по элементам есть.
- Подмена тел на этапе Room `@Insert` без дедупа — PK `accountId_serverId`, конфликт игнора.
- IMAP/POP3 в C1: `MessageToEntityConverter` ключ `accountId_messageId`; ротация Exchange-пути. Если баг на IMAP — отдельно.
- Полноэкранный spinner **после** того как Room отдал письмо: тогда это ветка `isLoadingBody` (шапка письма есть, крутилка вместо тела). Ручной «пустой экран» ближе к `email == null`.

### Порядок фикса (SOC)

1. C1 — сделано.
2. C2/H1 — сделано (остаток: user folders без EWS).
3. H2 — сделано (звезда); меню «Без срока» — retry.
4. M1: общий header цепочки — **не делали**.
5. M2/L1: редизайн — **не делали**.

## Принципы при фиксе

- **SOC:** не лечить C1 в Compose; сопоставление тел — только EAS ServerId / InternetMessageId.
- **Не маскировать** C2 показом «какого-нибудь» тела.
- **YAGNI:** не тащить полный EWS ItemId mapping в 1.6.3b, если достаточно запрета небезопасного FindItem.
- Тесты: FindItem 0/1/2 кандидатов; флаг Status 6 с/без дат; Search header показывает ExpandMore; VM key смена emailId.
)
