package com.exchange.mailclient.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

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
    val save: String @Composable get() = if (isRussian()) "Сохранить" else "Save"
    val delete: String @Composable get() = if (isRussian()) "Удалить" else "Delete"
    val edit: String @Composable get() = if (isRussian()) "Редактировать" else "Edit"
    val close: String @Composable get() = if (isRussian()) "Закрыть" else "Close"
    val yes: String @Composable get() = if (isRussian()) "Да" else "Yes"
    val no: String @Composable get() = if (isRussian()) "Нет" else "No"
    val back: String @Composable get() = if (isRussian()) "Назад" else "Back"
    val next: String @Composable get() = if (isRussian()) "Далее" else "Next"
    val done: String @Composable get() = if (isRussian()) "Готово" else "Done"
    val error: String @Composable get() = if (isRussian()) "Ошибка" else "Error"
    val success: String @Composable get() = if (isRussian()) "Успешно" else "Success"
    val empty: String @Composable get() = if (isRussian()) "Пусто" else "Empty"
    
    // Главный экран
    val hello: String @Composable get() = if (isRussian()) "Привет! 👋" else "Hello! 👋"
    val inbox: String @Composable get() = if (isRussian()) "Входящие" else "Inbox"
    val unread: String @Composable get() = if (isRussian()) "Непрочитано" else "Unread"
    val favorites: String @Composable get() = if (isRussian()) "Избранные" else "Favorites"
    val folders: String @Composable get() = if (isRussian()) "Папки" else "Folders"
    val refresh: String @Composable get() = if (isRussian()) "Обновить" else "Refresh"
    val compose: String @Composable get() = if (isRussian()) "Написать" else "Compose"
    val searchInMail: String @Composable get() = if (isRussian()) "Поиск в почте" else "Search mail"
    val syncingMail: String @Composable get() = if (isRussian()) "Синхронизация почты..." else "Syncing mail..."
    val emails: String @Composable get() = if (isRussian()) "писем" else "emails"
    val menu: String @Composable get() = if (isRussian()) "Меню" else "Menu"
    val noAccount: String @Composable get() = if (isRussian()) "Нет аккаунта" else "No account"
    
    // Папки
    val drafts: String @Composable get() = if (isRussian()) "Черновики" else "Drafts"
    val sent: String @Composable get() = if (isRussian()) "Отправленные" else "Sent"
    val trash: String @Composable get() = if (isRussian()) "Удалённые" else "Trash"
    val outbox: String @Composable get() = if (isRussian()) "Исходящие" else "Outbox"
    val spam: String @Composable get() = if (isRussian()) "Спам" else "Spam"
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
    
    // Автоочистка корзины
    val autoEmptyTrash: String @Composable get() = if (isRussian()) "Автоочистка корзины" else "Auto-empty trash"
    val autoEmptyTrashDesc: String @Composable get() = if (isRussian()) 
        "Автоматически удалять старые письма из корзины" 
        else "Automatically delete old emails from trash"
    
    // Настройки
    val settings: String @Composable get() = if (isRussian()) "Настройки" else "Settings"
    val accounts: String @Composable get() = if (isRussian()) "Аккаунты" else "Accounts"
    val addAccount: String @Composable get() = if (isRussian()) "Добавить аккаунт" else "Add account"
    val deleteAccount: String @Composable get() = if (isRussian()) "Удалить аккаунт?" else "Delete account?"
    val deleteAccountConfirm: String @Composable get() = if (isRussian()) 
        "Аккаунт и все связанные данные будут удалены." 
        else "Account and all related data will be deleted."
    val general: String @Composable get() = if (isRussian()) "Общие" else "General"
    val appearance: String @Composable get() = if (isRussian()) "Внешний вид" else "Appearance"
    val syncSettings: String @Composable get() = if (isRussian()) "Синхронизация" else "Sync"
    val sync: String @Composable get() = if (isRussian()) "Синхронизация" else "Sync"
    
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
    val saveDraft: String @Composable get() = if (isRussian()) "Сохранить черновик" else "Save draft"
    val discard: String @Composable get() = if (isRussian()) "Отменить" else "Discard"
    val discardDraft: String @Composable get() = if (isRussian()) "Отменить черновик?" else "Discard draft?"
    val discardDraftConfirm: String @Composable get() = if (isRussian()) 
        "Черновик будет удалён" 
        else "Draft will be discarded"
    
    // Поиск
    val search: String @Composable get() = if (isRussian()) "Поиск" else "Search"
    val searchHint: String @Composable get() = if (isRussian()) "Введите запрос для поиска" else "Enter search query"
    val noResults: String @Composable get() = if (isRussian()) "Ничего не найдено" else "No results"
    
    // Фильтры
    val filters: String @Composable get() = if (isRussian()) "Фильтры" else "Filters"
    val all: String @Composable get() = if (isRussian()) "Все" else "All"
    val unreadOnly: String @Composable get() = if (isRussian()) "Непрочитанные" else "Unread"
    val withAttachments: String @Composable get() = if (isRussian()) "С вложениями" else "With attachments"
    val flagged: String @Composable get() = if (isRussian()) "Избранные" else "Flagged"
    val today: String @Composable get() = if (isRussian()) "Сегодня" else "Today"
    val yesterday: String @Composable get() = if (isRussian()) "Вчера" else "Yesterday"
    val thisWeek: String @Composable get() = if (isRussian()) "На этой неделе" else "This week"
    val thisMonth: String @Composable get() = if (isRussian()) "В этом месяце" else "This month"
    val older: String @Composable get() = if (isRussian()) "Старше" else "Older"
    
    // Донат - реквизиты НЕ переводятся, остаются на русском (имена и номера)
    val supportDeveloper: String @Composable get() = if (isRussian()) "Поддержать разработчика" else "Support developer"
    val supportText: String @Composable get() = if (isRussian()) 
        "Если приложение вам понравилось, вы можете поддержать разработку:" 
        else "If you like the app, you can support development:"
    // Метки переводятся, значения (имена, номера) - нет
    val recipient: String @Composable get() = if (isRussian()) "Получатель:" else "Recipient:"
    val accountNumber: String @Composable get() = if (isRussian()) "Номер счёта:" else "Account number:"
    val bank: String @Composable get() = if (isRussian()) "Банк:" else "Bank:"
    val orByPhone: String @Composable get() = if (isRussian()) "Или по номеру телефона через СБП:" else "Or by phone via SBP:"
    val copyAccount: String @Composable get() = if (isRussian()) "Копировать счёт" else "Copy account"
    val accountCopied: String @Composable get() = if (isRussian()) "Номер счёта скопирован" else "Account number copied"
    val closeDialog: String @Composable get() = if (isRussian()) "Закрыть" else "Close"
    
    // О приложении
    val appDescription: String @Composable get() = if (isRussian()) 
        "Почтовый клиент для Microsoft Exchange Server с поддержкой EAS, IMAP и POP3." 
        else "Mail client for Microsoft Exchange Server with EAS, IMAP and POP3 support."
    val featureSync: String @Composable get() = if (isRussian()) "📧 Синхронизация" else "📧 Sync"
    val featureAttachments: String @Composable get() = if (isRussian()) "📎 Вложения" else "📎 Attachments"
    val featureSend: String @Composable get() = if (isRussian()) "✉️ Отправка" else "✉️ Send"
    val featureSearch: String @Composable get() = if (isRussian()) "🔍 Поиск" else "🔍 Search"
    val featureFolders: String @Composable get() = if (isRussian()) "📁 Папки" else "📁 Folders"
    val developerLabel: String @Composable get() = if (isRussian()) "Разработчик:" else "Developer:"
    
    // Советы по работе с приложением
    val tipsTitle: String @Composable get() = if (isRussian()) "💡 Полезно знать" else "💡 Good to know"
    val tipNotification: String @Composable get() = if (isRussian()) 
        "Уведомление «Ожидание писем» нельзя убрать — это требование Android для фоновой работы."
        else "The «Waiting for emails» notification cannot be removed — it's an Android requirement."
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
    val calendar: String @Composable get() = if (isRussian()) "Календарь" else "Calendar"
    val contacts: String @Composable get() = if (isRussian()) "Контакты" else "Contacts"
    val notes: String @Composable get() = if (isRussian()) "Заметки" else "Notes"
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
    val emailWillBeMovedToTrash: String @Composable get() = if (isRussian()) "Письмо будет перемещено в корзину." else "Email will be moved to trash."
    @Composable
    fun emailsWillBeMovedToTrash(count: Int): String = if (isRussian()) "$count писем будут перемещены в корзину." else "$count emails will be moved to trash."
    val deleteForever: String @Composable get() = if (isRussian()) "Удалить навсегда?" else "Delete forever?"
    val emailWillBeDeletedPermanently: String @Composable get() = if (isRussian()) "Письмо будет удалено безвозвратно." else "Email will be deleted permanently."
    @Composable
    fun emailsWillBeDeletedPermanently(count: Int): String = if (isRussian()) "$count писем будут удалены безвозвратно." else "$count emails will be deleted permanently."
    
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
    val discardDraftQuestion: String @Composable get() = if (isRussian()) "Удалить черновик?" else "Discard draft?"
    val draftWillBeDeleted: String @Composable get() = if (isRussian()) "Письмо будет удалено без сохранения" else "Email will be deleted without saving"
    val selectSender: String @Composable get() = if (isRussian()) "Выберите отправителя" else "Select sender"
    val attach: String @Composable get() = if (isRussian()) "Прикрепить" else "Attach"
    val showCopy: String @Composable get() = if (isRussian()) "Показать копию" else "Show Cc/Bcc"
    val hiddenCopy: String @Composable get() = if (isRussian()) "Скрытая" else "Bcc"
    val messageText: String @Composable get() = if (isRussian()) "Текст сообщения" else "Message"
    val attachmentsCount: String @Composable get() = if (isRussian()) "Вложения" else "Attachments"
    val selectAccount: String @Composable get() = if (isRussian()) "Выбрать аккаунт" else "Select account"
    val sendScheduled: String @Composable get() = if (isRussian()) "Отправка запланирована" else "Send scheduled"
    val authError: String @Composable get() = if (isRussian()) "Ошибка авторизации" else "Authorization error"
    
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
    
    // Локализация ошибок из репозитория
    fun localizeError(errorCode: String, isRussian: Boolean): String {
        return when (errorCode) {
            "ALREADY_IN_FOLDER" -> getAlreadyInFolder(isRussian)
            "Email not found" -> getEmailNotFound(isRussian)
            "Account not found" -> getAccountNotFound(isRussian)
            "Trash folder not found" -> getTrashFolderNotFound(isRussian)
            "Spam folder not found" -> getSpamFolderNotFound(isRussian)
            "NO_INTERNET" -> getNoInternetConnection(isRussian)
            else -> errorCode
        }
    }
    
    // Сообщения об отправке
    fun getEmailSent(isRussian: Boolean): String {
        return if (isRussian) "Письмо отправлено" else "Email sent"
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
