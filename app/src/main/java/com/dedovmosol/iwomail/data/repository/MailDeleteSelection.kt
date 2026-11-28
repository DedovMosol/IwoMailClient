package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.database.EmailServerRef
import com.dedovmosol.iwomail.domain.MailMessageIdentity

/** Identity from the displayed confirmation, not from a later lookup of a potentially reused ID. */
internal class MailDeleteSelection(
    val targets: Map<String, MailMessageIdentity>,
    val resetCheckpoint: Long = FolderSyncGate.resetCheckpoint()
) {
    val ids: List<String> get() = targets.keys.toList()

    fun matches(row: EmailServerRef): Boolean = targets[row.id] == MailMessageIdentity.of(row) &&
        !FolderSyncGate.wasResetSince(row.folderId, resetCheckpoint)

    companion object {
        fun of(email: EmailEntity): MailDeleteSelection = MailDeleteSelection(mapOf(email.id to MailMessageIdentity.of(email)))
    }
}
