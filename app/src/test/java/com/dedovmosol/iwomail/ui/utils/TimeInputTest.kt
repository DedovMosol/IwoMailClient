package com.dedovmosol.iwomail.ui.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** PIM-04: поле времени не принимает невозможное время, а пикер всегда получает 0-23 и 0-59. */
class TimeInputTest {

    @Test fun `normal typing builds HH colon MM`() {
        assertThat(TimeInput.sanitize("1")).isEqualTo("1")
        assertThat(TimeInput.sanitize("14")).isEqualTo("14")
        assertThat(TimeInput.sanitize("143")).isEqualTo("14:3")
        assertThat(TimeInput.sanitize("1430")).isEqualTo("14:30")
        assertThat(TimeInput.sanitize("14:30")).isEqualTo("14:30")
        assertThat(TimeInput.sanitize("2359")).isEqualTo("23:59")
        assertThat(TimeInput.sanitize("0000")).isEqualTo("00:00")
    }

    @Test fun `impossible digits are not accepted`() {
        assertThat(TimeInput.sanitize("9999")).isEqualTo("09")
        assertThat(TimeInput.sanitize("2500")).isEqualTo("20:0")
        assertThat(TimeInput.sanitize("24")).isEqualTo("2")
        assertThat(TimeInput.sanitize("1260")).isEqualTo("12:0")
        assertThat(TimeInput.sanitize("12345")).isEqualTo("12:34")
    }

    @Test fun `first digit 3-9 is the hour 0N`() {
        assertThat(TimeInput.sanitize("9")).isEqualTo("09")
        assertThat(TimeInput.sanitize("930")).isEqualTo("09:30")
    }

    @Test fun `anything typed always parses into picker bounds`() {
        val samples = listOf("", "9", "99", "9999", "25:61", "24:00", "-1:-1", "ab:cd", "12:", ":30", "12:3", "١٢:٣٠")
        for (s in samples) {
            val (h, m) = TimeInput.hourMinute(TimeInput.sanitize(s), defaultHour = 9)
            assertThat(h).isIn(0..23)
            assertThat(m).isIn(0..59)
            val (rh, rm) = TimeInput.hourMinute(s, defaultHour = 9)
            assertThat(rh).isIn(0..23)
            assertThat(rm).isIn(0..59)
        }
        assertThat(TimeInput.hourMinute("99:99", defaultHour = 18)).isEqualTo(18 to 0)
        assertThat(TimeInput.hourMinute("07:45", defaultHour = 18)).isEqualTo(7 to 45)
    }

    @Test fun `deleting characters keeps a valid prefix`() {
        assertThat(TimeInput.sanitize("14:")).isEqualTo("14")
        assertThat(TimeInput.sanitize("1")).isEqualTo("1")
        assertThat(TimeInput.sanitize("")).isEqualTo("")
    }
}
