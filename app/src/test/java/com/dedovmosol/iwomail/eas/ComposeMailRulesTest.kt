package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ComposeMailRulesTest {

    @Test
    fun `success is no status, 1 or 118`() {
        assertThat(isComposeMailSuccess(null)).isTrue()
        assertThat(isComposeMailSuccess("1")).isTrue()
        assertThat(isComposeMailSuccess("118")).isTrue()
        assertThat(isComposeMailSuccess("120")).isFalse()
        assertThat(isComposeMailSuccess("110")).isFalse()
    }

    @Test
    fun `forward query encodes the original message and saves a copy`() {
        assertThat(smartForwardQuery("5", "5:12")).isEqualTo("&CollectionId=5&ItemId=5%3A12&SaveInSent=T")
    }

    @Test
    fun `any one field with a recipient is enough`() {
        assertThat(recipientsError("a@b.cd", "", "")).isNull()
        assertThat(recipientsError("", "a@b.cd", "")).isNull()
        assertThat(recipientsError("", "", "a@b.cd")).isNull()
        assertThat(recipientsError("a@b.cd; c@d.ef", "g@h.ij, k@l.mn", "")).isNull()
    }

    @Test
    fun `no recipients is an error`() {
        assertThat(recipientsError("", " ", ";,")).isEqualTo("Неверный адрес получателя: нет получателей")
    }

    @Test
    fun `invalid address in any field is named`() {
        assertThat(recipientsError("a@b.cd", "", "not-an-address")).isEqualTo("Неверный адрес получателя: not-an-address")
        assertThat(recipientsError("a@b", "", "")).isEqualTo("Неверный адрес получателя: a@b")
    }
}
