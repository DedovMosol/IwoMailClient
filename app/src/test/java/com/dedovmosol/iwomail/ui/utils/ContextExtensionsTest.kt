package com.dedovmosol.iwomail.ui.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

/**
 * Юнит-тесты [Context.findActivity] — общего helper'а (DRY: раньше приватные копии в About/Search),
 * которым `SetupScreen.onDispose` отличает реальный уход от поворота (`isChangingConfigurations`),
 * чтобы не удалять выбранные cert-файлы при пересоздании Activity (баг SET-1).
 *
 * Контракт: раскрутить цепочку [ContextWrapper] до [Activity] или вернуть null. `Activity` сама —
 * `ContextWrapper`, поэтому порядок веток важен (проверяется первым тестом). Плейн JUnit + MockK,
 * без Robolectric — instanceof-ветвление и `baseContext` мокаются напрямую.
 */
class ContextExtensionsTest {

    @Test
    fun `returns the activity itself when context is already an Activity`() {
        val activity = mockk<Activity>()
        assertThat(activity.findActivity()).isSameInstanceAs(activity)
    }

    @Test
    fun `unwraps a nested ContextWrapper chain down to the Activity`() {
        val activity = mockk<Activity>()
        val inner = mockk<ContextWrapper>()
        val outer = mockk<ContextWrapper>()
        every { inner.baseContext } returns activity
        every { outer.baseContext } returns inner
        assertThat(outer.findActivity()).isSameInstanceAs(activity)
    }

    @Test
    fun `returns null when the context is not an Activity and not a wrapper`() {
        val plain = mockk<Context>()
        assertThat(plain.findActivity()).isNull()
    }

    @Test
    fun `returns null when a wrapper bottoms out without an Activity`() {
        val plain = mockk<Context>()
        val wrapper = mockk<ContextWrapper>()
        every { wrapper.baseContext } returns plain
        assertThat(wrapper.findActivity()).isNull()
    }
}
