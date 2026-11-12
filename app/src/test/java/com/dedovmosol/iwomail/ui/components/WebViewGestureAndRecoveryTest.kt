package com.dedovmosol.iwomail.ui.components

import com.dedovmosol.iwomail.ui.components.TouchDirectionLock.Owner
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Владелец жеста в WebView внутри прокручиваемого экрана и восстановление после смерти рендерера. */
class WebViewGestureAndRecoveryTest {

    private val lock = TouchDirectionLock(touchSlop = 16f)

    @Test
    fun `within touch slop nobody owns the gesture`() {
        lock.onDown(100f, 100f)

        assertThat(lock.onMove(110f, 108f)).isEqualTo(Owner.UNDECIDED)
    }

    @Test
    fun `horizontal drag goes to the web view and stays there`() {
        lock.onDown(100f, 100f)
        assertThat(lock.onMove(130f, 105f)).isEqualTo(Owner.CHILD)

        // Назад вправо-влево одним жестом: смещение по X проходит через ноль, вертикальный дрейф его перевешивает —
        // жест всё равно у WebView (раньше экран перехватывал его, и прокрутка дёргалась)
        assertThat(lock.onMove(102f, 140f)).isEqualTo(Owner.CHILD)
        assertThat(lock.onMove(60f, 160f)).isEqualTo(Owner.CHILD)
    }

    @Test
    fun `vertical drag goes to the screen and stays there`() {
        lock.onDown(100f, 100f)
        assertThat(lock.onMove(104f, 130f)).isEqualTo(Owner.PARENT)

        assertThat(lock.onMove(200f, 131f)).isEqualTo(Owner.PARENT)
    }

    @Test
    fun `second finger is a zoom - always the web view`() {
        lock.onDown(100f, 100f)
        lock.onMove(104f, 130f)

        assertThat(lock.onSecondPointer()).isEqualTo(Owner.CHILD)
        assertThat(lock.onMove(104f, 300f)).isEqualTo(Owner.CHILD)
    }

    @Test
    fun `every gesture is decided anew`() {
        lock.onDown(100f, 100f)
        lock.onMove(150f, 100f)
        lock.onEnd()
        assertThat(lock.owner).isEqualTo(Owner.UNDECIDED)

        lock.onDown(100f, 100f)
        assertThat(lock.onMove(100f, 150f)).isEqualTo(Owner.PARENT)
    }

    @Test
    fun `renderer is recreated after one crash, the second crash gives up`() {
        val recovery = RendererRecovery()

        assertThat(recovery.onRendererGone(crashed = true)).isTrue()
        assertThat(recovery.onRendererGone(crashed = true)).isFalse()
    }

    @Test
    fun `renderer killed for memory is always recreated`() {
        val recovery = RendererRecovery()

        repeat(5) { assertThat(recovery.onRendererGone(crashed = false)).isTrue() }
        assertThat(recovery.crashes).isEqualTo(0)
    }
}
