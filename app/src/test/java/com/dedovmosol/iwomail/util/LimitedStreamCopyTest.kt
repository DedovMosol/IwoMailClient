package com.dedovmosol.iwomail.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class LimitedStreamCopyTest {

    @Test
    fun `blank display name becomes file`() {
        assertThat(openableDisplayName(null)).isEqualTo("file")
        assertThat(openableDisplayName("  ")).isEqualTo("file")
        assertThat(openableDisplayName("photo.jpg")).isEqualTo("photo.jpg")
    }

    @Test
    fun `copies when under both limits`() {
        val out = ByteArrayOutputStream()
        val result = copyStreamLimited(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), out, 10, 0, 10)
        assertThat(result).isInstanceOf(LimitedCopyResult.Ok::class.java)
        assertThat((result as LimitedCopyResult.Ok).bytesCopied).isEqualTo(4)
        assertThat(out.toByteArray()).isEqualTo(byteArrayOf(1, 2, 3, 4))
    }

    @Test
    fun `stops at single-file ceiling`() {
        val out = ByteArrayOutputStream()
        val result = copyStreamLimited(ByteArrayInputStream(ByteArray(20) { 1 }), out, 8, 0, 100)
        assertThat(result).isEqualTo(LimitedCopyResult.ExceededSingle)
    }

    @Test
    fun `stops at remaining total budget`() {
        val out = ByteArrayOutputStream()
        val result = copyStreamLimited(ByteArrayInputStream(ByteArray(20) { 1 }), out, 100, 8, 10)
        assertThat(result).isEqualTo(LimitedCopyResult.ExceededTotal)
    }

    @Test
    fun `empty stream copies zero bytes`() {
        val out = ByteArrayOutputStream()
        val result = copyStreamLimited(ByteArrayInputStream(ByteArray(0)), out, 10, 0, 10)
        assertThat(result).isInstanceOf(LimitedCopyResult.Ok::class.java)
        assertThat((result as LimitedCopyResult.Ok).bytesCopied).isEqualTo(0)
    }

    @Test
    fun `zero remaining budget rejects the next byte`() {
        val out = ByteArrayOutputStream()
        val result = copyStreamLimited(ByteArrayInputStream(byteArrayOf(1)), out, 0, 0, 0)
        assertThat(result).isEqualTo(LimitedCopyResult.ExceededSingle)
    }

    @Test
    fun `readUtf8Limited returns text under cap and null when over`() {
        val ok = readUtf8Limited(ByteArrayInputStream("hello".toByteArray()), 16)
        assertThat(ok).isEqualTo("hello")
        val over = readUtf8Limited(ByteArrayInputStream(ByteArray(20) { 'a'.code.toByte() }), 8)
        assertThat(over).isNull()
    }
}
