package com.dedovmosol.iwomail.ui.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Enter онбординга: не на prefetch, не ценой пустого превью при свайпе.
 */
class OnboardingPageEnterTest {

    @Test
    fun `idle page is armed only as current`() {
        assertThat(OnboardingPageEnter.isArmed(page = 4, currentPage = 4, targetPage = 4)).isTrue()
        assertThat(OnboardingPageEnter.isArmed(page = 5, currentPage = 4, targetPage = 4)).isFalse()
    }

    @Test
    fun `swipe target is armed so the incoming page can enter during the gesture`() {
        assertThat(OnboardingPageEnter.isArmed(page = 5, currentPage = 4, targetPage = 5)).isTrue()
        assertThat(OnboardingPageEnter.isArmed(page = 4, currentPage = 4, targetPage = 5)).isTrue()
        assertThat(OnboardingPageEnter.isArmed(page = 6, currentPage = 4, targetPage = 5)).isFalse()
    }

    @Test
    fun `prefetch neighbor stays hidden while animations are on`() {
        val phase = OnboardingPageEnter.content(
            armed = false,
            animationsEnabled = true,
            alreadyShown = false
        )
        assertThat(phase).isEqualTo(OnboardingPageEnter.Content.Hidden)
    }

    @Test
    fun `armed page with animations asks for enter, not an instant show`() {
        val phase = OnboardingPageEnter.content(
            armed = true,
            animationsEnabled = true,
            alreadyShown = false
        )
        assertThat(phase).isEqualTo(OnboardingPageEnter.Content.Animate)
    }

    @Test
    fun `animations off shows immediately even for a prefetch neighbor`() {
        val phase = OnboardingPageEnter.content(
            armed = false,
            animationsEnabled = false,
            alreadyShown = false
        )
        assertThat(phase).isEqualTo(OnboardingPageEnter.Content.Show)
    }

    @Test
    fun `already shown stays shown so going back does not flash`() {
        val phase = OnboardingPageEnter.content(
            armed = false,
            animationsEnabled = true,
            alreadyShown = true
        )
        assertThat(phase).isEqualTo(OnboardingPageEnter.Content.Show)
    }
}
