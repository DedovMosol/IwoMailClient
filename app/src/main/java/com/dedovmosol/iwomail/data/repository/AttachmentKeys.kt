package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.eas.EasAttachment

/**
 * Сопоставление вложения с сервером: [MS-ASAIRS] FileReference, затем ContentId, имя — только если оно одно.
 */
object AttachmentKeys {

    fun indexOf(
        local: List<AttachmentEntity>,
        server: EasAttachment,
        skip: Set<Int> = emptySet()
    ): Int {
        fun unused(i: Int) = i !in skip
        val ref = server.fileReference
        if (ref.isNotBlank()) {
            val byRef = local.indices.firstOrNull { unused(it) && local[it].fileReference == ref }
            if (byRef != null) return byRef
        }
        val cid = server.contentId?.trim()?.takeIf { it.isNotEmpty() }
        if (cid != null) {
            val byCid = local.indices.firstOrNull { unused(it) && local[it].contentId == cid }
            if (byCid != null) return byCid
        }
        val name = server.displayName
        if (name.isBlank()) return -1
        val named = local.indices.filter { unused(it) && local[it].displayName == name }
        return if (named.size == 1) named[0] else -1
    }
}
