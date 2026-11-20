# Тестирование iwo Mail Client

Основной набор тестов находится в [`app/src/test/java/com/dedovmosol/iwomail/`](java/com/dedovmosol/iwomail). Это локальные тесты Gradle: они запускаются на JVM без эмулятора. Наряду с обычными тестами JUnit 4 здесь есть Robolectric-тесты, которые используют Android-ресурсы, SQLite, WorkManager и Compose UI. Тесты, которым нужен настоящий Exchange, включаются отдельно.

## Запуск

Команды выполняются **из корня репозитория**. Для чистого клона сначала подготовьте JDK 17, Android SDK и локальный аудиоресурс, как описано в [CONTRIBUTING.md](../../../CONTRIBUTING.md#getting-started). В Windows PowerShell используйте `./gradlew.bat` вместо `./gradlew`.

```sh
# Набор для debug, тот же вариант запускает CI
./gradlew :app:testDebugUnitTest

# Один класс или один метод: --tests передаётся задаче конкретного варианта
./gradlew :app:testDebugUnitTest --tests '*EasXmlTemplatesTest'
./gradlew :app:testDebugUnitTest --tests '*EasXmlTemplatesTest.syncInitial generates valid XML'

# XML-отчёт о покрытии
./gradlew :app:koverXmlReportDebug
```

HTML-отчёт: `app/build/reports/tests/testDebugUnitTest/index.html`, результаты JUnit в XML: `app/build/test-results/testDebugUnitTest/`, отчёт Kover: `app/build/reports/kover/reportDebug.xml`. [GitHub Actions](../../../.github/workflows/ci.yml) запускает тесты и Kover для push и pull request в `main`, отдельно собирает release и проверяет `lintDebug`. Процент покрытия смотрите в отчёте конкретного запуска.

## Где искать тест или добавить регрессию

| Если меняется… | Где смотреть и что проверяется |
|---|---|
| Протокол Exchange | [`eas/`](java/com/dedovmosol/iwomail/eas): WBXML, SOAP/XML, MIME, страницы синхронизации, вложения, черновики, календарь и задачи. [`FakeExchange`](java/com/dedovmosol/iwomail/eas/FakeExchange.kt) отвечает через перехватчик OkHttp: тест может проверить настоящий запрос и воспроизвести отказ или неполный ответ без подключения к серверу. |
| Хранение и синхронизация | [`data/database/`](java/com/dedovmosol/iwomail/data/database) и [`data/repository/`](java/com/dedovmosol/iwomail/data/repository): DAO, миграции, очереди локальных изменений, соответствие писем при смене ServerId. Исходные схемы прежних версий для миграционных тестов лежат в [`resources/room-schemas/`](resources/room-schemas). |
| Сеть и защита | [`network/`](java/com/dedovmosol/iwomail/network), [`data/security/`](java/com/dedovmosol/iwomail/data/security), [`shared/`](java/com/dedovmosol/iwomail/shared): проверка сертификатов и имени хоста, параметры JavaMail, хранение паролей, блокировка и восстановление доступа. Для TLS есть [тестовые сертификаты](resources/certs). |
| Письма и вложения | [`domain/`](java/com/dedovmosol/iwomail/domain) и [`util/`](java/com/dedovmosol/iwomail/util): группировка в цепочки, лимиты вложений, сохранение черновиков, обработка HTML/MIME, внешних изображений и `mailto:`. |
| Экран и навигация | [`ui/`](java/com/dedovmosol/iwomail/ui): ViewModel с тестовым диспетчером и MockK, реальные Compose-компоненты под Robolectric. Примеры: [`ComposeScreenOpenTest`](java/com/dedovmosol/iwomail/ui/screens/ComposeScreenOpenTest.kt) открывает редактор из `MainActivity`, [`WebViewUnavailableTest`](java/com/dedovmosol/iwomail/ui/components/WebViewUnavailableTest.kt) проверяет поведение без системного WebView. |
| Фоновая работа и виджет | [`sync/`](java/com/dedovmosol/iwomail/sync) и [`widget/`](java/com/dedovmosol/iwomail/widget): уведомления и их приватность, push, будильники, отложенная отправка, действия виджета. |

## Проверки, выключенные по умолчанию

- [`live/`](java/com/dedovmosol/iwomail/live) — проверки на настоящем Exchange. Они читают почту, а отдельные сценарии меняют данные или выполняют неудачную попытку входа. Используйте только выделенный тестовый ящик. Переменные окружения, команды и побочные эффекты описаны в [руководстве участника](../../../CONTRIBUTING.md#live-tests-against-a-real-exchange-server).
- [`SettingsScreenshotProbe`](java/com/dedovmosol/iwomail/ui/screens/SettingsScreenshotProbe.kt) — ручная визуальная проверка компонентов настроек на подготовленных данных. Включается через `IWO_SHOTS=1` и сохраняет PNG в `app/build/settings-shots/`. Это не сравнение с эталонными изображениями и не проверка на физическом устройстве.
