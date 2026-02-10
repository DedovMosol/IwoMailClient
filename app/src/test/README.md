# Unit Tests для Mail Client

## Структура тестов

```
test/
└── java/com/iwo/mailclient/eas/
    ├── EasXmlTemplatesTest.kt              # Тесты генерации XML-шаблонов
    ├── EasNotesServiceTest.kt               # Юнит-тесты сервиса заметок
    └── EasClientNotesIntegrationTest.kt     # Интеграционные тесты делегирования
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

### Следующие шаги:
1. ✅ EasXmlTemplates - базовые тесты
2. ✅ EasNotesService - юнит-тесты
3. ✅ EasClient - интеграционные тесты
4. ⚪ EasContactsService - юнит-тесты
5. ⚪ EasTasksService - юнит-тесты
6. ⚪ XmlValueExtractor - тесты парсинга
7. ⚪ EasPatterns - тесты regex

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
