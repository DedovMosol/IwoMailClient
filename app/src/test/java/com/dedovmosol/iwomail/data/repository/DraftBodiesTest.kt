package com.dedovmosol.iwomail.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Какое тело черновика открыть: сохранённое на устройстве или свежее с сервера. */
class DraftBodiesTest {

    private val serverHtml = "<html><body><p>Привет</p><p>Полный текст черновика</p></body></html>"

    @Test
    fun `server body wins when local is empty or the server did not answer keeps local`() {
        assertThat(DraftBodies.choose("", 10L, serverHtml, null)).isEqualTo(serverHtml)
        assertThat(DraftBodies.choose("<p>Локально</p>", 10L, null, null)).isNull()
        assertThat(DraftBodies.choose("<p>Локально</p>", 10L, "  ", null)).isNull()
    }

    @Test
    fun `same text in different markup keeps the local copy with its pictures`() {
        // Exchange переписывает разметку; картинки локально — data: URL, на сервере — cid:.
        val local = """<p>Привет</p><p>Полный текст черновика</p><img src="data:image/png;base64,AAAA">"""
        assertThat(DraftBodies.choose(local, 10L, serverHtml, null)).isNull()
    }

    @Test
    fun `different text - newer server version wins, a later local edit is kept`() {
        val local = "<p>Правка на телефоне</p>"
        // Время изменения на сервере не известно — с сервера (там правка из Outlook).
        assertThat(DraftBodies.choose(local, 10_000L, serverHtml, null)).isEqualTo(serverHtml)
        // На сервере изменено позже — с сервера.
        assertThat(DraftBodies.choose(local, 10_000L, serverHtml, 20_000L)).isEqualTo(serverHtml)
        // Сохранено на телефоне позже изменения на сервере (с запасом на часы) — сохранённое.
        val serverAt = 1_000_000L
        assertThat(DraftBodies.choose(local, serverAt + DraftBodies.CLOCK_SKEW_MS + 1, serverHtml, serverAt)).isNull()
        assertThat(DraftBodies.choose(local, serverAt + DraftBodies.CLOCK_SKEW_MS - 1, serverHtml, serverAt)).isEqualTo(serverHtml)
    }

    @Test
    fun `server counts as newer only when changed later than the local save beyond clock skew`() {
        val savedAt = 1_000_000L
        assertThat(DraftBodies.serverIsNewer(savedAt, savedAt + DraftBodies.CLOCK_SKEW_MS + 1)).isTrue()
        // Тот же момент (сохранение самим приложением) и время не известно — не новее: сохранённое не затирается.
        assertThat(DraftBodies.serverIsNewer(savedAt, savedAt + DraftBodies.CLOCK_SKEW_MS)).isFalse()
        assertThat(DraftBodies.serverIsNewer(savedAt, null)).isFalse()
    }

    @Test
    fun `body cut to 10 KB by the old sync is replaced by the full one even if saved later`() {
        val full = "<p>" + "Длинный текст черновика. ".repeat(800) + "</p>"
        // Прежняя синхронизация: TruncationSize = 10240 байт, хвост — оборванный тег.
        val bytes = full.toByteArray(Charsets.UTF_8)
        val cut = String(bytes.copyOf(10_240), Charsets.UTF_8).dropLast(1) + "<sp"

        assertThat(DraftBodies.choose(cut, Long.MAX_VALUE / 2, full, 1L)).isEqualTo(full)
    }
}
