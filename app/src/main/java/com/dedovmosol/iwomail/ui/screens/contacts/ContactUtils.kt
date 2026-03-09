package com.dedovmosol.iwomail.ui.screens.contacts

import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

internal val BRACKET_EMAIL_RE = Regex("<([^>]+@[^>]+)>")
internal val SIMPLE_EMAIL_RE = Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}")

internal fun cleanContactEmail(raw: String): String {
    if (raw.isBlank()) return ""
    BRACKET_EMAIL_RE.find(raw)?.groupValues?.get(1)?.let { return it.trim() }
    SIMPLE_EMAIL_RE.find(raw)?.value?.let { return it }
    return raw.trim()
}

internal val GROUP_COLORS = listOf(
    0xFF1976D2.toInt(), 0xFFD32F2F.toInt(), 0xFF388E3C.toInt(), 0xFFF57C00.toInt(),
    0xFF7B1FA2.toInt(), 0xFF0097A7.toInt(), 0xFFC2185B.toInt(), 0xFF5D4037.toInt(),
    0xFF303F9F.toInt(), 0xFF00796B.toInt(), 0xFFFBC02D.toInt(), 0xFF455A64.toInt()
)

internal fun shareFile(context: android.content.Context, content: String, fileName: String, mimeType: String) {
    try {
        val file = File(context.cacheDir, fileName)
        file.writeText(content)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    } catch (e: Exception) {
        Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
    }
}
