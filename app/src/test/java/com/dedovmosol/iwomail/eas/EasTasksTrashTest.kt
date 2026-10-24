package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.eas.FakeExchange.Reply
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Корзина задач — восстановление и окончательное удаление, от [EasTasksService] до HTTP.
 *
 * На EAS-синке (Exchange 2010/2013) локальная корзина хранит прежний ServerId из папки Tasks: Sync
 * Delete с DeletesAsMoves не сообщает ServerId копии в Deleted Items. Раньше корзину стирал каждый
 * синк, и это не всплывало; теперь корзина живёт, и прежние пути ломались: MoveItems по такому
 * ServerId не находил копию, а Sync Delete по нему бил по папке Tasks (после полного пересинка
 * ServerId может принадлежать другой задаче). Копию в корзине адресует EWS-поиск по теме и классу.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EasTasksTrashTest {

    private val staleEasServerId = "7:3"

    /** EWS: FindItem в deleteditems → [found] (ItemId задач), MoveItem — успех, DeleteItem — [delete]. */
    private fun ews(
        delete: (FakeExchange.Call) -> Reply = { Reply.Text(EwsXml.success("DeleteItem")) },
        found: (String) -> List<String>
    ): (FakeExchange.Call) -> Reply = { call ->
        when {
            !call.isEws -> Reply.Http(500) // ни одного EAS-запроса здесь быть не должно
            call.command == "FindItem" -> {
                val subject = Regex("""<t:Constant Value="([^"]*)"/>""").find(call.xml)?.groupValues?.get(1).orEmpty()
                val ids = found(subject)
                Reply.Text(EwsXml.findItem(ids.joinToString("") { EwsXml.idOnlyTask(it) }, ids.size, includesLast = true))
            }
            call.command == "MoveItem" -> Reply.Text(EwsXml.success("MoveItem", EwsXml.idOnlyTask("RESTORED-1")))
            call.command == "DeleteItem" -> delete(call)
            else -> Reply.Http(500)
        }
    }

    private val accessDenied: (FakeExchange.Call) -> Reply = { Reply.Text(EwsXml.error("DeleteItem", "ErrorAccessDenied")) }

    private fun FakeExchange.ewsCalls(command: String) = calls.filter { it.isEws && it.command == command }

    // ───────────────────────── EAS 14.1 (Exchange 2010/2013) ─────────────────────────

    @Test
    fun `EAS - restore finds the trashed copy in Deleted Items by subject and moves it back`() = runBlocking<Unit> {
        val exchange = FakeExchange(ews { subject -> if (subject == "Report") listOf("DEL-1") else emptyList() })

        val result = exchange.tasksService("14.1", TASKS).restoreTask(staleEasServerId, "Report")

        assertThat(result).isEqualTo(EasResult.Success("RESTORED-1"))
        val find = exchange.ewsCalls("FindItem").single().xml
        assertThat(find).contains("""<t:DistinguishedFolderId Id="deleteditems"/>""")
        assertThat(find).contains("""<t:FieldURI FieldURI="item:ItemClass"/>""")
        assertThat(find).contains("""<t:Constant Value="IPM.Task"/>""")
        val move = exchange.ewsCalls("MoveItem").single().xml
        assertThat(move).contains("""<t:ItemId Id="DEL-1"/>""")
        assertThat(move).contains("""<t:DistinguishedFolderId Id="tasks"/>""")
        assertThat(exchange.calls.none { !it.isEws }).isTrue()
    }

    @Test
    fun `EAS - restore with an ambiguous subject moves nothing`() = runBlocking<Unit> {
        // Две задачи «Report» в корзине: какую из них восстанавливать, неизвестно (EWS-4).
        val exchange = FakeExchange(ews { listOf("DEL-1", "DEL-2") })

        val result = exchange.tasksService("14.1", TASKS).restoreTask(staleEasServerId, "Report")

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        assertThat(exchange.ewsCalls("MoveItem")).isEmpty()
    }

    @Test
    fun `EAS - permanent delete purges the copy found in Deleted Items, never Sync Delete by the stale ServerId`() = runBlocking<Unit> {
        val exchange = FakeExchange(ews { listOf("DEL-1") })

        val result = exchange.tasksService("14.1", TASKS).deleteTaskPermanently(staleEasServerId, "Report")

        assertThat(result).isEqualTo(EasResult.Success(true))
        val delete = exchange.ewsCalls("DeleteItem").single().xml
        assertThat(delete).contains("""DeleteType="HardDelete"""")
        assertThat(delete).contains("""<t:ItemId Id="DEL-1"/>""")
        assertThat(exchange.calls.none { !it.isEws }).isTrue()
    }

    @Test
    fun `EAS - permanent delete without a reachable copy leaves the server alone and succeeds locally`() = runBlocking<Unit> {
        // EWS закрыт (бывает при публикации только ActiveSync): запись не должна застревать в корзине.
        val exchange = FakeExchange { Reply.Disconnect }

        val result = exchange.tasksService("14.1", TASKS).deleteTaskPermanently(staleEasServerId, "Report")

        assertThat(result).isEqualTo(EasResult.Success(true))
        assertThat(exchange.calls.none { !it.isEws }).isTrue()
    }

    @Test
    fun `EAS - emptying the trash purges each copy by its subject`() = runBlocking<Unit> {
        val exchange = FakeExchange(ews { subject -> mapOf("A" to listOf("DEL-A"), "B" to listOf("DEL-B"))[subject].orEmpty() })

        val result = exchange.tasksService("14.1", TASKS).deleteTasksPermanentlyBatch(listOf("7:3" to "A", "7:4" to "B"))

        assertThat(result).isEqualTo(EasResult.Success(2))
        val purged = exchange.ewsCalls("DeleteItem").map { Regex("""<t:ItemId Id="([^"]+)"/>""").find(it.xml)?.groupValues?.get(1) }
        assertThat(purged).containsExactly("DEL-A", "DEL-B")
        assertThat(exchange.calls.none { !it.isEws }).isTrue()
    }

    @Test
    fun `EAS - a cut-off Deleted Items lookup is not taken for a unique match`() = runBlocking<Unit> {
        // Ответ оборван на втором совпадении: видно одно, но «единственным» оно не является.
        val full = EwsXml.findItem(EwsXml.idOnlyTask("DEL-1") + EwsXml.idOnlyTask("DEL-2"), 2, includesLast = true)
        val cut = full.substring(0, full.indexOf("DEL-2"))
        val exchange = FakeExchange { call ->
            when {
                call.isEws && call.command == "FindItem" -> Reply.Text(cut)
                call.isEws && call.command == "MoveItem" -> Reply.Text(EwsXml.success("MoveItem", EwsXml.idOnlyTask("RESTORED-1")))
                else -> Reply.Http(500)
            }
        }

        val result = exchange.tasksService("14.1", TASKS).restoreTask(staleEasServerId, "Report")

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        assertThat(exchange.ewsCalls("MoveItem")).isEmpty()
    }

    @Test
    fun `EAS - a refused purge is an error, not a success the repository would act on`() = runBlocking<Unit> {
        // До фикса: Success(false), а репозиторий смотрит только на тип — задача пропадала локально.
        val exchange = FakeExchange(ews(delete = accessDenied) { listOf("DEL-1") })

        val result = exchange.tasksService("14.1", TASKS).deleteTaskPermanently(staleEasServerId, "Report")

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        assertThat((result as EasResult.Error).message).contains("ErrorAccessDenied")
    }

    @Test
    fun `EAS - emptying the trash with one refused purge is a partial error`() = runBlocking<Unit> {
        // На успехе пакета репозиторий удаляет локально всю корзину; частичный успех обязан быть ошибкой.
        val exchange = FakeExchange(ews(
            delete = { call -> if (call.xml.contains("DEL-B")) accessDenied(call) else Reply.Text(EwsXml.success("DeleteItem")) }
        ) { subject -> mapOf("A" to listOf("DEL-A"), "B" to listOf("DEL-B"))[subject].orEmpty() })

        val result = exchange.tasksService("14.1", TASKS).deleteTasksPermanentlyBatch(listOf("7:3" to "A", "7:4" to "B"))

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        assertThat((result as EasResult.Error).message).contains("1 из 2")
    }

    // ───────────────────────── Exchange 2007 (EWS-синк, EAS 12.1) ─────────────────────────

    @Test
    fun `Exchange 2007 - a refused move to Deleted Items is an error`() = runBlocking<Unit> {
        val ewsItemId = "AAMkADActiveTaskItemId".padEnd(60, 'A')
        val exchange = FakeExchange(ews(delete = accessDenied) { emptyList() })

        val result = exchange.tasksService("12.1", TASKS).deleteTask(ewsItemId)

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        val delete = exchange.ewsCalls("DeleteItem").single().xml
        assertThat(delete).contains("""DeleteType="MoveToDeletedItems"""")
        assertThat(delete).contains("""<t:ItemId Id="$ewsItemId"/>""")
    }

    @Test
    fun `Exchange 2007 - restore moves the Deleted Items copy by the EWS ItemId from the trash sync`() = runBlocking<Unit> {
        val ewsItemId = "AAMkADeletedTaskItemId".padEnd(60, 'A')
        val exchange = FakeExchange(ews { emptyList() }) // FindItem не нужен: ItemId известен из синка корзины

        val result = exchange.tasksService("12.1", TASKS).restoreTask(ewsItemId, "Report")

        assertThat(result).isEqualTo(EasResult.Success("RESTORED-1"))
        assertThat(exchange.ewsCalls("FindItem")).isEmpty()
        assertThat(exchange.ewsCalls("MoveItem").single().xml).contains("""<t:ItemId Id="$ewsItemId"/>""")
    }

    private companion object {
        const val TASKS = "tasks1"
    }
}
