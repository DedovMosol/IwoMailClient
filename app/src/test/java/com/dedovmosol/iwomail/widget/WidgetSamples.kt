package com.dedovmosol.iwomail.widget

/** Данные виджета для отрисовки: длинные имена и темы — худший случай для обрезки и наложений. */
internal object WidgetSamples {
    private val now = System.currentTimeMillis()

    val full = WidgetData(
        accounts = listOf(
            AccountUnread(1, "Работа", 0xFF1976D2.toInt(), 128, isActive = true),
            AccountUnread(2, "Дом", 0xFF2E7D32.toInt(), 3),
            AccountUnread(3, "Отдел продаж", 0xFFC62828.toInt(), 0),
            AccountUnread(4, "Поддержка", 0xFF6A1B9A.toInt(), 12)
        ),
        nextEvent = EventInfo("Совещание по бюджету третьего квартала с отделом закупок", "14:30"),
        hasAccount = true,
        todayDate = "Чт, 27 ноября",
        todayTasksCount = 5,
        activeTasksCount = 17,
        recentEmails = listOf(
            RecentEmail("1", "Константин Константинопольский", "Согласование договора поставки оборудования на 2027 год", dateReceived = now),
            RecentEmail("2", "noreply@notifications.corporate-portal.example", "Ваш пароль истекает через 3 дня", dateReceived = now - 86_400_000L * 2),
            RecentEmail("3", "Анна", "Привет", dateReceived = now - 60_000)
        ),
        totalUnread = 128,
        notesCount = 42,
        lastSyncStr = "в 14:05",
        calendarEventsCount = 236,
        nextTaskTitle = "Подготовить квартальный отчёт для руководства",
        themeColor = 0xFF5C00D4.toInt()
    )
}
