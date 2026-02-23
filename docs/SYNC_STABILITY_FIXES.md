# План исправления нестабильности синхронизации

**Дата:** 2025-02-21  
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

**Файл:** `EmailSyncService.kt:713`  
**Текущее значение:** `val windowSize = 200`

**Проблема:**  
MS-ASCMD прямо указывает: *"WindowSize values larger than 100 cause larger responses, which are more susceptible to communication errors."*

Расчёт объёма одного батча:
- 200 писем × 51200 bytes (TruncationSize) = **~10 MB** контента
- WBXML overhead + заголовки = **5-15 MB** на один HTTP response
- При 50-100 KB/s (3G/EDGE): **50-300 секунд** на скачивание одного батча

**Файл:** `HttpClientProvider.kt:288`  
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

**Файл:** `EasClient.kt:1569-1572`
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

**Файл:** `EasClient.kt:1669-1705` (`parseSyncResponse`)
```kotlin
val addPattern = "<Add>(.*?)</Add>".toRegex(RegexOption.DOT_MATCHES_ALL)
addPattern.findAll(xml).forEach { ... }  // На строке ~40 MB
```

4 отдельных regex прохода на каждом батче (`<Add>`, `<Change>`, `<Delete>`, `<SoftDelete>`).

Внутри `parseEmail()` — ещё ~12 вызовов `extractValue()`, каждый сканирует подстроку с начала. Для 200 писем: 200 × 12 = **2400 regex операций** на батч.

### P5 — УМЕРЕННАЯ: Конфликт таймаутов syncEmailsEas vs SyncWorker

**Файл:** `EmailSyncService.kt:745`
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

---

## План исправлений

### Фаза 1 — Критические исправления (снижение размера батчей и фильтрация папок)

#### 1.1 Уменьшить windowSize со 200 до 50 при медленном sync

**Файл:** `EmailSyncService.kt:713`

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
        .sortedByDescending { it.unreadCount }
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

**Файл:** `HttpClientProvider.kt:288`

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

#### 2.2 Streaming parsing вместо загрузки всего response в RAM

**Файл:** `EasClient.kt:1569`

**Было:**
```kotlin
val responseBody = resp.body?.bytes()
```

**Стало:**
```kotlin
val responseBody = resp.body?.byteStream()?.use { stream ->
    stream.readBytes() // Или постепенный parsing
}
```

> **Примечание:** Полноценный streaming WbxmlParser — сложная задача. Промежуточное решение: уменьшение windowSize и TruncationSize (фазы 1.1 и 2.1) снижает нагрузку на RAM с ~80-120 MB до ~5-15 MB на батч.

### Фаза 3 — Согласование таймаутов

#### 3.1 Согласовать maxSyncDurationMs с внешними таймаутами

**Файл:** `EmailSyncService.kt:745`

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

---

## Приоритет реализации

| # | Задача | Файлы | Сложность | Влияние |
|---|--------|-------|-----------|---------|
| 1 | windowSize 200→50 | EmailSyncService.kt | 1 строка | Критическое — снижает объём батча в 4× |
| 2 | Лимит user folders | SyncWorker.kt, PushService.kt | ~15 строк | Критическое — 16 папок вместо 200+ |
| 3 | callTimeout 120s→300s | HttpClientProvider.kt | 1 строка | Серьёзное — даёт запас для 3G |
| 4 | TruncationSize 51200→10240 | EasEmailService.kt | 1 строка | Серьёзное — снижает RAM в 5× |
| 5 | INBOX first | SyncWorker.kt, PushService.kt | ~3 строки | Умеренное — гарантирует sync INBOX |
| 6 | maxSyncDurationMs согласование | EmailSyncService.kt | 1 строка | Умеренное — предотвращает прерывание |

---

## Ожидаемый результат после исправлений

**До:**
- 200 писем × 50 KB = 10 MB/батч → timeout на 3G → sync failure → full resync loop
- 200 папок × 2 аккаунта = 400 sequential syncs → WorkManager killed → INBOX не синхронизируется
- RAM: ~80-120 MB на батч → GC storm → socket timeout

**После:**
- 50 писем × 10 KB = 0.5 MB/батч → 10 сек на 3G → успешно
- 6+10 папок × 2 аккаунта = 32 sequential syncs → ~3 мин → WorkManager OK
- RAM: ~5-15 MB на батч → без GC pressure

---

## Что НЕ является проблемой (проверено)

1. **Порядок XML элементов (SyncKey/CollectionId)** — [MS-ASCMD](https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-ascmd/9bc36da0-2ecc-4618-a4a3-f0fcda460d44) прямо указывает порядок: SyncKey(1) → CollectionId(2). Наш код **корректен**.
2. **EAS версия** — код корректно определяет 12.1 для Exchange 2007 SP1 и не пытается использовать 16.x.
3. **WBXML code pages** — `WbxmlParser.kt` поддерживает все необходимые code pages для EAS 12.1.
4. **activeSyncs lock** — уже исправлен в предыдущем коммите (60 сек incremental timeout + fallback to full resync).
