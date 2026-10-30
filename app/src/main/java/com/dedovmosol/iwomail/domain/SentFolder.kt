package com.dedovmosol.iwomail.domain

import android.content.Context
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType

/** Паузы перед попытками: Exchange кладёт копию в «Отправленные» не сразу (обработка на CAS/MBX). */
private val SENT_REFRESH_DELAYS_MS = longArrayOf(3_000L, 6_000L, 10_000L)

/**
 * После отправки — синхронизировать «Отправленные», иначе письмо появится в них только при следующей синхронизации
 * (через 15+ минут). Инкрементальная синхронизация, пока не придёт новое письмо; ошибка — одна полная (на случай
 * устаревшего ключа синхронизации). Не вышло — не беда: письмо уже отправлено.
 */
suspend fun refreshSentFolder(context: Context, accountId: Long) {
    try {
        val sent = MailDatabase.getInstance(context).folderDao().getFolderByType(accountId, FolderType.SENT_ITEMS) ?: return
        val mailRepo = RepositoryProvider.getMailRepository(context)
        for (pause in SENT_REFRESH_DELAYS_MS) {
            kotlinx.coroutines.delay(pause)
            when (val result = mailRepo.syncEmails(accountId, sent.id)) {
                is EasResult.Success -> if (result.data > 0) return
                is EasResult.Error -> {
                    mailRepo.syncSentFull(accountId, sent.id)
                    return
                }
            }
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        android.util.Log.w("SentFolder", "Sent folder refresh failed: ${e.message}")
    }
}
