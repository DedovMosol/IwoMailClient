package com.dedovmosol.iwomail.domain

import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.database.EmailServerRef

/** Guard a UI selection when EAS 12.x reuses a ServerId. Flags, read state and body are not identity. */
internal data class MailMessageIdentity(
    val accountId: Long,
    val folderId: String,
    val messageId: String?,
    val from: String,
    val to: String,
    val subject: String,
    val date: Long
) {
    companion object {
        fun of(email: EmailEntity) = with(email) {
            MailMessageIdentity(accountId, folderId, internetMessageId, from, to, subject, dateReceived)
        }

        fun of(email: EmailServerRef) = with(email) {
            MailMessageIdentity(accountId, folderId, internetMessageId, from, to, subject, dateReceived)
        }
    }
}

internal fun retainSameMessages(selected: Set<String>, before: List<EmailEntity>, after: List<EmailEntity>): Set<String> {
    if (selected.isEmpty() || before === after) return selected
    val identities = HashMap<String, MailMessageIdentity>(selected.size)
    before.forEach { if (it.id in selected) identities[it.id] = MailMessageIdentity.of(it) }
    return after.asSequence().filter { it.id in identities && identities[it.id] == MailMessageIdentity.of(it) }
        .map { it.id }.toSet()
}
