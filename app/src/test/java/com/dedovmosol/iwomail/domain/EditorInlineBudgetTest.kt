package com.dedovmosol.iwomail.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EditorInlineBudgetTest {

    @Test
    fun `accepts small signature image`() {
        val budget = EditorInlineBudget()
        assertThat(budget.offer(8 * 1024)).isTrue()
        assertThat(budget.count).isEqualTo(1)
        assertThat(budget.totalBytes).isEqualTo(8 * 1024)
    }

    @Test
    fun `rejects empty or oversize image without consuming a slot`() {
        val budget = EditorInlineBudget()
        assertThat(budget.offer(0)).isFalse()
        assertThat(budget.offer(EditorInlineBudget.MAX_BYTES_PER_IMAGE + 1)).isFalse()
        assertThat(budget.count).isEqualTo(0)
        assertThat(budget.totalBytes).isEqualTo(0)
    }

    @Test
    fun `stops at max count`() {
        val budget = EditorInlineBudget(maxCount = 2, maxBytesPerImage = 100, maxTotalBytes = 10_000)
        assertThat(budget.offer(10)).isTrue()
        assertThat(budget.offer(10)).isTrue()
        assertThat(budget.offer(10)).isFalse()
        assertThat(budget.count).isEqualTo(2)
    }

    @Test
    fun `stops at total bytes`() {
        val budget = EditorInlineBudget(maxCount = 10, maxBytesPerImage = 100, maxTotalBytes = 150)
        assertThat(budget.offer(100)).isTrue()
        assertThat(budget.offer(51)).isFalse()
        assertThat(budget.offer(50)).isTrue()
        assertThat(budget.totalBytes).isEqualTo(150)
    }

    @Test
    fun `filterDataUrls keeps fitting images in order`() {
        val budget = EditorInlineBudget(maxCount = 2, maxBytesPerImage = 1000, maxTotalBytes = 1000)
        // "AAAA" → 3 decoded bytes
        val a = "data:image/png;base64,AAAA"
        val b = "data:image/png;base64,AAAABBBB"
        val kept = budget.filterDataUrls(linkedMapOf("cid-a" to a, "cid-b" to b, "cid-c" to a))
        assertThat(kept.keys).containsExactly("cid-a", "cid-b").inOrder()
    }

    @Test
    fun `decodedDataUrlBytes uses base64 length without allocating payload`() {
        val url = "data:image/png;base64," + "A".repeat(8)
        assertThat(decodedDataUrlBytes(url)).isEqualTo(6)
        assertThat(decodedDataUrlBytes("not-a-data-url")).isEqualTo(("not-a-data-url".length * 3) / 4)
    }
}
