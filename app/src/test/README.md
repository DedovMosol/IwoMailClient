# Unit Tests для Mail Client

## Структура тестов

```
test/
└── java/com/dedovmosol/iwomail/
    ├── eas/
    │   ├── EasXmlTemplatesTest.kt           # Генерация XML-шаблонов
    │   ├── EasNotesServiceTest.kt           # Юнит-тесты сервиса заметок
    │   ├── EasClientNotesIntegrationTest.kt # Интеграционные тесты делегирования
    │   ├── XmlUtilsTest.kt                  # escape/unescape, извлечение тегов
    │   ├── XmlValueExtractorTest.kt         # Унифицированное извлечение значений
    │   ├── EasPatternsTest.kt               # Regex-паттерны EAS/EWS + кэш
    │   ├── WbxmlParserSendMailTest.kt       # SendMail WBXML: стабильный ClientId, дедуп (N-11)
    │   ├── EasMimeHeaderSanitizeTest.kt     # stripHeaderCrlf: защита от инъекции MIME/MDN-заголовков (N-1)
    │   └── EasMimeSubjectEncodingTest.kt    # RFC 2047 Subject folding + UTF-8 chunking (N-3)
    ├── util/
    │   ├── HtmlUtilsTest.kt                 # escapeHtml, sanitizeEmailHtml (XSS), strip
    │   ├── EmailUtilsTest.kt                # Имена/адреса/получатели, размеры файлов
    │   ├── DateUtilsTest.kt                 # Границы дня, диапазон (TZ-pinned)
    │   ├── ICalParserTest.kt                # iCalendar/задачи (TZ-pinned)
    │   └── MimeHtmlProcessorInlineImageTest.kt # Единое извлечение inline-картинок MIME + guard рекурсии (N-5)
    └── ui/
        ├── components/
        │   └── RichTextEditorSanitizeTest.kt   # Санитайзер редактора: on*/javascript:/base/link (L-3)
        └── screens/
            ├── SearchViewModelTest.kt          # MVVM: StateFlow + one-shot события (DIP-моки)
            ├── SyncCleanupViewModelTest.kt     # MVVM: настройки синхронизации/очистки (SyncEffects-мок)
            ├── NotesViewModelTest.kt           # MVVM: заметки/корзина/выделение + прогресс-обёртки
            ├── TasksViewModelTest.kt           # MVVM: задачи/корзина/фильтр + пакетные прогресс-обёртки
            ├── UserFoldersViewModelTest.kt     # MVVM: папки/фильтр/выделение + пакетное удаление
            ├── EmailListViewModelTest.kt       # MVVM: письма/фильтры/выделение + пакетные операции и синк
            ├── EmailDetailViewModelTest.kt     # MVVM: письмо/тело/inline-картинки + операции (удаление/перенос/MDN)
            ├── ComposeAttachmentSizeTest.kt    # Лимит суммарного размера вложений ДО чтения в память (N-2)
            └── compose/
                └── ComposeTextUtilsTest.kt     # Подпись/цитата, cid→data:, извлечение email
```

## Запуск тестов

### Из командной строки

```bash
# Все тесты
./gradlew test

# Только unit тесты (быстрые)
./gradlew testDebugUnitTest

# С подробным выводом
./gradlew test --info

# Конкретный класс тестов
./gradlew test --tests "com.dedovmosol.iwomail.eas.EasXmlTemplatesTest"

# Конкретный тест
./gradlew test --tests "com.dedovmosol.iwomail.eas.EasXmlTemplatesTest.syncInitial generates valid XML"
```

### Из Android Studio

1. Правый клик на файле теста → Run 'TestName'
2. Правый клик на папке `test/` → Run 'Tests in...'
3. View → Tool Windows → Run → выбрать тест

## Покрытие тестами

### EasXmlTemplates (20/20 методов)
- ✅ syncInitial
- ✅ syncWithBody
- ✅ syncLegacy
- ✅ syncDelete
- ✅ folderSync
- ✅ searchGal
- ✅ searchMailbox
- ✅ ewsSoapRequest
- ✅ ewsDeleteItem
- ✅ ewsFindItem
- ✅ ewsGetItem
- ✅ noteCreate
- ✅ noteUpdate
- ✅ ewsNoteCreate
- ✅ ewsNoteUpdate
- ✅ ewsTaskCreate
- ✅ ewsFindTasks
- ✅ moveItems
- ✅ SOAP constants

### EasNotesService (6/6 публичных методов)
- ✅ syncNotes
- ✅ createNote
- ✅ updateNote
- ✅ deleteNote
- ✅ deleteNotePermanently
- ✅ restoreNote

### SearchViewModel (MVVM-слой, 13 тестов)
Первый юнит-тест ViewModel. VM тестируется БЕЗ Robolectric благодаря конструкторной инъекции (DIP): репозитории — MockK-моки, IO-диспетчер и `Dispatchers.setMain` — тестовые.
- ✅ onQueryChange / setDateFilter / clearQuery
- ✅ search: игнор < 2 символов, наполнение результатов, пасхалка (`ShowEasterEgg`)
- ✅ выделение: toggle / set
- ✅ deleteSelected: `MovedToTrash` / `DeletedPermanently` / `ShowError` + удаление из списка
- ✅ markSelectedAsRead / starSelected (оптимистичное обновление + сброс выделения)
- ✅ one-shot события через `Channel`/`receiveAsFlow` (сбор в `backgroundScope`)

### SyncCleanupViewModel (MVVM-слой, 15 тестов)
VM без Robolectric: `AccountRepository`, `SettingsRepository` и интерфейс `SyncEffects` (push/reschedule) — MockK-моки.
- ✅ init: загрузка аккаунта + настроек очистки в state
- ✅ setSyncMode PUSH/SCHEDULED: запись режима + `setPushEnabled` + `rescheduleSync` + refresh
- ✅ setSyncInterval/NightMode/IgnoreBatterySaver: запись + `rescheduleSync` (без push)
- ✅ contacts/notes/calendar/tasks intervals: запись + НЕ reschedule (verify exactly=0)
- ✅ autoCleanup trash/drafts/spam: запись + refresh аккаунта
- ✅ downloads/rollback days: запись в settings + оптимистичное обновление state

### NotesViewModel (MVVM-слой)
VM без Robolectric: `NoteRepository` + `AccountRepository` — MockK-моки, IO-диспетчер тестовый.
- ✅ init: загрузка заметок/корзины/счётчика в state
- ✅ авто-синхронизация ровно один раз при пустом локальном списке
- ✅ syncNotes: `Synced`/`Error`
- ✅ выбор вкладки корзины: тихий синк + троттлинг 30с + сброс выделения
- ✅ create/update: `NoteCreated`+`ScrollToTop` / `NoteUpdated`, защита от double-tap, `Error`
- ✅ мягкое удаление (одиночное/пакетное) + сброс выделения
- ✅ выделение/запрос; делегирование прогресс-обёрток репозиторию

### TasksViewModel (MVVM-слой)
VM без Robolectric: `TaskRepository` + `AccountRepository` — MockK-моки, IO-диспетчер тестовый, `initialFilter` в конструкторе.
- ✅ init: задачи/корзина/email аккаунта в state; учёт `initialFilter`
- ✅ авто-синхронизация один раз при пустом списке; пропуск при наличии данных
- ✅ syncTasks: `Synced`/`Error`
- ✅ selectFilter(DELETED): тихий синк + троттлинг 30с + сброс обоих наборов выделения
- ✅ create/update: `TaskCreated`/`TaskUpdated`, защита от double-tap, `Error`
- ✅ toggleComplete → `CompleteToggled`; мягкое удаление (одиночное/пакетное) + сброс выделения
- ✅ выделение (активные/удалённые/removeFromSelection); запрос
- ✅ пакетные прогресс-обёртки: цикл `restoreTasks`/`deleteTasksPermanently` + `onProgress` + подсчёт успехов; `emptyTrash`

### UserFoldersViewModel (MVVM-слой)
VM без Robolectric: `MailRepository` + `AccountRepository` — MockK-моки, IO-диспетчер тестовый.
- ✅ init: фильтр только пользовательских папок (EAS type 1/12), сортировка по имени, `isInitialLoadDone`
- ✅ авто-синхронизация один раз при пустом списке; пропуск при наличии папок; нет активного аккаунта → no-op
- ✅ syncFolders: `Synced`/`Error`
- ✅ create: `FolderCreated`/`Error`, trim имени, защита от double-tap, пустое имя → no-op
- ✅ rename: `FolderRenamed`/`Error` (trim), пустое имя → no-op
- ✅ delete (одиночное): `FolderDeleted`/`Error`
- ✅ пакетное удаление: цикл по выбранным + прогресс в state (синхронная инициализация `0 to N`), `FoldersDeleted(count)` + первая `Error`, сброс выделения; пустой выбор → no-op
- ✅ выделение: toggle/set/clear/selectAll; сброс устаревших id при изменении списка папок

### EmailListViewModel (MVVM-слой)
VM без Robolectric: `MailRepository` + `AccountRepository` — MockK-моки, IO-диспетчер тестовый, `folderId`/`initialFilter`/`initialDateFilter` в конструкторе.
- ✅ init: письма/папки/текущая папка/`accountId` из единого реактивного потока; `showFilters` при начальном фильтре
- ✅ спец-папки: «Избранное» → `getFlaggedEmails` (folder=null), «Сегодня» → `getTodayEmailsAcrossFolders`
- ✅ авто-синк «Черновиков» ровно один раз; обычная папка не синкается
- ✅ refresh: `isRefreshing` toggle, `Error`+`dismissError`, no-op в «Избранном»
- ✅ фильтры: setMailFilter/setDateFilter/toggleFilters/clearFilters
- ✅ выделение: toggle/set/clear/selectAll (выбрать все ↔ снять)
- ✅ пакетные операции: `MovedToTrash`/`DeletedPermanently`/`Moved`/`Restored`/`MovedToSpam` + сброс выделения; пустой выбор → no-op
- ✅ markSelectedAsRead/starSelected/toggleFlag — делегирование репозиторию; `Error` для batch
- ✅ `deleteEmailsPermanently` — делегирование прогресс-обёртки репозиторию

### EmailDetailViewModel (MVVM-слой, ядро экрана)
VM без Robolectric: `EmailDetailActions` + `MailRepository` + `AccountRepository` — MockK-моки, IO-диспетчер тестовый, `emailId` в конструкторе.
- ✅ init: письмо/вложения/папки из единого реактивного потока; derive флагов корзина/отправленные/черновики по типу папки
- ✅ openEmail: непрочитанное → markAsRead; пустое тело → loadEmailBody; отсутствует → `BodyLoadError.NotFound`
- ✅ OBJECT_NOT_FOUND и письма больше нет → `DeletedOnServer` + `NavigateBack`
- ✅ inline-картинки загружаются в состояние
- ✅ refresh: `Refreshed` / `NoBodyFromServer` / `Error` + соответствующий `bodyLoadError`
- ✅ операции: deleteToTrash (`MovedToTrash`/`DeletedPermanently`+`NavigateBack`), move (`Moved`), restore (`Restored`), markUnread/sendMdn `Error`
- ✅ toggleFlag/dismissMdn — делегирование; `deleteEmailPermanently` — прогресс-обёртка репозиторию

### EasClient (делегирование заметок)
- ✅ syncNotes → notesService
- ✅ createNote → notesService
- ✅ updateNote → notesService
- ✅ deleteNote → notesService
- ✅ deleteNotePermanently → notesService
- ✅ restoreNote → notesService

## Используемые библиотеки

- **JUnit 4** - фреймворк для тестирования
- **MockK** - мокирование для Kotlin
- **Google Truth** - улучшенные ассершены
- **Coroutines Test** - тестирование suspend функций

## Отчеты о тестах

После запуска тесты генерируют HTML-отчет:

```
app/build/reports/tests/testDebugUnitTest/index.html
```

## Лучшие практики

### ✅ DO:
- Тестируй публичный API, а не приватные методы
- Используй `runTest` для suspend функций
- Моки через MockK для внешних зависимостей
- Проверяй и успешные, и ошибочные сценарии
- Используй понятные имена тестов (backticks в Kotlin)

### ❌ DON'T:
- Не тестируй приватные методы напрямую
- Не делай тесты зависимыми друг от друга
- Не используй реальные сетевые запросы
- Не тестируй Android Framework напрямую

## Примеры

### Тест генерации XML
```kotlin
@Test
fun `syncInitial generates valid XML`() {
    val xml = EasXmlTemplates.syncInitial("folder123")
    
    assertThat(xml).contains("<SyncKey>0</SyncKey>")
    assertThat(xml).contains("<CollectionId>folder123</CollectionId>")
}
```

### Тест с моками
```kotlin
@Test
fun `createNote creates note successfully`() = runTest {
    coEvery { getNotesFolderId() } returns "notes123"
    coEvery { executeEasCommand<String>(any(), any(), any()) } 
        returns EasResult.Success("noteId")
    
    val result = service.createNote("Test", "Body")
    
    assertThat(result).isInstanceOf(EasResult.Success::class.java)
    coVerify { getNotesFolderId() }
}
```

### Интеграционный тест
```kotlin
@Test
fun `syncNotes delegates to notesService`() = runTest {
    coEvery { notesService.syncNotes() } returns EasResult.Success(notes)
    
    val result = client.syncNotes()
    
    coVerify(exactly = 1) { notesService.syncNotes() }
}
```

## Что дальше?

### Сделано (чистый JUnit, без Android-зависимостей):
1. ✅ EasXmlTemplates — генерация XML
2. ✅ EasNotesService — юнит-тесты
3. ✅ EasClient — интеграционные тесты делегирования
4. ✅ XmlUtils — escape/unescape, извлечение тегов/блоков
5. ✅ XmlValueExtractor — извлечение значений XML
6. ✅ EasPatterns — regex-паттерны + кэш динамических паттернов
7. ✅ HtmlUtils — escapeHtml, sanitizeEmailHtml (XSS), strip, decodeNumericEntity
8. ✅ EmailUtils — имена/адреса/получатели, formatFileSize (Locale-pinned)
9. ✅ DateUtils — границы дня и диапазон (TZ-pinned)
10. ✅ ICalParser — iCalendar и задачи из писем (TZ-pinned)
11. ✅ ComposeTextUtils — подпись/цитата, cid→data:, извлечение email
12. ✅ SearchViewModel — MVVM-слой (StateFlow + события), тестируем через конструкторную инъекцию
13. ✅ SyncCleanupViewModel — MVVM-слой (настройки синхронизации/очистки), side-effects за интерфейсом `SyncEffects`
14. ✅ NotesViewModel — MVVM-слой (заметки/корзина/выделение + прогресс-обёртки)
15. ✅ TasksViewModel — MVVM-слой (задачи/корзина/фильтр + пакетные прогресс-обёртки)
16. ✅ UserFoldersViewModel — MVVM-слой (папки/фильтр/выделение + пакетное удаление с прогрессом)
17. ✅ EmailListViewModel — MVVM-слой (письма/фильтры/выделение + пакетные операции и синк)
18. ✅ EmailDetailViewModel — MVVM-слой, ядро экрана (письмо/тело/inline-картинки + операции удаление/перенос/восстановление/MDN)
19. ✅ WbxmlParserSendMail — детерминизм WBXML при стабильном ClientId, встраивание ClientId/raw MIME (дедуп N-11)
20. ✅ RichTextEditorSanitize — stripDangerousTags: `on*`/`javascript:`/`data:text/html`/`base`/`link` (L-3)
21. ✅ ComposeAttachmentSize — суммарный размер вложений и лимит 10 МБ ДО чтения в память (N-2)
22. ✅ EasMimeHeaderSanitize — stripHeaderCrlf: нейтрализация CRLF-инъекции адресных/Message-ID заголовков (N-1)
23. ✅ EasMimeSubjectEncoding — chunkByUtf8Bytes (UTF-8/суррогаты) + RFC 2047 folding Subject (N-3)
24. ✅ MimeHtmlProcessorInlineImage — единое извлечение CID→data:URL, вложенные multipart, guard рекурсии (N-5)

> **Паттерн тестирования ViewModel:** принимай зависимости (репозитории + `CoroutineDispatcher`) через конструктор. Фабрика берёт реальные из `RepositoryProvider`, тест — моки. Андроид-конструктор репозиториев не запускается (MockK через Objenesis), поэтому Robolectric не нужен.

### Не покрывается чистым JUnit (нужен Robolectric или androidTest):
- ⚪ RecurrenceHelper — использует `org.json.JSONObject` (Android-stub → "not mocked")
- ⚪ EasContactsService / EasTasksService — сетевые/Android-зависимости
- ⚪ Compose-экраны и диалоги — нужны Compose UI тесты в `androidTest`

### Цель: > 60% покрытия кода

## CI/CD Integration

Добавить в `.github/workflows/tests.yml`:

```yaml
- name: Run Unit Tests
  run: ./gradlew testDebugUnitTest
  
- name: Upload Test Report
  uses: actions/upload-artifact@v3
  with:
    name: test-results
    path: app/build/reports/tests/
```
