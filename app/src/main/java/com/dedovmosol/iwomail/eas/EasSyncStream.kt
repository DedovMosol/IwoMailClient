package com.dedovmosol.iwomail.eas

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/**
 * Потоковые примитивы разбора EAS Sync (MS-ASCMD) для парсеров классов: Calendar, Notes, Tasks, Contacts.
 *
 * Вход — вывод [WbxmlParser.parse]: голые теги, поэтому принадлежность поля задаётся только позицией
 * в дереве (имена сравниваются без namespace-префикса — клиентский XML префиксный). Один проход
 * [XmlPullParser] по всему ответу, каждое поле читается один раз. Прежний разбор «поле за полем»
 * (новый парсер на каждое поле поверх пересобранного inner-XML) проходил крупное тело и фото контакта
 * десятки раз.
 *
 * Контракт хелперов: парсер стоит на START_TAG элемента и выходит на его END_TAG.
 * Оборванный или битый документ — исключение, а не «часть элементов»: страница с Status=1 без
 * MoreAvailable иначе сошла бы за полную, а полный снимок разрешает удалять локальные данные.
 * Вызовы идут внутри parser-лямбды транспорта, исключение там становится EasResult.Error.
 */
internal object EasSyncStream {

    /** Команды выдачи сервера, которые несут данные элемента. */
    val ADD_CHANGE: Set<String> = setOf("Add", "Change")

    /** Имя тега без namespace-префикса. */
    fun localName(name: String): String = name.substringAfter(':')

    /**
     * Обходит элементы `Commands/<команда>` из [commands] в документном порядке. `Responses/Add`
     * (ответы на команды клиента, без ApplicationData) не затрагиваются. [onItem] получает имя
     * команды; парсер стоит на её START_TAG, [onItem] обязан дочитать элемент до END_TAG.
     */
    inline fun forEachCommand(xml: String, commands: Set<String>, onItem: (command: String, p: XmlPullParser) -> Unit) {
        val p = EasXmlParser.createParser(xml) // единая усиленная фабрика (DOCTYPE/XXE отключены)
        var commandsDepth = -1
        var type = p.eventType
        while (type != XmlPullParser.END_DOCUMENT) {
            when (type) {
                XmlPullParser.START_TAG -> {
                    val name = localName(p.name)
                    when {
                        commandsDepth < 0 && name == "Commands" -> commandsDepth = p.depth
                        commandsDepth > 0 && p.depth == commandsDepth + 1 && name in commands -> onItem(name, p)
                    }
                }
                XmlPullParser.END_TAG -> if (p.depth == commandsDepth) commandsDepth = -1
            }
            type = p.next()
        }
        // KXmlParser на обрыве молча выдаёт END_DOCUMENT с открытыми тегами (проверено эмпирически).
        if (p.depth > 0) throw XmlPullParserException("Truncated EAS Sync response: unclosed elements at depth ${p.depth}")
    }

    /** Полный текст элемента (вся вложенность), сущности развёрнуты; trim; пустой → null. */
    fun leafText(p: XmlPullParser): String? {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> sb.append(p.text)
                XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Unexpected end of document")
            }
        }
        return sb.toString().trim().ifEmpty { null }
    }

    /** Обходит ПРЯМЫХ детей текущего элемента; [onChild] получает имя без префикса и обязан дочитать ребёнка. */
    inline fun children(p: XmlPullParser, onChild: (String) -> Unit) {
        val parentDepth = p.depth
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> onChild(localName(p.name))
                XmlPullParser.END_TAG -> if (p.depth == parentDepth) return
                XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Unexpected end of document")
            }
        }
    }

    /** Карта «имя прямого ребёнка → текст» (первое вхождение); вложенные контейнеры пропускаются. */
    fun leafMap(p: XmlPullParser): Map<String, String> {
        val map = HashMap<String, String>()
        children(p) { name ->
            val value = leafText(p)
            if (value != null && name !in map) map[name] = value
        }
        return map
    }

    /**
     * `<Body>`: текст `Data` (AirSyncBase: `<Body><Type/><Data>…</Data></Body>`), иначе собственный
     * текст элемента (EAS 2.5: тело прямо в `<Body>`). Прочие дети (`Type`, `EstimatedDataSize`…)
     * пропускаются. Значение тримится.
     */
    fun readBody(p: XmlPullParser): String? {
        if (p.isEmptyElementTag) { p.next(); return null }
        val parentDepth = p.depth
        val ownText = StringBuilder()
        var data: String? = null
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG ->
                    if (localName(p.name) == "Data" && data == null) data = leafText(p) else EasXmlParser.skip(p)
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> ownText.append(p.text)
                XmlPullParser.END_TAG -> if (p.depth == parentDepth) break
                XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Unexpected end of document")
            }
        }
        return data ?: ownText.toString().trim().ifEmpty { null }
    }

    /** `<Categories><Category>…</Category>…</Categories>`: непустые Category по порядку. */
    fun readCategories(p: XmlPullParser): List<String> {
        val result = mutableListOf<String>()
        children(p) { name -> if (name == "Category") leafText(p)?.let { result.add(it) } else EasXmlParser.skip(p) }
        return result
    }

    /**
     * Элемент выдачи: [serverId] — прямой ребёнок `ServerId` (null, если его нет); поля —
     * прямые дети `ApplicationData`.
     */
    class SyncItem(
        val serverId: String?,
        /** Тексты запрошенных листьев (первое вхождение). */
        val fields: Map<String, String>,
        /** [readBody] первого `Body`; null — тела нет или оно не запрошено. */
        val body: String?,
        /** [readCategories] первого `Categories`; пусто — нет или не запрошены. */
        val categories: List<String>
    )

    /**
     * Читает элемент `Add|Change`. Из `ApplicationData` берутся только запрошенные листья [leaves],
     * `Body` (при [withBody]) и `Categories` (при [withCategories]). Остальное пропускается без
     * накопления текста: фото контакта, вложенные структуры.
     * null — в элементе нет `ApplicationData`.
     */
    fun readItem(p: XmlPullParser, leaves: Set<String>, withBody: Boolean, withCategories: Boolean): SyncItem? {
        var serverId: String? = null
        var hasData = false
        val fields = HashMap<String, String>()
        var body: String? = null
        var categories: List<String>? = null
        children(p) { name ->
            when {
                name == "ServerId" && serverId == null -> serverId = leafText(p)
                name == "ApplicationData" && !hasData -> {
                    hasData = true
                    children(p) { field ->
                        when {
                            field == "Body" && withBody -> readBody(p).let { if (body == null) body = it }
                            field == "Categories" && withCategories ->
                                readCategories(p).let { if (categories == null) categories = it }
                            field in leaves -> leafText(p)?.let { if (field !in fields) fields[field] = it }
                            else -> EasXmlParser.skip(p)
                        }
                    }
                }
                else -> EasXmlParser.skip(p)
            }
        }
        if (!hasData) return null
        return SyncItem(serverId, fields, body, categories.orEmpty())
    }
}
