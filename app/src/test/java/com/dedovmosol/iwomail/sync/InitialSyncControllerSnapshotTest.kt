package com.dedovmosol.iwomail.sync

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Краш 1.7.0 «Reading a state that was created after the snapshot was taken or in a snapshot that has not yet been
 * applied»: объект с Compose-состоянием впервые создавался в фоне (PushService, SyncWorker), пока главный поток
 * собирал кадр, и кадр читал это состояние.
 */
class InitialSyncControllerSnapshotTest {

    @Test
    fun `the hazard is real - snapshot state created on another thread after a snapshot was taken is unreadable in it`() {
        lateinit var late: androidx.compose.runtime.MutableState<Boolean>
        val snapshot = Snapshot.takeMutableSnapshot()
        try {
            kotlin.concurrent.thread { late = mutableStateOf(false) }.join()
            val error = runCatching { snapshot.enter { late.value } }.exceptionOrNull()
            assertThat(error).isInstanceOf(IllegalStateException::class.java)
            assertThat(error).hasMessageThat().contains("created after the snapshot was taken")
        } finally {
            snapshot.dispose()
        }
    }

    @Test
    fun `sync status touched first from a background thread is readable inside a snapshot taken earlier`() {
        val snapshot = Snapshot.takeMutableSnapshot()
        try {
            kotlin.concurrent.thread { InitialSyncController.isSyncingAccount(42L) }.join()
            val read = runCatching {
                snapshot.enter {
                    val status = InitialSyncController.status.value
                    listOf(status.isSyncing, status.syncDone, status.noNetwork,
                        InitialSyncController.isSyncing, InitialSyncController.syncDone, InitialSyncController.noNetwork)
                }
            }
            assertThat(read.exceptionOrNull()).isNull()
        } finally {
            snapshot.dispose()
        }
    }

    /**
     * Слои синхронизации, данных, протокола и сети не создают Compose snapshot state: их объекты живут на весь процесс
     * и создаются из фоновых потоков. Для UI состояние отдаётся через Flow/StateFlow.
     */
    @Test
    fun `non-UI layers never create Compose snapshot state`() {
        val root = File("src/main/java/com/dedovmosol/iwomail")
        assertThat(root.isDirectory).isTrue()
        val layers = listOf("sync", "data", "domain", "eas", "network", "imap", "pop3", "shared", "update", "util")
        val creation = Regex("""\b(mutableStateOf|mutable(Int|Long|Float|Double)StateOf|mutableStateListOf|mutableStateMapOf|derivedStateOf)\s*[(<{]""")
        val offenders = layers.flatMap { layer ->
            File(root, layer).walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//") && creation.containsMatchIn(line) }
                    .map { (i, line) -> "${file.relativeTo(root)}:${i + 1}: ${line.trim()}" }
            }.toList()
        }
        assertThat(offenders).isEmpty()
    }
}
