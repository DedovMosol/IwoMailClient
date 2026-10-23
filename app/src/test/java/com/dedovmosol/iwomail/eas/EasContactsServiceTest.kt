package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Характеризация разбора ответов [EasContactsService] ПЕРЕД миграцией regex → [EasXmlParser].
 *
 * Тестируются чистые парсеры `parseContactsSyncResponse` (EAS Sync папки Contacts) и
 * `parseGalSearchResponse` (GAL Search) на реально-подобных WBXML→XML-ответах (голые теги, как
 * выдаёт [WbxmlParser.parse]). Это контракт эквивалентности: тесты зелёные на СТАРОМ regex-коде,
 * миграция, те же тесты зелёные ⇒ разбор для Exchange 2007 SP2…2013 не изменился.
 *
 * Robolectric: после миграции парсеры используют EasXmlParser (android.util.Xml).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EasContactsServiceTest {

    // Парсеры не зовут сеть; executeCommand/folderSync/getEasVersion — заглушки.
    // extractValue инжектируем РЕАЛЬНЫЙ (XmlUtils) — так parseGalSearchResponse характеризуется
    // как в проде (его проверка Status идёт через инжектированный extractValue на старом коде).
    private fun service() = EasContactsService(
        executeCommand = { _, _, _ -> EasResult.Success(emptyList()) },
        folderSync = { EasResult.Error("unused in parse tests") },
        extractValue = { xml, tag -> XmlUtils.extractTagValue(xml, tag) },
        getEasVersion = { "12.1" }
    )

    // ───────────────────────── Contacts Sync ─────────────────────────

    @Test
    fun `parseContactsSyncResponse maps fields, fallbacks and extracts email`() {
        // Голые теги (как после WBXML-декодирования). Контакт 1 — FileAs + Business-телефон +
        // Email в RFC2822 с экранированными <>. Контакт 2 — без FileAs (fallback на DisplayName),
        // Home-телефон (fallback). Контакт 3 — ни имени, ни email → пропускается.
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync><Collections><Collection><SyncKey>5</SyncKey><CollectionId>9</CollectionId><Status>1</Status>
<Commands>
<Add><ServerId>9:1</ServerId><ApplicationData>
<FileAs>Ivanov, Ivan</FileAs><FirstName>Ivan</FirstName><LastName>Ivanov</LastName>
<Email1Address>"Ivan Ivanov" &lt;ivan@contoso.com&gt;</Email1Address>
<CompanyName>Contoso</CompanyName><JobTitle>Engineer</JobTitle>
<BusinessPhoneNumber>+1-555-0100</BusinessPhoneNumber><MobilePhoneNumber>+1-555-0199</MobilePhoneNumber>
</ApplicationData></Add>
<Add><ServerId>9:2</ServerId><ApplicationData>
<DisplayName>Petrov Petr</DisplayName><Email1Address>petr@contoso.com</Email1Address>
<HomePhoneNumber>+1-555-0200</HomePhoneNumber>
</ApplicationData></Add>
<Add><ServerId>9:3</ServerId><ApplicationData><FirstName>Ghost</FirstName></ApplicationData></Add>
</Commands></Collection></Collections></Sync>""".trimIndent()

        val contacts = service().parseContactsSyncResponse(xml)

        assertThat(contacts.map { it.easServerId }).containsExactly("9:1", "9:2").inOrder()
        val c1 = contacts[0]
        assertThat(c1.displayName).isEqualTo("Ivanov, Ivan")
        assertThat(c1.email).isEqualTo("ivan@contoso.com") // извлечён из RFC2822 <...>
        assertThat(c1.firstName).isEqualTo("Ivan")
        assertThat(c1.lastName).isEqualTo("Ivanov")
        assertThat(c1.company).isEqualTo("Contoso")
        assertThat(c1.jobTitle).isEqualTo("Engineer")
        assertThat(c1.phone).isEqualTo("+1-555-0100")      // Business
        assertThat(c1.mobilePhone).isEqualTo("+1-555-0199")
        val c2 = contacts[1]
        assertThat(c2.displayName).isEqualTo("Petrov Petr") // fallback на DisplayName
        assertThat(c2.email).isEqualTo("petr@contoso.com")
        assertThat(c2.phone).isEqualTo("+1-555-0200")       // fallback на Home
    }

    @Test
    fun `parseContactsSyncResponse skips contact photos and keeps the fields around them`() {
        // Реальный WBXML round-trip: фото (base64) до и после полей, пустое фото, контакт без фото.
        val wbxml = WbxmlParser()
        val photo = "QUJD".repeat(2_000)
        val sent = """<?xml version="1.0" encoding="utf-8"?><Sync xmlns="AirSync" xmlns:contacts="Contacts">""" +
            "<Collections><Collection><SyncKey>5</SyncKey><CollectionId>9</CollectionId><Status>1</Status><Commands>" +
            "<Add><ServerId>9:1</ServerId><ApplicationData><contacts:Picture>$photo</contacts:Picture>" +
            "<contacts:FileAs>Ivanov</contacts:FileAs><contacts:Email1Address>ivan@corp.local</contacts:Email1Address>" +
            "<contacts:Picture>$photo</contacts:Picture><contacts:MobilePhoneNumber>+7 900</contacts:MobilePhoneNumber></ApplicationData></Add>" +
            "<Add><ServerId>9:2</ServerId><ApplicationData><contacts:Picture/><contacts:FileAs>Petrov</contacts:FileAs></ApplicationData></Add>" +
            "<Add><ServerId>9:3</ServerId><ApplicationData><contacts:FileAs>Sidorov</contacts:FileAs></ApplicationData></Add>" +
            "</Commands></Collection></Collections></Sync>"
        val decoded = wbxml.parse(wbxml.generate(sent))
        assertThat(decoded).contains("<Picture>")

        val contacts = service().parseContactsSyncResponse(decoded)

        assertThat(contacts.map { it.easServerId }).containsExactly("9:1", "9:2", "9:3").inOrder()
        assertThat(contacts[0].displayName).isEqualTo("Ivanov")
        assertThat(contacts[0].email).isEqualTo("ivan@corp.local")
        assertThat(contacts[0].mobilePhone).isEqualTo("+7 900")
        assertThat(contacts[1].displayName).isEqualTo("Petrov")
        assertThat(contacts[2].displayName).isEqualTo("Sidorov")
    }

    @Test
    fun `parseContactsSyncResponse rejects a response cut inside a photo`() {
        val xml = "<Sync><Collections><Collection><SyncKey>5</SyncKey><Status>1</Status><Commands>" +
            "<Add><ServerId>9:1</ServerId><ApplicationData><FileAs>Ivanov</FileAs><Picture>QUJDQUJD"

        assertThrows(org.xmlpull.v1.XmlPullParserException::class.java) { service().parseContactsSyncResponse(xml) }
    }

    @Test
    fun `parseContactsSyncResponse returns empty for response without Add`() {
        val xml = "<Sync><Collections><Collection><SyncKey>5</SyncKey><Status>1</Status></Collection></Collections></Sync>"
        assertThat(service().parseContactsSyncResponse(xml)).isEmpty()
    }

    // ───────────────────────── GAL Search ─────────────────────────

    @Test
    fun `parseGalSearchResponse maps fields and extracts email`() {
        // GAL: department ← Office, jobTitle ← Title (иное отображение, чем у Contacts).
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<Search><Status>1</Status><Response><Store><Status>1</Status>
<Result><Properties>
<DisplayName>Ivan Ivanov</DisplayName><EmailAddress>ivan@contoso.com</EmailAddress>
<FirstName>Ivan</FirstName><LastName>Ivanov</LastName><Company>Contoso</Company>
<Office>HQ</Office><Title>Engineer</Title><Phone>+1-555-0100</Phone>
<MobilePhone>+1-555-0199</MobilePhone><Alias>iivanov</Alias>
</Properties></Result>
<Result><Properties>
<DisplayName>Petr Petrov</DisplayName><EmailAddress>petr@contoso.com</EmailAddress>
</Properties></Result>
</Store></Response></Search>""".trimIndent()

        val contacts = service().parseGalSearchResponse(xml)

        assertThat(contacts.map { it.displayName }).containsExactly("Ivan Ivanov", "Petr Petrov").inOrder()
        val c1 = contacts[0]
        assertThat(c1.email).isEqualTo("ivan@contoso.com")
        assertThat(c1.firstName).isEqualTo("Ivan")
        assertThat(c1.lastName).isEqualTo("Ivanov")
        assertThat(c1.company).isEqualTo("Contoso")
        assertThat(c1.department).isEqualTo("HQ")       // ← Office
        assertThat(c1.jobTitle).isEqualTo("Engineer")   // ← Title
        assertThat(c1.phone).isEqualTo("+1-555-0100")
        assertThat(c1.mobilePhone).isEqualTo("+1-555-0199")
        assertThat(c1.alias).isEqualTo("iivanov")
        assertThat(contacts[1].email).isEqualTo("petr@contoso.com")
    }

    @Test
    fun `parseGalSearchResponse returns empty when Status is not 1`() {
        val xml = """<Search><Status>2</Status><Response><Store><Result><Properties>
<DisplayName>Ignored</DisplayName><EmailAddress>x@contoso.com</EmailAddress>
</Properties></Result></Store></Response></Search>""".trimIndent()
        assertThat(service().parseGalSearchResponse(xml)).isEmpty()
    }
}
