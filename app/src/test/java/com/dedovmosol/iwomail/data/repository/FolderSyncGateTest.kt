package com.dedovmosol.iwomail.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Один EAS-запрос на папку за раз; разные папки друг друга не ждут. Виртуальное время runTest. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FolderSyncGateTest {

    @Test
    fun `requests to one folder never overlap`() = runTest {
        val inside = AtomicInteger(0)
        var maxInside = 0

        (1..5).map {
            async {
                FolderSyncGate.withFolder("gate-test-same") {
                    maxInside = maxOf(maxInside, inside.incrementAndGet())
                    delay(100)
                    inside.decrementAndGet()
                }
            }
        }.awaitAll()

        assertThat(maxInside).isEqualTo(1)
        assertThat(currentTime).isEqualTo(500)
    }

    @Test
    fun `different folders run in parallel`() = runTest {
        listOf("gate-test-a", "gate-test-b", "gate-test-c").map { folder ->
            async { FolderSyncGate.withFolder(folder) { delay(100) } }
        }.awaitAll()

        assertThat(currentTime).isEqualTo(100)
    }

    @Test
    fun `tryWithFolder skips when the folder is busy`() = runTest {
        val held = async {
            FolderSyncGate.withFolder("gate-test-try") { delay(100); "held" }
        }
        delay(1)
        assertThat(FolderSyncGate.tryWithFolder("gate-test-try") { "skip" }).isNull()
        assertThat(held.await()).isEqualTo("held")
        assertThat(FolderSyncGate.tryWithFolder("gate-test-try") { "ok" }).isEqualTo("ok")
    }

    @Test
    fun `failure inside releases the gate`() = runTest {
        runCatching { FolderSyncGate.withFolder("gate-test-fail") { error("boom") } }

        assertThat(FolderSyncGate.withFolder("gate-test-fail") { "next" }).isEqualTo("next")
    }
}
