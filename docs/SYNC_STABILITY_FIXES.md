# План исправления нестабильности синхронизации

**Дата:** 2025-02-21 (ревью: 2026-02-23)  
**Версия:** 1.6.2 
**Сценарий:** 4000-5000 писем, 100-200 папок, 2 аккаунта, медленный интернет, Exchange 2007 SP1

---

## Источники

- [MS-ASCMD: Collection (Sync)](https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-ascmd/9bc36da0-2ecc-4618-a4a3-f0fcda460d44) — порядок элементов **подтверждён корректным** (SyncKey → CollectionId)
- [MS-ASCMD: WindowSize](https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-ascmd/8643e5a0-bf6e-464b-8b38-82619d98ca1b) — "values larger than 100 cause larger responses, which are more susceptible to communication errors"
- [OkHttp Timeouts (Baeldung)](https://www.baeldung.com/okhttp-timeouts) — callTimeout = полное время HTTP вызова включая DNS, connect, write, server processing, read
- [SmarterTools: EAS Stability](https://portal.smartertools.com/kb/a3055/how-to-improve-exchange-activesync-stability.aspx) — "mailboxes over 5 GB — sync key mismatches, general slowness"
- [MS: EWS Throttling](https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/ews-throttling-in-exchange) — Exchange throttling policies для concurrent connections

---

## Верифицированные проблемы

### P1 — КРИТИЧЕСКАЯ: windowSize=200 + TruncationSize=51200 → timeout на медленном интернете

**Файл:** `EmailSyncService.kt:711`  
**Текущее значение:** `val windowSize = 200`

**Проблема:**  
MS-ASCMD прямо указывает: *"WindowSize values larger than 100 cause larger responses, which are more susceptible to communication errors."*

Расчёт объёма одного батча:
- 200 писем × 51200 bytes (TruncationSize) = **~10 MB** контента
- WBXML overhead + заголовки = **5-15 MB** на один HTTP response
- При 50-100 KB/s (3G/EDGE): **50-300 секунд** на скачивание одного батча

**Файл:** `HttpClientProvider.kt:287`  
**Текущее значение:** `callTimeout(120, TimeUnit.SECONDS)`

`callTimeout` — это **полное** время HTTP вызова (DNS + connect + write + server processing + read response). При 10 MB response на 3G (100 KB/s) = 100 сек только на download. С учётом серверной обработки Exchange 2007 SP1 — **превышает 120 сек** → `InterruptedIOException` → `EasResult.Error`.

**Цепная реакция:**  
callTimeout → EasResult.Error → consecutiveErrors++ → при 5 ошибках sync прерывается → SyncWorker/PushService запускает forceFullSync → `syncKey="0"` → ещё больше батчей (4000 писем / 200 = 20 батчей) → всё повторяется.

При 2 аккаунтах: пропускная способность делится пополам → каждый аккаунт получает ~50 KB/s → timeout гарантирован.

### P2 — КРИТИЧЕСКАЯ: Все 100-200 папок синхронизируются последовательно

**Файл:** `PushService.kt:778-784`
```kotlin
val folders = database.folderDao().getFoldersByAccountList(account.id)
    .filter { it.type in listOf(
        FolderType.INBOX, FolderType.DRAFTS, FolderType.DELETED_ITEMS,
        FolderType.SENT_ITEMS, FolderType.OUTBOX, 1, FolderType.USER_CREATED
    ) }

for (folder in folders) { ... }
```

**Файл:** `SyncWorker.kt:141-147`
```kotlin
val userFolderTypes = listOf(1, FolderType.USER_CREATED)
val foldersToSync = allFolders.filter { it.type in mainFolderTypes } +
    allFolders.filter { it.type in userFolderTypes }

for (folder in foldersToSync) { ... }
```

**Проблема:**  
`FolderType.USER_CREATED` и тип `1` включают **все** пользовательские папки.  
При 150 папках × 2 аккаунта = **300 папок** синхронизируются **последовательно**.

Минимальное время на "пустую" папку (HTTP round-trip + NTLM + WBXML):
- Быстрый интернет: ~1-2 сек × 300 = **5-10 мин**
- Медленный 3G: ~3-5 сек × 300 = **15-25 мин**

WorkManager ограничивает время выполнения Worker до **~10 минут**. При 300 папках worker **никогда не завершается** → убивается → `Result.retry()` → повторяется → INBOX может никогда не синхронизироваться.

PushService не имеет жёсткого лимита, но при 300 папках × 60 сек timeout = до **5 часов** на один цикл.

### P3 — СЕРЬЁЗНАЯ: Весь WBXML-ответ загружается в RAM как строка

**Файл:** `EasClient.kt:1573-1577`
```kotlin
val responseBody = resp.body?.bytes()              // ByteArray: ~10 MB
val responseXml = wbxmlParser.parse(responseBody)  // StringBuilder → String: ~30-50 MB
EasResult.Success(parser(responseXml))             // Ещё объекты при парсинге
```

**Файл:** `WbxmlParser.kt:35-56`
```kotlin
fun parse(data: ByteArray): String {
    val input = ByteArrayInputStream(data)
    val result = StringBuilder()
    // ... побайтовый parsing
    return result.toString()
}
```

**Проблема:**  
Цепочка аллокаций для одного батча из 200 писем:
1. `resp.body?.bytes()` → ByteArray ~10 MB
2. `WbxmlParser.parse()` → StringBuilder → String ~30-50 MB (XML в 3-5× больше WBXML)
3. `parseSyncResponse(xml)` → regex `.findAll()` → множественные подстроки

**Итого на 1 батч: ~80-120 MB** временных объектов.  
Android heap limit: 256-512 MB. При 2 аккаунтах одновременно → **GC storm** → паузы 500-2000 мс → timeout в read socket → ещё одна ошибка sync.

### P4 — СЕРЬЁЗНАЯ: Regex-парсинг O(n) на строках 30-50 MB

**Файл:** `EasClient.kt:1664-1713` (`parseSyncResponse`)
```kotlin
val addPattern = "<Add>(.*?)</Add>".toRegex(RegexOption.DOT_MATCHES_ALL)
addPattern.findAll(xml).forEach { ... }  // На строке ~40 MB
```

4 отдельных regex прохода на каждом батче (`<Add>`, `<Change>`, `<Delete>`, `<SoftDelete>`).

Внутри `parseEmail()` — ~14 вызовов `extractValue()` + отдельные regex для `<Attachment>`, `<Flag>`. Regex паттерны кешируются в `extractValueCache` (ConcurrentHashMap), но `.find()` всё равно сканирует подстроку. Для 200 писем: 200 × 14 = **2800+ regex операций** на батч.

### P5 — УМЕРЕННАЯ: Конфликт таймаутов syncEmailsEas vs SyncWorker

**Файл:** `EmailSyncService.kt:743`
```kotlin
val maxSyncDurationMs = if (isFullResync) 900_000L else 300_000L  // 15 мин / 5 мин
```

**Файл:** `SyncWorker.kt:160`
```kotlin
val retryResult = kotlinx.coroutines.withTimeoutOrNull(300_000L) { ... }
```

`syncEmailsEas` считает что у него 15 минут на full resync, но `SyncWorker` даёт ему максимум 300 секунд (5 мин). `SyncWorker` убьёт `syncEmailsEas` по timeout → `activeSyncs` lock освобождается через `finally` блок, но SyncKey может быть в промежуточном состоянии.

### P6 — УМЕРЕННАЯ: NTLM + нестабильное соединение = overhead

Exchange 2007 SP1 IIS Keep-Alive timeout ~30-60 сек. При медленном sync каждой папки (>30 сек) TCP соединение сбрасывается → каждый следующий запрос требует нового NTLM handshake (3 round-trips × ~200-500 мс RTT).

При 300 папках × потенциальный NTLM handshake = дополнительные **3-5 минут** overhead.

### P7 — ИНФОРМАЦИОННАЯ: Exchange 2007 SP1 + большой ящик = нестабильный SyncKey

Из документации SmarterTools: *"mailboxes over 5 GB — sync key mismatches, general slowness"*. При 4000-5000 писем ящик может приближаться к этому лимиту. SyncKey mismatch → Status=3 → полный ресинк → усугубляет все вышеописанные проблемы.

### P8 — КРИТИЧЕСКАЯ: CancellationException глотится в SyncWorker и PushService

**Файлы:**
- `SyncWorker.kt:167, 173` — `catch (_: Exception)` в sync loop
- `PushService.kt:730, 799, 805` — `catch (_: Exception)` в `doPing` и `doSyncForAccount`

```kotlin
// SyncWorker.kt:167
} catch (_: Exception) {          // ← ГЛОТИТ CancellationException!
    try {
        withTimeoutOrNull(300_000L) {
            mailRepo.syncEmails(...)  // Запускает 5-минутный full resync
        }
    } catch (_: Exception) { ... } // ← ТОЖЕ ГЛОТИТ
}
```

**Проблема:**  
`CancellationException` — механизм отмены корутин в Kotlin. Когда WorkManager убивает Worker (по таймауту или при отмене), он отменяет корутину через `CancellationException`. Если exception глотится:
1. Корутина **не прерывается** → продолжает full resync (ещё 300 сек)
2. WorkManager считает Worker зависшим → принудительно убивает процесс
3. SyncKey остаётся в промежуточном состоянии → при следующем запуске Status=3 → полный ресинк

В PushService: `doPing` (строка 730) при `folders.isEmpty()` делает full sync **всех** папок без таймаута и без лимита — при 200 папках это может занять часы, и `CancellationException` не прервёт цикл.

**Это race condition**: WorkManager отменяет → catch глотит → sync продолжается → WorkManager убивает процесс → данные повреждены.

### P9 — УМЕРЕННАЯ (DRY): Дублирование кода sync-паттерна и списков типов папок

**Файлы:**
- `SyncWorker.kt:129-131, 141` — `mainFolderTypes`, `userFolderTypes`
- `PushService.kt:711-713, 719-721, 736-738, 779-781` — тот же список 4 раза

```kotlin
// Один и тот же список дублируется 5+ раз:
listOf(FolderType.INBOX, FolderType.DRAFTS, FolderType.DELETED_ITEMS,
       FolderType.SENT_ITEMS, FolderType.OUTBOX, 1, FolderType.USER_CREATED)
```

Sync-паттерн (incremental → timeout → full resync → catch) **идентичен** в SyncWorker (строки 147-176) и PushService (строки 784-808).

**Проблема:**  
- При изменении логики (например, добавлении `JUNK_EMAIL`) нужно менять в 5+ местах
- Баг P8 (CancellationException) присутствует в **обоих** файлах — если исправить в одном и забыть в другом, баг останется
- Нарушает SRP: и SyncWorker, и PushService содержат одинаковую бизнес-логику sync

### P10 — СЕРЬЁЗНАЯ: doPing без лимита папок → Exchange может отклонить Ping

**Файл:** `PushService.kt:710-714`

```kotlin
var folders = database.folderDao().getFoldersByAccountList(account.id)
    .filter { it.syncKey != "0" && it.type in listOf(...) }
```

**Проблема:**  
[MS-ASCMD Ping](https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-ascmd/c2599803-87f0-4c60-b3e2-035e5e9b2d1e): *"The server can limit the number of folders that can be monitored."* Exchange 2007 SP1 может отклонить Ping с Status=6 (ServerError) или Status=5 (InvalidHeartbeat) при слишком большом количестве папок. При 150+ пользовательских папках Ping может стабильно отклоняться → PushService переходит в retry loop → батарея разряжается.

Также при `folders.isEmpty()` (строки 726-733) запускается full sync **всех** папок без общего таймаута — потенциально бесконечный цикл.

---

## План исправлений

### Фаза 1 — Критические исправления (снижение размера батчей и фильтрация папок)

#### 1.1 Уменьшить windowSize со 200 до 50 при медленном sync

**Файл:** `EmailSyncService.kt:711`

**Было:**
```kotlin
val windowSize = 200
```

**Стало:**
```kotlin
// MS-ASCMD: "values larger than 100 cause larger responses, more susceptible to communication errors"
// При 50 × 51200 bytes = ~2.5 MB на батч → укладывается в callTimeout даже на 3G
val windowSize = 50
```

**Обоснование:**
- 50 × 51200 = ~2.5 MB на батч
- При 50 KB/s (3G): 2.5 MB = 50 сек — укладывается в callTimeout=120s
- Для 4000 писем: 4000/50 = 80 батчей (вместо 20), но каждый завершится успешно
- MS-ASCMD: "WindowSize value less than 100 can be useful if the client can display the initial set of objects while additional ones are still being retrieved"

**Альтернатива (адаптивный windowSize):**
```kotlin
// Начинаем с 50, увеличиваем если батчи завершаются быстро
val baseWindowSize = 50
val windowSize = if (consecutiveErrors == 0 && iterations > 3) {
    minOf(baseWindowSize * 2, 100) // До 100, не больше
} else {
    baseWindowSize
}
```

#### 1.2 Ограничить пользовательские папки в SyncWorker и PushService

**Файл:** `SyncWorker.kt:141-143`

**Было:**
```kotlin
val userFolderTypes = listOf(1, FolderType.USER_CREATED)
val foldersToSync = allFolders.filter { it.type in mainFolderTypes } +
    allFolders.filter { it.type in userFolderTypes }
```

**Стало:**
```kotlin
val userFolderTypes = listOf(1, FolderType.USER_CREATED)
val userFolders = allFolders.filter { it.type in userFolderTypes }
// Ограничиваем пользовательские папки: при большом количестве синхронизируем
// только те, у которых есть непрочитанные или которые менялись недавно.
// Без лимита 200 папок × 2 аккаунта = 400 последовательных sync → превышает WorkManager ~10 мин.
val MAX_USER_FOLDERS_PER_SYNC = 10
val limitedUserFolders = if (userFolders.size > MAX_USER_FOLDERS_PER_SYNC) {
    userFolders
        // Приоритет: непрочитанные > недавно синхронизированные > остальные
        // ВАЖНО: после resetAllSyncKeys все unreadCount=0, поэтому нужен fallback
        .sortedWith(compareByDescending<FolderEntity> { it.unreadCount }
            .thenByDescending { it.syncKey != "0" })  // Папки с syncKey приоритетнее
        .take(MAX_USER_FOLDERS_PER_SYNC)
} else {
    userFolders
}
val foldersToSync = allFolders.filter { it.type in mainFolderTypes } + limitedUserFolders
```

**Файл:** `PushService.kt:778-783` — аналогичное изменение.

**Обоснование:**
- При 200 папках: 6 системных + 10 пользовательских = 16 папок вместо 206
- Время: 16 × 5 сек = 80 сек вместо 206 × 5 сек = 17 мин
- Папки с `unreadCount > 0` приоритетнее — там могут быть непрочитанные письма
- Остальные пользовательские папки синхронизируются при ротации или ручном sync

#### 1.3 Увеличить callTimeout для Sync-клиента

**Файл:** `HttpClientProvider.kt:287`

**Было:**
```kotlin
.callTimeout(120, TimeUnit.SECONDS)
```

**Стало:**
```kotlin
.callTimeout(0, TimeUnit.SECONDS)  // Без callTimeout; readTimeout=60s достаточно как защита
```

**Обоснование:**  
Из Baeldung: *"callTimeout... includes resolving DNS, connecting, writing the request body, server processing, as well as reading the response body"*. `readTimeout=60s` уже защищает от зависших соединений (60 сек без получения данных). `callTimeout` избыточно обрезает длинные, но работающие загрузки.

**Альтернатива (если полное отключение рискованно):**
```kotlin
.callTimeout(300, TimeUnit.SECONDS)  // 5 мин — даёт запас для медленного 3G
```

### Фаза 2 — Оптимизация памяти

#### 2.1 Уменьшить TruncationSize

**Файл:** `EasEmailService.kt:104`

**Было:**
```kotlin
<TruncationSize>51200</TruncationSize>
```

**Стало:**
```kotlin
<TruncationSize>10240</TruncationSize>
```

**Обоснование:**  
- 50 писем × 10 KB = 500 KB на батч вместо 50 × 50 KB = 2.5 MB
- Для sync нужны только заголовки + preview. Полное тело загружается через `fetchEmailBody` при открытии письма.
- Поле `preview` в `EmailEntity` ограничено `.take(150)` — 10 KB более чем достаточно для генерации preview.

#### 2.2 Снижение RAM через уменьшение батча (промежуточное решение)

**Файл:** `EasClient.kt:1574`

> **ВНИМАНИЕ:** Предыдущее предложение `resp.body?.byteStream()?.use { stream -> stream.readBytes() }` **идентично** `resp.body?.bytes()` — оба загружают весь response в `ByteArray`. Это НЕ streaming.

**Настоящий streaming** требует переписать `WbxmlParser` для работы с `InputStream` вместо `ByteArray`, и `parseSyncResponse` для SAX-подобного event-driven парсинга вместо regex на полной XML-строке. Это **масштабный рефакторинг** (~500+ строк).

**Промежуточное решение:** Фазы 1.1 (windowSize 200→50) и 2.1 (TruncationSize 51200→10240) снижают нагрузку на RAM с ~80-120 MB до ~5-15 MB на батч — достаточно для стабильной работы без streaming.

### Фаза 3 — Согласование таймаутов

#### 3.1 Согласовать maxSyncDurationMs с внешними таймаутами

**Файл:** `EmailSyncService.kt:743`

**Было:**
```kotlin
val maxSyncDurationMs = if (isFullResync) 900_000L else 300_000L
```

**Стало:**
```kotlin
// SyncWorker даёт 300s на full resync, PushService — тоже 300s.
// maxSyncDurationMs не должен превышать внешний timeout, иначе sync будет прерван
// снаружи в неопределённом состоянии. Оставляем запас 10 сек для cleanup.
val maxSyncDurationMs = if (isFullResync) 280_000L else 55_000L
```

**Обоснование:**  
SyncWorker: `withTimeoutOrNull(300_000L)` для full resync, `60_000L` для incremental.
`syncEmailsEas` должен **сам** корректно завершиться до внешнего timeout, сохранив промежуточный SyncKey.

### Фаза 4 — Приоритезация INBOX

#### 4.1 INBOX синхронизируется первым, всегда

**Файл:** `SyncWorker.kt:142-143`

Сортировка `foldersToSync` так чтобы INBOX был первым:
```kotlin
val foldersToSync = (allFolders.filter { it.type in mainFolderTypes } + limitedUserFolders)
    .sortedBy { if (it.type == FolderType.INBOX) 0 else 1 }
```

**Файл:** `PushService.kt:778-783` — аналогично.

**Обоснование:**  
Если WorkManager убьёт worker через 10 мин, INBOX уже будет синхронизирован. Пользователь получит самые важные письма.

### Фаза 5 — Исправление CancellationException (P8)

#### 5.1 Перебрасывать CancellationException в SyncWorker и PushService

**Файлы:** `SyncWorker.kt:167, 173`, `PushService.kt:730, 799, 805`

**Было:**
```kotlin
} catch (_: Exception) {
    // При исключении тоже пробуем полный ресинк
    try { ... } catch (_: Exception) { ... }
}
```

**Стало:**
```kotlin
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    // При исключении тоже пробуем полный ресинк
    try { ... } catch (e2: Exception) {
        if (e2 is kotlinx.coroutines.CancellationException) throw e2
        // Продолжаем с другими папками
    }
}
```

**Обоснование:**  
Без этого WorkManager не может корректно отменить Worker — корутина продолжает работу после отмены, что приводит к повреждению SyncKey и принудительному убийству процесса. Это **однострочное исправление** в каждом catch-блоке, но критически важное для корректности корутин.

**Количество мест:** 5 catch-блоков (SyncWorker: 2, PushService: 3).

#### 5.2 Добавить общий таймаут в doPing initial sync

**Файл:** `PushService.kt:726-733`

**Было:**
```kotlin
for (folder in allFolders) {
    try {
        mailRepo.syncEmails(account.id, folder.id, forceFullSync = true)
    } catch (_: Exception) { }
}
```

**Стало:**
```kotlin
val initialSyncTimeout = 300_000L // 5 мин на весь initial sync
kotlinx.coroutines.withTimeoutOrNull(initialSyncTimeout) {
    for (folder in allFolders.take(MAX_PING_FOLDERS)) {
        try {
            mailRepo.syncEmails(account.id, folder.id, forceFullSync = true)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }
}
```

### Фаза 6 — DRY рефакторинг (P9)

#### 6.1 Вынести общие константы в FolderType

**Файл:** `FolderType.kt`

```kotlin
object FolderType {
    // ... существующие константы ...
    
    /** Типы папок для синхронизации (системные) */
    val SYNC_MAIN_TYPES = listOf(INBOX, DRAFTS, DELETED_ITEMS, SENT_ITEMS, OUTBOX, JUNK_EMAIL)
    
    /** Типы пользовательских папок */
    val SYNC_USER_TYPES = listOf(1, USER_CREATED)
    
    /** Все типы для push-мониторинга */
    val PUSH_TYPES = SYNC_MAIN_TYPES + SYNC_USER_TYPES
}
```

**Обоснование:**  
Один источник правды вместо 5+ дублирующихся списков. При добавлении нового типа (например, `JUNK_EMAIL` уже есть в SyncWorker, но **отсутствует** в PushService) — меняем в одном месте.

> **ВНИМАНИЕ:** SyncWorker включает `JUNK_EMAIL` в `mainFolderTypes` (строка 131), но PushService **не включает** — это расхождение. Нужно решить: синхронизировать спам через Push или нет.

#### 6.2 Вынести sync-паттерн в общую функцию

**Файл:** Новый файл `SyncHelper.kt` или метод в `MailRepository`

```kotlin
suspend fun syncFolderWithRetry(
    mailRepo: MailRepository,
    accountId: Long,
    folderId: Long,
    incrementalTimeoutMs: Long = 60_000L,
    fullResyncTimeoutMs: Long = 300_000L
): Boolean {
    try {
        val result = withTimeoutOrNull(incrementalTimeoutMs) {
            mailRepo.syncEmails(accountId, folderId, forceFullSync = false)
        }
        if (result == null || result is EasResult.Error) {
            val retryResult = withTimeoutOrNull(fullResyncTimeoutMs) {
                mailRepo.syncEmails(accountId, folderId, forceFullSync = true)
            }
            return retryResult != null && retryResult !is EasResult.Error
        }
        return true
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        try {
            withTimeoutOrNull(fullResyncTimeoutMs) {
                mailRepo.syncEmails(accountId, folderId, forceFullSync = true)
            }
        } catch (e2: Exception) {
            if (e2 is kotlinx.coroutines.CancellationException) throw e2
        }
        return false
    }
}
```

**Обоснование:**  
- Исправление P8 (CancellationException) автоматически применяется в обоих местах
- SRP: SyncWorker и PushService отвечают за scheduling, а не за retry-логику
- Тестируемость: одна функция вместо двух идентичных блоков

### Фаза 7 — Лимит папок для Ping (P10)

#### 7.1 Ограничить количество папок в doPing

**Файл:** `PushService.kt:710-714`

```kotlin
val MAX_PING_FOLDERS = 25  // Exchange 2007 SP1 может отклонить Ping с >50 папками
var folders = database.folderDao().getFoldersByAccountList(account.id)
    .filter { it.syncKey != "0" && it.type in FolderType.PUSH_TYPES }

// Приоритет: системные папки > пользовательские с непрочитанными
if (folders.size > MAX_PING_FOLDERS) {
    val systemFolders = folders.filter { FolderType.isSystemFolder(it.type) }
    val userFolders = folders.filter { !FolderType.isSystemFolder(it.type) }
        .sortedByDescending { it.unreadCount }
        .take(MAX_PING_FOLDERS - systemFolders.size)
    folders = systemFolders + userFolders
}
```

**Обоснование:**  
MS-ASCMD: сервер может ограничить количество папок в Ping. 25 — безопасный лимит для Exchange 2007 SP1. Системные папки всегда мониторятся, пользовательские — по приоритету непрочитанных.

---

## Приоритет реализации

| # | Задача | Файлы | Сложность | Влияние | Фаза |
|---|--------|-------|-----------|---------|------|
| 1 | **CancellationException rethrow** | SyncWorker.kt, PushService.kt | 5 строк (5 catch) | **Критическое** — без этого корутины не отменяются | 5 |
| 2 | windowSize 200→50 | EmailSyncService.kt | 1 строка | **Критическое** — снижает объём батча в 4× | 1 |
| 3 | Лимит user folders (sync) | SyncWorker.kt, PushService.kt | ~15 строк | **Критическое** — 16 папок вместо 200+ | 1 |
| 4 | callTimeout 120s→0 (или 300s) | HttpClientProvider.kt | 1 строка | Серьёзное — убирает ложные timeout | 1 |
| 5 | TruncationSize 51200→10240 | EasEmailService.kt | 1 строка | Серьёзное — снижает RAM в 5× | 2 |
| 6 | INBOX first | SyncWorker.kt, PushService.kt | ~3 строки | Умеренное — гарантирует sync INBOX | 4 |
| 7 | maxSyncDurationMs согласование | EmailSyncService.kt | 1 строка | Умеренное — предотвращает прерывание | 3 |
| 8 | Лимит папок в Ping | PushService.kt | ~10 строк | Серьёзное — предотвращает Ping rejection | 7 |
| 9 | DRY: FolderType константы | FolderType.kt | ~5 строк | Умеренное — один источник правды | 6 |
| 10 | DRY: syncFolderWithRetry | SyncHelper.kt | ~25 строк | Умеренное — устраняет дублирование | 6 |
| 11 | doPing initial sync timeout | PushService.kt | ~5 строк | Серьёзное — предотвращает бесконечный цикл | 5 |

**Рекомендуемый порядок:** 1 → 2 → 3 → 4 → 5 → 8 → 11 → 7 → 6 → 9 → 10

---

## Ожидаемый результат после исправлений

**До:**
- 200 писем × 50 KB = 10 MB/батч → timeout на 3G → sync failure → full resync loop
- 200 папок × 2 аккаунта = 400 sequential syncs → WorkManager killed → INBOX не синхронизируется
- RAM: ~80-120 MB на батч → GC storm → socket timeout
- CancellationException глотится → WorkManager не может отменить Worker → процесс убивается
- Ping с 200 папками → Exchange отклоняет → retry loop → батарея

**После:**
- 50 писем × 10 KB = 0.5 MB/батч → 10 сек на 3G → успешно
- 6+10 папок × 2 аккаунта = 32 sequential syncs → ~3 мин → WorkManager OK
- RAM: ~5-15 MB на батч → без GC pressure
- CancellationException корректно прерывает корутины → чистая отмена
- Ping с ≤25 папками → Exchange принимает → стабильный push

---

## Что НЕ является проблемой (проверено)

1. **Порядок XML элементов (SyncKey/CollectionId)** — [MS-ASCMD](https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-ascmd/9bc36da0-2ecc-4618-a4a3-f0fcda460d44) прямо указывает порядок: SyncKey(1) → CollectionId(2). Наш код **корректен**.
2. **EAS версия** — код корректно определяет 12.1 для Exchange 2007 SP1 и не пытается использовать 16.x.
3. **WBXML code pages** — `WbxmlParser.kt` поддерживает все необходимые code pages для EAS 12.1.
4. **activeSyncs lock** — уже исправлен в предыдущем коммите (60 сек incremental timeout + fallback to full resync).
5. **extractValue regex cache** — `extractValueCache` (ConcurrentHashMap) кеширует скомпилированные regex паттерны. Компиляция не повторяется, но `.find()` всё равно O(n) на каждый вызов.
