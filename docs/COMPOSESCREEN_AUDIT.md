# Аудит ComposeScreen (v1.6.3b)

> Дата: 03.07.2026 · Обновлено: 03.07.2026 (перепроверка кода + верификация best practices по внешним источникам)
> Объём: `ComposeScreen.kt` ~2549 строк + связанные `ComposeUtils.kt`, `ui/screens/compose/ComposeModels.kt`, `ui/components/SendProgressBar.kt` (`SendController`), `ui/screens/ScheduledEmailWorker.kt`, `EasEmailService.kt`/`EasAttachmentService.kt` (лимиты), жизненный цикл WebView в `ui/components/RichTextEditor.kt`.
> Цель: найти крэш/OOM/гонко-склонные места и нарушения DRY/KISS/SOLID перед миграцией на MVVM. **Только аудит — код не менялся.**
> Инвариант: протокол EAS/EWS и совместимость с **Exchange 2007 SP1/SP2** не затрагиваются ни на одном этапе (меняется только слой представления и pre-check'и ДО протокола).

---

## 1. Резюме

`ComposeScreen` — последний крупный немигрированный на MVVM экран и самый «тяжёлый» по побочным эффектам: 5 длинных загрузчиков (`reply`/`forward`/`draft`/`share`/`mailto`), отправка через внешний `SendController`, сохранение черновика с чтением байт вложений, дебаунс-подсказки получателей с GAL, ручное управление жизненным циклом WebView.

Экран **функционально не роняет процесс** благодаря `rememberSafeScope()` (крах-безопасный scope с `CoroutineExceptionHandler`, класс UI-1) — непойманное исключение/`OutOfMemoryError` логируется. Однако найдены:

- **2 реальных пробела в защите от OOM** (фикс N-2 покрывает не все пути): `saveDraft` без предпроверки размера и inline data:URL-картинки в body, которые не считает ни `ComposeScreen`, ни путь `sendMail` в EAS.
- **1 корректностный баг устойчивости к повороту**: длинные загрузчики `reply`/`forward`/`draft` не переживают пересоздание (флаг `*Loaded` ставится ДО завершения загрузки).
- Средние/низкие: отсутствие дебаунса локального поиска подсказок, окно утечки `Activity`-контекста в `SendController`, двойной `destroy()` WebView, залипание `isSending`.
- **Нарушения DRY/KISS/SOLID**: дублирование логики загрузки вложений reply≈forward≈draft (3 почти идентичных блока ~80 строк), бизнес-логика в `@Composable` (SRP), прямые обращения к `database.*Dao()` из UI (нарушение слоёв).

Все находки перепроверены по коду (§3) и сверены с отраслевыми best practices (§4). План устранения — поэтапный, со сложностью и оценкой риска (§6).

---

## 2. Сводная таблица находок

| ID | Область | Серьёзность | Кратко |
|----|---------|-------------|--------|
| CS-1 | OOM / память | **Высокая** | `saveDraft` читает байты вложений БЕЗ предпроверки суммарного размера (в отличие от `sendEmail`/N-2) → OOM и «тихий» провал сохранения |
| CS-2 | OOM / память | **Высокая** | Лимит 10 МБ (N-2) не учитывает inline data:URL-картинки в body; путь `sendMail` (без файловых вложений) вообще не проверяет размер MIME → «тяжёлое» тело не ограничено нигде |
| CS-3 | Устойчивость / корректность | **Средняя** | `replyLoaded`/`forwardLoaded`/`draftLoaded` выставляются ДО завершения; поворот/пересоздание при загрузке прерывает её без возобновления → частичное/пустое тело, потерянные вложения |
| CS-4 | Память | **Средняя** | Загрузка inline-картинок читает все файлы целиком (`readBytes`→base64) в один in-memory `body` без лимита количества/размера |
| CS-5 | Утечка | Низкая | `SendController` удерживает `Activity`-контекст в процесс-долгоживущем scope на время отсчёт+отправка+~19с досинка Sent → окно утечки Activity |
| CS-6 | Хрупкость | Низкая | Двойной `destroy()` WebView (ручной в `sendEmail` + `onRelease` у `AndroidView`); спасает `try/catch`, но противоречит официальной рекомендации |
| CS-7 | Производительность | **Средняя** | Локальный поиск подсказок без дебаунса — 3+ запроса к БД на нажатие; дебаунс только у GAL (500 мс) |
| CS-8 | UX / залипание | Низкая | `isSending` не сбрасывается на успешном/отложенном пути; при срыве навигации экран залипает |
| CS-9 | Потеря данных (край) | Низкая | `flushEditorHtml` по таймауту откатывается к возможно устаревшему `body` |
| CS-10 | Дизайн | Инфо | Нет периодического авто-сохранения черновика |
| CS-11 | Диск | Низкая | Каталоги `reply_/forward_/draft_attachments` копят файлы по timestamp; полагается на внешнюю очистку |
| CS-12 | Функц. асимметрия | Инфо | Автодополнение только у «Кому»; Cc/Bcc без подсказок |
| CS-13 | Уточнение ТЗ | Инфо | Drag-n-drop в `ComposeScreen` отсутствует; вложения только add/remove |
| CS-14 | Beta-ограничение | Инфо | Путь отправки только EAS (`createEasClient`); IMAP/POP3 отправлять не могут |
| CS-15 | DRY | **Средняя** | 3 почти идентичных блока загрузки вложений (reply/forward/draft, ~80 строк каждый) — дублирование логики скачивания/base64/CID-резолвинга |
| CS-16 | SRP / слои | **Средняя** | Бизнес-логика (нормализация, дедуп, дельта-детект, save/send-оркестрация) и прямые `database.*Dao()`-вызовы внутри `@Composable` |

---

## 3. Детальные находки (перепроверено по коду)

### CS-1 — `saveDraft`: чтение вложений без предпроверки размера (OOM) — Высокая

Подтверждено: `totalAttachmentBytes(...)`/`MAX_TOTAL_ATTACHMENT_BYTES` вызываются только в `sendEmail` (строка 1495). В `saveDraft` (строка ~1258) байты читаются напрямую:

```kotlin
val fileDraftAttachments = withContext(Dispatchers.IO) {
    attachments.mapNotNull { att ->
        context.contentResolver.openInputStream(att.uri)?.use { input ->
            val bytes = input.readBytes()   // ← нет верхнего лимита
            DraftAttachmentData(att.name, att.mimeType, bytes)
        }
    }
}
```

Плюс inline-картинки декодируются из base64 в цикле `DATA_URL_REGEX.findAll(body)` (дублирование в памяти: base64 уже в `body` + декодированные байты в `inlineDraftAttachments`).

Последствие: вложение, которое `sendEmail` отклонил бы, при «Сохранить черновик» читается целиком → `OutOfMemoryError`. `catch (e: Exception)` не ловит `Error` → тост об ошибке лимита не показывается; `finally { isSavingDraft = false }` отрабатывает, пользователь видит «тихий» провал. Нарушение принципа единообразной защиты (та же операция — те же гарантии).

---

### CS-2 — Лимит N-2 не покрывает inline-картинки; путь `sendMail` без проверки размера (OOM) — Высокая

Подтверждено двумя фактами:

1. `totalAttachmentBytes` суммирует **только** список файловых `attachments`. Inline-картинки (вставленные `insertImageWithQuality`) остаются как `data:<mime>;base64,...` в `body` и в `sendEmail` НЕ извлекаются — уходят как HTML-тело.
2. В `EasEmailService.sendMail` (путь без файловых вложений) `buildMimeMessageBytes(...)` вызывается **без проверки `maxMimeSize`**. Проверки 7 МБ (вложения) и 10 МБ (MIME) есть только в `EasAttachmentService.sendMailWithAttachments`/`smartForwardWithAttachments`.

Итог: письмо с крупными inline-картинками и без файловых вложений **не ограничено нигде** — ни в UI (N-2 считает 0), ни в `sendMail`. На устройствах с 2 ГБ ОЗУ сборка MIME + base64 (RFC 4648: +33% к размеру) может вызвать OOM. Даже при наличии файловых вложений inline-вес не учитывается в предпроверке.

---

### CS-3 — Загрузчики reply/forward/draft не переживают поворот в момент загрузки — Средняя

Все три ставят флаг завершения **до** `await`:

```kotlin
LaunchedEffect(replyToEmailId) {
    if (replyLoaded) return@LaunchedEffect
    replyToEmailId?.let { emailId ->
        replyLoaded = true                 // ← флаг ДО загрузки тела/вложений/CID
        mailRepo.getEmailSync(emailId)?.let { email -> /* длинная сетевая загрузка */ }
    }
}
```

Флаги — `rememberSaveable`. При повороте (`MainActivity` без `android:configChanges`) композиция уничтожается, `LaunchedEffect` отменяется на полпути, а после пересоздания флаг уже `true` → загрузчик выходит рано и **не возобновляет** скачивание. Результат: частичное/пустое тело, отсутствующие пересланные вложения. Корень — длинная сетевая работа в scope, привязанном к композиции (не `viewModelScope`).

---

### CS-4 — Inline-картинки грузятся целиком в один in-memory body без лимита — Средняя

В reply/forward/draft для каждого inline-вложения: `file.readBytes()` → base64 → `inlineImages[cid] = "data:...;base64,..."` → `replaceCidWithDataUrl(body, inlineImages)`. Нет ограничения количества/суммарного размера. Письмо с десятком крупных встроенных изображений раздувает `body` до многих МБ. Совмещается с CS-2. Аналог защиты PB-2 из `EmailDetailScreen` (не строить inline из чрезмерно больших писем) здесь отсутствует.

---

### CS-5 — Окно утечки Activity-контекста в SendController — Низкая

`sendEmail` передаёт `context = LocalContext.current` (Activity) в `sendController.startSend(...)`. `sendScope = supervisedScope(Dispatchers.Main)` живёт весь процесс; захваченный `context` удерживается на время 3 с отсчёта + отправка + фоновый досинк Sent (задержки 3+6+10 с) ≈ 15–20 с после закрытия экрана → `Activity` не собирается GC. Не критично (самоисцеляется), но правильнее `applicationContext`.

---

### CS-6 — Двойной destroy() WebView — Низкая

`sendEmail`: `hideWebView = true; delay(100)`, затем ручной `webView.destroy()`; при этом убирание `RichTextEditor` из композиции запускает `AndroidView.onRelease`, который **тоже** уничтожает тот же инстанс. Двойной `destroy()`, спасает `try/catch`/null-guard. Противоречит официальной рекомендации (WebView привязан к Activity, освобождением должен управлять `AndroidView`/`onRelease`, см. §4). Ручной блок дублирует `onRelease` и должен быть удалён при миграции.

---

### CS-7 — Нет дебаунса локального поиска подсказок — Средняя

`searchSuggestions` вызывается из `onValueChange` «Кому» на каждое нажатие; дебаунс (`delay(500)`) только у GAL. Локальная часть — 3+ синхронных запроса к БД на символ (`contactGroupDao`, `contactDao`, `emailDao` + per-group `getContactsByGroupList`). Для быстрого набора — заметная нагрузка/частые отмены job. Канонический фикс — единый `MutableStateFlow(query).debounce(...)` (см. §4).

---

### CS-8 / CS-9 / CS-10 / CS-11 — см. предыдущую редакцию (подтверждены)

- **CS-8**: `isSending=true` синхронно (ок), но сброса нет на успешном/отложенном пути — расчёт на `onSent()`; при срыве навигации экран залипает. При MVVM — сброс в `finally`.
- **CS-9**: `flushEditorHtml` по таймауту оставляет `body` прежним; частично смягчено дебаунс-обновлением `onHtmlChanged` (< 500 000 символов). Краевой риск потери последних символов.
- **CS-10**: нет авто-сейва; несохранённое держится на `rememberSaveable` (переживает поворот/process-death с сохранённым state, теряется при удалении задачи).
- **CS-11**: временные каталоги вложений копят файлы по timestamp; проверить их вхождение в `AppFileCleanupService`.

### CS-12 / CS-13 / CS-14 — Инфо (без изменений)

- **CS-12**: автодополнение только у «Кому»; Cc/Bcc — `onValueChange = { cc = it }` без подсказок.
- **CS-13**: drag-n-drop в `ComposeScreen` **не реализован** (вложения — `Column` + кнопка удаления). Вероятно спутано с `DragSelectionIndicator` списка писем — **нужно уточнить ожидание**.
- **CS-14**: `SendController` всегда через `createEasClient(...)`; IMAP/POP3 отправлять не могут (согласуется с beta-статусом), но ошибка недружелюбна («Failed to create client»).

### CS-15 — Дублирование логики загрузки вложений (нарушение DRY) — Средняя

Три блока (reply / forward / draft) содержат почти идентичный код (~80 строк каждый): проверка `localPath`→`readBytes`→base64 для inline / `FileProvider` для файловых; иначе `downloadAttachment`/`downloadDraftAttachment`→сохранение в каталог→base64/`FileProvider`; резолвинг недостающих `cid:` через `fetchInlineImages`/`fetchInlineImagesEws`. Отличия минимальны (имя каталога, `downloadAttachment` vs `downloadDraftAttachment`, наличие `collectionId`). Это ~240 строк дублирования → баги правятся в трёх местах (риск рассинхрона). Требует извлечения в единый use-case (`AttachmentLoader`) с параметризацией.

### CS-16 — Бизнес-логика и доступ к БД внутри @Composable (нарушение SRP/слоёв) — Средняя

`@Composable ComposeScreen` содержит: нормализацию/раскрытие групп/дедуп (`normalizeRecipients`, `applyGroupsSelection`), дельта-детект черновика (`hasDraftChanges`), оркестрацию save/send, прямые `database.contactGroupDao()/contactDao()/emailDao()/folderDao()`-вызовы. Нарушает SRP (Composable отвечает и за отрисовку, и за бизнес-правила) и границы слоёв (UI → DAO минуя Repository). Затрудняет тестирование (нельзя проверить без Robolectric). Устраняется выносом в `ComposeViewModel` + делегированием DAO-доступа репозиториям.

---

## 4. Верификация по отраслевым best practices (внешние источники)

Проверено пошагово; выводы согласуются с текущей архитектурой проекта (`docs/ARCHITECTURE.md` §2.1). Контент источников пересказан для соответствия лицензионным ограничениям.

1. **MVVM / UDF, ViewModel как источник состояния.** Официальное руководство Android: следовать однонаправленному потоку данных — ViewModel отдаёт UI-состояние через наблюдаемый паттерн и принимает действия через вызовы методов ([Architecture, developer.android.com](https://developer.android.com/topic/architecture/views/recommendations-views); [Compose architecture](https://developer.android.com/jetpack/compose/architecture)). → Обосновывает CS-16 и план §6.

2. **Одноразовые события.** Официальная статья Android предостерегает от анти-паттернов one-off событий и рекомендует по возможности сводить их к состоянию ([ViewModel: One-off event antipatterns](https://medium.com/androiddevelopers/viewmodel-one-off-event-antipatterns-16a1da869b95)). → Для навигации/тостов/звука проект уже стандартизировал `Channel + receiveAsFlow` (ARCHITECTURE §2.1); `ComposeViewModel` должен следовать **этому же** паттерну (консистентность/DRY), а не вводить новый.

3. **CancellationException.** Для корректной кооперативной отмены исключение отмены должно доходить до верха стека непойманным; его нельзя «глушить» ([kotlinlang.org: cancellation](https://kotlinlang.org/docs/cancellation-and-timeouts.html); [proandroiddev](https://proandroiddev.com/age-mastering-coroutine-cancellation-in-kotlin-best-practices-common-pitfalls-and-safe-handling-41f702503977)). → Текущий паттерн `catch (e: CancellationException) { throw e }` верен; сохранить его в VM.

4. **WebView в Compose.** Официальная документация: WebView — legacy-View, привязанная к Activity; её экземпляр **не должен переживать** жизненный цикл Activity, а конфиг-изменения/навигация сложны ([Wrap a WebView in Compose](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/wrap-webview-in-compose)). → Валидирует CS-6: освобождением WebView должен управлять `AndroidView.onRelease`, а не ручной `destroy()`.

5. **Дебаунс поиска.** Канонический паттерн — `MutableStateFlow(query)` + оператор `debounce(...)` во `viewModelScope` (ждёт паузы ввода, отменяет отложенное значение при новом вводе) ([Debounce in Kotlin Flow, substack](https://androidengineers.substack.com/i/179779431/understanding-debounce); [MVVM Compose search](https://medium.com/@a.poplawski96/implement-modern-search-functionality-on-android-with-compose-mvvm-clean-architecture-junit5-61cbbee963ba)). → Обосновывает CS-7.

6. **Утечки/lifecycle в Compose.** Ссылки на `Activity`-контекст и ресурсы, живущие дольше композиции, — источник утечек; сбор потоков должен быть lifecycle-aware ([Memory Leaks in Jetpack Compose, proandroiddev](https://proandroiddev.com/memory-leaks-in-jetpack-compose-a-technical-deep-dive-3afb7b78a82e)). → Обосновывает CS-5 (`applicationContext`) и переход на `collectAsStateWithLifecycle` (N-8) в VM.

7. **Base64 overhead.** RFC 4648: base64 увеличивает размер на ~33% (3 байта → 4 символа). → Количественно обосновывает пик памяти в CS-2/CS-4.

---

## 5. Что сделано хорошо (не трогать)

- **Крах-безопасность**: `rememberSafeScope()` (`SupervisorJob` + `CoroutineExceptionHandler`) — класс UI-1.
- **Переживание поворота ввода**: `rememberSaveable` + file-backed `createLargeStringSaver` для `body`/`initialDraftBody`; флаги-гарды ввода (v1.6.2).
- **Независимость отправки от экрана**: `SendController` с процесс-scope + `SendProgressBar` вне экрана — отправка переживает навигацию (правильный undo-send).
- **N-2 (частично)**: предпроверка размера файловых вложений в `sendEmail`.
- **Отложенная отправка**: байты вложений в файлы + manifest (обход `TransactionTooLargeException`, лимит `WorkManager inputData` 10 КБ).
- **Дедуп/безопасность**: нормализация получателей, раскрытие групп, проверка дубликатов, `compressImageForInline` с `recycle()`/обработкой `OutOfMemoryError`.
- **Стабильный ClientId/Message-ID** (N-11) — дедуп отправки при retry (актуально и для Exchange 2007 EAS 12.1 raw-MIME).

---

## 6. План устранения по этапам (со сложностью и риском)

Шкала сложности: **T** (тривиально, < 0.5 дня) · **S** (малая, ~1 день) · **M** (средняя, 2–3 дня) · **L** (крупная, ~1 неделя) · **XL** (очень крупная, > 1 недели).
Риск для Exchange 2007 SP1/SP2 указан отдельно; по умолчанию — «нет» (изменения в слое представления / pre-check ДО протокола, wire-формат не меняется).

Каждый этап = отдельный коммит с регрессионным прогоном соответствующей функциональности (по правилам `XMLPULLPARSER_MIGRATION_PLAN.md`).

### Этап 0 — Точечные защитные фиксы (без MVVM) · Приоритет 1
Можно и нужно сделать до миграции — закрывают OOM-риски малой кровью.

- **CS-1 + CS-2 → единый бюджет вложений (DRY/KISS).** Ввести helper `checkAttachmentBudget(files, body): Result` (SRP): суммирует файловые вложения (`totalAttachmentBytes`) **плюс** оценку inline data:URL в body (сумма длин base64 × 3/4). Вызывать в `sendEmail` И `saveDraft` до `readBytes`. Показ `attachmentLimitExceeded`. Лимит согласовать с EAS (7 МБ вложений / 10 МБ MIME) — pre-check, протокол не трогаем. **Сложность: S.** Риск Exchange 2007: нет.
- **CS-5 → `applicationContext`** в `sendController.startSend(context = ...)`. **Сложность: T.** Риск: нет.
- **CS-8 → сброс `isSending`** на всех терминальных путях (в т.ч. отложенном). **Сложность: T.** Риск: нет.
- **(опц.) EAS: добавить `maxMimeSize`-guard в `sendMail`** для симметрии с `sendMailWithAttachments` (KISS, единый предел). **Сложность: S.** Риск Exchange 2007: низкий (только отклонение слишком больших MIME — поведение уже есть в соседнем пути; протокол не меняется). Обязательная проверка отправки обычных писем на Exchange 2007 SP1.

### Этап 1 — Дебаунс подсказок (CS-7) · Приоритет 2
- Перевести поиск на `MutableStateFlow(query).debounce(~200ms)` + `flatMapLatest`; локальные источники и GAL — в единый поток (GAL — с доп. задержкой/фильтром длины). **Сложность: S–M.** Риск: нет (GAL-запрос к Exchange не меняется, меняется только триггер).

### Этап 2 — Инфраструктура MVVM · Приоритет 3
- `ComposeUiState` (immutable), `ComposeEvent` (`Channel + receiveAsFlow` — как в существующих VM), `ComposeViewModel.Factory` из `RepositoryProvider` (DIP), nav-аргументы (`replyToEmailId`/`forwardEmailId`/`editDraftId`/`initialTo/Subject/Body`) в фабрику. Диспетчер — конструктором (тестируемость). **Сложность: M.** Риск: нет.

### Этап 3 — Загрузчики reply/forward/draft в `viewModelScope` (CS-3, CS-4, CS-15) · Приоритет 3
- Извлечь единый `AttachmentLoader`/use-case (устраняет CS-15, DRY): параметры — источник (`reply`/`forward`/`draft`), каталог, `download`-функция, наличие `collectionId`. Резолвинг `cid:` через `fetchInlineImages`/`fetchInlineImagesEws` — вынести в общий шаг.
- Перенести загрузку в `viewModelScope` (переживает поворот, снимает CS-3); флаг «загружено» ставить **после** успешного завершения.
- **CS-4**: лимит суммарного размера/числа резолвинга inline-картинок (аналог PB-2).
- **Сложность: L** (самый сложный шаг). **Риск Exchange 2007: средний** — затрагивает EWS-пути inline-картинок/вложений (Exchange 2007 SP1 использует `fetchInlineImagesEws`, `downloadDraftAttachment`, EWS ItemId). Протокольные вызовы **не менять** — только их размещение/дедупликация. Обязательная регрессия reply/forward/draft с inline и файловыми вложениями на Exchange 2007 SP1.

### Этап 4 — Полный `ComposeViewModel` (CS-6, CS-8, CS-9, CS-16) · Приоритет 4
- Перенести в VM: оркестрацию `sendEmail`/`saveDraft`, нормализацию/группы/дедуп, дельта-детект черновика, подписи, аккаунты; DAO-доступ делегировать репозиториям (устраняет CS-16, SRP + границы слоёв).
- Убрать ручной `destroy()` WebView — освобождение через `AndroidView.onRelease` (CS-6, по официальной рекомендации §4).
- Сброс `isSending`/`isSavingDraft` в `finally`; поведение `flushEditorHtml` по таймауту — явное (CS-9).
- UI-only остаётся в Composable: `RichTextEditor`/WebView, launcher'ы файлов/картинок, `compressImageForInline`, рендер полей, локализация.
- Сохранить `catch(CancellationException){throw}` (§4.3); реактивные `observe`-потоки — с `loggingExceptionHandler` (CR-2) и `collectAsStateWithLifecycle` (N-8).
- **Сложность: XL** (2549 строк; мигрировать инкрементально — сперва «ядро», как делали для `EmailDetailScreen`). Риск Exchange 2007: нет (перенос слоя представления; протокол/`SendController` не трогаем).

### Этап 5 — Тесты (JUnit + MockK, без Robolectric) · Приоритет 4
- `ComposeViewModelTest`: валидация получателей, раскрытие групп, дедуп, **бюджет вложений включая inline** (CS-1/CS-2), ветки send/saveDraft, дебаунс подсказок, crash-resistance (репозиторий бросает → `Error`-событие, не краш).
- Юнит-тест helper'а бюджета и `AttachmentLoader`. **Сложность: M–L.** Риск: нет.

### Этап 6 — Опционально · Приоритет 5
- **CS-10**: авто-сохранение черновика (по таймеру/`onStop`) — в VM удобно. **Сложность: S–M.**
- **CS-11**: подтвердить/добавить очистку `reply_/forward_/draft_attachments` в `AppFileCleanupService`. **Сложность: S.**
- **CS-13/CS-14**: уточнить требование drag-n-drop; дружелюбная ошибка для IMAP/POP3-отправки. **Сложность: T–S.**

---

## 7. Быстрый чек-лист приоритетов

1. **Этап 0** (CS-1, CS-2, CS-5, CS-8, +EAS `sendMail` guard) — закрыть OOM/утечку/залипание малой кровью.
2. **Этап 1** (CS-7) — дебаунс.
3. **Этапы 2–3** (CS-3, CS-4, CS-15) — инфраструктура MVVM + единый `AttachmentLoader`.
4. **Этап 4** (CS-6, CS-9, CS-16) — полный `ComposeViewModel`.
5. **Этап 5** — тесты.
6. **Этап 6** — авто-сейв, очистка, уточнения.

> Соответствие принципам: **DRY** — единый `checkAttachmentBudget` и `AttachmentLoader` вместо дублей (CS-1/CS-2/CS-15); **KISS** — симметричные лимиты во всех путях отправки, устранение ручного WebView-destroy; **SOLID/SRP+DIP** — вынос бизнес-логики и DAO-доступа из `@Composable` в тестируемый `ComposeViewModel` с конструкторной инъекцией (CS-16), системные эффекты за интерфейсом.

---

## 8. Статус реализации

### Этапы 0–1 — выполнено (03.07.2026)

Все правки — в слое представления (`ComposeScreen.kt`), протокол EAS/EWS и совместимость с Exchange 2007 SP1/SP2 **не затронуты**.

- **CS-1 + CS-2 (решено).** Введены `inlineImageBytes(body)` и единая `composeAttachmentBudgetBytes(context, attachments, body)` (DRY/SRP). Бюджет (файловые вложения + inline data:URL-картинки в теле, оценка base64 → `len*3/4`, RFC 4648) проверяется ДО чтения байт в память И при отправке, И при сохранении черновика. Закрыт OOM в `saveDraft` и обход лимита inline-картинками при отправке. Длина base64 измеряется через `MatchGroup.range` (O(1), без материализации подстроки) — `groupValues[2]` скопировал бы всю картинку (мегабайты) в память ради `.length`.
- **CS-5 (решено).** `SendController.startSend(context = context.applicationContext)` — убрано окно утечки Activity в процесс-долгоживущем scope.
- **CS-7 (решено).** Дебаунс локального поиска подсказок: `delay(SUGGESTION_DEBOUNCE_MS = 200)` в начале `suggestionSearchJob` (идиома «отмена предыдущего job + delay»). Запросы к БД — только после паузы ввода.
- **CS-8 (переоценено → откат, отложено до MVVM).** Первичная правка сбрасывала `isSending` на отложенном пути. При самопроверке выявлено: после успешной постановки письма в очередь WorkManager сброс `isSending` при (редком) сбое навигации **включил бы кнопку отправки → риск двойной отправки**. Держать кнопку заблокированной безопаснее (письмо уже запланировано; «застрявший» спиннер — косметика). Правка откатана; корректное решение — в общем сбросе состояния при MVVM-миграции (Этап 4), а не точечным патчем. Downgrade: Низкая → косметика.

### Низкие (CS-6, CS-9, CS-11) — выполнено (03.07.2026)

Все правки — слой представления / файловая очистка; протокол EAS/EWS и Exchange 2007 SP1/SP2 **не затронуты**.

- **CS-6 (решено).** Убран ручной `webView.destroy()` в `sendEmail` — единственный teardown теперь через `RichTextEditor.onRelease`, который срабатывает при `hideWebView = true` (удаление редактора из композиции) ДО навигации и делает полный teardown (`stopLoading`/`about:blank`/`removeView`/`destroy`/`webView=null`/`isLoaded=false`). Устранён двойной `destroy()` одного инстанса. Совпадает с паттерном teardown WebView в `EmailDetailScreen` (единственный путь в `onDispose`) и с офиц. рекомендацией (WebView уничтожается вместе с композицией/Activity, ссылку на view не держать вне `AndroidView`).
- **CS-9 (решено).** `flushEditorHtml`: при таймауте flush вместо устаревшего `body` берётся самый свежий `richTextController.latestHtml` (@Volatile, обновляется немедленно на каждый ввод через `cacheHtml`). Пусто → `body` не перезатирается. Последние введённые символы не теряются при отправке/сохранении; хуже прежнего поведение не становится.
- **CS-11 (решено).** `AttachmentManager.cleanupOldAttachments` расширен (DRY: единый `cleanupDirByAgeAndSize`) на все внутренние temp-каталоги вложений: `attachments`, `reply_attachments`, `forward_attachments`, `draft_attachments`. Возрастная очистка (7 дней) + cap 500 МБ на каталог; порог возраста исключает удаление файлов активной сессии. Вызывается на старте (`MailApplication.onCreate` → IO).

**Осознанно отложено (defense-in-depth, не требуется после CS-2):** guard `maxMimeSize` в `EasEmailService.sendMail`. После фикса CS-2 все реальные вызывающие `sendMail` (`SendController`, `OutboxWorker`, `ScheduledEmailWorker`) получают тело, уже прошедшее UI-бюджет, поэтому дополнительный протокольный guard избыточен (KISS) и потребовал бы менять непроверяемый сейчас код Exchange 2007. DRY-дедупликация лимитов 7/10 МБ в `EasAttachmentService` — кандидат на будущий рефакторинг.

**Тесты (добавлено):** `ComposeAttachmentSizeTest` расширен для `inlineImageBytes` и `composeAttachmentBudgetBytes` (нет data:URL → 0; оценка `len*3/4`; суммирование нескольких; файлы+inline; файлы под лимитом + inline сверх лимита). Чистый JUnit + MockK, без Robolectric.

**Не покрыто в этом проходе (по плану — отдельные этапы):** CS-3, CS-4, CS-15 (Этап 3, единый `AttachmentLoader` + `viewModelScope`), CS-8, CS-16 (Этап 4, MVVM), CS-10/CS-13/CS-14 (Этап 6). Дебаунс (CS-7) и teardown WebView (CS-6) — UI-поведение с таймингом, покрываются инструментальными/Compose-тестами, не чистым JUnit.

### Проверка через интернет (best practices), пошагово

- MVVM/UDF, ре-throw `CancellationException`, WebView не переживает Activity, `StateFlow.debounce` — §4.
- base64 → байты `(3·len/4)−padding ≈ len·3/4` — [SO](https://stackoverflow.com/questions/34546498/calculate-the-size-to-a-base-64-decoded-message), [aaronlenoir](https://blog.aaronlenoir.com/2017/11/10/get-original-length-from-base-64-string/); оценка консервативна (безопасна для бюджета).
- `MatchGroup.range` даёт длину группы за O(1) без аллокаций и доступен на JVM/Android — [kotlinlang.org](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.text/-match-group/index.html); в отличие от `groupValues`, который материализует захваченные строки — [kotlinlang.org](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/-match-result/group-values.html).
- Debounce через «отмена job + delay» — канонический паттерн вне Flow ([SO 57252799](https://stackoverflow.com/a/57252799/6352712)).
- Teardown WebView (CS-6): экземпляр уничтожается вместе с композицией/Activity, ссылку на view не держат вне `AndroidView` — [Wrap a WebView in Compose](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/wrap-webview-in-compose); единый путь освобождения через `onRelease`/`onDispose` (сверено с `EmailDetailScreen`).

---

© 2025–2026 DedovMosol — внутренний аудит, не является пользовательской документацией.
