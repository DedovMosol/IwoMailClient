package com.dedovmosol.iwomail.eas

/**
 * Ссылка на вложение — EAS FileReference ([MS-ASAIRS]), а не EWS AttachmentId. Exchange 2007–2013 отдаёт FileReference
 * в URL-кодировке ("5%3a1%3a0", так и в примерах [MS-ASCMD] ItemOperations): двоеточия в ней может не быть. EWS
 * AttachmentId — base64, в нём нет ни ':', ни '%'.
 */
internal fun isEasFileReference(reference: String): Boolean =
    reference.contains(':') || reference.contains("%3a", ignoreCase = true)
