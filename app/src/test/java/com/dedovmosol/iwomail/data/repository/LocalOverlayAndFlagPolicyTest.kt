package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.database.ContactEntity
import com.dedovmosol.iwomail.data.database.ContactSource
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocalOverlayAndFlagPolicyTest {

    @Test
    fun `GAL replace keeps favorite and group`() {
        val incoming = ContactEntity(
            id = "1_exchange_ab",
            accountId = 1L,
            displayName = "New",
            email = "a@b.c",
            source = ContactSource.EXCHANGE
        )
        val existing = incoming.copy(isFavorite = true, groupId = "g1", displayName = "Old")
        val merged = keepLocalContactOverlay(incoming, existing)
        assertThat(merged.isFavorite).isTrue()
        assertThat(merged.groupId).isEqualTo("g1")
        assertThat(merged.displayName).isEqualTo("New")
    }

    @Test
    fun `new contact stays as incoming`() {
        val incoming = ContactEntity(
            id = "1_x",
            accountId = 1L,
            displayName = "N",
            email = "n@e.c"
        )
        assertThat(keepLocalContactOverlay(incoming, null)).isSameInstanceAs(incoming)
    }

    @Test
    fun `local change is rolled back only when the server rejects the change itself`() {
        // Отказ в самом изменении — откат
        assertThat(isChangeRejected("Exception: markAsRead failed: Change Status=5")).isTrue()
        assertThat(isChangeRejected("Exception: setFlag failed: Collection Status=6")).isTrue()
        assertThat(isChangeRejected("Exception: setFlag failed: Collection Status=4")).isTrue()
        // Объект не найден (8): Exchange 2007 после SyncKey=0 ещё не знает id — отправить позже, не откатывать
        assertThat(isChangeRejected("Exception: markAsReadBatch failed: Collection Status=8")).isFalse()
        assertThat(isChangeRejected("Exception: markAsRead failed: Change Status=8")).isFalse()
        assertThat(isRejectingChangeStatus(8)).isFalse()
        assertThat(isRejectingChangeStatus(6)).isTrue()
        assertThat(isRejectingChangeStatus(7)).isTrue()
        // Устаревший ключ, иерархия папок, сбой сервера, HTTP, сеть — отправить позже
        assertThat(isChangeRejected("Exception: setFlag failed: Collection Status=3")).isFalse()
        assertThat(isChangeRejected("Exception: setFlag failed: Collection Status=12")).isFalse()
        assertThat(isChangeRejected("Exception: setFlag failed: Collection Status=5")).isFalse()
        assertThat(isChangeRejected("HTTP 503: Service Unavailable")).isFalse()
        assertThat(isChangeRejected("UNAUTHORIZED")).isFalse()
        assertThat(isChangeRejected("[NET:TIMEOUT] SocketTimeoutException: timeout")).isFalse()
        assertThat(isChangeRejected("[NET:DNS] UnknownHostException: Unable to resolve host")).isFalse()
    }
}
