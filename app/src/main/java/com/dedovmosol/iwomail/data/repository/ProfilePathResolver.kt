package com.dedovmosol.iwomail.data.repository

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.dedovmosol.iwomail.data.database.AccountEntity
import java.io.File

/**
 * SRP: разрешение и управление путями скачанных файлов, привязанных к профилю аккаунта.
 *
 * Download/IwoMail/{sanitizedDisplayName}/          — вложения писем
 * Download/IwoMail/{sanitizedDisplayName}/Calendar/ — вложения календаря
 *
 * Rollback APK остаётся общим (Download/iwomail rollback/).
 */
object ProfilePathResolver {

    private const val BASE = "Download/IwoMail"
    private const val MAX_FOLDER_NAME_BYTES = 255
    private const val LIKE_ESCAPE = '\\'

    fun sanitizeDisplayName(displayName: String): String {
        val sanitized = displayName
            .map { char -> if (isUnsafeFilenameChar(char)) '_' else char }
            .joinToString("")
            .trim()
            .trimStart('.')
            .trimEnd('.')
        val truncated = truncateUtf8Bytes(sanitized, MAX_FOLDER_NAME_BYTES)
            .trim()
            .trimStart('.')
            .trimEnd('.')
        return truncated.ifBlank { "profile" }
    }

    fun getProfileRelativePath(displayName: String): String =
        "$BASE/${sanitizeDisplayName(displayName)}"

    fun getCalendarRelativePath(displayName: String): String =
        "$BASE/${sanitizeDisplayName(displayName)}/Calendar"

    fun resolveProfileFolderName(accounts: List<AccountEntity>, accountId: Long, displayName: String): String {
        val sanitized = sanitizeDisplayName(displayName)
        val collidingAccounts = accounts.filter { account ->
            account.id != accountId && sanitizeDisplayName(account.displayName) == sanitized
        }
        if (collidingAccounts.isEmpty()) return sanitized

        val smallestAccountId = minOf(accountId, collidingAccounts.minOf { it.id })
        if (accountId == smallestAccountId) return sanitized

        return appendCollisionSuffix(sanitized, "_$accountId")
    }

    fun getProfileRelativePath(accounts: List<AccountEntity>, accountId: Long, displayName: String): String =
        "$BASE/${resolveProfileFolderName(accounts, accountId, displayName)}"

    fun getCalendarRelativePath(accounts: List<AccountEntity>, accountId: Long, displayName: String): String =
        "${getProfileRelativePath(accounts, accountId, displayName)}/Calendar"

    /**
     * Переименовывает подпапку профиля в Downloads при изменении displayName.
     * API 29+: обновляет RELATIVE_PATH у каждого файла через MediaStore.
     * API < 29: File.renameTo.
     */
    fun renameProfileFolder(context: Context, oldSanitized: String, newSanitized: String) {
        if (oldSanitized == newSanitized) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            renameViaMediaStore(context, oldSanitized, newSanitized)
        } else {
            renameViaFileApi(oldSanitized, newSanitized)
        }
    }

    /**
     * Удаляет все файлы профиля из Downloads.
     * API 29+: MediaStore delete.
     * API < 29: рекурсивное удаление через File API.
     */
    fun deleteProfileFolder(context: Context, displayName: String) {
        val sanitized = sanitizeDisplayName(displayName)
        deleteProfileFolderByName(context, sanitized)
    }

    fun deleteProfileFolderByName(context: Context, folderName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            deleteViaMediaStore(context, folderName)
        } else {
            deleteViaFileApi(folderName)
        }
    }

    // ── MediaStore (API 29+) ────────────────────────────────────────────

    private fun renameViaMediaStore(context: Context, oldSanitized: String, newSanitized: String) {
        val oldPrefix = "$BASE/$oldSanitized/"
        val newPrefix = "$BASE/$newSanitized/"

        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.RELATIVE_PATH
        )
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? ESCAPE '$LIKE_ESCAPE'"
        val selectionArgs = arrayOf("${escapeLikeArgument(oldPrefix)}%")

        try {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val currentPath = cursor.getString(pathCol)
                    val updatedPath = newPrefix + currentPath.removePrefix(oldPrefix)
                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                    )
                    try {
                        val cv = ContentValues().apply {
                            put(MediaStore.Downloads.RELATIVE_PATH, updatedPath)
                        }
                        context.contentResolver.update(uri, cv, null, null)
                    } catch (_: SecurityException) {
                        android.util.Log.d("ProfilePathResolver", "Skip non-owned file id=$id")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ProfilePathResolver", "renameViaMediaStore failed", e)
        }
    }

    private fun deleteViaMediaStore(context: Context, sanitized: String) {
        val prefix = "$BASE/$sanitized/"
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? ESCAPE '$LIKE_ESCAPE'"
        val selectionArgs = arrayOf("${escapeLikeArgument(prefix)}%")

        try {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                    )
                    try {
                        context.contentResolver.delete(uri, null, null)
                    } catch (_: SecurityException) {
                        android.util.Log.d("ProfilePathResolver", "Cannot delete non-owned file id=$id")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ProfilePathResolver", "deleteViaMediaStore failed", e)
        }
    }

    // ── File API (API < 29) ─────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun renameViaFileApi(oldSanitized: String, newSanitized: String) {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val oldDir = File(downloadsDir, "IwoMail/$oldSanitized")
            if (!oldDir.exists()) return
            val newDir = File(downloadsDir, "IwoMail/$newSanitized")
            if (!oldDir.renameTo(newDir)) {
                android.util.Log.w("ProfilePathResolver", "renameTo failed: $oldDir -> $newDir")
            }
        } catch (e: Exception) {
            android.util.Log.w("ProfilePathResolver", "renameViaFileApi failed", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun deleteViaFileApi(sanitized: String) {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(downloadsDir, "IwoMail/$sanitized")
            if (dir.exists()) dir.deleteRecursively()
        } catch (e: Exception) {
            android.util.Log.w("ProfilePathResolver", "deleteViaFileApi failed", e)
        }
    }

    private fun escapeLikeArgument(value: String): String = buildString(value.length) {
        value.forEach { char ->
            if (char == LIKE_ESCAPE || char == '%' || char == '_') {
                append(LIKE_ESCAPE)
            }
            append(char)
        }
    }

    private fun truncateUtf8Bytes(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value

        val result = StringBuilder()
        var usedBytes = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val chunk = String(Character.toChars(codePoint))
            val chunkBytes = chunk.toByteArray(Charsets.UTF_8).size
            if (usedBytes + chunkBytes > maxBytes) break
            result.append(chunk)
            usedBytes += chunkBytes
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    private fun appendCollisionSuffix(base: String, suffix: String): String {
        val suffixBytes = suffix.toByteArray(Charsets.UTF_8).size
        val truncatedBase = truncateUtf8Bytes(base, (MAX_FOLDER_NAME_BYTES - suffixBytes).coerceAtLeast(0))
            .trimEnd('.')
        return "${truncatedBase.ifBlank { "profile" }}$suffix"
    }

    private fun isUnsafeFilenameChar(char: Char): Boolean {
        if (char.code in 0x00..0x1F || char.code == 0x7F) return true
        return when (char) {
            '\\', '/', ':', '*', '?', '"', '<', '>', '|' -> true
            else -> false
        }
    }
}
