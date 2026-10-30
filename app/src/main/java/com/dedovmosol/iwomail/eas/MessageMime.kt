package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.util.MimeHtmlProcessor

/**
 * Какой MIME сохранять в .eml. EAS ItemOperations — основной путь (Exchange 2007–2013). Разбор WBXML читает его как
 * UTF-8, поэтому 8-битная часть в другой кодировке приходит с U+FFFD — тогда точная копия по EWS (MimeContent —
 * base64 исходных байтов); EAS не отдал MIME — тоже EWS. EWS недоступен — то, что дал EAS.
 */
internal object MessageMime {

    suspend fun choose(eas: EasResult<String>, ews: suspend () -> EasResult<ByteArray>?): EasResult<ByteArray> {
        val easMime = (eas as? EasResult.Success)?.data?.takeIf { it.isNotBlank() }
        if (easMime != null && !easMime.contains('\uFFFD')) return EasResult.Success(MimeHtmlProcessor.mimeBytes(easMime))
        val exact = ews()
        if (exact is EasResult.Success && exact.data.isNotEmpty()) return exact
        return when {
            easMime != null -> EasResult.Success(MimeHtmlProcessor.mimeBytes(easMime))
            eas is EasResult.Error -> eas
            exact is EasResult.Error -> exact
            else -> EasResult.Success(ByteArray(0))
        }
    }
}
