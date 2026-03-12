package com.dedovmosol.iwomail.data.database

/**
 * Лёгкая проекция для уведомлений о новых письмах.
 * Не содержит body/preview и не тянет тяжёлые поля из БД при массовых батчах.
 */
data class NotificationEmailSummary(
    val id: String,
    val from: String,
    val fromName: String = "",
    val subject: String,
    val dateReceived: Long
)
