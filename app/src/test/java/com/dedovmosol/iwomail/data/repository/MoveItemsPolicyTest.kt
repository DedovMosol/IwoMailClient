package com.dedovmosol.iwomail.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MoveItemsPolicyTest {

    @Test
    fun `status 1 invalid source needs a folder sync then retry`() {
        assertThat(moveItemsNeedsSrcSync("MOVEITEMS_ALL_FAILED:status=1,failed=2,locked=0")).isTrue()
        assertThat(moveItemsAllFailed("MOVEITEMS_ALL_FAILED:status=1,failed=2,locked=0")).isTrue()
    }

    @Test
    fun `missing status still syncs so an old parser string is not skipped`() {
        assertThat(moveItemsNeedsSrcSync("MOVEITEMS_ALL_FAILED")).isTrue()
    }

    @Test
    fun `status 2 destination error does not sync the source folder`() {
        assertThat(moveItemsNeedsSrcSync("MOVEITEMS_ALL_FAILED:status=2,failed=1,locked=0")).isFalse()
        assertThat(moveItemsAllFailed("MOVEITEMS_ALL_FAILED:status=2,failed=1,locked=0")).isTrue()
    }

    @Test
    fun `status 7 lock retries items without a folder sync`() {
        assertThat(moveItemsNeedsSrcSync("MOVEITEMS_ALL_FAILED:status=7,failed=1,locked=1")).isFalse()
        assertThat(moveItemsAllFailed("MOVEITEMS_ALL_FAILED:status=7,failed=1,locked=1")).isTrue()
    }

    @Test
    fun `other errors are not a MoveItems batch failure`() {
        assertThat(moveItemsAllFailed("NET: timeout")).isFalse()
        assertThat(moveItemsNeedsSrcSync("ALREADY_IN_FOLDER")).isFalse()
        assertThat(moveItemsNeedsSrcSync("")).isFalse()
    }
}
