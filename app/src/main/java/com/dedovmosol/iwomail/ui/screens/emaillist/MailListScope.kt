package com.dedovmosol.iwomail.ui.screens.emaillist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.repository.MailDeleteSelection
import com.dedovmosol.iwomail.domain.EmailThread
import com.dedovmosol.iwomail.domain.EmailThreads
import com.dedovmosol.iwomail.domain.MailMessageIdentity
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.LocalLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One action scope for collapsed/expanded rows, range selection, select-all and batch actions. */
internal class MailListScope private constructor(
    val matchedEmails: List<EmailEntity>,
    private val threads: List<EmailThread>?,
    val selectableIds: Set<String>
) {
    fun rows(expanded: Set<String>): List<MailRow> =
        threads?.let { MailRow.threaded(it, expanded, selectableIds) } ?: MailRow.flat(matchedEmails)

    fun selectOnly(ids: Set<String>): Set<String> =
        if (ids.all { it in selectableIds }) ids else ids.intersect(selectableIds)

    fun dragTargets(key: String, headers: Map<String, List<String>>): List<String> =
        headers[key] ?: if (key in selectableIds) listOf(key) else emptyList()

    companion object {
        /** Group first, then retain threads with a current-folder/result match. Other members are context. */
        fun of(matched: List<EmailEntity>, grouped: List<EmailThread>?, onlyThreads: Boolean): MailListScope {
            val matchedIds = matched.mapTo(LinkedHashSet()) { it.id }
            val visible = grouped?.filter { thread ->
                (!onlyThreads || thread.isThread) && thread.ids.any { it in matchedIds }
            }
            val eligible = if (onlyThreads) {
                visible.orEmpty().flatMapTo(HashSet()) { it.ids.filter(matchedIds::contains) }
            } else matchedIds
            return MailListScope(matched, visible ?: if (onlyThreads) emptyList() else null, eligible)
        }
    }
}

/** A destructive confirmation may be cancelled, but must never silently acquire different targets. */
internal class MailConfirmation(ids: Set<String>, emails: List<EmailEntity>) {
    private val targets = snapshot(ids, emails)
    val deletion = MailDeleteSelection(targets)
    val count: Int get() = targets.size

    fun matches(selected: Set<String>, emails: List<EmailEntity>): Boolean =
        targets.isNotEmpty() && targets.keys == selected && targets == snapshot(selected, emails)

    private fun snapshot(ids: Set<String>, emails: List<EmailEntity>) = buildMap {
        for (email in emails) if (email.id in ids) {
            put(email.id, MailMessageIdentity.of(email))
        }
    }
}

@Composable
internal fun rememberMailListScope(
    source: List<EmailEntity>,
    matched: List<EmailEntity>,
    threaded: Boolean,
    onlyThreads: Boolean,
    peer: List<EmailEntity> = emptyList()
): MailListScope? {
    // Key the result by its input. Never use the old graph to grant eligibility after a refresh.
    val generation = remember(threaded, source, peer) { Any() }
    val grouped = if (threaded && source.isNotEmpty()) key(generation) {
        produceState<List<EmailThread>?>(null) {
            value = withContext(Dispatchers.Default) { EmailThreads.group(source + peer) }
        }.value
    } else emptyList()
    return remember(matched, grouped, threaded, onlyThreads) {
        if (grouped == null) null else MailListScope.of(matched, grouped.takeIf { threaded }, onlyThreads)
    }
}

/** Follows the in-app language override (not the system locale), without touching the shared catalog. */
@Composable
internal fun mailListLabel(russian: String, english: String): String =
    if (LocalLanguage.current == AppLanguage.RUSSIAN) russian else english

@Composable
internal fun ContextMailLabel() {
    androidx.compose.material3.Text(
        text = mailListLabel("В контексте цепочки", "Thread context"),
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    )
}
