package com.dedovmosol.iwomail.eas

/**
 * Отчёты, которые просит отправитель письма: о прочтении и о доставке. В черновике Exchange — свойства
 * IsReadReceiptRequested и IsDeliveryReceiptRequested (EWS MessageType, Exchange 2007 и новее): Outlook видит и
 * отправляет черновик с теми же флажками. EAS этих свойств не передаёт.
 */
data class ReceiptRequests(val read: Boolean, val delivery: Boolean) {
    val any: Boolean get() = read || delivery

    companion object {
        val NONE = ReceiptRequests(read = false, delivery = false)

        /** Из столбцов записи: оба null — не известны (черновик пришёл синхронизацией, флажки на сервере не читались). */
        fun of(read: Boolean?, delivery: Boolean?): ReceiptRequests? =
            if (read == null && delivery == null) null else ReceiptRequests(read == true, delivery == true)
    }
}
