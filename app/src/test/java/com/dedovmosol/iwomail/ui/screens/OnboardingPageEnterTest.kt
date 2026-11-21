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
    fun `initial animated layer is visible without waiting for enter`() {
        val start = OnboardingPageEnter.initialProgress(animationsEnabled = true, alreadyShown = false)
        assertThat(start).isEqualTo(0f)
        assertThat(OnboardingPageEnter.alpha(start)).isGreaterThan(0.8f)
    }

    @Test
    fun `animations off and revisited pages are fully visible on first frame`() {
        val disabled = OnboardingPageEnter.initialProgress(animationsEnabled = false, alreadyShown = false)
        val revisited = OnboardingPageEnter.initialProgress(animationsEnabled = true, alreadyShown = true)
        assertThat(OnboardingPageEnter.alpha(disabled)).isEqualTo(1f)
        assertThat(OnboardingPageEnter.alpha(revisited)).isEqualTo(1f)
    }
}
