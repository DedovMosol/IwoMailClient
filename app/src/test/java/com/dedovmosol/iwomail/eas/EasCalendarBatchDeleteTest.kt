package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * EWS-5: строгая оценка ответа пакетного EWS DeleteItem.
 *
 * Чистая функция [EasCalendarCrudService.evaluateBatchDeleteResponse] должна:
 *  - считать Success ТОЛЬКО когда учтены ВСЕ запрошенные id (удалено + ErrorItemNotFound);
 *  - на частичном успехе возвращать Error (не маскировать), чтобы вызывающий слой не стёр
 *    локально события, оставшиеся на сервере («воскрешение» при следующем синке);
 *  - ErrorItemNotFound трактовать как идемпотентный успех (элемента уже нет).
 *
 * Формат ответа — EWS DeleteItem (MS-OXWSCORE): по одному
 * <m:DeleteItemResponseMessage ResponseClass="..."><m:ResponseCode>...</m:ResponseCode> на id.
 */
class EasCalendarBatchDeleteTest {

    private fun successMsg() =
        """<m:DeleteItemResponseMessage ResponseClass="Success"><m:ResponseCode>NoError</m:ResponseCode></m:DeleteItemResponseMessage>"""

    private fun notFoundMsg() =
        """<m:DeleteItemResponseMessage ResponseClass="Error"><m:MessageText>not found</m:MessageText><m:ResponseCode>ErrorItemNotFound</m:ResponseCode></m:DeleteItemResponseMessage>"""

    private fun errorMsg(code: String) =
        """<m:DeleteItemResponseMessage ResponseClass="Error"><m:ResponseCode>$code</m:ResponseCode></m:DeleteItemResponseMessage>"""

    private fun envelope(vararg msgs: String) =
        """<s:Envelope><s:Body><m:DeleteItemResponse><m:ResponseMessages>${msgs.joinToString("")}</m:ResponseMessages></m:DeleteItemResponse></s:Body></s:Envelope>"""

    @Test
    fun allDeleted_returnsSuccessWithCount() {
        val resp = envelope(successMsg(), successMsg())
        val r = EasCalendarCrudService.evaluateBatchDeleteResponse(resp, 2)
        assertThat(r).isInstanceOf(EasResult.Success::class.java)
        assertThat((r as EasResult.Success).data).isEqualTo(2)
    }

    @Test
    fun itemNotFound_isIdempotentSuccess() {
        val resp = envelope(notFoundMsg(), notFoundMsg())
        val r = EasCalendarCrudService.evaluateBatchDeleteResponse(resp, 2)
        assertThat(r).isInstanceOf(EasResult.Success::class.java)
        assertThat((r as EasResult.Success).data).isEqualTo(2)
    }

    @Test
    fun partialFailure_isNotMasked_returnsError() {
        // 3 запрошено: 1 удалён + 1 notFound (ок) + 1 реальная ошибка → ok=2 < 3 → Error.
        val resp = envelope(successMsg(), notFoundMsg(), errorMsg("ErrorAccessDenied"))
        val r = EasCalendarCrudService.evaluateBatchDeleteResponse(resp, 3)
        assertThat(r).isInstanceOf(EasResult.Error::class.java)
        assertThat((r as EasResult.Error).message).contains("частично удалено 2 из 3")
    }

    @Test
    fun allFailed_returnsErrorWithResponseCode() {
        val resp = envelope(errorMsg("ErrorAccessDenied"), errorMsg("ErrorAccessDenied"))
        val r = EasCalendarCrudService.evaluateBatchDeleteResponse(resp, 2)
        assertThat(r).isInstanceOf(EasResult.Error::class.java)
        assertThat((r as EasResult.Error).message).contains("ErrorAccessDenied")
    }

    @Test
    fun emptyRequest_returnsSuccessZero() {
        val r = EasCalendarCrudService.evaluateBatchDeleteResponse("", 0)
        assertThat(r).isInstanceOf(EasResult.Success::class.java)
        assertThat((r as EasResult.Success).data).isEqualTo(0)
    }
}
