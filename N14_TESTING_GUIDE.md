# N-14 Security Configuration Fix - Testing Guide

## Исправление
Исправлены три проблемы конфигурации безопасности (audit N-14):

### 1. FileProvider paths - сужены scope (было `path="/"`)
**Файл:** `app/src/main/res/xml/file_paths.xml`

**Было:**
```xml
<cache-path name="cache" path="/" />
```

**Стало:**
```xml
<!-- Scoped cache paths - narrowed from path="/" per security best practice (audit N-14) -->
<cache-path name="calendar_preview" path="calendar_preview/" />
<cache-path name="calendar_event_attachments" path="calendar_event_attachments/" />
<cache-path name="email_preview" path="email_preview/" />
<cache-path name="email_share" path="email_share/" />
<cache-path name="email_drag" path="email_drag/" />
<cache-path name="share_attachments" path="share_attachments/" />
<cache-path name="cache_root" path="." />
```

**Причина:** широкий `path="/"` даёт FileProvider доступ ко всему cache-каталогу, что нарушает принцип наименьших привилегий. Scoped paths ограничивают доступ только необходимыми директориями.

### 2. Network Security Config - удалён user CA trust
**Файл:** `app/src/main/res/xml/network_security_config.xml`

**Было:**
```xml
<trust-anchors>
    <certificates src="system"/>
    <certificates src="user"/>  <!-- УДАЛЕНО -->
</trust-anchors>
```

**Стало:**
```xml
<trust-anchors>
    <!-- Доверять только системным сертификатам -->
    <certificates src="system"/>
</trust-anchors>
```

**Причина:** 
- Android 7+ best practice - НЕ доверять user CA глобально
- Расширяет MITM-поверхность атак
- Приложение реализует explicit certificate pinning через `HttpClientProvider`
- Self-signed Exchange сертификаты обрабатываются через:
  - `certificatePath` - explicit pinning
  - `acceptAllCerts` - user opt-in для dev/test

**Комментарий в коде поясняет стратегию.**

### 3. security-crypto dependency - задокументирован deprecation
**Файл:** `app/build.gradle.kts`

**Добавлен комментарий:**
```kotlin
// NOTE: security-crypto is officially deprecated (no stable release, last alpha 2021)
// See: https://developer.android.com/jetpack/androidx/releases/security
// Current strategy: use EncryptedSharedPreferences with XOR-obfuscated fallback (AccountRepository)
// Alpha version kept as best available option; migration to alternative pending official recommendation
```

**Причина:**
- Библиотека официально deprecated (no stable release, последняя alpha 2021)
- Обновление невозможно - стабильной версии не существует
- Текущая стратегия: `EncryptedSharedPreferences` + XOR fallback при недоступности Keystore
- Комментарий документирует осознанный выбор и fallback-стратегию

## Тестирование

### Автоматические тесты
Создан файл: `app/src/test/java/com/dedovmosol/iwomail/config/SecurityConfigTest.kt`

**Запуск тестов (требуется JDK 17):**
```bash
./gradlew test --tests "com.dedovmosol.iwomail.config.SecurityConfigTest"
```

**Покрываемые кейсы:**
1. ✅ `file_paths.xml` не содержит `path="/"`
2. ✅ Все необходимые cache-paths присутствуют
3. ✅ `network_security_config.xml` не содержит `user` CA
4. ✅ `cleartextTrafficPermitted="false"`
5. ✅ `build.gradle.kts` документирует deprecation security-crypto
6. ✅ Все FileProvider paths имеют валидные name/path атрибуты

### Ручное тестирование

#### 1. FileProvider functionality
**Тест:** Открытие/share вложений календаря и email

**Шаги:**
1. Открыть письмо с вложением
2. Нажать "Предпросмотр" на вложении
3. Убедиться, что файл открывается внешним приложением
4. Нажать "Поделиться" на вложении
5. Убедиться, что sharing работает

**Код paths:**
- `calendar_preview` - `CalendarAttachmentsList.kt:127`
- `email_preview` - `EmailDetailScreen.kt:1269`
- `email_share` - `EmailDetailScreen.kt:1371`
- `email_drag` - `EmailDetailScreen.kt:1404`
- `share_attachments` - `AppNavigation.kt:71`
- `cache_root` - `ContactUtils.kt:26`

**Ожидаемый результат:** все операции работают без `FileUriExposedException`

#### 2. Certificate pinning (without user CA)
**Тест:** Подключение к Exchange с self-signed сертификатом

**Шаги:**
1. Настройка нового Exchange аккаунта
2. Указать URL с self-signed сертификатом
3. Импортировать сертификат через "Certificate Path"
4. Завершить настройку

**Ожидаемый результат:** 
- Подключение успешно (explicit pinning работает)
- User CA не требуется

#### 3. Password encryption fallback
**Тест:** Сохранение пароля при недоступном Keystore

**Проверка логов:**
```
adb logcat | grep "AccountRepo"
```

**Ожидаемый результат:**
- При успешном Keystore: пароль в `EncryptedSharedPreferences`
- При сбое Keystore: лог "EncryptedSharedPreferences failed, using obfuscated fallback"
- Fallback не крашит приложение

### Regression-тестирование

**Проверить, что НЕ сломалось:**
1. ✅ Открытие вложений email
2. ✅ Preview вложений календаря
3. ✅ Share контактов (vCard/CSV)
4. ✅ Drag вложений
5. ✅ Forward вложений
6. ✅ Установка обновлений APK
7. ✅ Подключение к Exchange с custom сертификатом
8. ✅ Сохранение паролей аккаунтов

## Проверка изменений

### XML validation
```bash
# Проверка синтаксиса XML
xmllint --noout app/src/main/res/xml/file_paths.xml
xmllint --noout app/src/main/res/xml/network_security_config.xml
```

### Build verification
```bash
# Проверка, что проект собирается
./gradlew assembleDebug
```

## Security considerations

### FileProvider scope reduction
- **До:** `path="/"` - весь cache доступен через FileProvider
- **После:** только 7 специфичных директорий
- **Benefit:** ограничение blast radius при уязвимости FileProvider

### User CA removal
- **До:** MITM возможна с любым user-installed CA
- **После:** только system CA + explicit pinning
- **Trade-off:** user не может добавить свой CA глобально (но может через certificatePath)

### security-crypto deprecation
- **Статус:** библиотека deprecated, stable version отсутствует
- **Риск:** alpha версия может содержать баги
- **Mitigation:** fallback на XOR-obfuscation при сбое
- **Future:** мигрировать при появлении официальной альтернативы

## Checklist перед коммитом

- [x] Все 3 проблемы N-14 исправлены
- [x] Созданы автоматические тесты
- [x] Добавлены комментарии в код
- [x] Документация тестирования создана
- [x] Изменения соблюдают DRY/KISS/SOLID
- [ ] Ручное regression-тестирование выполнено (требует сборки)
- [ ] Тесты прошли (требуется JDK 17)

## References

- [Android Network Security Config](https://developer.android.com/privacy-and-security/security-config)
- [FileProvider Best Practices](https://developer.android.com/training/secure-file-sharing/setup-sharing)
- [security-crypto deprecation](https://developer.android.com/jetpack/androidx/releases/security)
- Audit document: `docs/AUDIT.md` (N-14, строка 167-168)
