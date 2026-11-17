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

        assertThat(saved.length).isEqualTo(text.length + 2)
        assertThat(dir.root.listFiles()).isEmpty()
        assertThat(saver.restore(saved)).isEqualTo(text)
    }

    // ---------- NAV-09: текст пользователя (или чужого приложения через «Поделиться») — не путь к файлу ----------

    @get:Rule
    val outside = TemporaryFolder()

    @Test
    fun `shared text that looks like the old file marker is kept as text and the file is untouched`() {
        val victim = outside.newFile("mail_database").apply { writeText("DB") }
        val saver = createLargeStringSaver(dir.root, "body")
        val text = "\u0000FILE:" + victim.absolutePath

        assertThat(saver.restore(saver.saveValue(text))).isEqualTo(text)
        assertThat(victim.readText()).isEqualTo("DB")
    }

    @Test
    fun `old-format reference outside the cache is neither read nor deleted`() {
        val victim = outside.newFile("mail_database").apply { writeText("DB") }
        val saver = createLargeStringSaver(dir.root, "body")

        assertThat(saver.restore("\u0000FILE:" + victim.absolutePath)).isEmpty()
        assertThat(victim.exists()).isTrue()
    }

    @Test
    fun `file reference with a path or a foreign name is rejected`() {
        val victim = dir.newFile("secret.tmp").apply { writeText("S") }
        val saver = createLargeStringSaver(dir.root, "body")

        for (name in listOf("../secret.tmp", "secret.tmp", "${LARGE_STATE_FILE_PREFIX}x_../../secret.tmp", victim.absolutePath)) {
            assertThat(saver.restore("\u0001F$name")).isEmpty()
        }
        assertThat(victim.readText()).isEqualTo("S")
    }

    @Test
    fun `text that starts with the new markers round-trips unchanged`() {
        val saver = createLargeStringSaver(dir.root, "body")
        for (text in listOf("\u0001Fcompose_state_x.tmp", "\u0001T", "", "F", "T")) {
            assertThat(saver.restore(saver.saveValue(text))).isEqualTo(text)
        }
    }

    @Test
    fun `old-format reference to an own state file is still restored`() {
        val saver = createLargeStringSaver(dir.root, "body")
        val own = dir.newFile("${LARGE_STATE_FILE_PREFIX}body_123e4567-e89b-12d3-a456-426614174000.tmp").apply { writeText("draft") }

        assertThat(saver.restore("\u0000FILE:" + own.absolutePath)).isEqualTo("draft")
        assertThat(own.exists()).isFalse()
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
