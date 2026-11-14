package com.dedovmosol.iwomail.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MoveItemsPolicyTest {

    @Test
    fun `batch invalid source needs a folder sync then retry`() {
        assertThat(moveItemsNeedsSrcSync("MOVEITEMS_ALL_FAILED:status=1,src=abc")).isTrue()
    }

    @Test
    fun `other errors do not trigger a folder sync`() {
        assertThat(moveItemsNeedsSrcSync("NET: timeout")).isFalse()
        assertThat(moveItemsNeedsSrcSync("ALREADY_IN_FOLDER")).isFalse()
        assertThat(moveItemsNeedsSrcSync("")).isFalse()
    }
}
