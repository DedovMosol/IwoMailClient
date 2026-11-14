package com.dedovmosol.iwomail.ui.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotificationDeepLinkTest {

    @Test
    fun `hides id while switching to another account`() {
        assertThat(
            visibleNotificationItemId(
                itemId = "evt-b",
                accountSwitchCompleted = true,
                switchToAccountId = 2L,
                lastSwitchedAccountId = 1L
            )
        ).isNull()
    }

    @Test
    fun `hides id until switch work finishes`() {
        assertThat(
            visibleNotificationItemId(
                itemId = "evt-b",
                accountSwitchCompleted = false,
                switchToAccountId = 2L,
                lastSwitchedAccountId = 2L
            )
        ).isNull()
    }

    @Test
    fun `shows id after switch of that account`() {
        assertThat(
            visibleNotificationItemId(
                itemId = "evt-b",
                accountSwitchCompleted = true,
                switchToAccountId = 2L,
                lastSwitchedAccountId = 2L
            )
        ).isEqualTo("evt-b")
    }

    @Test
    fun `shows id when no switch is requested`() {
        assertThat(
            visibleNotificationItemId(
                itemId = "evt-a",
                accountSwitchCompleted = true,
                switchToAccountId = null,
                lastSwitchedAccountId = 1L
            )
        ).isEqualTo("evt-a")
    }

    @Test
    fun `null id stays null`() {
        assertThat(
            visibleNotificationItemId(null, true, null, 1L)
        ).isNull()
    }

    @Test
    fun `opens when the item is already in the cache`() {
        assertThat(
            notificationItemAction("e1", accountReady = true, found = true, loadSettled = false)
        ).isEqualTo(NotificationItemAction.OPEN_AND_CONSUME)
    }

    @Test
    fun `waits while the first load of the account is running`() {
        assertThat(
            notificationItemAction("e1", accountReady = true, found = false, loadSettled = false)
        ).isEqualTo(NotificationItemAction.WAIT)
    }

    @Test
    fun `consumes missing item after load settled so the id cannot stick`() {
        assertThat(
            notificationItemAction("gone", accountReady = true, found = false, loadSettled = true)
        ).isEqualTo(NotificationItemAction.CONSUME)
    }

    @Test
    fun `waits when account is not ready even if list is empty and not syncing`() {
        assertThat(
            notificationItemAction("e1", accountReady = false, found = false, loadSettled = true)
        ).isEqualTo(NotificationItemAction.WAIT)
    }

    @Test
    fun `null id never opens even if a row looks found`() {
        assertThat(
            notificationItemAction(null, accountReady = true, found = true, loadSettled = true)
        ).isEqualTo(NotificationItemAction.WAIT)
    }
}
