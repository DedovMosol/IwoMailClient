package com.dedovmosol.iwomail.widget

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.floor

/**
 * Раскладка виджета под размер и системный масштаб шрифта: что показывать, чтобы ничего не обрезалось и не
 * наползало. Glance `Column`/`Row` — `LinearLayout` без прокрутки: всё, что не влезло по высоте или ширине,
 * лаунчер просто срезает. Поэтому строки добавляются по приоритету, пока хватает высоты, с запасом по высоте
 * строки текста (sp × масштаб шрифта × [LINE_FACTOR]). Чистая функция — проверяется тестами на всех размерах.
 */
internal data class WidgetSpec(
    val textSp: Float,
    val showSearch: Boolean,
    val showSummary: Boolean,
    /** «128 непрочитанных» справа от даты: только если обе надписи помещаются в строку. */
    val showUnreadText: Boolean,
    /** Сколько строк «Календарь / Задачи / Заметки» по порядку: 0..3. */
    val infoRows: Int,
    /** Широкий виджет: название и счётчик, затем подробность («• 14:30 Совещание»); узкий — главное одной надписью. */
    val infoDetails: Boolean,
    /** Сколько строк писем (или строк-заменителей при блокировке / пустых «Входящих»). */
    val mailRows: Int,
    /** Превью письма рядом с отправителем. */
    val mailPreview: Boolean,
    val avatars: Int,
    val showSyncLabel: Boolean,
    val buttonSize: Dp,
    /** Зона нажатия кнопки: не меньше круга, 48 dp — если есть место. */
    val buttonTouch: Dp,
    val avatarSize: Dp,
    val iconSize: Dp,
    /** Поля виджета, нижнего блока и промежуток между кнопками: на маленьком виджете меньше. */
    val padding: Dp,
    val blockPadding: Dp,
    val buttonGap: Dp
) {
    val dateSp get() = textSp + 2f
    val searchSp get() = textSp + 1f
    val mailSp get() = textSp - 1f
}

/** Больше строк писем не бывает: запрос виджета берёт столько, раскладка показывает, сколько влезет. */
internal const val WIDGET_MAX_MAIL_ROWS = 5
internal val WIDGET_PADDING = 12.dp
internal val WIDGET_GAP = 6.dp
internal val WIDGET_BLOCK_PADDING = 8.dp
internal val WIDGET_AVATAR_GAP = 4.dp
internal val WIDGET_BUTTON_GAP = 8.dp
internal const val SEARCH_PADDING_V = 9f
internal const val MAIL_ROW_PADDING_V = 4f

/** Высота строки текста к его размеру с полями шрифта (includeFontPadding) — с запасом. */
private const val LINE_FACTOR = 1.4f
/** Средняя ширина символа Roboto к размеру шрифта — для оценки, влезут ли две надписи в строку. */
private const val CHAR_WIDTH_FACTOR = 0.58f

private const val WIDGET_MIN_TOUCH = 48f
private val AVATAR_FULL = 40.dp
private val BUTTON_FULL = 40.dp
private val BUTTON_MIN = 32.dp

/** Размеры для `SizeMode.Responsive`: лаунчер берёт наибольший, что помещается, — раскладка по нему не обрезается. */
internal val WIDGET_SIZES: Set<DpSize> = setOf(
    DpSize(110.dp, 100.dp),
    DpSize(180.dp, 100.dp),
    DpSize(250.dp, 100.dp),
    DpSize(110.dp, 180.dp),
    DpSize(180.dp, 140.dp),
    DpSize(250.dp, 140.dp),
    DpSize(180.dp, 210.dp),
    DpSize(250.dp, 210.dp),
    DpSize(320.dp, 210.dp),
    DpSize(320.dp, 280.dp),
    DpSize(400.dp, 420.dp),
    DpSize(530.dp, 600.dp)
)

private fun line(sp: Float, fontScale: Float): Float = sp * fontScale * LINE_FACTOR

private const val MIN_TEXT_SP = 11f

/**
 * Текст растёт, пока все строки и аватары, которые поместились при минимальном размере, остаются доступны.
 * Равное число строк недостаточно: письмо не должно вытеснять поиск при увеличении ширины виджета.
 */
internal fun widgetSpec(
    size: DpSize,
    fontScale: Float,
    accounts: Int,
    mailLines: Int,
    summaryChars: Int
): WidgetSpec {
    val maxSp = (size.width.value / 17f).coerceIn(MIN_TEXT_SP, 30f)
    val smallest = layoutAt(MIN_TEXT_SP, size, fontScale, accounts, mailLines, summaryChars)
    var sp = (maxSp * 2f).toInt() / 2f
    while (sp > MIN_TEXT_SP) {
        val spec = layoutAt(sp, size, fontScale, accounts, mailLines, summaryChars)
        if ((!smallest.showSearch || spec.showSearch) && (!smallest.showSummary || spec.showSummary) &&
            spec.infoRows >= smallest.infoRows && spec.mailRows >= smallest.mailRows && spec.avatars >= smallest.avatars
        ) return spec
        sp -= 0.5f
    }
    return smallest
}

private fun layoutAt(
    textSp: Float,
    size: DpSize,
    fontScale: Float,
    accounts: Int,
    mailLines: Int,
    summaryChars: Int
): WidgetSpec {
    val f = fontScale.coerceAtLeast(0.5f)
    val w = size.width.value
    val h = size.height.value
    // Иконки, кнопки и аватары — вместе с текстом (14 sp — 1)
    val ui = (textSp / 14f).coerceIn(0.85f, 1.5f)
    // Маленький виджет — поля меньше, иначе кнопки не помещаются в ширину (110 dp: было 72 dp кнопок в 70 dp)
    val pad = if (w < 150f || h < 120f) 8f else WIDGET_PADDING.value
    val innerW = w - 2 * pad
    val blockPad = if (innerW < 120f) 4f else WIDGET_BLOCK_PADDING.value
    val rowW = innerW - 2 * blockPad
    val buttonGap = if (rowW < 100f) 4f else WIDGET_BUTTON_GAP.value

    // Нижняя строка: две кнопки всегда, аватары — сколько влезет, метка синхронизации — если остаётся место
    val button = ((rowW - buttonGap) / 2f).coerceIn(BUTTON_MIN.value, BUTTON_FULL.value * ui).coerceAtMost((rowW - buttonGap) / 2f)
    val buttonsW = 2 * button + buttonGap
    val avatarRoom = rowW - buttonsW
    val avatarGap = WIDGET_AVATAR_GAP.value
    val pairSize = avatarRoom / 2 - avatarGap
    val fullAvatar = minOf(AVATAR_FULL.value * ui, button)
    // Keep a second mailbox tappable on compact widths when two 28 dp avatars fit.
    val avatar = if (accounts >= 2 && pairSize >= 28f) minOf(fullAvatar, pairSize) else fullAvatar
    val avatarStep = avatar + avatarGap
    val slots = floor(avatarRoom / avatarStep).toInt().coerceAtLeast(0)
    // Не больше 5: аватар и промежуток — два ребёнка вложенного Row, а у Row виджета их не больше 10
    val avatars = minOf(accounts, slots, 5)
    var left = rowW - buttonsW - avatars * avatarStep
    // SET-12: зона нажатия кнопок — 48 dp (минимум Android), если в строке остаётся место; круг прежний
    val touchExtra = 2 * (maxOf(WIDGET_MIN_TOUCH, button) - button)
    val touch = if (touchExtra > 0 && left >= touchExtra) WIDGET_MIN_TOUCH.also { left -= touchExtra } else button
    val iconSize = 16f * ui
    val syncLabel = left >= 64f * f

    // По высоте: внешние поля, нижний блок с его полями и строкой кнопок — обязательны
    var room = h - 2 * pad - (2 * blockPad + maxOf(avatar, touch))
    val gap = WIDGET_GAP.value
    fun take(need: Float): Boolean = (need <= room).also { if (it) room -= need }

    val searchH = line(textSp + 1f, f) + 2 * SEARCH_PADDING_V
    val summaryH = line(textSp + 2f, f)
    val infoH = maxOf(line(textSp, f), iconSize + 2f)
    val mailH = line(textSp - 1f, f) + 2 * MAIL_ROW_PADDING_V

    val showSummary = take(summaryH + gap)
    val showSearch = take(searchH + gap)
    var mailRows = 0
    // Первая строка писем ставит и отступ над кнопками внутри блока
    if (mailLines > 0 && take(mailH + gap)) mailRows = 1
    var infoRows = 0
    repeat(2) { if (infoRows == it && take(infoH + gap)) infoRows++ }
    while (mailRows in 1 until mailLines && take(mailH)) mailRows++
    if (infoRows == 2 && take(infoH + gap)) infoRows = 3

    val summaryW = summaryChars * (textSp + 1f) * f * CHAR_WIDTH_FACTOR + 12f
    return WidgetSpec(
        textSp = textSp,
        showSearch = showSearch,
        showSummary = showSummary,
        showUnreadText = showSummary && summaryW <= innerW,
        infoRows = infoRows,
        infoDetails = innerW >= 220f * minOf(f, 1.5f),
        mailRows = mailRows,
        mailPreview = innerW >= 260f * minOf(f, 1.5f),
        avatars = avatars,
        showSyncLabel = syncLabel,
        buttonSize = button.dp,
        buttonTouch = touch.dp,
        avatarSize = avatar.dp,
        iconSize = iconSize.dp,
        padding = pad.dp,
        blockPadding = blockPad.dp,
        buttonGap = buttonGap.dp
    )
}
