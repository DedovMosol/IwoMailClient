# План миграции на XmlPullParser (v1.7.0)

> Обновлено: 27.02.2026

## Текущее состояние
- **190 Regex** в 8 EAS/EWS сервисах (было 251 — часть вынесена в `EasPatterns`, часть заменена `XmlValueExtractor`)
- `EasPatterns.kt` — кэш Regex паттернов (ConcurrentHashMap)
- `XmlValueExtractor.kt` — обёртка над Regex с namespace support
- `deps.extractValue()` — per-service Regex extraction

| Сервис | Regex | Сложность |
|--------|-------|-----------|
| EasCalendarService | 70 | Очень высокая |
| EasDraftsService | 30 | Высокая |
| EasTasksService | 26 | Средняя |
| EasEmailService | 25 | Высокая |
| EasClient | 18 | Критическая |
| EasNotesService | 15 | Средняя |
| EasContactsService | 4 | Низкая |
| EasAttachmentService | 2 | Низкая |

## Утилита (ГОТОВА)
`EasXmlParser.kt` — обёртка над `android.util.Xml.newPullParser()`:
- Drop-in замены: `extractValue`, `extractAttribute`, `extractAll`
- Обход: `forEachElement`, `collectElements`
- DSL: `parse(xml) { onElement("Body") { text() } }`
- Shorthand: `.eas()`, `.ews()`, `.note()`, `.task()`, `.calendar()`, `.contact()`, `.gal()`, `.airsync()`

## Ключевые решения

### FEATURE_PROCESS_NAMESPACES = false
- EAS XML после WBXML-декодирования не всегда содержит `xmlns` declarations
- Exchange 2007 SP1 EWS — содержит, но проще работать с raw prefix:name
- Метод `matchesTag("t:Subject", "Subject", ["t"])` обрабатывает оба случая

### Что НЕ мигрируем (остаётся Regex)
- MIME парсинг (Content-Type, boundary, Content-ID, MDN headers) — это НЕ XML
- `EasPatterns.MDN_*`, `BOUNDARY`, `EMAIL_BRACKET` — оставляем
- Inline image extraction из HTML — не XML

## Порядок миграции (от простого к сложному)

### Этап 1: EasContactsService (4 regex)
- `extractValue` для GAL Search response
- Простейший сервис, идеален для smoke test
- Проверить: GAL поиск, список контактов

### Этап 2: EasAttachmentService (2 regex)
- FileReference extraction, content download
- Проверить: скачивание вложений email/calendar/tasks

### Этап 3: EasNotesService (15 regex)
- Sync response парсинг (Add/Change/Delete)
- EWS CreateItem/UpdateItem/GetItem для заметок
- `parseNoteFromXml`, `parseEwsNotesResponse`
- Проверить: CRUD заметок, синхронизация, Exchange 2007 SP1

### Этап 4: EasTasksService (26 regex)
- Аналогично EasNotesService + EWS FindItem (AllProperties)
- `parseTaskFromXml`, `parseEwsTasksResponse`
- `discoverTaskSubfolderIds` — FindFolder response парсинг
- `getTaskBodiesEws` — GetItem для тел задач
- Проверить: CRUD задач, синхронизация, Exchange 2007 SP1, задачи без дат

### Этап 5: EasEmailService (25 regex)
- ItemOperations Data extraction
- Attachment parsing
- Sync Add/Change/Delete
- Mark read/flag/move
- Inline image MIME parsing — НЕ ТРОГАТЬ (остаётся Regex)
- Проверить: полный цикл работы с почтой

### Этап 6: EasDraftsService (30 regex)
- EWS CreateItem MimeContent
- GetItem для черновиков
- Attachment download
- Проверить: создание/редактирование/отправка черновиков

### Этап 7: EasCalendarService (70 regex) — самый сложный
- Sync response с событиями (много полей)
- EWS FindItem/GetItem/CreateItem/UpdateItem/DeleteItem
- Attendees, Exceptions, Attachments парсинг
- `deleteCalendarAttachments` — DeleteAttachment response
- `findCalendarItemIdBySubject` — FindItem response
- Проверить: CRUD событий, повторяющиеся события, вложения, Exchange 2007 SP1

### Этап 8: EasClient (18 regex)
- КРИТИЧЕСКИЙ — FolderSync, Provision, Autodiscover, EWS fallback
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
- Удалить `EasPatterns.kt` (кроме MIME regex)
- Удалить `XmlValueExtractor.kt`
- Удалить `extractValue` из `EasClient` и deps
- Удалить `extractValueCache` из `EasClient`

## Тестирование
- Каждый этап = отдельный коммит
- После каждого этапа: полный регрессионный тест соответствующей функциональности
- Exchange 2007 SP1: обязательная проверка EWS fallback (этапы 3-8)
- Проверка с разными типами Exchange серверов
