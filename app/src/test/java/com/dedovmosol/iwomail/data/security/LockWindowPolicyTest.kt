package com.dedovmosol.iwomail.data.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** NAV-02: «Недавние» и скриншоты при блокировке приложения. */
class LockWindowPolicyTest {

    @Test
    fun `api 33 hides the recents snapshot while lock is on and keeps screenshots when unlocked`() {
        assertThat(LockWindowPolicy.of(lockActive = true, locked = false, sdk = 33))
            .isEqualTo(LockWindowPolicy(secure = false, recentsScreenshot = false))
        assertThat(LockWindowPolicy.of(lockActive = true, locked = true, sdk = 34))
            .isEqualTo(LockWindowPolicy(secure = true, recentsScreenshot = false))
    }

    @Test
    fun `api 33 without lock restores the snapshot and screenshots`() {
        assertThat(LockWindowPolicy.of(lockActive = false, locked = false, sdk = 33))
            .isEqualTo(LockWindowPolicy(secure = false, recentsScreenshot = true))
    }

    @Test
    fun `older api keeps FLAG_SECURE for as long as the lock is on`() {
        assertThat(LockWindowPolicy.of(lockActive = true, locked = false, sdk = 32).secure).isTrue()
        assertThat(LockWindowPolicy.of(lockActive = true, locked = true, sdk = 26).secure).isTrue()
        assertThat(LockWindowPolicy.of(lockActive = false, locked = false, sdk = 32).secure).isFalse()
    }

    @Test
    fun `lock privacy needs both the setting and a password`() {
        assertThat(LockPrivacy.hides(lockEnabled = true, passwordSet = true)).isTrue()
        assertThat(LockPrivacy.hides(lockEnabled = true, passwordSet = false)).isFalse()
        assertThat(LockPrivacy.hides(lockEnabled = false, passwordSet = true)).isFalse()
    }
}
