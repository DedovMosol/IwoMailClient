package com.dedovmosol.iwomail.eas

/**
 * SYNC-6 / N-11: единый источник стабильного MIME Message-ID для исходящих писем (DRY).
 *
 * Если задан стабильный clientId (например, из WorkManager inputData, который неизменен при
 * retry), Message-ID детерминирован → повторная отправка той же записи не порождает новый
 * идентификатор. Сервер/клиент с дедупликацией по Message-ID не продублирует письмо, а на
 * EAS 14+ тот же идентификатор дополнительно используется как WBXML ClientId. Если clientId
 * не задан (обычная интерактивная отправка) — генерируется свежий уникальный токен.
 *
 * Чистая функция (без IO) — покрыта EasSendIdentityTest.
 */
internal fun buildStableMessageId(clientId: String?, deviceId: String): String =
    "<${clientId ?: freshSendToken()}@$deviceId>"

/**
 * N-11 (DRY): единый источник уникального токена отправки. Используется как fallback, когда
 * стабильный ClientId не задан (обычная интерактивная отправка), и как основа для Message-ID.
 * Единый формат гарантирует, что токен совпадает во всех путях отправки (с вложениями и без).
 */
internal fun freshSendToken(): String = "${System.currentTimeMillis()}.${System.nanoTime()}"
