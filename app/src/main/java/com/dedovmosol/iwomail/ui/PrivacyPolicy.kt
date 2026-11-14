package com.dedovmosol.iwomail.ui

/** Ссылка на политику конфиденциальности: язык интерфейса приложения, не системы. */
internal object PrivacyPolicy {
    const val EN = "https://github.com/DedovMosol/IwoMailClient/blob/main/docs/PRIVACY_POLICY_EN.md"
    const val RU = "https://github.com/DedovMosol/IwoMailClient/blob/main/docs/PRIVACY_POLICY_RU.md"

    fun url(russian: Boolean): String = if (russian) RU else EN
}
