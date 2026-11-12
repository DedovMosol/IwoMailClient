package com.dedovmosol.iwomail.ui.utils

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Крупный текст в сохранённом состоянии — файлом (лимит Binder ~1 МБ на всё состояние Activity), короткий — как есть.
 */
class LargeStateSaverTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val scope = SaverScope { true }

    private fun Saver<String, String>.saveValue(value: String): String = with(this) { scope.save(value) }!!

    @Test
    fun `short text stays in the bundle`() {
        val saver = createLargeStringSaver(dir.root, "body")
        val text = "x".repeat(LARGE_STATE_INLINE_CHARS)

        val saved = saver.saveValue(text)

        assertThat(saved).isEqualTo(text)
        assertThat(dir.root.listFiles()).isEmpty()
        assertThat(saver.restore(saved)).isEqualTo(text)
    }

    @Test
    fun `large text goes to a file and comes back, the file is removed`() {
        val saver = createLargeStringSaver(dir.root, "body")
        val text = "<p>Черновик</p>" + "y".repeat(500_000)

        val saved = saver.saveValue(text)

        assertThat(saved.length).isLessThan(500)
        assertThat(dir.root.listFiles()!!).hasLength(1)
        assertThat(saver.restore(saved)).isEqualTo(text)
        assertThat(dir.root.listFiles()).isEmpty()
    }

    @Test
    fun `repeated saves of one screen reuse its file`() {
        val saver = createLargeStringSaver(dir.root, "body")

        saver.saveValue("a".repeat(20_000))
        val last = saver.saveValue("b".repeat(30_000))

        assertThat(dir.root.listFiles()!!).hasLength(1)
        assertThat(saver.restore(last)).isEqualTo("b".repeat(30_000))
    }

    @Test
    fun `two screens with the same tag do not overwrite each other`() {
        val first = createLargeStringSaver(dir.root, "body")
        val second = createLargeStringSaver(dir.root, "body")

        val savedFirst = first.saveValue("1".repeat(20_000))
        val savedSecond = second.saveValue("2".repeat(20_000))

        assertThat(first.restore(savedFirst)).isEqualTo("1".repeat(20_000))
        assertThat(second.restore(savedSecond)).isEqualTo("2".repeat(20_000))
    }

    @Test
    fun `missing file restores to empty text instead of crashing`() {
        val saver = createLargeStringSaver(dir.root, "body")
        val saved = saver.saveValue("z".repeat(20_000))
        dir.root.listFiles()!!.forEach { it.delete() }

        assertThat(saver.restore(saved)).isEmpty()
    }

    @Test
    fun `only old state files are stale`() {
        val now = 10L * 24 * 60 * 60 * 1000
        val fresh = dir.newFile("${LARGE_STATE_FILE_PREFIX}body_1.tmp").apply { setLastModified(now - 60_000) }
        val old = dir.newFile("${LARGE_STATE_FILE_PREFIX}body_2.tmp").apply { setLastModified(now - 25L * 60 * 60 * 1000) }
        val foreign = dir.newFile("other.tmp").apply { setLastModified(0) }

        assertThat(isStaleLargeStateFile(fresh, now)).isFalse()
        assertThat(isStaleLargeStateFile(old, now)).isTrue()
        assertThat(isStaleLargeStateFile(foreign, now)).isFalse()
    }
}
