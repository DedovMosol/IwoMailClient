# План миграции на XmlPullParser (v1.7.0)

> Обновлено: 11.03.2026

## Текущее состояние
- **~280 XML-Regex** в 8 EAS/EWS сервисах (считая inline `.toRegex()`, `deps.extractValue()`, `XmlValueExtractor.*`, `EasPatterns.*`; без MIME-паттернов)
- `EasPatterns.kt` — кэш Regex паттернов (ConcurrentHashMap + `getTagPattern`/`getTagPatternWithNs`)
- `XmlValueExtractor.kt` — обёртка над Regex с namespace support
- `deps.extractValue()` — per-service Regex extraction
- Calendar декомпозирован из монолита в 7 файлов (EasCalendarService → facade + 6 сервисов)
- Последние рефакторинги синхронизации/уведомлений (`PushService`, `SyncWorker`, `NotificationHelper`) не меняли XML parsing surface; scope миграции остаётся прежним

| Сервис | XML Regex | MIME (не мигрируем) | Сложность |
|--------|-----------|---------------------|-----------|
| Calendar (7 файлов) | ~77 | 0 | Очень высокая |
| EasTasksService | ~68 | 4 | Очень высокая |
| EasNotesService | ~47 | 0 | Высокая |
| EasDraftsService | ~39 | 2 | Высокая |
| EasClient | ~20 | 0 | Критическая |
| EasContactsService | ~18 | 2 | Средняя |
| EasEmailService | ~15 | ~12 | Средняя |
| EasAttachmentService | ~3 | 3 | Низкая |

## Утилита (ГОТОВА)
`EasXmlParser.kt` — обёртка над `android.util.Xml.newPullParser()`:
- Drop-in замены: `extractValue`, `extractAttribute`, `extractAll`, `extractAllAttributes`
- Обход: `forEachElement`, `collectElements`
- Проверка: `hasElement`
- DSL: `parse(xml) { onElement("Body") { text() } }`
- Shorthand: `.email()`, `.email2()`, `.ews()`, `.note()`, `.task()`, `.calendar()`, `.contact()`, `.gal()`, `.airsync()`, `.composeMail()`, `.soap()`

## Ключевые решения

### FEATURE_PROCESS_NAMESPACES = false
- EAS XML после WBXML-декодирования не всегда содержит `xmlns` declarations
- Exchange 2007 SP1 EWS — содержит, но проще работать с raw prefix:name
- Метод `matchesTag("t:Subject", "Subject", ["t"])` обрабатывает оба случая

### Что НЕ мигрируем (остаётся Regex)
- MIME парсинг (Content-Type, boundary, Content-ID, MDN headers, Message-ID) — это НЕ XML
- `EasPatterns.MDN_DISPOSITION`, `MDN_RETURN_RECEIPT`, `MDN_CONFIRM_READING`, `MIME_MESSAGE_ID`, `BOUNDARY`, `EMAIL_BRACKET` — оставляем
- Inline image extraction из HTML (Content-ID, Content-Type, data:image patterns) — не XML
- HTML-чистка regex (BR, P, DIV tags в removeDuplicateLines) — не XML

## Порядок миграции (от простого к сложному)

### Этап 1: EasContactsService (~18 regex)
- 4 inline pattern + 12 `deps.extractValue` + 2 `XmlValueExtractor`
- GAL Search response, Sync Add/Change parsing
- Проверить: GAL поиск, список контактов, синхронизация контактов

### Этап 2: EasAttachmentService (~3 regex)
- 3 `EasPatterns` (ItemOps status/data/props)
- FileReference extraction, content download
- Проверить: скачивание вложений email/calendar/tasks, отправка MDN

### Этап 3: EasNotesService (~47 regex)
- ~15 inline patterns + 22 `deps.extractValue` + 8 `XmlValueExtractor` + 2 `EasPatterns`
- Sync response парсинг (Add/Change/Delete)
- EWS CreateItem/UpdateItem/GetItem для заметок
- `parseNoteFromXml`, `parseEwsNotesResponse`
- Проверить: CRUD заметок, синхронизация, Exchange 2007 SP1

### Этап 4: EasTasksService (~68 regex)
- ~30 companion patterns + 25 `XmlValueExtractor` + 12 `deps.extractValue` + 1 `EasPatterns`
- `parseTaskFromXml`, `parseEwsTasksResponse`
- `discoverTaskSubfolderIds` — FindFolder response парсинг
- `getTaskBodiesEws` — GetItem для тел задач
- Проверить: CRUD задач, синхронизация, Exchange 2007 SP1, задачи без дат

### Этап 5: EasEmailService (~15 XML regex + ~12 MIME)
- XML: AIRSYNC_DATA_REGEX, responsePattern, itemPattern, bodyPattern + `deps.extractValue`
- MIME (НЕ ТРОГАТЬ): MDN_*, MIME_MESSAGE_ID, BOUNDARY, Content-ID/Type, htmlPartPattern, textPartPattern
- Sync Add/Change/Delete, ItemOperations Data extraction
- Проверить: полный цикл работы с почтой, MDN диалог

### Этап 6: EasDraftsService (~39 regex)
- 23 companion patterns + 12 `XmlValueExtractor` + 4 `EasPatterns`
- EWS CreateItem MimeContent
- GetItem для черновиков
- Attachment download
- Проверить: создание/редактирование/отправка черновиков

### Этап 7: Calendar (~77 regex) — самый сложный, декомпозирован

Календарь декомпозирован из монолита `EasCalendarService` в 7 файлов:

| Файл | XML Regex | Описание |
|------|-----------|----------|
| EasCalendarService.kt | 0 | Facade (делегирует) |
| CalendarXmlParser.kt | ~35 | Парсинг событий, attendees, EWS |
| EasCalendarCrudService.kt | ~11 | CRUD + EWS paths |
| EasCalendarSyncService.kt | ~10 | Sync loop + SyncKey |
| CalendarAttachmentService.kt | ~10 | EWS attachment CRUD |
| CalendarExceptionService.kt | ~7 | Recurring exceptions |
| CalendarDateUtils.kt | ~4 | Date/timezone regex |
| CalendarRecurrenceBuilder.kt | 0 | XML building (нет regex) |

- Проверить: CRUD событий, повторяющиеся события, вложения, приглашения, Exchange 2007 SP1

### Этап 8: EasClient (~20 regex)
- КРИТИЧЕСКИЙ — FolderSync, Provision, Autodiscover, EWS fallback
- 14 `EasPatterns` aliases + 2 inline + ~8 `deps.extractValue`
- `fetchEmailBodyViaEws` (FindItem + GetItem)
- FolderHierarchy парсинг
- Проверить: авторизация, синхронизация папок, тело письма fallback

## Стратегия миграции каждого сервиса

1. **Заменить `deps.extractValue(xml, "Tag")` на `EasXmlParser.extractValue(xml, "Tag")`**
   - Самая простая замена, 1:1
2. **Заменить `XmlValueExtractor.extractEws(xml, "Tag")` на `EasXmlParser.ews(xml, "Tag")`**
3. **Заменить `XmlValueExtractor.extractAttribute(xml, "El", "Attr")` на `EasXmlParser.extractAttribute(xml, "El", "Attr", listOf("t"))`**
4. **Заменить inline `"<Tag>(.*?)</Tag>".toRegex().find(xml)` на `EasXmlParser.extractValue(xml, "Tag")`**
5. **Заменить `pattern.findAll(xml).forEach` на `EasXmlParser.forEachElement(xml, "Tag") { ... }`**
6. **Сложные вложенные парсинги → DSL `EasXmlParser.parse(xml) { ... }`**

## После полной миграции (cleanup)
- Удалить `EasPatterns.kt` (кроме MIME regex: `MDN_*`, `MIME_MESSAGE_ID`, `BOUNDARY`, `EMAIL_BRACKET`)
- Удалить `XmlValueExtractor.kt`
- Удалить `extractValue` из `EasClient` и deps
- Удалить `extractValueCache` из `EasClient`
- Удалить `getTagPattern` / `getTagPatternWithNs` из `EasPatterns`

## Тестирование
- Каждый этап = отдельный коммит
- После каждого этапа: полный регрессионный тест соответствующей функциональности
- Exchange 2007 SP1: обязательная проверка EWS fallback (этапы 3-8)
- Проверка с разными типами Exchange серверов
