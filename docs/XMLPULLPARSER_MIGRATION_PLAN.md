# План миграции на XmlPullParser (v1.7.0)

## Текущее состояние
- **251 Regex** в 8 EAS/EWS сервисах
- `EasPatterns.kt` — кэш Regex паттернов (ConcurrentHashMap)
- `XmlValueExtractor.kt` — обёртка над Regex с namespace support
- `deps.extractValue()` — per-service Regex extraction

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

### Этап 2: EasAttachmentService (10 regex)
- FileReference extraction, content download
- Проверить: скачивание вложений email/calendar/tasks

### Этап 3: EasNotesService (17 regex)
- Sync response парсинг (Add/Change/Delete)
- EWS CreateItem/UpdateItem/GetItem для заметок
- `parseNoteFromXml`, `parseEwsNotesResponse`
- Проверить: CRUD заметок, синхронизация, Exchange 2007 SP1

### Этап 4: EasTasksService (23 regex)
- Аналогично EasNotesService
- `parseTaskFromXml`, `parseEwsTasksResponse`
- Проверить: CRUD задач, синхронизация, Exchange 2007 SP1

### Этап 5: EasDraftsService (35 regex)
- EWS CreateItem MimeContent
- GetItem для черновиков
- Attachment download
- Проверить: создание/редактирование/отправка черновиков

### Этап 6: EasEmailService (44 regex)
- ItemOperations Data extraction
- Attachment parsing
- Sync Add/Change/Delete
- Mark read/flag/move
- Inline image MIME parsing — НЕ ТРОГАТЬ (остаётся Regex)
- Проверить: полный цикл работы с почтой

### Этап 7: EasCalendarService (74 regex) — самый сложный
- Sync response с событиями (много полей)
- EWS FindItem/GetItem/CreateItem/UpdateItem/DeleteItem
- Attendees, Exceptions, Attachments парсинг
- Проверить: CRUD событий, повторяющиеся события, Exchange 2007 SP1

### Этап 8: EasClient (44 regex)
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
