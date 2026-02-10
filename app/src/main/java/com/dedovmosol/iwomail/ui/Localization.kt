package com.dedovmosol.iwomail.ui

import androidx.compose.runtime.*

/**
 * Поддерживаемые языки приложения
 */
enum class AppLanguage(val code: String, val displayName: String) {
    RUSSIAN("ru", "🇷🇺 Русский"),
    ENGLISH("en", "🇬🇧 English")
}

/**
 * CompositionLocal для размера шрифта
 */
val LocalFontScale = compositionLocalOf { 1.0f }

/**
 * Строки локализации
 */
object Strings {
    // Общие
    val appName: String @Composable get() = if (isRussian()) "iwo Mail Client" else "iwo Mail Client"
    val loading: String @Composable get() = if (isRussian()) "Загрузка..." else "Loading..."
    val cancel: String @Composable get() = if (isRussian()) "Отмена" else "Cancel"
    val ok: String @Composable get() = if (isRussian()) "ОК" else "OK"
    val save: String @Composable get() = if (isRussian()) "Сохранить" else "Save"
    val delete: String @Composable get() = if (isRussian()) "Удалить" else "Delete"
    val edit: String @Composable get() = if (isRussian()) "Редактировать" else "Edit"
    val close: String @Composable get() = if (isRussian()) "Закрыть" else "Close"
    val clear: String @Composable get() = if (isRussian()) "Очистить" else "Clear"
    val yes: String @Composable get() = if (isRussian()) "Да" else "Yes"
    val no: String @Composable get() = if (isRussian()) "Нет" else "No"
    val back: String @Composable get() = if (isRussian()) "Назад" else "Back"
    val next: String @Composable get() = if (isRussian()) "Далее" else "Next"
    val done: String @Composable get() = if (isRussian()) "Готово" else "Done"
    val error: String @Composable get() = if (isRussian()) "Ошибка" else "Error"
    val success: String @Composable get() = if (isRussian()) "Успешно" else "Success"
    val empty: String @Composable get() = if (isRussian()) "Пусто" else "Empty"
    
    // Главный экран
    val hello: String @Composable get() = if (isRussian()) "Привет!" else "Hello!"
    val waveEmoji: String get() = "👋"
    val inbox: String @Composable get() = if (isRussian()) "Входящие" else "Inbox"
    val unread: String @Composable get() = if (isRussian()) "Непрочитано" else "Unread"
    val favorites: String @Composable get() = if (isRussian()) "Избранные письма" else "Favorites"
    val notes: String @Composable get() = if (isRussian()) "Заметки" else "Notes"
    val calendar: String @Composable get() = if (isRussian()) "Календарь" else "Calendar"
    val folders: String @Composable get() = if (isRussian()) "Папки" else "Folders"
    val refresh: String @Composable get() = if (isRussian()) "Обновить" else "Refresh"
    val compose: String @Composable get() = if (isRussian()) "Написать" else "Compose"
    val searchInMail: String @Composable get() = if (isRussian()) "Поиск в почте" else "Search mail"
    val syncingMail: String @Composable get() = if (isRussian()) "Синхронизация..." else "Syncing..."
    val noNetwork: String @Composable get() = if (isRussian()) "Нет подключения к сети" else "No network connection"
    val emails: String @Composable get() = if (isRussian()) "писем" else "emails"
    val events: String @Composable get() = if (isRussian()) "событий" else "events"
    val notesCount: String @Composable get() = if (isRussian()) "заметок" else "notes"
    val menu: String @Composable get() = if (isRussian()) "Меню" else "Menu"
    val noAccount: String @Composable get() = if (isRussian()) "Нет аккаунта" else "No account"
    val appFeatures: String @Composable get() = if (isRussian()) "Возможности программы" else "App features"
    
    // Папки
    val drafts: String @Composable get() = if (isRussian()) "Черновики" else "Drafts"
    val sent: String @Composable get() = if (isRussian()) "Отправленные" else "Sent"
    val trash: String @Composable get() = if (isRussian()) "Удалённые" else "Trash"
    val outbox: String @Composable get() = if (isRussian()) "Исходящие" else "Outbox"
    val spam: String @Composable get() = if (isRussian()) "Спам" else "Spam"
    val toPrefix: String @Composable get() = if (isRussian()) "Кому:" else "To:"
    val userFolders: String @Composable get() = if (isRussian()) "Папки" else "Folders"
    val userFoldersEmpty: String @Composable get() = if (isRussian()) "Нет пользовательских папок" else "No user folders"
    val foldersSynced: String @Composable get() = if (isRussian()) "Папки синхронизированы" else "Folders synced"
    val syncFolders: String @Composable get() = if (isRussian()) "Синхронизировать папки" else "Sync folders"
    val createFolder: String @Composable get() = if (isRussian()) "Создать папку" else "Create folder"
    val folderName: String @Composable get() = if (isRussian()) "Название папки" else "Folder name"
    val folderCreated: String @Composable get() = if (isRussian()) "Папка создана" else "Folder created"
    val deleteFolder: String @Composable get() = if (isRussian()) "Удалить папку?" else "Delete folder?"
    val deleteFolderConfirm: String @Composable get() = if (isRussian()) 
        "Вы желаете удалить папку вместе с письмами с сервера?" 
        else "Do you want to delete this folder with all emails from server?"
    val folderDeleted: String @Composable get() = if (isRussian()) "Папка удалена" else "Folder deleted"
    val renameFolder: String @Composable get() = if (isRussian()) "Переименовать папку" else "Rename folder"
    val newName: String @Composable get() = if (isRussian()) "Новое название" else "New name"
    val rename: String @Composable get() = if (isRussian()) "Переименовать" else "Rename"
    val folderRenamed: String @Composable get() = if (isRussian()) "Папка переименована" else "Folder renamed"
    
    // Очистка корзины
    val emptyTrash: String @Composable get() = if (isRussian()) "Очистить корзину" else "Empty trash"
    val emptyTrashConfirm: String @Composable get() = if (isRussian()) 
        "Все письма в корзине будут удалены безвозвратно. Продолжить?" 
        else "All emails in trash will be permanently deleted. Continue?"
    val trashEmptied: String @Composable get() = if (isRussian()) "Корзина очищена" else "Trash emptied"
    val deletionCancelled: String @Composable get() = if (isRussian()) "Удаление отменено" else "Deletion cancelled"
    @Composable
    fun deletingEmails(count: Int): String = if (isRussian()) "Удаление $count писем..." else "Deleting $count emails..."
    @Composable
    fun deletingNotes(count: Int): String = if (isRussian()) "Удаление $count заметок..." else "Deleting $count notes..."
    @Composable
    fun deletingTasks(count: Int): String = if (isRussian()) "Удаление $count задач..." else "Deleting $count tasks..."
    @Composable
    fun deletingEvents(count: Int): String = if (isRussian()) "Удаление $count событий..." else "Deleting $count events..."
    @Composable
    fun restoringNotes(count: Int): String = if (isRussian()) "Восстановление $count заметок..." else "Restoring $count notes..."
    
    // Автоочистка корзины
    val autoEmptyTrash: String @Composable get() = if (isRussian()) "Автоочистка корзины" else "Auto-empty trash"
    val autoEmptyTrashDesc: String @Composable get() = if (isRussian()) 
        "Автоматически удалять старые письма из корзины" 
        else "Automatically delete old emails from trash"
    
    // Автоматическая очистка
    val autoCleanup: String @Composable get() = if (isRussian()) "Автоматическая очистка" else "Auto cleanup"
    val autoCleanupDesc: String @Composable get() = if (isRussian()) 
        "Настройка автоматической очистки папок" 
        else "Configure automatic folder cleanup"
    val autoCleanupTrash: String @Composable get() = if (isRussian()) "Корзина" else "Trash"
    val autoCleanupDrafts: String @Composable get() = if (isRussian()) "Черновики" else "Drafts"
    val autoCleanupSpam: String @Composable get() = if (isRussian()) "Спам" else "Spam"
    
    // Настройки
    val settings: String @Composable get() = if (isRussian()) "Настройки" else "Settings"
    val accounts: String @Composable get() = if (isRussian()) "Аккаунты" else "Accounts"
    val addAccount: String @Composable get() = if (isRussian()) "Добавить аккаунт" else "Add account"
    val accountSettings: String @Composable get() = if (isRussian()) "Настройки аккаунта" else "Account settings"
    val changeCredentials: String @Composable get() = if (isRussian()) "Изменить учётные данные" else "Change credentials"
    val deleteAccount: String @Composable get() = if (isRussian()) "Удалить аккаунт?" else "Delete account?"
    val deleteAccountConfirm: String @Composable get() = if (isRussian()) 
        "Аккаунт и все связанные данные будут удалены." 
        else "Account and all related data will be deleted."
    val general: String @Composable get() = if (isRussian()) "Общие" else "General"
    val appearance: String @Composable get() = if (isRussian()) "Внешний вид" else "Appearance"
    val syncSettings: String @Composable get() = if (isRussian()) "Синхронизация" else "Sync"
    val sync: String @Composable get() = if (isRussian()) "Синхронизация" else "Sync"
    val serverCertificate: String @Composable get() = if (isRussian()) "Сертификат сервера" else "Server certificate"
    
    // Цветовые темы
    val colorTheme: String @Composable get() = if (isRussian()) "Цветовая тема" else "Color theme"
    val selectColorTheme: String @Composable get() = if (isRussian()) "Выберите тему" else "Select theme"
    val dailyThemes: String @Composable get() = if (isRussian()) "Темы по дням недели" else "Daily themes"
    val dailyThemesDesc: String @Composable get() = if (isRussian()) "Разные цвета для каждого дня" else "Different colors for each day"
    val dailyThemesActive: String @Composable get() = if (isRussian()) "Активны темы по дням" else "Daily themes active"
    val configureDailyThemes: String @Composable get() = if (isRussian()) "Настроить темы по дням" else "Configure daily themes"
    
    // Анимации
    val animations: String @Composable get() = if (isRussian()) "Анимации" else "Animations"
    val animationsDesc: String @Composable get() = if (isRussian()) "Анимированные элементы интерфейса" else "Animated UI elements"
    
    // Персонализация интерфейса
    val interfacePersonalization: String @Composable get() = if (isRussian()) "Персонализация интерфейса" else "Interface personalization"
    val interfacePersonalizationDesc: String @Composable get() = if (isRussian()) "Язык, темы, шрифты, анимации" else "Language, themes, fonts, animations"
    
    // Дни недели
    val monday: String @Composable get() = if (isRussian()) "Понедельник" else "Monday"
    val tuesday: String @Composable get() = if (isRussian()) "Вторник" else "Tuesday"
    val wednesday: String @Composable get() = if (isRussian()) "Среда" else "Wednesday"
    val thursday: String @Composable get() = if (isRussian()) "Четверг" else "Thursday"
    val friday: String @Composable get() = if (isRussian()) "Пятница" else "Friday"
    val saturday: String @Composable get() = if (isRussian()) "Суббота" else "Saturday"
    val sunday: String @Composable get() = if (isRussian()) "Воскресенье" else "Sunday"
    
    val wifiOnly: String @Composable get() = if (isRussian()) "Только по Wi-Fi" else "Wi-Fi only"
    val wifiOnlyDesc: String @Composable get() = if (isRussian()) 
        "Синхронизация только через Wi-Fi" 
        else "Sync only over Wi-Fi"
    val anyNetwork: String @Composable get() = if (isRussian()) 
        "Синхронизация через любую сеть" 
        else "Sync over any network"
    val notifications: String @Composable get() = if (isRussian()) "Уведомления" else "Notifications"
    val enabled: String @Composable get() = if (isRussian()) "Включены" else "Enabled"
    val disabled: String @Composable get() = if (isRussian()) "Выключены" else "Disabled"
    val aboutApp: String @Composable get() = if (isRussian()) "О приложении" else "About"
    val version: String @Composable get() = if (isRussian()) "Версия" else "Version"
    val developer: String @Composable get() = if (isRussian()) "Разработчик" else "Developer"
    val supportedProtocols: String @Composable get() = if (isRussian()) "Поддерживаемые протоколы" else "Supported protocols"
    val language: String @Composable get() = if (isRussian()) "Язык" else "Language"
    val selectLanguage: String @Composable get() = if (isRussian()) "Выберите язык" else "Select language"
    val fontSize: String @Composable get() = if (isRussian()) "Размер шрифта" else "Font size"
    val selectFontSize: String @Composable get() = if (isRussian()) "Выберите размер шрифта" else "Select font size"
    
    // Режим синхронизации
    val syncMode: String @Composable get() = if (isRussian()) "Режим синхронизации" else "Sync mode"
    val syncModePush: String @Composable get() = if (isRussian()) "Push (мгновенно)" else "Push (instant)"
    val syncModeScheduled: String @Composable get() = if (isRussian()) "По расписанию" else "Scheduled"
    val syncModePushDesc: String @Composable get() = if (isRussian()) 
        "Мгновенные уведомления, больше расход батареи" 
        else "Instant notifications, higher battery usage"
    val syncModeScheduledDesc: String @Composable get() = if (isRussian()) 
        "Проверка по интервалу, экономит батарею" 
        else "Checks on interval, saves battery"
    
    // Ночной режим
    val nightMode: String @Composable get() = if (isRussian()) "Ночной режим" else "Night mode"
    val nightModeDesc: String @Composable get() = if (isRussian()) 
        "23:00-7:00 синхронизация каждые 60 мин" 
        else "23:00-7:00 sync every 60 min"
    
    // Режим экономии батареи
    val batterySaverMode: String @Composable get() = if (isRussian()) "Режим экономии батареи" else "Battery saver mode"
    val batterySaverActive: String @Composable get() = if (isRussian()) "Экономия батареи" else "Battery saver"
    val ignoreBatterySaver: String @Composable get() = if (isRussian()) "Игнорировать режим экономии аккумулятора при синхронизации" else "Ignore battery saver when syncing"
    
    // Интервалы синхронизации
    val syncOff: String @Composable get() = if (isRussian()) "Выключено" else "Off"
    val syncInterval: String @Composable get() = if (isRussian()) "Интервал синхронизации" else "Sync interval"
    
    // Подпись
    val signature: String @Composable get() = if (isRussian()) "Подпись" else "Signature"
    val signatureHint: String @Composable get() = if (isRussian()) "Текст подписи для писем" else "Email signature text"
    val editSignature: String @Composable get() = if (isRussian()) "Редактировать подпись" else "Edit signature"
    val noSignature: String @Composable get() = if (isRussian()) "Не задана" else "Not set"
    val syncModeDesc: String @Composable get() = if (isRussian()) 
        "Push — мгновенные уведомления, По расписанию — экономия батареи" 
        else "Push — instant notifications, Scheduled — battery saving"
    @Composable
    fun minutes(n: Int): String = if (LocalLanguage.current == AppLanguage.RUSSIAN) {
        when {
            n == 1 -> "1 минута"
            n in 2..4 -> "$n минуты"
            else -> "$n минут"
        }
    } else {
        if (n == 1) "1 minute" else "$n minutes"
    }
    
    // Добавление аккаунта
    val welcomeTitle: String @Composable get() = if (isRussian()) "Добро пожаловать!" else "Welcome!"
    val welcomeSubtitle: String @Composable get() = if (isRussian()) 
        "Добавьте почтовый аккаунт для начала работы" 
        else "Add an email account to get started"
    val accountType: String @Composable get() = if (isRussian()) "Тип аккаунта" else "Account type"
    val email: String @Composable get() = if (isRussian()) "Email" else "Email"
    val password: String @Composable get() = if (isRussian()) "Пароль" else "Password"
    val server: String @Composable get() = if (isRussian()) "Сервер" else "Server"
    val port: String @Composable get() = if (isRussian()) "Порт" else "Port"
    val displayName: String @Composable get() = if (isRussian()) "Отображаемое имя" else "Display name"
    val domain: String @Composable get() = if (isRussian()) "Домен" else "Domain"
    val optional: String @Composable get() = if (isRussian()) "опционально" else "optional"
    val connecting: String @Composable get() = if (isRussian()) "Подключение..." else "Connecting..."
    val connectionSuccess: String @Composable get() = if (isRussian()) "Подключено успешно!" else "Connected successfully!"
    val connectionFailed: String @Composable get() = if (isRussian()) "Ошибка подключения" else "Connection failed"
    val addAccountBtn: String @Composable get() = if (isRussian()) "Добавить аккаунт" else "Add account"
    val useSSL: String @Composable get() = if (isRussian()) "Использовать SSL" else "Use SSL"
    
    // Верификация email
    val verifyingAccount: String @Composable get() = if (isRussian()) "Проверка учётной записи..." else "Verifying account..."
    val verifyingEmail: String @Composable get() = if (isRussian()) "Проверяем email..." else "Verifying email..."
    val emailMismatch: String @Composable get() = if (isRussian()) "Email не соответствует учётной записи" else "Email does not match account"
    val enteredEmail: String @Composable get() = if (isRussian()) "Введённый email:" else "Entered email:"
    val actualEmail: String @Composable get() = if (isRussian()) "Реальный email:" else "Actual email:"
    val pleaseEnterCorrectEmail: String @Composable get() = if (isRussian()) "Пожалуйста, введите правильный email." else "Please enter the correct email."
    @Composable
    fun emailMismatchDetails(entered: String, actual: String): String = if (isRussian()) 
        "Введённый email: $entered\nРеальный email: $actual\n\nПожалуйста, введите правильный email."
        else "Entered email: $entered\nActual email: $actual\n\nPlease enter the correct email."
    val sendingTestEmail: String @Composable get() = if (isRussian()) "Отправка тестового письма..." else "Sending test email..."
    val testEmailSubject: String @Composable get() = if (isRussian()) "Проверка iwo Mail Client" else "iwo Mail Client verification"
    val testEmailBody: String @Composable get() = if (isRussian()) 
        "Это тестовое письмо для проверки учётной записи. Можете удалить его."
        else "This is a test email for account verification. You can delete it."
    
    // Письма
    val noEmails: String @Composable get() = if (isRussian()) "Нет писем" else "No emails"
    val from: String @Composable get() = if (isRussian()) "От" else "From"
    val to: String @Composable get() = if (isRussian()) "Кому" else "To"
    val cc: String @Composable get() = if (isRussian()) "Копия" else "Cc"
    val bcc: String @Composable get() = if (isRussian()) "Скрытая копия" else "Bcc"
    val subject: String @Composable get() = if (isRussian()) "Тема" else "Subject"
    val noSubject: String @Composable get() = if (isRussian()) "(Без темы)" else "(No subject)"
    val attachments: String @Composable get() = if (isRussian()) "Вложения" else "Attachments"
    val reply: String @Composable get() = if (isRussian()) "Ответить" else "Reply"
    val writeMore: String @Composable get() = if (isRussian()) "Написать ещё" else "Write more"
    val replyAll: String @Composable get() = if (isRussian()) "Ответить всем" else "Reply all"
    val forward: String @Composable get() = if (isRussian()) "Переслать" else "Forward"
    val markUnread: String @Composable get() = if (isRussian()) "Отметить непрочитанным" else "Mark as unread"
    val markRead: String @Composable get() = if (isRussian()) "Отметить прочитанным" else "Mark as read"
    val addToFavorites: String @Composable get() = if (isRussian()) "В избранное" else "Add to favorites"
    val removeFromFavorites: String @Composable get() = if (isRussian()) "Убрать из избранного" else "Remove from favorites"
    val moveToSpam: String @Composable get() = if (isRussian()) "В спам" else "Move to spam"
    val moveToTrash: String @Composable get() = if (isRussian()) "В корзину" else "Move to trash"
    val moveTo: String @Composable get() = if (isRussian()) "Переместить в..." else "Move to..."
    val send: String @Composable get() = if (isRussian()) "Отправить" else "Send"
    val sending: String @Composable get() = if (isRussian()) "Отправка..." else "Sending..."
    val sent_success: String @Composable get() = if (isRussian()) "Письмо отправлено" else "Email sent"
    val discard: String @Composable get() = if (isRussian()) "Отменить" else "Discard"
    val discardDraft: String @Composable get() = if (isRussian()) "Отменить черновик?" else "Discard draft?"
    val discardDraftConfirm: String @Composable get() = if (isRussian()) 
        "Черновик будет удалён" 
        else "Draft will be discarded"
    
    // Цитирование в ответе/пересылке
    val originalMessage: String @Composable get() = if (isRussian()) "Исходное сообщение" else "Original message"
    val forwardedMessage: String @Composable get() = if (isRussian()) "Пересылаемое сообщение" else "Forwarded message"
    val quoteFrom: String @Composable get() = if (isRussian()) "От" else "From"
    val quoteDate: String @Composable get() = if (isRussian()) "Дата" else "Date"
    val quoteSubject: String @Composable get() = if (isRussian()) "Тема" else "Subject"
    val quoteTo: String @Composable get() = if (isRussian()) "Кому" else "To"
    
    // Поиск
    val search: String @Composable get() = if (isRussian()) "Поиск" else "Search"
    val searchHint: String @Composable get() = if (isRussian()) "Введите запрос для поиска" else "Enter search query"
    val noResults: String @Composable get() = if (isRussian()) "Ничего не найдено" else "No results"
    
    // Фильтры
    val filters: String @Composable get() = if (isRussian()) "Фильтры" else "Filters"
    val all: String @Composable get() = if (isRussian()) "Все" else "All"
    val unreadOnly: String @Composable get() = if (isRussian()) "Непрочитанные" else "Unread"
    val withAttachments: String @Composable get() = if (isRussian()) "С вложениями" else "With attachments"
    val important: String @Composable get() = if (isRussian()) "Важные" else "Important"
    val flagged: String @Composable get() = if (isRussian()) "Избранные" else "Flagged"
    val today: String @Composable get() = if (isRussian()) "Сегодня" else "Today"
    val yesterday: String @Composable get() = if (isRussian()) "Вчера" else "Yesterday"
    val thisWeek: String @Composable get() = if (isRussian()) "На этой неделе" else "This week"
    val thisMonth: String @Composable get() = if (isRussian()) "В этом месяце" else "This month"
    val older: String @Composable get() = if (isRussian()) "Старше" else "Older"
    
    // Донат - реквизиты НЕ переводятся, остаются на русском (имена и номера)
    val supportDeveloper: String @Composable get() = if (isRussian()) "Помощь проекту" else "Help the project"
    val supportText: String @Composable get() = if (isRussian()) 
        "Если приложение вам понравилось, вы можете поддержать разработку:" 
        else "If you like the app, you can support development:"
    val contactDeveloper: String @Composable get() = if (isRussian()) "Связаться с разработчиком:" else "Contact developer:"
    val telegram: String @Composable get() = "Telegram"
    // Метки переводятся, значения (имена, номера) - нет
    val recipient: String @Composable get() = if (isRussian()) "Получатель:" else "Recipient:"
    val accountNumber: String @Composable get() = if (isRussian()) "Номер счёта:" else "Account number:"
    val bank: String @Composable get() = if (isRussian()) "Банк:" else "Bank:"
    val orByPhone: String @Composable get() = if (isRussian()) "Или по номеру телефона через СБП:" else "Or by phone via SBP:"
    val copyAccount: String @Composable get() = if (isRussian()) "Копировать счёт" else "Copy account"
    val accountCopied: String @Composable get() = if (isRussian()) "Номер счёта скопирован" else "Account number copied"
    val closeDialog: String @Composable get() = if (isRussian()) "Закрыть" else "Close"
    val financialSupport: String @Composable get() = if (isRussian()) "Финансовая поддержка:" else "Financial support:"
    
    // О приложении
    val appDescription: String @Composable get() = if (isRussian()) 
        "Почтовый клиент для Microsoft Exchange Server с поддержкой EAS, IMAP и POP3." 
        else "Mail client for Microsoft Exchange Server with EAS, IMAP and POP3 support."
    val featureSync: String @Composable get() = if (isRussian()) "📧 Синхронизация" else "📧 Sync"
    val featureAttachments: String @Composable get() = if (isRussian()) "📎 Вложения" else "📎 Attachments"
    val featureSend: String @Composable get() = if (isRussian()) "✉️ Отправка" else "✉️ Send"
    val featureSearch: String @Composable get() = if (isRussian()) "🔍 Поиск" else "🔍 Search"
    val featureFolders: String @Composable get() = if (isRussian()) "📁 Папки" else "📁 Folders"
    val featureContacts: String @Composable get() = if (isRussian()) "👥 Контакты" else "👥 Contacts"
    val featureNotes: String @Composable get() = if (isRussian()) "📝 Заметки" else "📝 Notes"
    val featureCalendar: String @Composable get() = if (isRussian()) "📅 Календарь" else "📅 Calendar"
    val featureTasks: String @Composable get() = if (isRussian()) "✅ Задачи" else "✅ Tasks"
    val developerLabel: String @Composable get() = if (isRussian()) "Разработчик:" else "Developer:"
    
    // Советы по работе с приложением
    val tipsTitle: String @Composable get() = if (isRussian()) "Советы" else "Tips"
    val tipNotification: String @Composable get() = if (isRussian()) 
        "Уведомление «Ожидание писем» нельзя убрать — это требование Android для фоновой работы (только в режиме Push)."
        else "The «Waiting for emails» notification cannot be removed — it's an Android requirement (Push mode only)."
    val tipBattery: String @Composable get() = if (isRussian())
        "Для надёжной доставки писем отключите оптимизацию батареи для приложения в настройках телефона."
        else "For reliable email delivery, disable battery optimization for the app in phone settings."
    val tipCertificate: String @Composable get() = if (isRussian())
        "Для корпоративной почты рекомендую пользоваться сертификатом сервера."
        else "For corporate email, I recommend using the server certificate."
    val tipBeta: String @Composable get() = if (isRussian())
        "IMAP и POP3 в бета-режиме."
        else "IMAP and POP3 are in beta."
    
    // Ссылки
    val viewChangelog: String @Composable get() = if (isRussian()) "Ознакомиться с развитием программы" else "View changelog"
    val privacyPolicy: String @Composable get() = if (isRussian()) "Политика конфиденциальности" else "Privacy Policy"
    
    // Папки не найдены
    val noFoldersFound: String @Composable get() = if (isRussian()) "Папки не найдены" else "No folders found"
    val tapToSync: String @Composable get() = if (isRussian()) "Нажмите для синхронизации" else "Tap to sync"
    val synchronize: String @Composable get() = if (isRussian()) "Синхронизировать" else "Synchronize"
    val loadingFolders: String @Composable get() = if (isRussian()) "Загрузка папок..." else "Loading folders..."
    
    // Специальные папки Exchange
    val tasks: String @Composable get() = if (isRussian()) "Задачи" else "Tasks"
    val contacts: String @Composable get() = if (isRussian()) "Контакты" else "Contacts"
    val journal: String @Composable get() = if (isRussian()) "Журнал" else "Journal"
    
    // Функция для локализации названия папки по типу
    @Composable
    fun getFolderName(type: Int, originalName: String): String {
        return when (type) {
            2 -> inbox
            3 -> drafts
            4 -> trash
            5 -> sent
            6 -> outbox
            7 -> tasks
            8 -> calendar
            9 -> contacts
            10 -> notes
            11 -> spam
            14 -> journal
            else -> originalName
        }
    }
    
    // Дополнительные строки для главного экрана
    val emailsCount: String @Composable get() = if (isRussian()) "писем" else "emails"
    
    @Composable
    fun pluralEmails(count: Int): String {
        return if (isRussian()) {
            val mod10 = count % 10
            val mod100 = count % 100
            when {
                mod100 in 11..19 -> "писем"
                mod10 == 1 -> "письмо"
                mod10 in 2..4 -> "письма"
                else -> "писем"
            }
        } else {
            if (count == 1) "email" else "emails"
        }
    }
    
    // Время последней синхронизации
    val lastSync: String @Composable get() = if (isRussian()) "Последняя синхронизация:" else "Last sync:"
    val neverSynced: String @Composable get() = if (isRussian()) "Ещё не синхронизировано" else "Not synced yet"
    
    // Рекомендация дня
    val recommendationOfDay: String @Composable get() = if (isRussian()) "Рекомендация дня:" else "Tip of the day:"
    @Composable
    fun cleanupFolderRecommendation(folderNames: String): String {
        return if (isRussian()) "Почистить папку $folderNames 😊" else "Clean up $folderNames folder 😊"
    }
    @Composable
    fun cleanupFoldersRecommendation(folderNames: String): String {
        return if (isRussian()) "Почистить папки $folderNames 😊" else "Clean up $folderNames folders 😊"
    }
    
    // Сообщения об удалении
    val movedToTrash: String @Composable get() = if (isRussian()) "Перемещено в корзину" else "Moved to trash"
    val deletedPermanently: String @Composable get() = if (isRussian()) "Удалено окончательно" else "Deleted permanently"
    val nothingDeleted: String @Composable get() = if (isRussian()) "Ничего не удалено" else "Nothing deleted"
    val folderSyncing: String @Composable get() = if (isRussian()) "Дождитесь завершения синхронизации" else "Wait for sync to complete"
    val alreadyInFolder: String @Composable get() = if (isRussian()) "Письма уже в этой папке" else "Emails already in this folder"
    val emailNotFound: String @Composable get() = if (isRussian()) "Письмо не найдено" else "Email not found"
    val accountNotFound: String @Composable get() = if (isRussian()) "Аккаунт не найден" else "Account not found"
    val folderNotFound: String @Composable get() = if (isRussian()) "Папка не найдена" else "Folder not found"
    val trashFolderNotFound: String @Composable get() = if (isRussian()) "Папка 'Удалённые' не найдена" else "Trash folder not found"
    val spamFolderNotFound: String @Composable get() = if (isRussian()) "Папка 'Спам' не найдена" else "Spam folder not found"
    val restored: String @Composable get() = if (isRussian()) "Восстановлено" else "Restored"
    val movedToSpam: String @Composable get() = if (isRussian()) "Перемещено в спам" else "Moved to spam"
    val moved: String @Composable get() = if (isRussian()) "Перемещено" else "Moved"
    
    // EmailListScreen - фильтры
    val allMail: String @Composable get() = if (isRussian()) "Вся почта" else "All mail"
    val starred: String @Composable get() = if (isRussian()) "Помеченные" else "Starred"
    val allDates: String @Composable get() = if (isRussian()) "Все даты" else "All dates"
    val week: String @Composable get() = if (isRussian()) "Неделя" else "Week"
    val month: String @Composable get() = if (isRussian()) "Месяц" else "Month"
    val year: String @Composable get() = if (isRussian()) "Год" else "Year"
    val sender: String @Composable get() = if (isRussian()) "Отправитель" else "Sender"
    val nameOrEmail: String @Composable get() = if (isRussian()) "Имя или email" else "Name or email"
    val showFilters: String @Composable get() = if (isRussian()) "Показать фильтры" else "Show filters"
    val hideFilters: String @Composable get() = if (isRussian()) "Скрыть фильтры" else "Hide filters"
    val resetAll: String @Composable get() = if (isRussian()) "Сбросить все" else "Reset all"
    val total: String @Composable get() = if (isRussian()) "Всего" else "Total"
    val shown: String @Composable get() = if (isRussian()) "Показано" else "Shown"
    val of: String @Composable get() = if (isRussian()) "из" else "of"
    val selectAll: String @Composable get() = if (isRussian()) "Выбрать все" else "Select all"
    val noFavoriteEmails: String @Composable get() = if (isRussian()) "Нет избранных писем" else "No favorite emails"
    val retry: String @Composable get() = if (isRussian()) "Повторить" else "Retry"
    val toOld: String @Composable get() = if (isRussian()) "К старым" else "To old"
    val toNew: String @Composable get() = if (isRussian()) "К новым" else "To new"
    
    // Действия с письмами
    val restore: String @Composable get() = if (isRussian()) "Восстановить" else "Restore"
    val star: String @Composable get() = if (isRussian()) "Пометить" else "Star"
    val read: String @Composable get() = if (isRussian()) "Прочитано" else "Read"
    val unreadAction: String @Composable get() = if (isRussian()) "Непрочитанное" else "Unread"
    val toSpam: String @Composable get() = if (isRussian()) "В спам" else "To spam"
    val deletePermanently: String @Composable get() = if (isRussian()) "Удалить окончательно" else "Delete permanently"
    val cancelSelection: String @Composable get() = if (isRussian()) "Отменить выбор" else "Cancel selection"
    val more: String @Composable get() = if (isRussian()) "Ещё" else "More"
    val noUserFolders: String @Composable get() = if (isRussian()) "Нет пользовательских папок для перемещения" else "No user folders to move to"
    
    // Диалоги удаления
    val deleteEmail: String @Composable get() = if (isRussian()) "Удалить письмо?" else "Delete email?"
    val deleteEmails: String @Composable get() = if (isRussian()) "Удалить письма?" else "Delete emails?"
    val emailWillBeMovedToTrash: String @Composable get() = if (isRussian()) "Письмо будет перемещено в удалённые." else "Email will be moved to deleted."
    @Composable
    fun emailsWillBeMovedToTrash(count: Int): String = if (isRussian()) {
        when {
            count % 10 == 1 && count % 100 != 11 -> "$count письмо будет перемещено в удалённые."
            count % 10 in 2..4 && count % 100 !in 12..14 -> "$count письма будут перемещены в удалённые."
            else -> "$count писем будут перемещены в удалённые."
        }
    } else {
        if (count == 1) "Email will be moved to deleted." else "$count emails will be moved to deleted."
    }
    val deleteForever: String @Composable get() = if (isRussian()) "Удалить навсегда?" else "Delete forever?"
    val emailWillBeDeletedPermanently: String @Composable get() = if (isRussian()) "Письмо будет удалено окончательно." else "Email will be deleted permanently."
    @Composable
    fun emailsWillBeDeletedPermanently(count: Int): String = if (isRussian()) {
        when {
            count % 10 == 1 && count % 100 != 11 -> "$count письмо будет удалено окончательно."
            count % 10 in 2..4 && count % 100 !in 12..14 -> "$count письма будут удалены окончательно."
            else -> "$count писем будут удалены окончательно."
        }
    } else {
        if (count == 1) "Email will be deleted permanently." else "$count emails will be deleted permanently."
    }
    
    // Ошибка интернета
    val noInternetConnection: String @Composable get() = if (isRussian()) "Нет подключения к интернету. Проверьте сетевое соединение." else "No internet connection. Check your network."
    
    // Загрузка письма
    val loadingEmail: String @Composable get() = if (isRussian()) "Загрузка письма..." else "Loading email..."
    val loadingTimeout: String @Composable get() = if (isRussian()) "Таймаут загрузки" else "Loading timeout"
    val loadError: String @Composable get() = if (isRussian()) "Ошибка загрузки" else "Loading error"
    val noText: String @Composable get() = if (isRussian()) "(Нет текста)" else "(No text)"
    val errorPrefix: String @Composable get() = if (isRussian()) "Ошибка" else "Error"
    
    // ComposeScreen
    val scheduleSend: String @Composable get() = if (isRussian()) "Запланировать отправку" else "Schedule send"
    val doNotSave: String @Composable get() = if (isRussian()) "Не сохранять" else "Don't save"
    val saveDraft: String @Composable get() = if (isRussian()) "Сохранить" else "Save"
    val draftSaved: String @Composable get() = if (isRussian()) "Черновик сохранён" else "Draft saved"
    val draftSaveError: String @Composable get() = if (isRussian()) "Ошибка сохранения черновика" else "Draft save error"
    val discardDraftQuestion: String @Composable get() = if (isRussian()) "Сохранить черновик?" else "Save draft?"
    val draftWillBeDeleted: String @Composable get() = if (isRussian()) "Сохранить письмо в черновики?" else "Save email to drafts?"
    val selectSender: String @Composable get() = if (isRussian()) "Выберите отправителя" else "Select sender"
    val attach: String @Composable get() = if (isRussian()) "Прикрепить" else "Attach"
    val showCopy: String @Composable get() = if (isRussian()) "Показать копию" else "Show Cc/Bcc"
    val hiddenCopy: String @Composable get() = if (isRussian()) "Скрытая" else "Bcc"
    val messageText: String @Composable get() = if (isRussian()) "Текст сообщения" else "Message"
    val attachmentsCount: String @Composable get() = if (isRussian()) "Вложения" else "Attachments"
    val selectAccount: String @Composable get() = if (isRussian()) "Выбрать аккаунт" else "Select account"
    val sendScheduled: String @Composable get() = if (isRussian()) "Отправка запланирована" else "Send scheduled"
    val localDraftsNotice: String @Composable get() = if (isRussian()) 
        "Черновики сохраняются локально и удаляются сразу" 
        else "Drafts are saved locally and deleted immediately"
    val authError: String @Composable get() = if (isRussian()) "Ошибка авторизации" else "Authorization error"
    val sendError: String @Composable get() = if (isRussian()) "Ошибка отправки" else "Send error"
    val unknownError: String @Composable get() = if (isRussian()) "Неизвестная ошибка" else "Unknown error"
    val certLoadError: String @Composable get() = if (isRussian()) "Ошибка загрузки сертификата" else "Certificate loading error"
    val clientCertLoadError: String @Composable get() = if (isRussian()) "Ошибка загрузки клиентского сертификата" else "Client certificate loading error"
    
    // ScheduleSendDialog
    val tomorrowMorning: String @Composable get() = if (isRussian()) "Завтра утром" else "Tomorrow morning"
    val tomorrowAfternoon: String @Composable get() = if (isRussian()) "Завтра днём" else "Tomorrow afternoon"
    val mondayMorning: String @Composable get() = if (isRussian()) "В понедельник утром" else "Monday morning"
    val selectDateTime: String @Composable get() = if (isRussian()) "Выбрать дату и время" else "Pick date & time"
    val specifyExactTime: String @Composable get() = if (isRussian()) "Указать точное время отправки" else "Specify exact send time"
    val timezone: String @Composable get() = if (isRussian()) "Часовой пояс" else "Timezone"
    val date: String @Composable get() = if (isRussian()) "Дата" else "Date"
    val hour: String @Composable get() = if (isRussian()) "Час" else "Hour"
    val minute: String @Composable get() = if (isRussian()) "Мин" else "Min"
    val second: String @Composable get() = if (isRussian()) "Сек" else "Sec"
    val schedule: String @Composable get() = if (isRussian()) "Запланировать" else "Schedule"
    val selectDate: String @Composable get() = if (isRussian()) "Выбрать дату" else "Select date"
    
    // Отчёт о прочтении
    val requestReadReceipt: String @Composable get() = if (isRussian()) "Запросить отчёт о прочтении" else "Request read receipt"
    val requestDeliveryReceipt: String @Composable get() = if (isRussian()) "Запросить отчёт о доставке" else "Request delivery receipt"
    val highPriority: String @Composable get() = if (isRussian()) "Высокий приоритет" else "High priority"
    val readReceiptRequest: String @Composable get() = if (isRussian()) "Запрос отчёта о прочтении" else "Read receipt request"
    val readReceiptRequestText: String @Composable get() = if (isRussian()) "Отправитель запросил уведомление о прочтении этого письма. Отправить?" else "The sender requested a read receipt for this message. Send it?"
    val readReceiptSent: String @Composable get() = if (isRussian()) "Отчёт о прочтении отправлен" else "Read receipt sent"
    
    // Названия цветовых тем
    val themePurple: String @Composable get() = if (isRussian()) "Фиолетовая" else "Purple"
    val themeBlue: String @Composable get() = if (isRussian()) "Синяя" else "Blue"
    val themeRed: String @Composable get() = if (isRussian()) "Красная" else "Red"
    val themeYellow: String @Composable get() = if (isRussian()) "Жёлтая" else "Yellow"
    val themeOrange: String @Composable get() = if (isRussian()) "Оранжевая" else "Orange"
    val themeGreen: String @Composable get() = if (isRussian()) "Зелёная" else "Green"
    val themePink: String @Composable get() = if (isRussian()) "Розовая" else "Pink"
    
    // Контакты
    val personalContacts: String @Composable get() = if (isRussian()) "Личные" else "Personal"
    val organization: String @Composable get() = if (isRussian()) "Организация" else "Organization"
    val selectContacts: String @Composable get() = if (isRussian()) "Выбор контактов" else "Select contacts"
    val selectContact: String @Composable get() = if (isRussian()) "Выбрать контакт" else "Select contact"
    val addContact: String @Composable get() = if (isRussian()) "Добавить контакт" else "Add contact"
    val editContact: String @Composable get() = if (isRussian()) "Редактировать контакт" else "Edit contact"
    val deleteContact: String @Composable get() = if (isRussian()) "Удалить контакт?" else "Delete contact?"
    val deleteContacts: String @Composable get() = if (isRussian()) "Удалить контакты?" else "Delete contacts?"
    val deleteContactConfirm: String @Composable get() = if (isRussian()) "Контакт будет удалён." else "Contact will be deleted."
    val deleteContactsConfirm: String @Composable get() = if (isRussian()) "Будет удалено контактов:" else "Contacts to delete:"
    val contactDeleted: String @Composable get() = if (isRussian()) "Контакт удалён" else "Contact deleted"
    val contactSaved: String @Composable get() = if (isRussian()) "Контакт сохранён" else "Contact saved"
    val selected: String @Composable get() = if (isRussian()) "Выбрано" else "Selected"
    val select: String @Composable get() = if (isRussian()) "Выбрать" else "Select"
    val favoriteContacts: String @Composable get() = if (isRussian()) "Избранные" else "Favorites"
    val filterAll: String @Composable get() = if (isRussian()) "Все" else "All"
    val filterWithEmail: String @Composable get() = if (isRussian()) "С email" else "With email"
    val filterWithPhone: String @Composable get() = if (isRussian()) "С телефоном" else "With phone"
    val noContacts: String @Composable get() = if (isRussian()) "Нет контактов" else "No contacts"
    val noNotes: String @Composable get() = if (isRussian()) "Нет заметок" else "No notes"
    val noEvents: String @Composable get() = if (isRussian()) "Нет событий" else "No events"
    val syncNotes: String @Composable get() = if (isRussian()) "Синхронизировать заметки" else "Sync notes"
    val syncCalendar: String @Composable get() = if (isRussian()) "Синхронизировать календарь" else "Sync calendar"
    val notesSynced: String @Composable get() = if (isRussian()) "Заметки синхронизированы" else "Notes synced"
    val calendarSynced: String @Composable get() = if (isRussian()) "Календарь синхронизирован" else "Calendar synced"
    val searchNotes: String @Composable get() = if (isRussian()) "Поиск заметок..." else "Search notes..."
    val searchEvents: String @Composable get() = if (isRussian()) "Поиск событий..." else "Search events..."
    val agenda: String @Composable get() = if (isRussian()) "Повестка" else "Agenda"
    val allDay: String @Composable get() = if (isRussian()) "Весь день" else "All day"
    val location: String @Composable get() = if (isRussian()) "Место" else "Location"
    val organizer: String @Composable get() = if (isRussian()) "Организатор" else "Organizer"
    val attendees: String @Composable get() = if (isRussian()) "Участники" else "Attendees"
    val showMore: String @Composable get() = if (isRussian()) "Показать ещё" else "Show more"
    val showLess: String @Composable get() = if (isRussian()) "Свернуть" else "Show less"
    val sortNewestFirst: String @Composable get() = if (isRussian()) "Сначала новые" else "Newest first"
    val sortOldestFirst: String @Composable get() = if (isRussian()) "Сначала старые" else "Oldest first"
    val meetingInvitation: String @Composable get() = if (isRussian()) "Приглашение на встречу" else "Meeting invitation"
    val accept: String @Composable get() = if (isRussian()) "Принять" else "Accept"
    val tentative: String @Composable get() = if (isRussian()) "Под вопросом" else "Tentative"
    val decline: String @Composable get() = if (isRussian()) "Отклонить" else "Decline"
    val responseSent: String @Composable get() = if (isRussian()) "Ответ отправлен" else "Response sent"
    val responseAccepted: String @Composable get() = if (isRussian()) "Вы приняли приглашение" else "You accepted"
    val responseTentative: String @Composable get() = if (isRussian()) "Вы ответили «Под вопросом»" else "You tentatively accepted"
    val responseDeclined: String @Composable get() = if (isRussian()) "Вы отклонили приглашение" else "You declined"
    val notResponded: String @Composable get() = if (isRussian()) "Ожидает ответа" else "Not responded"
    
    // Задачи
    val noTasks: String @Composable get() = if (isRussian()) "Нет задач" else "No tasks"
    val syncTasks: String @Composable get() = if (isRussian()) "Синхронизировать задачи" else "Sync tasks"
    val tasksSynced: String @Composable get() = if (isRussian()) "Задачи синхронизированы" else "Tasks synced"
    val searchTasks: String @Composable get() = if (isRussian()) "Поиск задач..." else "Search tasks..."
    val newTask: String @Composable get() = if (isRussian()) "Новая задача" else "New task"
    val editTask: String @Composable get() = if (isRussian()) "Редактировать задачу" else "Edit task"
    val taskTitle: String @Composable get() = if (isRussian()) "Название задачи" else "Task title"
    val taskDescription: String @Composable get() = if (isRussian()) "Описание" else "Description"
    val startDate: String @Composable get() = if (isRussian()) "Дата начала" else "Start date"
    val dueDate: String @Composable get() = if (isRussian()) "Срок выполнения" else "Due date"
    val priority: String @Composable get() = if (isRussian()) "Приоритет" else "Priority"
    val priorityLow: String @Composable get() = if (isRussian()) "Низ." else "Low"
    val priorityNormal: String @Composable get() = if (isRussian()) "Обычн." else "Normal"
    val priorityHigh: String @Composable get() = if (isRussian()) "Выс." else "High"
    val taskCreated: String @Composable get() = if (isRussian()) "Задача создана" else "Task created"
    val taskUpdated: String @Composable get() = if (isRussian()) "Задача обновлена" else "Task updated"
    val taskDeleted: String @Composable get() = if (isRussian()) "Задача удалена" else "Task deleted"
    val taskCompleted: String @Composable get() = if (isRussian()) "Задача выполнена" else "Task completed"
    val taskNotCompleted: String @Composable get() = if (isRussian()) "Задача не выполнена" else "Task not completed"
    val activeTasks: String @Composable get() = if (isRussian()) "Активные" else "Active"
    val taskInProgress: String @Composable get() = if (isRussian()) "В процессе выполнения" else "In progress"
    val completedTasks: String @Composable get() = if (isRussian()) "Выполненные" else "Completed"
    val allTasks: String @Composable get() = if (isRussian()) "Все" else "All"
    val highPriorityTasks: String @Composable get() = if (isRussian()) "Важные" else "High priority"
    val overdueTasks: String @Composable get() = if (isRussian()) "Просроченные" else "Overdue"
    val deletedTasks: String @Composable get() = if (isRussian()) "Удалённые" else "Deleted"
    val emptyTasksTrash: String @Composable get() = if (isRussian()) "Очистить корзину задач?" else "Empty tasks trash?"
    @Composable
    fun emptyTasksTrashConfirm(count: Int): String = if (isRussian()) 
        "Все удалённые задачи ($count) будут удалены безвозвратно. Продолжить?" 
        else "All deleted tasks ($count) will be permanently deleted. Continue?"
    val tasksTrashEmptied: String @Composable get() = if (isRussian()) "Задач удалено" else "Tasks deleted"
    val taskRestored: String @Composable get() = if (isRussian()) "Задача восстановлена" else "Task restored"
    val taskDeletedPermanently: String @Composable get() = if (isRussian()) "Задача удалена навсегда" else "Task permanently deleted"
    val tasksRestored: String @Composable get() = if (isRussian()) "Задач восстановлено" else "Tasks restored"
    val tasksDeletedPermanently: String @Composable get() = if (isRussian()) "Задач удалено навсегда" else "Tasks permanently deleted"
    val deleteTasks: String @Composable get() = if (isRussian()) "Удалить задачи?" else "Delete tasks?"
    val deleteTasksPermanently: String @Composable get() = if (isRussian()) "Удалить задачи навсегда?" else "Delete tasks permanently?"
    val deleteTaskConfirm: String @Composable get() = if (isRussian()) "Задача будет перемещена в корзину." else "Task will be moved to trash."
    val deleteTaskPermanentlyConfirm: String @Composable get() = if (isRussian()) "Задача будет удалена безвозвратно." else "Task will be permanently deleted."
    @Composable
    fun deleteTasksConfirm(count: Int): String = if (isRussian()) 
        "Выбранные задачи ($count) будут перемещены в корзину." 
        else "Selected tasks ($count) will be moved to trash."
    @Composable
    fun deleteTasksPermanentlyConfirm(count: Int): String = if (isRussian()) 
        "Выбранные задачи ($count) будут удалены безвозвратно." 
        else "Selected tasks ($count) will be permanently deleted."
    @Composable
    fun selectedCount(count: Int): String = if (isRussian()) "Выбрано: $count" else "Selected: $count"
    val taskInTrash: String @Composable get() = if (isRussian()) "Задача в корзине" else "Task in trash"
    val noTitle: String @Composable get() = if (isRussian()) "(Без названия)" else "(No title)"
    val tasksCount: String @Composable get() = if (isRussian()) "задач" else "tasks"
    
    // Функции для правильного склонения в русском языке
    @Composable
    fun pluralNotes(count: Int): String {
        return if (isRussian()) {
            val mod10 = count % 10
            val mod100 = count % 100
            when {
                mod100 in 11..19 -> "заметок"
                mod10 == 1 -> "заметка"
                mod10 in 2..4 -> "заметки"
                else -> "заметок"
            }
        } else {
            if (count == 1) "note" else "notes"
        }
    }
    
    @Composable
    fun pluralTasks(count: Int): String {
        return if (isRussian()) {
            val mod10 = count % 10
            val mod100 = count % 100
            when {
                mod100 in 11..19 -> "задач"
                mod10 == 1 -> "задача"
                mod10 in 2..4 -> "задачи"
                else -> "задач"
            }
        } else {
            if (count == 1) "task" else "tasks"
        }
    }
    
    @Composable
    fun pluralFolders(count: Int): String {
        return if (isRussian()) {
            val mod10 = count % 10
            val mod100 = count % 100
            when {
                mod100 in 11..19 -> "папок"
                mod10 == 1 -> "папка"
                mod10 in 2..4 -> "папки"
                else -> "папок"
            }
        } else {
            if (count == 1) "folder" else "folders"
        }
    }
    
    @Composable
    fun pluralEvents(count: Int): String {
        return if (isRussian()) {
            val mod10 = count % 10
            val mod100 = count % 100
            when {
                mod100 in 11..19 -> "событий"
                mod10 == 1 -> "событие"
                mod10 in 2..4 -> "события"
                else -> "событий"
            }
        } else {
            if (count == 1) "event" else "events"
        }
    }
    
    val reminder: String @Composable get() = if (isRussian()) "Напоминание" else "Reminder"
    val setReminder: String @Composable get() = if (isRussian()) "Установить напоминание" else "Set reminder"
    val selectTime: String @Composable get() = if (isRussian()) "Выберите время" else "Select time"
    val assignTo: String @Composable get() = if (isRussian()) "Назначить" else "Assign to"
    val assignToHint: String @Composable get() = if (isRussian()) "Email получателя (опционально)" else "Recipient email (optional)"
    
    val searchContacts: String @Composable get() = if (isRussian()) "Поиск контактов..." else "Search contacts..."
    val enterNameToSearch: String @Composable get() = if (isRussian()) "Введите имя для поиска" else "Enter name to search"
    val firstName: String @Composable get() = if (isRussian()) "Имя" else "First name"
    val lastName: String @Composable get() = if (isRussian()) "Фамилия" else "Last name"
    val emailAddress: String @Composable get() = if (isRussian()) "Email" else "Email"
    val phone: String @Composable get() = if (isRussian()) "Телефон" else "Phone"
    val mobilePhone: String @Composable get() = if (isRussian()) "Мобильный" else "Mobile"
    val workPhone: String @Composable get() = if (isRussian()) "Рабочий" else "Work phone"
    val company: String @Composable get() = if (isRussian()) "Компания" else "Company"
    val department: String @Composable get() = if (isRussian()) "Отдел" else "Department"
    val jobTitle: String @Composable get() = if (isRussian()) "Должность" else "Job title"
    val contactNotes: String @Composable get() = if (isRussian()) "Заметки" else "Notes"
    val writeEmail: String @Composable get() = if (isRussian()) "Написать письмо" else "Write email"
    val copyEmail: String @Composable get() = if (isRussian()) "Копировать email" else "Copy email"
    val callPhone: String @Composable get() = if (isRussian()) "Позвонить" else "Call"
    val addToContacts: String @Composable get() = if (isRussian()) "Добавить в контакты" else "Add to contacts"
    val exportContacts: String @Composable get() = if (isRussian()) "Экспорт контактов" else "Export contacts"
    val importContacts: String @Composable get() = if (isRussian()) "Импорт контактов" else "Import contacts"
    val exportToVCard: String @Composable get() = if (isRussian()) "Экспорт в vCard (.vcf)" else "Export to vCard (.vcf)"
    val exportToCSV: String @Composable get() = if (isRussian()) "Экспорт в CSV" else "Export to CSV"
    val importFromVCard: String @Composable get() = if (isRussian()) "Импорт из vCard (.vcf)" else "Import from vCard (.vcf)"
    val importFromCSV: String @Composable get() = if (isRussian()) "Импорт из CSV" else "Import from CSV"
    val contactsExported: String @Composable get() = if (isRussian()) "Контакты экспортированы" else "Contacts exported"
    @Composable
    fun contactsImported(count: Int): String = if (isRussian()) "Импортировано контактов: $count" else "Imported contacts: $count"
    val noContactsToExport: String @Composable get() = if (isRussian()) "Нет контактов для экспорта" else "No contacts to export"
    val selectContactsToExport: String @Composable get() = if (isRussian()) "Выберите контакты для экспорта" else "Select contacts to export"
    val exportAll: String @Composable get() = if (isRussian()) "Экспортировать все" else "Export all"
    val exportSelected: String @Composable get() = if (isRussian()) "Экспортировать выбранные" else "Export selected"
    
    // Группы контактов
    val contactGroups: String @Composable get() = if (isRussian()) "Группы" else "Groups"
    val createGroup: String @Composable get() = if (isRussian()) "Создать группу" else "Create group"
    val groupName: String @Composable get() = if (isRussian()) "Название группы" else "Group name"
    val renameGroup: String @Composable get() = if (isRussian()) "Переименовать группу" else "Rename group"
    val deleteGroup: String @Composable get() = if (isRussian()) "Удалить группу?" else "Delete group?"
    val deleteGroupConfirm: String @Composable get() = if (isRussian()) "Группа будет удалена. Контакты останутся без группы." else "Group will be deleted. Contacts will remain without group."
    val groupDeleted: String @Composable get() = if (isRussian()) "Группа удалена" else "Group deleted"
    val groupCreated: String @Composable get() = if (isRussian()) "Группа создана" else "Group created"
    val groupRenamed: String @Composable get() = if (isRussian()) "Группа переименована" else "Group renamed"
    val noGroups: String @Composable get() = if (isRussian()) "Нет групп" else "No groups"
    val withoutGroup: String @Composable get() = if (isRussian()) "Без группы" else "Without group"
    val moveToGroup: String @Composable get() = if (isRussian()) "Переместить в группу" else "Move to group"
    val removeFromGroup: String @Composable get() = if (isRussian()) "Убрать из группы" else "Remove from group"
    val selectGroup: String @Composable get() = if (isRussian()) "Выберите группу" else "Select group"
    val groupColor: String @Composable get() = if (isRussian()) "Цвет группы" else "Group color"
    @Composable
    fun contactsCount(count: Int): String = if (isRussian()) {
        when {
            count % 10 == 1 && count % 100 != 11 -> "$count контакт"
            count % 10 in 2..4 && count % 100 !in 12..14 -> "$count контакта"
            else -> "$count контактов"
        }
    } else {
        if (count == 1) "$count contact" else "$count contacts"
    }
    
    // Синхронизация контактов
    val contactsSync: String @Composable get() = if (isRussian()) "Синхронизация контактов" else "Contacts sync"
    val contactsSyncDesc: String @Composable get() = if (isRussian()) "Загрузка контактов с сервера Exchange" else "Download contacts from Exchange server"
    val contactsSyncNever: String @Composable get() = if (isRussian()) "Никогда" else "Never"
    val contactsSyncDaily: String @Composable get() = if (isRussian()) "Ежедневно" else "Daily"
    val contactsSyncWeekly: String @Composable get() = if (isRussian()) "Еженедельно" else "Weekly"
    val contactsSyncBiweekly: String @Composable get() = if (isRussian()) "Раз в 2 недели" else "Every 2 weeks"
    val contactsSyncMonthly: String @Composable get() = if (isRussian()) "Ежемесячно" else "Monthly"
    val syncNow: String @Composable get() = if (isRussian()) "Синхронизировать сейчас" else "Sync now"
    val syncing: String @Composable get() = if (isRussian()) "Синхронизация..." else "Syncing..."
    val syncComplete: String @Composable get() = if (isRussian()) "Синхронизация завершена" else "Sync complete"
    
    // Создание/редактирование события календаря
    val newEvent: String @Composable get() = if (isRussian()) "Новое событие" else "New event"
    val editEvent: String @Composable get() = if (isRussian()) "Редактировать событие" else "Edit event"
    val eventTitle: String @Composable get() = if (isRussian()) "Название" else "Title"
    val eventLocation: String @Composable get() = if (isRussian()) "Место" else "Location"
    val eventDescription: String @Composable get() = if (isRussian()) "Описание" else "Description"
    val endDate: String @Composable get() = if (isRussian()) "Дата окончания" else "End date"
    val startTime: String @Composable get() = if (isRussian()) "Время начала" else "Start time"
    val endTime: String @Composable get() = if (isRussian()) "Время окончания" else "End time"
    
    // Плейсхолдеры для ввода даты/времени
    val datePlaceholder: String @Composable get() = if (isRussian()) "дд.мм.гггг" else "dd.mm.yyyy"
    val timePlaceholder: String @Composable get() = if (isRussian()) "чч:мм" else "hh:mm"
    val noReminder: String @Composable get() = if (isRussian()) "Без напоминания" else "No reminder"
    val minutes5: String @Composable get() = if (isRussian()) "5 минут" else "5 minutes"
    val minutes15: String @Composable get() = if (isRussian()) "15 минут" else "15 minutes"
    val minutes30: String @Composable get() = if (isRussian()) "30 минут" else "30 minutes"
    val hour1: String @Composable get() = if (isRussian()) "1 час" else "1 hour"
    val hours2: String @Composable get() = if (isRussian()) "2 часа" else "2 hours"
    val day1: String @Composable get() = if (isRussian()) "1 день" else "1 day"
    val invalidDateTime: String @Composable get() = if (isRussian()) "Неверный формат даты/времени" else "Invalid date/time format"
    val endBeforeStart: String @Composable get() = if (isRussian()) "Время окончания должно быть позже времени начала" else "End time must be after start time"
    val eventCreated: String @Composable get() = if (isRussian()) "Событие создано" else "Event created"
    val eventUpdated: String @Composable get() = if (isRussian()) "Событие обновлено" else "Event updated"
    val eventDeleted: String @Composable get() = if (isRussian()) "Событие удалено" else "Event deleted"
    val eventsRestored: String @Composable get() = if (isRussian()) "События восстановлены" else "Events restored"
    val undo: String @Composable get() = if (isRussian()) "Отменить" else "Undo"
    val deleteEvent: String @Composable get() = if (isRussian()) "Удалить событие?" else "Delete event?"
    val deleteEventConfirm: String @Composable get() = if (isRussian()) "Событие будет удалено с сервера" else "Event will be deleted from server"
    val deleteEvents: String @Composable get() = if (isRussian()) "Удалить события?" else "Delete events?"
    @Composable
    fun deleteEventsConfirm(count: Int): String = if (isRussian()) 
        "Выбранные события ($count) будут удалены с сервера." 
        else "Selected events ($count) will be deleted from server."
    val addToCalendar: String @Composable get() = if (isRussian()) "Добавить в календарь" else "Add to calendar"
    val addToTasks: String @Composable get() = if (isRussian()) "Добавить в задачи" else "Add to tasks"
    val taskAddedToCalendar: String @Composable get() = if (isRussian()) "Задача добавлена в календарь" else "Task added to calendar"
    val taskAddedToTasks: String @Composable get() = if (isRussian()) "Задача добавлена" else "Task added"
    val acceptInvitation: String @Composable get() = if (isRussian()) "Принять" else "Accept"
    val declineInvitation: String @Composable get() = if (isRussian()) "Отклонить" else "Decline"
    val tentativeInvitation: String @Composable get() = if (isRussian()) "Под вопросом" else "Tentative"
    val invitationAccepted: String @Composable get() = if (isRussian()) "Приглашение принято, событие добавлено в календарь" else "Invitation accepted, event added to calendar"
    val inviteAttendees: String @Composable get() = if (isRussian()) "Пригласить" else "Invite"
    val attendeesHint: String @Composable get() = if (isRussian()) "Email участников через запятую" else "Attendee emails, comma separated"
    val invitationSent: String @Composable get() = if (isRussian()) "Приглашения отправлены" else "Invitations sent"
    val titleRequired: String @Composable get() = if (isRussian()) "Введите название" else "Enter title"
    val busyStatus: String @Composable get() = if (isRussian()) "Статус" else "Status"
    val statusFree: String @Composable get() = if (isRussian()) "Свободен" else "Free"
    val statusTentative: String @Composable get() = if (isRussian()) "под вопросом" else "tentative"
    val statusBusy: String @Composable get() = if (isRussian()) "Занят" else "Busy"
    val statusOof: String @Composable get() = if (isRussian()) "Нет на месте" else "Out of office"
    val statusAccepted: String @Composable get() = if (isRussian()) "принял" else "accepted"
    val statusDeclined: String @Composable get() = if (isRussian()) "отклонил" else "declined"
    val statusNotResponded: String @Composable get() = if (isRussian()) "не ответил" else "not responded"
    
    // Создание/редактирование заметок
    val newNote: String @Composable get() = if (isRussian()) "Новая заметка" else "New note"
    val editNote: String @Composable get() = if (isRussian()) "Редактировать заметку" else "Edit note"
    val noteTitle: String @Composable get() = if (isRussian()) "Заголовок" else "Title"
    val noteBody: String @Composable get() = if (isRussian()) "Текст заметки" else "Note text"
    val noteCreated: String @Composable get() = if (isRussian()) "Заметка создана" else "Note created"
    val noteUpdated: String @Composable get() = if (isRussian()) "Заметка обновлена" else "Note updated"
    val noteDeleted: String @Composable get() = if (isRussian()) "Заметка удалена" else "Note deleted"
    val notesDeleted: String @Composable get() = if (isRussian()) "Заметки удалены" else "Notes deleted"
    val noteRestored: String @Composable get() = if (isRussian()) "Заметка восстановлена" else "Note restored"
    val notesRestored: String @Composable get() = if (isRussian()) "Заметки восстановлены" else "Notes restored"
    val deleted: String @Composable get() = if (isRussian()) "Удалённые" else "Deleted"
    val deleteNote: String @Composable get() = if (isRussian()) "Удалить заметку?" else "Delete note?"
    val deleteNoteConfirm: String @Composable get() = if (isRussian()) "Заметка будет удалена" else "Note will be deleted"
    @Composable
    fun deleteNotesConfirm(count: Int): String = if (isRussian()) {
        when {
            count % 10 == 1 && count % 100 != 11 -> "$count заметка будет удалена"
            count % 10 in 2..4 && count % 100 !in 12..14 -> "$count заметки будут удалены"
            else -> "$count заметок будут удалены"
        }
    } else {
        if (count == 1) "Note will be deleted" else "$count notes will be deleted"
    }
    val deleteNotePermanently: String @Composable get() = if (isRussian()) "Удалить заметку окончательно?" else "Delete note permanently?"
    val deleteNotePermanentlyConfirm: String @Composable get() = if (isRussian())
        "Заметка будет удалена из корзины безвозвратно" else "The note will be permanently removed from trash"
    val emptyNotesTrashConfirm: String @Composable get() = if (isRussian())
        "Все удалённые заметки будут удалены безвозвратно. Продолжить?"
        else "All deleted notes will be permanently removed. Continue?"
    val notesTrashEmptied: String @Composable get() = if (isRussian()) "Корзина заметок очищена" else "Notes trash emptied"
    val syncError: String @Composable get() = if (isRussian()) "Ошибка синхронизации" else "Sync error"
    
    // Вложения
    @Composable
    fun attachmentsWithCount(count: Int): String = if (isRussian()) "Вложения ($count)" else "Attachments ($count)"
    val downloaded: String @Composable get() = if (isRussian()) "Скачано" else "Downloaded"
    val download: String @Composable get() = if (isRussian()) "Скачать" else "Download"
    val noAppToOpenFile: String @Composable get() = if (isRussian()) "Нет приложения для открытия файла" else "No app to open file"
    val couldNotOpenLink: String @Composable get() = if (isRussian()) "Не удалось открыть ссылку" else "Could not open link"
    
    // Размер файла
    @Composable
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> if (isRussian()) "$bytes Б" else "$bytes B"
            bytes < 1024 * 1024 -> if (isRussian()) "${bytes / 1024} КБ" else "${bytes / 1024} KB"
            else -> if (isRussian()) "${bytes / (1024 * 1024)} МБ" else "${bytes / (1024 * 1024)} MB"
        }
    }
    
    // Календарь - названия месяцев
    val monthNames: List<String> @Composable get() = if (isRussian()) 
        listOf("Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь")
        else listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    
    val monthNamesShort: List<String> @Composable get() = if (isRussian())
        listOf("янв", "фев", "мар", "апр", "май", "июнь", "июль", "авг", "сен", "окт", "ноя", "дек")
        else listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    
    // Дни недели короткие
    val dayNamesShort: List<String> @Composable get() = if (isRussian())
        listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
        else listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    
    val dayNamesMin: List<String> @Composable get() = if (isRussian())
        listOf("П", "В", "С", "Ч", "П", "С", "В")
        else listOf("M", "T", "W", "T", "F", "S", "S")
    
    // Навигация по месяцам/годам
    val previousMonth: String @Composable get() = if (isRussian()) "Предыдущий месяц" else "Previous month"
    val nextMonth: String @Composable get() = if (isRussian()) "Следующий месяц" else "Next month"
    val previousYear: String @Composable get() = if (isRussian()) "Предыдущий год" else "Previous year"
    val nextYear: String @Composable get() = if (isRussian()) "Следующий год" else "Next year"
    
    // Статусы событий
    val completed: String @Composable get() = if (isRussian()) "Завершено" else "Completed"
    val recurringEvent: String @Composable get() = if (isRussian()) "Повторяющееся событие" else "Recurring event"
    val exchangeContacts: String @Composable get() = if (isRussian()) "Контакты Exchange" else "Exchange contacts"
    val exchangeContactsDesc: String @Composable get() = if (isRussian()) "Контакты с сервера (только чтение)" else "Server contacts (read-only)"
    @Composable
    fun contactsSyncedCount(count: Int): String = if (isRussian()) {
        when {
            count % 10 == 1 && count % 100 != 11 -> "Синхронизирован $count контакт"
            count % 10 in 2..4 && count % 100 !in 12..14 -> "Синхронизировано $count контакта"
            else -> "Синхронизировано $count контактов"
        }
    } else {
        if (count == 1) "Synced $count contact" else "Synced $count contacts"
    }
    @Composable
    fun days(n: Int): String = if (LocalLanguage.current == AppLanguage.RUSSIAN) {
        when {
            n == 1 -> "1 день"
            n in 2..4 -> "$n дня"
            else -> "$n дней"
        }
    } else {
        if (n == 1) "1 day" else "$n days"
    }
    
    // Сертификаты
    val fileLabel: String @Composable get() = if (isRussian()) "Файл:" else "File:"
    val sizeLabel: String @Composable get() = if (isRussian()) "Размер:" else "Size:"
    val exportAction: String @Composable get() = if (isRussian()) "Экспортировать" else "Export"
    val replaceAction: String @Composable get() = if (isRussian()) "Заменить" else "Replace"
    val removeAction: String @Composable get() = if (isRussian()) "Удалить" else "Remove"
    val removeCertificateTitle: String @Composable get() = if (isRussian()) "Удалить сертификат?" else "Remove certificate?"
    val removeCertificateWarning: String @Composable get() = if (isRussian()) 
        "Без сертификата подключение к серверу может не работать. Вы уверены?" 
        else "Connection to server may fail without certificate. Are you sure?"
    
    // Подписи
    val signaturesTitle: String @Composable get() = if (isRussian()) "Подписи" else "Signatures"
    val noSignaturesHint: String @Composable get() = if (isRussian()) "Нет подписей. Добавьте первую!" else "No signatures. Add your first!"
    val defaultLabel: String @Composable get() = if (isRussian()) "(по умолч.)" else "(default)"
    val addSignatureAction: String @Composable get() = if (isRussian()) "Добавить подпись" else "Add signature"
    val deleteSignatureTitle: String @Composable get() = if (isRussian()) "Удалить подпись?" else "Delete signature?"
    val editTitle: String @Composable get() = if (isRussian()) "Редактировать" else "Edit"
    val newSignatureTitle: String @Composable get() = if (isRussian()) "Новая подпись" else "New signature"
    val nameLabel: String @Composable get() = if (isRussian()) "Название" else "Name"
    val namePlaceholder: String @Composable get() = if (isRussian()) "Рабочая, Личная..." else "Work, Personal..."
    val signatureTextLabel: String @Composable get() = if (isRussian()) "Текст подписи" else "Signature text"
    val defaultCheckbox: String @Composable get() = if (isRussian()) "По умолчанию" else "Default"
    
    // Счётчик подписей
    @Composable
    fun signaturesCount(count: Int): String = if (isRussian()) {
        when {
            count == 1 -> "1 подпись"
            count in 2..4 -> "$count подписи"
            else -> "$count подписей"
        }
    } else {
        if (count == 1) "1 signature" else "$count signatures"
    }
    
    // Интервалы синхронизации
    val never: String @Composable get() = if (isRussian()) "Никогда" else "Never"
    val daily: String @Composable get() = if (isRussian()) "Ежедневно" else "Daily"
    val weekly: String @Composable get() = if (isRussian()) "Еженедельно" else "Weekly"
    val everyTwoWeeks: String @Composable get() = if (isRussian()) "Раз в 2 недели" else "Every 2 weeks"
    val monthly: String @Composable get() = if (isRussian()) "Ежемесячно" else "Monthly"
    @Composable
    fun everyNDays(n: Int): String = if (isRussian()) "Каждые $n дней" else "Every $n days"
    
    // Выбор подписи
    val selectSignature: String @Composable get() = if (isRussian()) "Выбрать подпись" else "Select signature"
    
    // Синхронизация
    val notesSync: String @Composable get() = if (isRussian()) "Синхронизация заметок" else "Notes sync"
    val calendarSync: String @Composable get() = if (isRussian()) "Синхронизация календаря" else "Calendar sync"
    val tasksSync: String @Composable get() = if (isRussian()) "Синхронизация задач" else "Tasks sync"
    
    // Синхронизация и очистка
    val syncAndCleanup: String @Composable get() = if (isRussian()) "Синхронизация и очистка" else "Sync & Cleanup"
    val syncAndCleanupDesc: String @Composable get() = if (isRussian()) "Настройки синхронизации и автоочистки" else "Sync and auto-cleanup settings"
    val cleanupSection: String @Composable get() = if (isRussian()) "Очистка" else "Cleanup"
    val cleanupInfo: String @Composable get() = if (isRussian()) "Автоматическая очистка" else "Auto cleanup"
    val cleanupInfoDesc: String @Composable get() = if (isRussian()) "Настройки очистки находятся в настройках каждого аккаунта" else "Cleanup settings are in each account's settings"
    
    // Диалоги разрешений
    val backgroundWorkTitle: String @Composable get() = if (isRussian()) "Фоновая работа" else "Background work"
    val backgroundWorkText: String @Composable get() = if (isRussian()) 
        "Для получения уведомлений о новых письмах приложению нужно работать в фоне.\n\nНажмите «Разрешить» в следующем окне."
        else "To receive notifications about new emails, the app needs to work in the background.\n\nTap «Allow» in the next screen."
    val exactAlarmsTitle: String @Composable get() = if (isRussian()) "Точные уведомления" else "Exact notifications"
    val exactAlarmsText: String @Composable get() = if (isRussian()) 
        "Для своевременной синхронизации почты приложению нужно разрешение на точные будильники.\n\nВключите переключатель в следующем окне."
        else "For timely mail sync, the app needs permission for exact alarms.\n\nEnable the toggle in the next screen."
    val later: String @Composable get() = if (isRussian()) "Позже" else "Later"
    val continueAction: String @Composable get() = if (isRussian()) "Продолжить" else "Continue"
    
    // Обновления
    val checkForUpdates: String @Composable get() = if (isRussian()) "Проверить обновления" else "Check for updates"
    val checkingForUpdates: String @Composable get() = if (isRussian()) "Проверка обновлений..." else "Checking for updates..."
    val updateAvailable: String @Composable get() = if (isRussian()) "Доступно обновление" else "Update available"
    val noUpdatesAvailable: String @Composable get() = if (isRussian()) "У вас последняя версия" else "You have the latest version"
    val updateError: String @Composable get() = if (isRussian()) "Ошибка проверки обновлений" else "Update check error"
    val downloadUpdate: String @Composable get() = if (isRussian()) "Скачать" else "Download"
    val downloading: String @Composable get() = if (isRussian()) "Скачивание..." else "Downloading..."
    val downloadComplete: String @Composable get() = if (isRussian()) "Скачивание завершено" else "Download complete"
    val install: String @Composable get() = if (isRussian()) "Установить" else "Install"
    val downloadError: String @Composable get() = if (isRussian()) "Ошибка скачивания" else "Download error"
    val newVersion: String @Composable get() = if (isRussian()) "Новая версия" else "New version"
    val currentVersion: String @Composable get() = if (isRussian()) "Текущая версия" else "Current version"
    val whatsNew: String @Composable get() = if (isRussian()) "Что нового" else "What's new"
    val autoUpdateCheck: String @Composable get() = if (isRussian()) "Автопроверка обновлений" else "Auto update check"
    @Composable
    fun downloadProgress(mb: Float, totalMb: Float): String = if (isRussian()) 
        "%.1f / %.1f МБ".format(mb, totalMb) 
        else "%.1f / %.1f MB".format(mb, totalMb)
    
    // Откат версии
    val rollbackToPrevious: String @Composable get() = if (isRussian()) "Предыдущая версия" else "Previous version"
    val rollbackTitle: String @Composable get() = if (isRussian()) "Возврат к версии" else "Return to version"
    val rollbackWarning: String @Composable get() = if (isRussian()) "В этой версии НЕТ:" else "This version does NOT have:"
    val rollbackDataLoss: String @Composable get() = if (isRussian()) "Будет потеряно:" else "Will be lost:"
    val rollbackDataSync: String @Composable get() = if (isRussian()) "Данные синхронизируются с сервера заново." else "Data will be synced from server again."
    val rollbackConfirm: String @Composable get() = if (isRussian()) "Установить" else "Install"
    val rollbackNotAvailable: String @Composable get() = if (isRussian()) "Предыдущая версия недоступна" else "Previous version not available"
    val rollbackChecking: String @Composable get() = if (isRussian()) "Проверка..." else "Checking..."
}

/**
 * Локализация onboarding с явным флагом языка (для выбора языка до применения настроек)
 */
object OnboardingStrings {
    fun skip(isRussian: Boolean): String = if (isRussian) "Пропустить" else "Skip"
    fun start(isRussian: Boolean): String = if (isRussian) "Начать" else "Start"
    fun next(isRussian: Boolean): String = if (isRussian) "Далее" else "Next"
    fun chooseLanguageTitle(isRussian: Boolean): String = if (isRussian) "Выберите язык" else "Choose language"
    fun animationsTitle(isRussian: Boolean): String = if (isRussian) "Анимации интерфейса" else "Interface animations"
    fun animationsDescription(isRussian: Boolean): String = if (isRussian)
        "Включите анимации для плавного интерфейса или отключите для экономии заряда"
    else
        "Enable animations for a smooth interface or disable to save battery"
    fun animationsLabel(isRussian: Boolean): String = if (isRussian) "Анимации" else "Animations"
    fun themeTitle(isRussian: Boolean): String = if (isRussian) "Цветовая тема" else "Color theme"
    fun themeDescription(isRussian: Boolean): String = if (isRussian)
        "Выберите цвет оформления приложения"
    else
        "Choose the app color scheme"
    
    fun pageMailTitle(isRussian: Boolean): String = if (isRussian) "Почта и уведомления" else "Mail & Notifications"
    fun pageOrganizerTitle(isRussian: Boolean): String = if (isRussian) "Органайзер" else "Organizer"
    fun pageSettingsTitle(isRussian: Boolean): String = if (isRussian) "Настройки" else "Settings"
    
    fun mailTitle(isRussian: Boolean): String = if (isRussian) "Почта" else "Mail"
    fun mailDescription(isRussian: Boolean): String = if (isRussian)
        "Exchange 2007 SP1 (протестировано)"
    else
        "Exchange 2007 SP1 (tested)"
    fun notificationsTitle(isRussian: Boolean): String = if (isRussian) "Уведомления" else "Notifications"
    fun notificationsDescription(isRussian: Boolean): String = if (isRussian)
        "Push-уведомления о новых письмах"
    else
        "Push notifications for new emails"
    fun exchangeTitle(isRussian: Boolean): String = if (isRussian) "Exchange" else "Exchange"
    fun exchangeDescription(isRussian: Boolean): String = if (isRussian)
        "Для стабильной работы на старых версиях требуется EWS"
    else
        "EWS required for stable work on older versions"
    
    fun contactsTitle(isRussian: Boolean): String = if (isRussian) "Контакты" else "Contacts"
    fun contactsDescription(isRussian: Boolean): String = if (isRussian)
        "Личные и корпоративные (GAL)"
    else
        "Personal and corporate (GAL)"
    fun calendarTitle(isRussian: Boolean): String = if (isRussian) "Календарь" else "Calendar"
    fun calendarDescription(isRussian: Boolean): String = if (isRussian)
        "События, напоминания, приглашения"
    else
        "Events, reminders, invitations"
    fun tasksTitle(isRussian: Boolean): String = if (isRussian) "Задачи" else "Tasks"
    fun tasksDescription(isRussian: Boolean): String = if (isRussian)
        "Приоритеты, сроки, напоминания"
    else
        "Priorities, due dates, reminders"
    fun notesTitle(isRussian: Boolean): String = if (isRussian) "Заметки" else "Notes"
    fun notesDescription(isRussian: Boolean): String = if (isRussian)
        "Синхронизация с сервером"
    else
        "Server synchronization"
    
    fun personalizationTitle(isRussian: Boolean): String = if (isRussian) "Персонализация" else "Personalization"
    fun personalizationDescription(isRussian: Boolean): String = if (isRussian)
        "7 тем, мультиаккаунт, индивидуальные подписи"
    else
        "7 themes, multi-account, individual signatures"
    fun updatesTitle(isRussian: Boolean): String = if (isRussian) "Обновления" else "Updates"
    fun updatesDescription(isRussian: Boolean): String = if (isRussian)
        "OTA-обновления с возможностью отката к предыдущей версии"
    else
        "OTA updates with rollback to previous version"
}

/**
 * Утилиты для локализации вне Composable контекста (для уведомлений и сервисов)
 */
object NotificationStrings {
    // Название канала уведомлений
    fun getNewMailChannelName(isRussian: Boolean): String {
        return if (isRussian) "Новые письма" else "New emails"
    }
    
    // Заголовок уведомления - для одного письма показываем отправителя
    fun getNewMailTitle(count: Int, senderName: String?, isRussian: Boolean): String {
        return if (count == 1 && !senderName.isNullOrBlank()) {
            senderName
        } else {
            if (isRussian) "Новая почта" else "New mail"
        }
    }
    
    // Текст уведомления - для одного письма показываем тему
    fun getNewMailText(count: Int, subject: String?, isRussian: Boolean): String {
        return if (count == 1 && !subject.isNullOrBlank()) {
            subject
        } else if (count == 1) {
            if (isRussian) "Новое письмо" else "New email"
        } else {
            if (isRussian) {
                when {
                    count in 2..4 -> "$count новых письма"
                    else -> "$count новых писем"
                }
            } else {
                "$count new emails"
            }
        }
    }
    
    // Развёрнутый текст для нескольких писем (BigTextStyle)
    fun getNewMailBigText(senders: List<String>, isRussian: Boolean): String {
        val uniqueSenders = senders.distinct().take(3)
        val sendersText = uniqueSenders.joinToString(", ")
        val more = if (senders.size > 3) {
            if (isRussian) " и ещё ${senders.size - 3}" else " and ${senders.size - 3} more"
        } else ""
        return if (isRussian) "От: $sendersText$more" else "From: $sendersText$more"
    }
    
    // Subtext для группы уведомлений
    fun getNewMailSubtext(accountEmail: String): String {
        return accountEmail
    }
    
    // Уведомление о доступном обновлении
    fun getUpdateAvailableTitle(isRussian: Boolean): String {
        return if (isRussian) "Доступно обновление" else "Update available"
    }
    
    fun getUpdateAvailableText(versionName: String, isRussian: Boolean): String {
        return if (isRussian) "Версия $versionName готова к установке" else "Version $versionName is ready to install"
    }
    
    fun getPushServiceTitle(isRussian: Boolean): String {
        return if (isRussian) "Почта" else "Mail"
    }
    
    fun getPushServiceText(isRussian: Boolean): String {
        return if (isRussian) "Ожидание новых писем..." else "Waiting for new emails..."
    }
    
    // Сообщения об удалении для Toast
    fun getMovedToTrash(isRussian: Boolean): String {
        return if (isRussian) "Перемещено в корзину" else "Moved to trash"
    }
    
    fun getDeletedPermanently(isRussian: Boolean): String {
        return if (isRussian) "Удалено окончательно" else "Deleted permanently"
    }
    
    fun getAlreadyInFolder(isRussian: Boolean): String {
        return if (isRussian) "Письма уже в этой папке" else "Emails already in this folder"
    }
    
    fun getEmailNotFound(isRussian: Boolean): String {
        return if (isRussian) "Письмо не найдено" else "Email not found"
    }
    
    fun getAccountNotFound(isRussian: Boolean): String {
        return if (isRussian) "Аккаунт не найден" else "Account not found"
    }
    
    fun getTrashFolderNotFound(isRussian: Boolean): String {
        return if (isRussian) "Папка 'Удалённые' не найдена" else "Trash folder not found"
    }
    
    fun getSpamFolderNotFound(isRussian: Boolean): String {
        return if (isRussian) "Папка 'Спам' не найдена" else "Spam folder not found"
    }
    
    // Локализация ошибок из репозитория и EasClient
    fun localizeError(errorCode: String, isRussian: Boolean): String {
        return when {
            errorCode == "ALREADY_IN_FOLDER" -> getAlreadyInFolder(isRussian)
            errorCode == "Email not found" -> getEmailNotFound(isRussian)
            errorCode == "Account not found" -> getAccountNotFound(isRussian)
            errorCode == "Trash folder not found" -> getTrashFolderNotFound(isRussian)
            errorCode == "Spam folder not found" -> getSpamFolderNotFound(isRussian)
            errorCode == "NO_INTERNET" -> getNoInternetConnection(isRussian)
            errorCode == "ACCOUNT_EXISTS" -> if (isRussian) "Аккаунт с таким email уже добавлен" else "Account with this email already exists"
            errorCode == "SYNC_NOT_READY" -> if (isRussian) "Дождитесь завершения синхронизации папки" else "Wait for folder sync to complete"
            errorCode == "DELETE_FAILED" -> if (isRussian) "Не удалось удалить. Попробуйте синхронизировать и повторить." else "Deletion failed. Try syncing and retry."
            errorCode.startsWith("Delete error:") -> if (isRussian) "Ошибка удаления: ${errorCode.removePrefix("Delete error: ")}" else errorCode
            // Ошибки удаления
            errorCode.contains("Неверный SyncKey") -> if (isRussian) "Неверный SyncKey. Попробуйте синхронизировать папку." else "Invalid SyncKey. Try syncing the folder."
            errorCode.contains("Письмо не найдено на сервере") -> if (isRussian) "Письмо не найдено на сервере" else "Email not found on server"
            errorCode.contains("Ошибка протокола") -> if (isRussian) "Ошибка протокола" else "Protocol error"
            errorCode.contains("Ошибка сервера") -> if (isRussian) "Ошибка сервера" else "Server error"
            errorCode.contains("Конфликт") -> if (isRussian) "Конфликт при удалении" else "Conflict during deletion"
            errorCode.contains("Требуется синхронизация папок") -> if (isRussian) "Требуется синхронизация папок" else "Folder sync required"
            errorCode.contains("Не удалось удалить письмо") -> if (isRussian) errorCode else errorCode.replace("Не удалось удалить письмо:", "Failed to delete email:")
            // EasClient ошибки
            errorCode.contains("PolicyKey not found") || 
            errorCode.contains("Provision failed") || 
            errorCode.contains("Provision phase") -> {
                // Показываем локализованное сообщение + оригинальную техническую строку для диагностики
                // Защита от двойной локализации — если уже содержит локализованный текст, возвращаем как есть
                val base = if (isRussian) "Ошибка согласования политики безопасности" else "Security policy provisioning failed"
                if (errorCode.contains(base)) {
                    errorCode // Уже локализовано
                } else {
                    "$base\n\n$errorCode"
                }
            }
            errorCode.contains("HTTP 401") || errorCode.contains("(401)") -> if (isRussian) "Ошибка авторизации (401). Проверьте логин и пароль." else "Authorization error (401). Check username and password."
            errorCode.contains("HTTP 403") -> if (isRussian) "Доступ запрещён (403)" else "Access forbidden (403)"
            errorCode.contains("HTTP 404") -> if (isRussian) "Сервер не найден (404)" else "Server not found (404)"
            errorCode.contains("HTTP 500") -> if (isRussian) "Ошибка сервера (500)" else "Server error (500)"
            errorCode.contains("HTTP 502") -> if (isRussian) "Ошибка шлюза (502)" else "Bad gateway (502)"
            errorCode.contains("HTTP 503") -> if (isRussian) "Сервер недоступен (503)" else "Service unavailable (503)"
            errorCode.contains("timeout") || errorCode.contains("Timeout") -> if (isRussian) "Превышено время ожидания" else "Connection timeout"
            errorCode.contains("Unable to resolve host") -> if (isRussian) "Не удалось найти сервер. Проверьте адрес." else "Unable to resolve host. Check server address."
            errorCode.contains("Connection refused") -> if (isRussian) "Соединение отклонено сервером" else "Connection refused by server"
            errorCode.contains("ConnectException") || errorCode.contains("Failed to connect") -> if (isRussian) "Не удалось подключиться к серверу. Проверьте сеть." else "Failed to connect to server. Check network."
            errorCode.contains("SSL") || errorCode.contains("Certificate") -> if (isRussian) "Ошибка сертификата SSL. Попробуйте включить 'Принимать все сертификаты' или добавить сертификат сервера." else "SSL certificate error. Try enabling 'Accept all certificates' or add server certificate."
            errorCode.contains("No address associated") -> if (isRussian) "Неверный адрес сервера" else "Invalid server address"
            errorCode == "Unknown error" -> if (isRussian) "Неизвестная ошибка" else "Unknown error"
            errorCode.contains("Inbox folder not found") -> if (isRussian) "Папка «Входящие» не найдена" else "Inbox folder not found"
            else -> errorCode
        }
    }
    
    // Сообщения об отправке
    fun getEmailSent(isRussian: Boolean): String {
        return if (isRussian) "Письмо отправлено" else "Email sent"
    }
    
    fun getScheduledEmailSent(to: String, isRussian: Boolean): String {
        return if (isRussian) "Запланированное письмо для $to отправлено" else "Scheduled email to $to sent"
    }
    
    fun getAttachmentsTooLarge(sizeMB: Int, limitMB: Int, isRussian: Boolean): String {
        return if (isRussian) {
            "Размер вложений ($sizeMB МБ) превышает лимит сервера ($limitMB МБ)"
        } else {
            "Attachments size ($sizeMB MB) exceeds server limit ($limitMB MB)"
        }
    }
    
    fun getEmailTooLarge(sizeMB: Int, limitMB: Int, isRussian: Boolean): String {
        return if (isRussian) {
            "Размер письма ($sizeMB МБ) превышает лимит сервера ($limitMB МБ)"
        } else {
            "Email size ($sizeMB MB) exceeds server limit ($limitMB MB)"
        }
    }
    
    fun getServerRejectedEmail(isRussian: Boolean): String {
        return if (isRussian) {
            "Сервер отклонил письмо. Возможно, размер вложений превышает лимит сервера."
        } else {
            "Server rejected the email. Attachments may exceed server size limit."
        }
    }
    
    // Дополнительные строки для EmailListScreen
    fun getRestored(isRussian: Boolean): String {
        return if (isRussian) "Восстановлено" else "Restored"
    }
    
    fun getMovedToSpam(isRussian: Boolean): String {
        return if (isRussian) "Перемещено в спам" else "Moved to spam"
    }
    
    fun getMoved(isRussian: Boolean): String {
        return if (isRussian) "Перемещено" else "Moved"
    }
    
    fun getNoInternetConnection(isRussian: Boolean): String {
        return if (isRussian) "Нет подключения к интернету. Проверьте сетевое соединение." else "No internet connection. Check your network."
    }
    
    // Сертификаты
    fun getCertificateExported(isRussian: Boolean): String {
        return if (isRussian) "Сертификат экспортирован" else "Certificate exported"
    }
    
    fun getExportError(isRussian: Boolean): String {
        return if (isRussian) "Ошибка экспорта" else "Export error"
    }
    
    fun getInvalidFileFormat(isRussian: Boolean): String {
        return if (isRussian) "Неверный формат файла" else "Invalid file format"
    }
    
    fun getCertificateUpdated(isRussian: Boolean): String {
        return if (isRussian) "Сертификат обновлён" else "Certificate updated"
    }
    
    fun getCertificateLoadingError(isRussian: Boolean): String {
        return if (isRussian) "Ошибка загрузки сертификата" else "Certificate loading error"
    }
    
    fun getClientCertificateLoadingError(isRussian: Boolean): String {
        return if (isRussian) "Ошибка загрузки клиентского сертификата" else "Client certificate loading error"
    }
    
    fun getSendError(isRussian: Boolean): String {
        return if (isRussian) "Ошибка отправки" else "Send error"
    }
    
    fun getUnknownError(isRussian: Boolean): String {
        return if (isRussian) "Неизвестная ошибка" else "Unknown error"
    }
    
    fun getErrorWithMessage(isRussian: Boolean, message: String?): String {
        val prefix = if (isRussian) "Ошибка" else "Error"
        return "$prefix: ${message ?: getUnknownError(isRussian)}"
    }
    
    fun getDeleteCertificateTitle(isRussian: Boolean): String {
        return if (isRussian) "Удалить сертификат?" else "Remove certificate?"
    }
    
    fun getDeleteCertificateWarning(isRussian: Boolean): String {
        return if (isRussian) "Без сертификата подключение к серверу может не работать. Вы уверены?" else "Connection to server may fail without certificate. Are you sure?"
    }
    
    fun getCertificateRemoved(isRussian: Boolean): String {
        return if (isRussian) "Сертификат удалён" else "Certificate removed"
    }
    
    fun getExport(isRussian: Boolean): String {
        return if (isRussian) "Экспортировать" else "Export"
    }
    
    fun getReplace(isRussian: Boolean): String {
        return if (isRussian) "Заменить" else "Replace"
    }
    
    fun getRemove(isRussian: Boolean): String {
        return if (isRussian) "Удалить" else "Remove"
    }
    
    fun getFileLabel(isRussian: Boolean): String {
        return if (isRussian) "Файл:" else "File:"
    }
    
    fun getSizeLabel(isRussian: Boolean): String {
        return if (isRussian) "Размер:" else "Size:"
    }
    
    // Подписи
    fun getSignaturesTitle(isRussian: Boolean): String {
        return if (isRussian) "Подписи" else "Signatures"
    }
    
    fun getNoSignaturesHint(isRussian: Boolean): String {
        return if (isRussian) "Нет подписей. Добавьте первую!" else "No signatures. Add your first!"
    }
    
    fun getDefaultLabel(isRussian: Boolean): String {
        return if (isRussian) "(по умолч.)" else "(default)"
    }
    
    fun getAddSignature(isRussian: Boolean): String {
        return if (isRussian) "Добавить подпись" else "Add signature"
    }
    
    fun getDeleteSignatureTitle(isRussian: Boolean): String {
        return if (isRussian) "Удалить подпись?" else "Delete signature?"
    }
    
    fun getEditTitle(isRussian: Boolean): String {
        return if (isRussian) "Редактировать" else "Edit"
    }
    
    fun getNewSignatureTitle(isRussian: Boolean): String {
        return if (isRussian) "Новая подпись" else "New signature"
    }
    
    fun getNameLabel(isRussian: Boolean): String {
        return if (isRussian) "Название" else "Name"
    }
    
    fun getNamePlaceholder(isRussian: Boolean): String {
        return if (isRussian) "Рабочая, Личная..." else "Work, Personal..."
    }
    
    fun getSignatureTextLabel(isRussian: Boolean): String {
        return if (isRussian) "Текст подписи" else "Signature text"
    }
    
    fun getDefaultCheckbox(isRussian: Boolean): String {
        return if (isRussian) "По умолчанию" else "Default"
    }
    
    // Синхронизация
    fun getNotesSyncTitle(isRussian: Boolean): String {
        return if (isRussian) "Синхронизация заметок" else "Notes sync"
    }
    
    fun getCalendarSyncTitle(isRussian: Boolean): String {
        return if (isRussian) "Синхронизация календаря" else "Calendar sync"
    }
    
    fun getNever(isRussian: Boolean): String {
        return if (isRussian) "Никогда" else "Never"
    }
    
    fun getDaily(isRussian: Boolean): String {
        return if (isRussian) "Ежедневно" else "Daily"
    }
    
    fun getWeekly(isRussian: Boolean): String {
        return if (isRussian) "Еженедельно" else "Weekly"
    }
    
    fun getEveryTwoWeeks(isRussian: Boolean): String {
        return if (isRussian) "Раз в 2 недели" else "Every 2 weeks"
    }
    
    fun getEveryNDays(days: Int, isRussian: Boolean): String {
        return if (isRussian) "Каждые $days дней" else "Every $days days"
    }
    
    fun getMonthly(isRussian: Boolean): String {
        return if (isRussian) "Ежемесячно" else "Monthly"
    }
    
    // Контакты - организация
    fun getOrganizationAddressBook(isRussian: Boolean): String {
        return if (isRussian) "Адресная книга организации" else "Organization Address Book"
    }
    
    fun getContactsCount(count: Int, isRussian: Boolean): String {
        return if (isRussian) "Контактов: $count" else "Contacts: $count"
    }
    
    fun getGlobalAddressList(isRussian: Boolean): String {
        return if (isRussian) "Глобальная адресная книга (GAL)" else "Global Address List (GAL)"
    }
    
    fun getSyncAction(isRussian: Boolean): String {
        return if (isRussian) "Синхронизировать" else "Sync"
    }
    
    fun getLoadingContacts(isRussian: Boolean): String {
        return if (isRussian) "Загрузка контактов..." else "Loading contacts..."
    }
    
    fun getTapToLoadContacts(isRussian: Boolean): String {
        return if (isRussian) "Нажмите для загрузки контактов" else "Tap to load contacts"
    }
    
    fun getLoadAction(isRussian: Boolean): String {
        return if (isRussian) "Загрузить" else "Load"
    }
    
    fun getSynced(count: Int, isRussian: Boolean): String {
        return if (isRussian) "Синхронизировано: $count" else "Synced: $count"
    }
    
    fun getCopiedToPersonalContacts(isRussian: Boolean): String {
        return if (isRussian) "Скопировано в личные контакты" else "Copied to personal contacts"
    }
}

/**
 * CompositionLocal для текущего языка
 */
val LocalLanguage = compositionLocalOf { AppLanguage.RUSSIAN }

/**
 * Проверка текущего языка
 */
@Composable
fun isRussian(): Boolean = LocalLanguage.current == AppLanguage.RUSSIAN

/**
 * Локализация названий системных папок
 */
@Composable
fun getLocalizedFolderName(folderType: Int, originalName: String): String {
    val isRu = isRussian()
    return when (folderType) {
        2 -> if (isRu) "Входящие" else "Inbox"
        3 -> if (isRu) "Черновики" else "Drafts"
        4 -> if (isRu) "Удалённые" else "Deleted Items"
        5 -> if (isRu) "Отправленные" else "Sent Items"
        6 -> if (isRu) "Исходящие" else "Outbox"
        7 -> if (isRu) "Задачи" else "Tasks"
        8 -> if (isRu) "Календарь" else "Calendar"
        9 -> if (isRu) "Контакты" else "Contacts"
        10 -> if (isRu) "Заметки" else "Notes"
        11 -> if (isRu) "Спам" else "Junk Email"
        14 -> if (isRu) "Журнал" else "Journal"
        else -> originalName // Пользовательские папки без изменений
    }
}
