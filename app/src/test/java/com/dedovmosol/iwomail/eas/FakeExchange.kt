package com.dedovmosol.iwomail.eas

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

/**
 * Фейковый Exchange на уровне HTTP для реальных тестов синка.
 *
 * Вместо сети отвечает OkHttp-интерсептор; всё остальное — прод-код. [EasTransport] шлёт настоящий
 * WBXML, фейк декодирует его РЕАЛЬНЫМ [WbxmlParser.parse], а ответ кодирует РЕАЛЬНЫМ
 * [WbxmlParser.generate]. Транспорт читает байты, декодирует и отдаёт XML парсерам сервисов — как
 * с живым сервером. EWS идёт тем же клиентом (Basic → NTLM), ответы — SOAP-текст.
 *
 * Маршрутизация — функцией [route]: по команде и телу запроса тест решает, что ответить.
 */
internal class FakeExchange(private val route: (Call) -> Reply) {

    /** Запрос к фейку: EAS `Cmd` или EWS SOAPAction, тело — как XML (EAS — после WBXML-декода). */
    data class Call(
        val command: String,
        val xml: String,
        val isEws: Boolean,
        val url: String = "",
        val contentType: String = ""
    ) {
        val syncKey: String? get() = SYNC_KEY.find(xml)?.groupValues?.get(1)
        val collectionId: String? get() = COLLECTION_ID.find(xml)?.groupValues?.get(1)
        val windowSize: Int? get() = WINDOW_SIZE.find(xml)?.groupValues?.get(1)?.toIntOrNull()
        /** EWS FindItem: смещение IndexedPageItemView. */
        val offset: Int get() = OFFSET.find(xml)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        /** EWS GetItem: запрошенные ItemId по порядку. */
        val itemIds: List<String> get() = ITEM_ID.findAll(xml).map { it.groupValues[1] }.toList()
    }

    sealed class Reply {
        /** 200 + WBXML, закодированный реальным генератором из [xml] (теги с префиксами code page). */
        data class Wbxml(val xml: String) : Reply()
        /** 200 + произвольные байты, например оборванный WBXML. */
        class RawBytes(val bytes: ByteArray) : Reply()
        /** 200 + SOAP/текст (EWS или страница прокси). */
        data class Text(val body: String) : Reply()
        /** HTTP-ошибка без тела. */
        data class Http(val code: Int) : Reply()
        /** Обрыв соединения: IOException из сетевого слоя. */
        object Disconnect : Reply()
    }

    private val wbxml = WbxmlParser()

    /** Все запросы, дошедшие до «сервера» (включая повторы Basic → NTLM). */
    val calls: MutableList<Call> = java.util.Collections.synchronizedList(mutableListOf())

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain -> answer(chain) })
        .build()

    private fun answer(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = Buffer().also { request.body?.writeTo(it) }.readByteArray()
        val isEws = request.url.encodedPath.endsWith("/EWS/Exchange.asmx")
        val call = if (isEws) {
            Call(
                command = request.header("SOAPAction").orEmpty().trim('"').substringAfterLast('/'),
                xml = String(body, Charsets.UTF_8),
                isEws = true
            )
        } else {
            // EAS 12.x SendMail/SmartForward: тело — MIME (message/rfc822), не WBXML
            val contentType = request.body?.contentType()?.toString().orEmpty()
            val raw = contentType.startsWith("message/rfc822")
            Call(
                command = request.url.queryParameter("Cmd").orEmpty(),
                xml = if (raw) String(body, Charsets.UTF_8) else wbxml.parse(body),
                isEws = false,
                url = request.url.toString(),
                contentType = contentType
            )
        }
        calls += call

        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        return when (val reply = route(call)) {
            is Reply.Wbxml -> builder.code(200).message("OK")
                .body(wbxml.generate(reply.xml).toResponseBody(WBXML)).build()
            is Reply.RawBytes -> builder.code(200).message("OK")
                .body(reply.bytes.toResponseBody(WBXML)).build()
            is Reply.Text -> builder.code(200).message("OK")
                .body(reply.body.toResponseBody(TEXT_XML)).build()
            is Reply.Http -> builder.code(reply.code).message("Error")
                .body(ByteArray(0).toResponseBody(TEXT_XML)).build()
            Reply.Disconnect -> throw IOException("Connection reset by peer (fake)")
        }
    }

    /** Транспорт, собранный как в EasClient: один OkHttp-клиент на EAS и EWS. */
    fun transport(easVersion: String): EasTransport {
        val ntlm = NtlmAuthenticator(DOMAIN, USER, PASSWORD)
        val ews = EwsClient(
            ewsUrl = "$BASE_URL/EWS/Exchange.asmx",
            username = USER,
            password = PASSWORD,
            domain = DOMAIN,
            httpClient = client,
            ntlmAuth = ntlm
        )
        return EasTransport(
            client = client,
            wbxmlParser = WbxmlParser(),
            normalizedServerUrl = BASE_URL,
            username = USER,
            password = PASSWORD,
            domain = DOMAIN,
            deviceId = "androidtestdevice01",
            deviceType = "Android",
            ntlmAuth = ntlm,
            ewsClient = ews
        ).also { it.easVersion = easVersion }
    }

    companion object {
        const val BASE_URL = "https://mail.example.test"
        private const val DOMAIN = "CORP"
        private const val USER = "user"
        private const val PASSWORD = "secret"

        private val WBXML = "application/vnd.ms-sync.wbxml".toMediaType()
        private val TEXT_XML = "text/xml; charset=utf-8".toMediaType()

        private val SYNC_KEY = Regex("<SyncKey>(.*?)</SyncKey>")
        private val COLLECTION_ID = Regex("<CollectionId>(.*?)</CollectionId>")
        private val WINDOW_SIZE = Regex("<WindowSize>(\\d+)</WindowSize>")
        private val OFFSET = Regex("""Offset="(\d+)"""")
        private val ITEM_ID = Regex("""<t:ItemId Id="([^"]+)"""")

        /** Реальный WBXML ответа, обрезанный до [keepBytes] байт — как при обрыве потока. */
        fun truncatedWbxml(xml: String, keepBytes: Int): ByteArray {
            val full = WbxmlParser().generate(xml)
            require(keepBytes in 1 until full.size) { "keepBytes=$keepBytes вне (0, ${full.size})" }
            return full.copyOf(keepBytes)
        }
    }
}

/** [EasTasksService], собранный как в EasClient, поверх транспорта фейка; папка задач — [tasksFolderId]. */
internal fun FakeExchange.tasksService(easVersion: String, tasksFolderId: String): EasTasksService {
    val transport = transport(easVersion)
    return EasTasksService(EasTasksService.TasksServiceDependencies(
        executeEasCommand = object : EasTasksService.EasCommandExecutor {
            override suspend fun <T> invoke(command: String, xml: String, parser: (String) -> T): EasResult<T> =
                transport.executeEasCommand(command, xml, parser = parser)
        },
        folderSync = { EasResult.Success(FolderSyncResponse("1", listOf(EasFolder(tasksFolderId, "Tasks", "0", FolderType.TASKS)))) },
        extractValue = { xml, tag -> XmlUtils.extractTagValue(xml, tag) },
        escapeXml = { XmlUtils.escape(it) },
        getEasVersion = { transport.easVersion },
        isVersionDetected = { true },
        detectEasVersion = { EasResult.Success(transport.easVersion) },
        getTasksFolderId = { tasksFolderId },
        performNtlmHandshake = { transport.performNtlmHandshake() },
        executeNtlmRequest = { request, action -> transport.executeNtlmRequest(request, action) },
        tryBasicAuthEws = { request, action -> transport.executeEwsWithAuth(request, action) },
        sendMail = { _, _, _, _, _, _ -> EasResult.Error("unused") }
    ))
}

/** Ответы EAS Sync в «серверном» виде: префиксы code page, их кодирует реальный генератор WBXML. */
internal object EasSyncXml {
    private const val ROOT =
        """<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:tasks="Tasks" xmlns:notes="Notes" xmlns:email="Email" xmlns:contacts="Contacts">"""

    /** Ответ на первичный Sync (SyncKey=0): только новый ключ. */
    fun initial(syncKey: String, collectionId: String): String =
        """<?xml version="1.0" encoding="utf-8"?>$ROOT<Collections><Collection><SyncKey>$syncKey</SyncKey>""" +
            """<CollectionId>$collectionId</CollectionId><Status>1</Status></Collection></Collections></Sync>"""

    /** Страница выдачи: команды Add и, при [more], MoreAvailable. */
    fun page(syncKey: String, collectionId: String, adds: List<String>, more: Boolean): String =
        """<?xml version="1.0" encoding="utf-8"?>$ROOT<Collections><Collection><SyncKey>$syncKey</SyncKey>""" +
            """<CollectionId>$collectionId</CollectionId><Status>1</Status>""" +
            (if (more) "<MoreAvailable/>" else "") +
            (if (adds.isEmpty()) "" else "<Commands>${adds.joinToString("")}</Commands>") +
            "</Collection></Collections></Sync>"

    /** Ответ с ошибкой коллекции (MS-ASCMD Sync Status ≠ 1). */
    fun status(status: Int, collectionId: String): String =
        """<?xml version="1.0" encoding="utf-8"?>$ROOT<Collections><Collection>""" +
            """<CollectionId>$collectionId</CollectionId><Status>$status</Status></Collection></Collections></Sync>"""

    fun task(serverId: String, subject: String): String =
        "<Add><ServerId>$serverId</ServerId><ApplicationData><tasks:Subject>$subject</tasks:Subject>" +
            "<tasks:Importance>1</tasks:Importance></ApplicationData></Add>"

    fun note(serverId: String, subject: String, body: String, messageClass: String = "IPM.StickyNote"): String =
        "<Add><ServerId>$serverId</ServerId><ApplicationData><notes:Subject>$subject</notes:Subject>" +
            "<airsyncbase:Body><airsyncbase:Type>1</airsyncbase:Type><airsyncbase:Data>$body</airsyncbase:Data></airsyncbase:Body>" +
            "<notes:MessageClass>$messageClass</notes:MessageClass></ApplicationData></Add>"

    fun contact(serverId: String, fileAs: String, email: String): String =
        "<Add><ServerId>$serverId</ServerId><ApplicationData><contacts:FileAs>$fileAs</contacts:FileAs>" +
            "<contacts:Email1Address>$email</contacts:Email1Address></ApplicationData></Add>"

    fun mail(serverId: String, subject: String): String =
        "<Add><ServerId>$serverId</ServerId><ApplicationData><email:Subject>$subject</email:Subject>" +
            "<email:MessageClass>IPM.Note</email:MessageClass></ApplicationData></Add>"
}

/** SOAP-ответы EWS в форме Exchange 2007 SP1 (префиксы m:/t:, по сообщению на элемент в GetItem). */
internal object EwsXml {
    private const val NS =
        """xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages" xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types""""

    private fun envelope(body: String): String =
        """<?xml version="1.0" encoding="utf-8"?><soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">""" +
            """<soap:Header><t:ServerVersionInfo xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types" """ +
            """MajorVersion="8" MinorVersion="1" MajorBuildNumber="240" MinorBuildNumber="6"/></soap:Header>""" +
            "<soap:Body>$body</soap:Body></soap:Envelope>"

    /** FindItem: [itemsXml] — элементы страницы; [nextOffset] — IndexedPagingOffset следующей страницы. */
    fun findItem(itemsXml: String, total: Int, includesLast: Boolean, nextOffset: Int? = null): String {
        val paging = nextOffset?.let { """IndexedPagingOffset="$it" """ }.orEmpty()
        return envelope(
            """<m:FindItemResponse $NS><m:ResponseMessages><m:FindItemResponseMessage ResponseClass="Success">""" +
                """<m:ResponseCode>NoError</m:ResponseCode><m:RootFolder ${paging}TotalItemsInView="$total" """ +
                """IncludesLastItemInRange="$includesLast"><t:Items>$itemsXml</t:Items></m:RootFolder>""" +
                "</m:FindItemResponseMessage></m:ResponseMessages></m:FindItemResponse>"
        )
    }

    /** Ошибка операции (HTTP 200, ResponseClass="Error") — так EWS сообщает, например, ErrorServerBusy. */
    fun error(operation: String, code: String): String = envelope(
        """<m:${operation}Response $NS><m:ResponseMessages><m:${operation}ResponseMessage ResponseClass="Error">""" +
            """<m:MessageText>$code</m:MessageText><m:ResponseCode>$code</m:ResponseCode>""" +
            "<m:DescriptiveLinkKey>0</m:DescriptiveLinkKey></m:${operation}ResponseMessage></m:ResponseMessages>" +
            "</m:${operation}Response>"
    )

    /** Успех операции (ResponseClass="Success", NoError), например UpdateItem. */
    fun success(operation: String, itemsXml: String = ""): String = envelope(
        """<m:${operation}Response $NS><m:ResponseMessages><m:${operation}ResponseMessage ResponseClass="Success">""" +
            "<m:ResponseCode>NoError</m:ResponseCode><m:Items>$itemsXml</m:Items></m:${operation}ResponseMessage>" +
            "</m:ResponseMessages></m:${operation}Response>"
    )

    /** GetItem: по сообщению на каждый элемент; null в списке — ошибка ErrorItemNotFound для элемента. */
    fun getItem(items: List<String?>): String = envelope(
        """<m:GetItemResponse $NS><m:ResponseMessages>""" +
            items.joinToString("") { item ->
                if (item != null) {
                    """<m:GetItemResponseMessage ResponseClass="Success"><m:ResponseCode>NoError</m:ResponseCode>""" +
                        "<m:Items>$item</m:Items></m:GetItemResponseMessage>"
                } else {
                    """<m:GetItemResponseMessage ResponseClass="Error"><m:MessageText>The specified object was not """ +
                        "found in the store.</m:MessageText><m:ResponseCode>ErrorItemNotFound</m:ResponseCode>" +
                        "<m:DescriptiveLinkKey>0</m:DescriptiveLinkKey><m:Items/></m:GetItemResponseMessage>"
                }
            } +
            "</m:ResponseMessages></m:GetItemResponse>"
    )

    fun findFolder(foldersXml: String = "", includesLast: Boolean = true): String = envelope(
        """<m:FindFolderResponse $NS><m:ResponseMessages><m:FindFolderResponseMessage ResponseClass="Success">""" +
            """<m:ResponseCode>NoError</m:ResponseCode><m:RootFolder TotalItemsInView="0" """ +
            """IncludesLastItemInRange="$includesLast"><t:Folders>$foldersXml</t:Folders></m:RootFolder>""" +
            "</m:FindFolderResponseMessage></m:ResponseMessages></m:FindFolderResponse>"
    )

    fun getFolder(): String = envelope(
        """<m:GetFolderResponse $NS><m:ResponseMessages><m:GetFolderResponseMessage ResponseClass="Success">""" +
            """<m:ResponseCode>NoError</m:ResponseCode><m:Folders><t:TasksFolder><t:FolderId Id="F-TASKS" ChangeKey="AQ=="/>""" +
            "<t:DisplayName>Tasks</t:DisplayName><t:TotalCount>0</t:TotalCount><t:ChildFolderCount>0</t:ChildFolderCount>" +
            "</t:TasksFolder></m:Folders></m:GetFolderResponseMessage></m:ResponseMessages></m:GetFolderResponse>"
    )

    /** Элемент FindItem с IdOnly. */
    fun idOnlyTask(id: String): String = """<t:Task><t:ItemId Id="$id" ChangeKey="CK-$id"/></t:Task>"""

    /** Задача из GetItem с AllProperties. */
    fun task(id: String, subject: String): String =
        """<t:Task><t:ItemId Id="$id" ChangeKey="CK-$id"/><t:ItemClass>IPM.Task</t:ItemClass><t:Subject>$subject</t:Subject>""" +
            """<t:Body BodyType="Text">Body of $subject</t:Body><t:Importance>Normal</t:Importance>""" +
            "<t:Status>NotStarted</t:Status></t:Task>"

    /** Заметка (IPM.StickyNote приходит как t:Message); [body] = null — как в FindItem, без тела. */
    fun note(id: String, subject: String, body: String? = null, itemClass: String = "IPM.StickyNote"): String =
        """<t:Message><t:ItemId Id="$id" ChangeKey="CK-$id"/><t:ItemClass>$itemClass</t:ItemClass>""" +
            "<t:Subject>$subject</t:Subject>" +
            (body?.let { """<t:Body BodyType="Text">$it</t:Body>""" } ?: "") +
            "</t:Message>"

    fun folder(id: String, folderClass: String, name: String): String =
        """<t:Folder><t:FolderId Id="$id" ChangeKey="AQ=="/><t:FolderClass>$folderClass</t:FolderClass>""" +
            "<t:DisplayName>$name</t:DisplayName><t:TotalCount>1</t:TotalCount></t:Folder>"

    /** GetUserConfiguration (Exchange 2010+): объект конфигурации с XmlData (base64). */
    fun userConfiguration(name: String, xmlDataBase64: String): String = envelope(
        """<m:GetUserConfigurationResponse $NS><m:ResponseMessages>""" +
            """<m:GetUserConfigurationResponseMessage ResponseClass="Success"><m:ResponseCode>NoError</m:ResponseCode>""" +
            """<m:UserConfiguration><t:UserConfigurationName Name="$name"><t:DistinguishedFolderId Id="calendar"/>""" +
            "</t:UserConfigurationName><t:XmlData>$xmlDataBase64</t:XmlData></m:UserConfiguration>" +
            "</m:GetUserConfigurationResponseMessage></m:ResponseMessages></m:GetUserConfigurationResponse>"
    )
}

/** [EasCalendarService], собранный как в EasClient, поверх транспорта фейка; папка календаря — [calendarFolderId]. */
internal fun FakeExchange.calendarService(
    easVersion: String,
    calendarFolderId: String,
    parseEasDate: (String?) -> Long? = { null }
): EasCalendarService {
    val transport = transport(easVersion)
    return EasCalendarService(EasCalendarService.CalendarServiceDependencies(
        executeEasCommand = object : EasCalendarService.EasCommandExecutor {
            override suspend fun <T> invoke(command: String, xml: String, parser: (String) -> T): EasResult<T> =
                transport.executeEasCommand(command, xml, parser = parser)
        },
        folderSync = {
            EasResult.Success(FolderSyncResponse("1", listOf(EasFolder(calendarFolderId, "Calendar", "0", FolderType.CALENDAR))))
        },
        refreshSyncKey = { _, key -> EasResult.Success(key) },
        extractValue = { xml, tag -> XmlUtils.extractTagValue(xml, tag) },
        escapeXml = { XmlUtils.escape(it) },
        getEasVersion = { transport.easVersion },
        isVersionDetected = { true },
        detectEasVersion = { EasResult.Success(transport.easVersion) },
        performNtlmHandshake = { transport.performNtlmHandshake() },
        executeNtlmRequest = { request, action -> transport.executeNtlmRequest(request, action) },
        tryBasicAuthEws = { request, action -> transport.executeEwsWithAuth(request, action) },
        parseEasDate = parseEasDate
    ))
}
