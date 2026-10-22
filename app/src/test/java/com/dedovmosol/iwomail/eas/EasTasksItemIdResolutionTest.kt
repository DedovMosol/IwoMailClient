package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * EWS-4: резолв EWS ItemId задачи по теме обязан быть однозначным.
 * При нескольких задачах с одинаковой темой нельзя выбирать первую (операция над чужой задачей) —
 * `singleTaskItemIdOrNull` возвращает null, и `resolveEwsTaskItemId` откатывается к сохранённому
 * serverId конкретной задачи.
 */
class EasTasksItemIdResolutionTest {

    private fun item(id: String) = """<t:ItemId Id="$id" ChangeKey="CK$id"/>"""
    private fun findResponse(vararg ids: String) =
        """<m:FindItemResponse><m:ResponseMessages><m:FindItemResponseMessage ResponseClass="Success">""" +
            """<m:RootFolder><t:Items>${ids.joinToString("") { item(it) }}</t:Items></m:RootFolder>""" +
            """</m:FindItemResponseMessage></m:ResponseMessages></m:FindItemResponse>"""

    @Test
    fun singleMatch_returnsThatId() {
        assertThat(EasTasksService.singleTaskItemIdOrNull(findResponse("AAA"))).isEqualTo("AAA")
    }

    @Test
    fun noMatch_returnsNull() {
        assertThat(EasTasksService.singleTaskItemIdOrNull(findResponse())).isNull()
    }

    @Test
    fun multipleMatches_returnNull_doesNotGuessFirst() {
        assertThat(EasTasksService.singleTaskItemIdOrNull(findResponse("AAA", "BBB"))).isNull()
    }
}
