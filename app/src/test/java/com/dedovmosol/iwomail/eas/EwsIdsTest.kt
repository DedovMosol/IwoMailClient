package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * EWS-1: устойчивый разбор ItemId/ChangeKey — независимо от порядка атрибутов и префикса,
 * с сохранением поведения на реальном выводе Exchange 2007 SP2.
 */
class EwsIdsTest {

    @Test
    fun standardOrder_tPrefix_asOn2007() {
        val xml = """<t:ItemId Id="AAA" ChangeKey="CK1"/>"""
        assertThat(EwsIds.parseItemIdAndChangeKey(xml)).isEqualTo("AAA" to "CK1")
    }

    @Test
    fun reversedAttributeOrder_nowSupported() {
        val xml = """<t:ItemId ChangeKey="CK1" Id="AAA"/>"""
        assertThat(EwsIds.parseItemIdAndChangeKey(xml)).isEqualTo("AAA" to "CK1")
    }

    @Test
    fun prefixOptionalAndAlternate() {
        assertThat(EwsIds.parseItemId("""<ItemId Id="X"/>""")).isEqualTo("X")
        assertThat(EwsIds.parseItemId("""<m:ItemId Id="Y"/>""")).isEqualTo("Y")
    }

    @Test
    fun base64ValuesWithSlashPlusEquals() {
        val xml = """<t:ItemId Id="AAMk/ADn+a==" ChangeKey="CQAA/BBB=="/>"""
        assertThat(EwsIds.parseItemIdAndChangeKey(xml)).isEqualTo("AAMk/ADn+a==" to "CQAA/BBB==")
    }

    @Test
    fun missingChangeKey_pairNull_idStillParsed() {
        val xml = """<t:ItemId Id="AAA"/>"""
        assertThat(EwsIds.parseItemIdAndChangeKey(xml)).isNull()
        assertThat(EwsIds.parseItemId(xml)).isEqualTo("AAA")
    }

    @Test
    fun doesNotMatchReferenceItemId() {
        assertThat(EwsIds.parseItemId("""<t:ReferenceItemId Id="R" ChangeKey="CK"/>""")).isNull()
    }

    @Test
    fun noItemId_returnsNull() {
        assertThat(EwsIds.parseItemId("<m:GetItemResponse/>")).isNull()
        assertThat(EwsIds.parseItemIdAndChangeKey("<m:GetItemResponse/>")).isNull()
    }

    @Test
    fun parseAll_multipleItems_inOrder() {
        val xml = """<t:ItemId Id="A" ChangeKey="CA"/><t:ItemId ChangeKey="CB" Id="B"/>"""
        assertThat(EwsIds.parseAllItemIdsAndChangeKeys(xml))
            .containsExactly("A" to "CA", "B" to "CB").inOrder()
    }
}
