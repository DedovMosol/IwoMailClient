package com.dedovmosol.iwomail.ui.components

import kotlin.math.abs
import kotlin.math.max

/**
 * Кому принадлежит жест в WebView внутри вертикально прокручиваемого экрана: горизонтальная прокрутка и масштаб —
 * WebView ([Owner.CHILD]), вертикальная — экрану ([Owner.PARENT]).
 *
 * Направление решается один раз — когда палец ушёл дальше порога касания ([touchSlop], ViewConfiguration) — и
 * держится до конца жеста. Раньше оно перерешалось на каждом движении по смещению от точки касания: при прокрутке
 * влево-вправо одним жестом смещение по X проходило через ноль, вертикальный дрейф его перевешивал, экран
 * перехватывал жест, WebView получал ACTION_CANCEL — горизонтальная прокрутка шла рывками.
 *
 * Второй палец — масштабирование: жест всегда у WebView.
 */
internal class TouchDirectionLock(private val touchSlop: Float) {

    enum class Owner { UNDECIDED, CHILD, PARENT }

    var owner: Owner = Owner.UNDECIDED
        private set

    private var downX = 0f
    private var downY = 0f

    fun onDown(x: Float, y: Float) {
        owner = Owner.UNDECIDED
        downX = x
        downY = y
    }

    fun onSecondPointer(): Owner {
        owner = Owner.CHILD
        return owner
    }

    fun onMove(x: Float, y: Float): Owner {
        if (owner == Owner.UNDECIDED) {
            val dx = abs(x - downX)
            val dy = abs(y - downY)
            if (max(dx, dy) > touchSlop) owner = if (dx > dy) Owner.CHILD else Owner.PARENT
        }
        return owner
    }

    fun onEnd() {
        owner = Owner.UNDECIDED
    }
}
