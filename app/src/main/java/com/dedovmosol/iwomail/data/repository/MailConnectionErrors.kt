package com.dedovmosol.iwomail.data.repository

/**
 * Понятное сообщение об ошибке входа IMAP/POP3 (JavaMail).
 *
 * Смотрит всю цепочку причин: JavaMail оборачивает причину («STARTTLS failure; nested exception is …
 * CertPathValidatorException», проверено на Exchange 2007), и по одному верхнему сообщению отказ сертификата
 * при STARTTLS неотличим от сервера без STARTTLS. Порядок веток важен: сначала сертификат, потом STARTTLS.
 */
internal fun mailConnectionErrorMessage(e: Throwable, protocol: String): String {
    val chain = generateSequence(e) { it.cause.takeIf { c -> c !== it } }.take(10).toList()
    val text = chain.joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
    fun has(s: String) = text.contains(s, ignoreCase = true)
    return when {
        has("Authentication failed") || has("AUTHENTICATIONFAILED") || has("AuthenticationFailedException") ->
            "Неверный логин или пароль"
        has("Connection refused") -> "Сервер недоступен. Проверьте адрес и порт."
        has("UnknownHostException") || has("Unable to resolve host") -> "Сервер не найден. Проверьте адрес."
        // Закреплённый ключ (ONB-08) не совпал — раньше общих веток «сертификат»: смысл другой
        chain.any { it is com.dedovmosol.iwomail.network.CertificatePinMismatchException } ->
            "Сертификат сервера изменился с тех пор, как вы ему доверились. Если замена плановая, подтвердите новый сертификат в настройках аккаунта."
        // JavaMail ssl.checkserveridentity: сертификат выдан на другое имя
        has("verify identity of server") ->
            "Сертификат сервера выдан на другое имя. Укажите адрес так, как он записан в сертификате, или включите «Принимать все сертификаты»."
        has("CertPathValidatorException") || has("Trust anchor") || has("CertificateException") ||
            has("certificate") ->
            "Сертификат сервера не подтверждён. Если доверяете серверу, сверьте отпечаток сертификата и доверьтесь ему или включите «Принимать все сертификаты»."
        // JavaMail: «STARTTLS is required but host does not support STARTTLS» (IMAP), «STLS is required…» (POP3)
        has("does not support STARTTLS") || has("does not support STLS") || has("STLS is required") ->
            "Сервер не включает шифрование на этом порту (STARTTLS). Выберите SSL/TLS (порт 993 для IMAP, 995 для POP3)."
        has("SSLHandshakeException") || has("handshake") || has("SSL") || has("STARTTLS") ->
            "Не удалось установить защищённое соединение (TLS). Проверьте порт и тип безопасности."
        has("timed out") || has("timeout") -> "Превышено время ожидания. Проверьте подключение к сети."
        else -> "Ошибка $protocol: ${e.message ?: "неизвестная ошибка"}"
    }
}
