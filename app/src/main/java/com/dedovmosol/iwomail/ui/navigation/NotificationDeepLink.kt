package com.dedovmosol.iwomail.ui.navigation

/**
 * Тап по напоминанию календаря/задачи: не отдавать id экрану, пока ящик ещё не тот.
 *
 * Пока [switchToAccountId] указывает на другой ящик, чем уже обработанный
 * [lastSwitchedAccountId], список на экране — старый; «не нашли» съело бы id.
 * Повтор того же ящика: switch == last → id сразу.
 */
internal fun visibleNotificationItemId(
    itemId: String?,
    accountSwitchCompleted: Boolean,
    switchToAccountId: Long?,
    lastSwitchedAccountId: Long?
): String? = itemId.takeIf {
    accountSwitchCompleted &&
        (switchToAccountId == null || switchToAccountId == lastSwitchedAccountId)
}

internal enum class NotificationItemAction {
    WAIT,
    OPEN_AND_CONSUME,
    CONSUME
}

/**
 * Когда открыть элемент из уведомления и когда сбросить id.
 * [loadSettled]: первый синк/загрузка этого ящика закончились.
 */
internal fun notificationItemAction(
    itemId: String?,
    accountReady: Boolean,
    found: Boolean,
    loadSettled: Boolean
): NotificationItemAction {
    if (itemId == null || !accountReady) return NotificationItemAction.WAIT
    if (found) return NotificationItemAction.OPEN_AND_CONSUME
    if (loadSettled) return NotificationItemAction.CONSUME
    return NotificationItemAction.WAIT
}
