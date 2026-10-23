package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParserException

/**
 * Общие потоковые примитивы разбора EAS Sync ([EasSyncStream]) на настоящем KXmlParser.
 * Их используют Calendar, Notes, Tasks и Contacts, поэтому контракт закреплён отдельно.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EasSyncStreamTest {

    private val leaves = setOf("Subject", "FileAs", "Email1Address")

    private fun items(xml: String, commands: Set<String> = EasSyncStream.ADD_CHANGE): List<Pair<String, EasSyncStream.SyncItem?>> {
        val result = mutableListOf<Pair<String, EasSyncStream.SyncItem?>>()
        EasSyncStream.forEachCommand(xml, commands) { command, p ->
            result += command to EasSyncStream.readItem(p, leaves, withBody = true, withCategories = true)
        }
        return result
    }

    private fun sync(commands: String, responses: String = "") =
        "<Sync><Collections><Collection><SyncKey>2</SyncKey><Status>1</Status>" +
            (if (responses.isEmpty()) "" else "<Responses>$responses</Responses>") +
            "<Commands>$commands</Commands></Collection></Collections></Sync>"

    @Test
    fun `forEachCommand visits Commands children in document order and skips Responses`() {
        val xml = sync(
            commands = "<Add><ServerId>1</ServerId><ApplicationData><Subject>A</Subject></ApplicationData></Add>" +
                "<Delete><ServerId>9</ServerId></Delete>" +
                "<Change><ServerId>2</ServerId><ApplicationData><Subject>B</Subject></ApplicationData></Change>",
            responses = "<Add><ClientId>c1</ClientId><ServerId>7</ServerId><Status>1</Status></Add>"
        )

        val visited = items(xml)

        assertThat(visited.map { it.first }).containsExactly("Add", "Change").inOrder()
        assertThat(visited.map { it.second?.serverId }).containsExactly("1", "2").inOrder()
    }

    @Test
    fun `truncated document is an error, not a partial list`() {
        // KXmlParser на обрыве выдаёт END_DOCUMENT с открытыми тегами — это должно стать ошибкой.
        val cut = sync("<Add><ServerId>1</ServerId><ApplicationData><Subject>A</Subject></ApplicationData></Add>")
            .substringBefore("</Commands>")

        assertThrows(XmlPullParserException::class.java) { items(cut) }
    }

    @Test
    fun `malformed document is an error`() {
        val broken = sync("<Add><ServerId>1</ServerId><ApplicationData><Subject>A</Body></ApplicationData></Add>")

        assertThrows(XmlPullParserException::class.java) { items(broken) }
    }

    @Test
    fun `readItem takes requested leaves, body Data and categories, skips the rest`() {
        val xml = sync(
            "<Add><ServerId>9:1</ServerId><ApplicationData>" +
                "<FileAs>Ivanov, Ivan</FileAs>" +
                "<Picture>${"QUJD".repeat(500)}</Picture>" +
                "<Email1Address>ivan@corp.local</Email1Address>" +
                "<Children><Child>Masha</Child></Children>" +
                "<Body><Type>1</Type><EstimatedDataSize>11</EstimatedDataSize><Data> Hello &amp; bye </Data></Body>" +
                "<Categories><Category> R&amp;D </Category><Category/><Category>Ops</Category></Categories>" +
                "<Subject>First</Subject><Subject>Second</Subject>" +
                "</ApplicationData></Add>"
        )

        val item = items(xml).single().second!!

        assertThat(item.serverId).isEqualTo("9:1")
        assertThat(item.fields).containsExactly(
            "FileAs", "Ivanov, Ivan",
            "Email1Address", "ivan@corp.local",
            "Subject", "First"                      // первое вхождение
        )
        assertThat(item.body).isEqualTo("Hello & bye")
        assertThat(item.categories).containsExactly("R&D", "Ops").inOrder()
    }

    @Test
    fun `body falls back to own text and is null without Data or text`() {
        val xml = sync(
            "<Add><ServerId>1</ServerId><ApplicationData><Body>EAS 2.5 body</Body></ApplicationData></Add>" +
                "<Add><ServerId>2</ServerId><ApplicationData><Body><Type>1</Type><Truncated>1</Truncated></Body></ApplicationData></Add>" +
                "<Add><ServerId>3</ServerId><ApplicationData><Body/></ApplicationData></Add>"
        )

        val bodies = items(xml).map { it.second!!.body }

        assertThat(bodies).containsExactly("EAS 2.5 body", null, null).inOrder()
    }

    @Test
    fun `item without ApplicationData is null, without ServerId has null id, empty data has no fields`() {
        val xml = sync(
            "<Add><ServerId>1</ServerId></Add>" +
                "<Add><ApplicationData><Subject>Orphan</Subject></ApplicationData></Add>" +
                "<Add><ServerId>3</ServerId><ApplicationData/></Add>"
        )

        val visited = items(xml).map { it.second }

        assertThat(visited[0]).isNull()
        assertThat(visited[1]!!.serverId).isNull()
        assertThat(visited[1]!!.fields).containsExactly("Subject", "Orphan")
        assertThat(visited[2]!!.serverId).isEqualTo("3")
        assertThat(visited[2]!!.fields).isEmpty()
        assertThat(visited[2]!!.body).isNull()
        assertThat(visited[2]!!.categories).isEmpty()
    }

    @Test
    fun `namespace prefixes of client-style XML are tolerated`() {
        val xml = "<airsync:Sync><airsync:Collections><airsync:Collection><airsync:Commands>" +
            "<airsync:Add><airsync:ServerId>1</airsync:ServerId><airsync:ApplicationData>" +
            "<notes:Subject>Prefixed</notes:Subject>" +
            "<airsyncbase:Body><airsyncbase:Data>Text</airsyncbase:Data></airsyncbase:Body>" +
            "<notes:Categories><notes:Category>Work</notes:Category></notes:Categories>" +
            "</airsync:ApplicationData></airsync:Add>" +
            "</airsync:Commands></airsync:Collection></airsync:Collections></airsync:Sync>"

        val item = items(xml).single().second!!

        assertThat(item.fields).containsExactly("Subject", "Prefixed")
        assertThat(item.body).isEqualTo("Text")
        assertThat(item.categories).containsExactly("Work")
    }

    @Test
    fun `first Categories block wins and nested ones are not mixed in`() {
        val xml = sync(
            "<Add><ServerId>1</ServerId><ApplicationData>" +
                "<Categories><Category>Main</Category></Categories>" +
                "<Exceptions><Exception><Categories><Category>Other</Category></Categories></Exception></Exceptions>" +
                "<Categories><Category>Late</Category></Categories>" +
                "</ApplicationData></Add>"
        )

        assertThat(items(xml).single().second!!.categories).containsExactly("Main")
    }

    @Test
    fun `real WBXML round-trip feeds bare tags the stream reads`() {
        val wbxml = WbxmlParser()
        val sent = """<?xml version="1.0" encoding="utf-8"?><Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:contacts="Contacts">""" +
            "<Collections><Collection><SyncKey>2</SyncKey><CollectionId>9</CollectionId><Status>1</Status><Commands>" +
            "<Add><ServerId>9:1</ServerId><ApplicationData><contacts:FileAs>Ivanov</contacts:FileAs>" +
            "<contacts:Picture>${"QUJD".repeat(64)}</contacts:Picture>" +
            "<contacts:Email1Address>ivan@corp.local</contacts:Email1Address></ApplicationData></Add>" +
            "</Commands></Collection></Collections></Sync>"

        val item = items(wbxml.parse(wbxml.generate(sent)), setOf("Add")).single().second!!

        assertThat(item.serverId).isEqualTo("9:1")
        assertThat(item.fields).containsExactly("FileAs", "Ivanov", "Email1Address", "ivan@corp.local")
    }
}
