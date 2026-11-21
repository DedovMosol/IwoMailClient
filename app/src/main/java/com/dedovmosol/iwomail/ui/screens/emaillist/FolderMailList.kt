package com.dedovmosol.iwomail.ui.screens.emaillist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.domain.EmailThread
import com.dedovmosol.iwomail.domain.EmailThreads
import com.dedovmosol.iwomail.ui.screens.EmailDateFilter
import com.dedovmosol.iwomail.ui.screens.MailFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

internal class FolderListRows(val items: List<MailRow>) {
    val keys = items.map { it.key }
    val threadSelection = MailRow.selectionOf(items)
}

internal class FolderMailList(
    val displayEmails: List<EmailEntity>,
    val scope: MailListScope
) {
    val emailsById = scope.matchedEmails.associateBy { it.id }
    val collapsedRows = FolderListRows(scope.rows(emptySet()))
}

internal fun buildFolderMailList(
    emails: List<EmailEntity>,
    pendingRemovalIds: Set<String>,
    mailFilter: MailFilter,
    dateFilter: EmailDateFilter,
    isTodayAll: Boolean,
    grouped: List<EmailThread>?,
    onlyThreads: Boolean,
    now: Long = System.currentTimeMillis()
): FolderMailList {
    val display = emails.withoutPending(pendingRemovalIds)
    val cutoff = if (isTodayAll) 0L else when (dateFilter) {
        EmailDateFilter.ALL -> 0L
        EmailDateFilter.TODAY -> Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        else -> now - (checkNotNull(dateFilter.days) * 86_400_000L)
    }
    // Preserve the first record per id, including when a later duplicate alone would match.
    val seen = HashSet<String>()
    val matched = display.filter { email ->
        seen.add(email.id) && (cutoff <= 0L || email.dateReceived >= cutoff) && when (mailFilter) {
            MailFilter.ALL -> true
            MailFilter.UNREAD -> !email.read
            MailFilter.STARRED -> email.flagged
            MailFilter.WITH_ATTACHMENTS -> email.hasAttachments
            MailFilter.IMPORTANT -> email.importance == 2
        }
    }
    return FolderMailList(display, MailListScope.of(matched, grouped, onlyThreads))
}

private fun List<EmailEntity>.withoutPending(ids: Set<String>) =
    if (ids.isEmpty()) this else filterNot { it.id in ids }

private val emptyFolderMailList = FolderMailList(emptyList(), MailListScope.of(emptyList(), null, false))

// Use identity for list snapshots: comparing a long equal prefix is itself main-thread work.
private class InputRef(private val value: Any?) {
    override fun equals(other: Any?) = other is InputRef && value === other.value
    override fun hashCode(): Int = System.identityHashCode(value)
}

@Composable
internal fun rememberFolderMailList(
    emails: List<EmailEntity>,
    peerEmails: List<EmailEntity>,
    pendingRemovalIds: Set<String>,
    mailFilter: MailFilter,
    dateFilter: EmailDateFilter,
    isTodayAll: Boolean,
    threaded: Boolean,
    onlyThreads: Boolean
): FolderMailList? {
    if (emails.isEmpty()) return emptyFolderMailList

    // Thread grouping is independent of filter changes. Never publish old action targets
    // when a new query, model or filter arrives; key() also cancels the old producer.
    val grouped = if (threaded) {
        val generation = remember(InputRef(emails), InputRef(peerEmails), pendingRemovalIds) { Any() }
        key(generation) {
            produceState<List<EmailThread>?>(null) {
                value = withContext(Dispatchers.Default) {
                    EmailThreads.group(emails.withoutPending(pendingRemovalIds) + peerEmails.withoutPending(pendingRemovalIds))
                }
            }.value
        } ?: return null
    } else null

    val generation = remember(InputRef(emails), pendingRemovalIds, mailFilter, dateFilter, isTodayAll, InputRef(grouped), onlyThreads) { Any() }
    return key(generation) {
        produceState<FolderMailList?>(null) {
            value = withContext(Dispatchers.Default) {
                buildFolderMailList(emails, pendingRemovalIds, mailFilter, dateFilter, isTodayAll, grouped, onlyThreads)
            }
        }.value
    }
}
