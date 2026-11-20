package com.dedovmosol.iwomail.ui.screens.compose

import android.net.Uri
import com.dedovmosol.iwomail.data.database.AccountEntity

data class EmailSuggestion(
    val email: String,
    val name: String,
    val source: SuggestionSource,
    val groupEmails: List<String> = emptyList(),
    val groupName: String = "",
    val groupColor: Int = 0
)

enum class SuggestionSource {
    CONTACT,
    HISTORY,
    GAL,
    GROUP
}

enum class ImageQuality(val maxSize: Int, val jpegQuality: Int, val labelRu: String, val labelEn: String) {
    SMALL(800, 70, "Маленькое (~100 КБ)", "Small (~100 KB)"),
    MEDIUM(1024, 85, "Среднее (~300 КБ)", "Medium (~300 KB)"),
    LARGE(1600, 90, "Большое (~600 КБ)", "Large (~600 KB)"),
    ORIGINAL(4096, 95, "Оригинал (макс. качество)", "Original (max quality)")
}

internal data class ComposeFieldSnap(
    val to: String,
    val cc: String,
    val bcc: String,
    val subject: String,
    val attachments: List<AttachmentInfo>,
    val attachmentStrings: List<String>,
    val highPriority: Boolean,
    val readReceipt: Boolean,
    val deliveryReceipt: Boolean,
    val account: AccountEntity?,
    val groupMappings: Map<String, List<String>>
)

data class AttachmentInfo(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String
) {
    fun toSaveableString(): String = "${uri}|||${name}|||${size}|||${mimeType}"

    companion object {
        fun fromSaveableString(s: String): AttachmentInfo? {
            val parts = s.split("|||")
            if (parts.size != 4) return null
            return try {
                AttachmentInfo(
                    uri = Uri.parse(parts[0]),
                    name = parts[1],
                    size = parts[2].toLong(),
                    mimeType = parts[3]
                )
            } catch (_: Exception) { null }
        }
    }
}
