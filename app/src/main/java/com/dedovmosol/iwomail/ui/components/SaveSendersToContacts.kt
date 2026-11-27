package com.dedovmosol.iwomail.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.ui.NotificationStrings
import com.dedovmosol.iwomail.ui.isRussian
import com.dedovmosol.iwomail.util.SafeToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * «Отправителя в контакты» — общий для письма и выделения в папке (DRY): сохраняет отправителей писем в локальные
 * контакты их аккаунта ([com.dedovmosol.iwomail.data.repository.ContactRepository.addSendersToLocal]) и показывает
 * итог тостом. Письма разных аккаунтов (папка «Избранное») сохраняются каждое в свой аккаунт.
 */
@Composable
internal fun rememberSaveSendersToContacts(): (List<EmailEntity>) -> Unit {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val ru by rememberUpdatedState(isRussian())
    return remember(context, scope) {
        { emails ->
            val byAccount = emails.groupBy({ it.accountId }, { it.from to it.fromName })
            scope.launch {
                try {
                    val accounts = RepositoryProvider.getAccountRepository(context)
                    val contacts = RepositoryProvider.getContactRepository(context)
                    var added = 0
                    var existing = 0
                    for ((accountId, senders) in byAccount) {
                        val own = accounts.getAccount(accountId)?.email
                        val saved = contacts.addSendersToLocal(accountId, senders, own)
                        added += saved.added
                        existing += saved.existing
                    }
                    SafeToast.short(context, NotificationStrings.sendersSaved(added, existing, ru))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    SafeToast.short(context, if (ru) "Ошибка сохранения контакта" else "Failed to save contact")
                }
            }
        }
    }
}
