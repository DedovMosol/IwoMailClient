package com.dedovmosol.iwomail.ui.screens.compose

/**
 * Тело письма к отправке: без хвоста пустых строк редактора и служебных не-HTML тегов — одинаково для немедленной и
 * отложенной отправки (раньше отложенная отправляла тело как есть).
 */
internal fun cleanOutgoingBody(html: String): String = html
    .replace(TRAILING_EMPTY_DIV_REGEX, "")
    .replace(TRAILING_EMPTY_P_REGEX, "")
    .replace(TRAILING_BR_REGEX, "")
    .trimEnd()
    .replace(NON_HTML_TAG_REGEX, "")
