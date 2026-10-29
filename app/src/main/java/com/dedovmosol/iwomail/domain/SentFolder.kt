package com.dedovmosol.iwomail.domain

import android.content.Context
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.eas.FolderType

/**
 * После отправки из фона (очередь, запланированное) — синхронизировать «Отправленные», иначе письмо появится в них только
 * при следующей синхронизации (через 15+ минут). Не вышло — не беда: письмо уже отправлено.
 */
suspend fun refreshSentFolder(context: Context, accountId: Long) {
    try {
        val sent = MailDatabase.getInstance(context).folderDao().getFolderByType(accountId, FolderType.SENT_ITEMS) ?: return
        // Сервер кладёт копию в «Отправленные» не мгновенно
        kotlinx.coroutines.delay(1_000)
        RepositoryProvider.getMailRepository(context).syncEmails(accountId, sent.id)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        android.util.Log.w("SentFolder", "Sent folder refresh failed: ${e.message}")
    }
}
