package com.dedovmosol.iwomail.ui.screens.compose

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OutgoingBodyTest {

    @Test
    fun `trailing empty editor lines are removed`() {
        assertThat(cleanOutgoingBody("<p>Hi</p><div><br></div><div>&nbsp;</div>\n")).isEqualTo("<p>Hi</p>")
        assertThat(cleanOutgoingBody("<p>Hi</p><br><br/> \n")).isEqualTo("<p>Hi</p>")
        assertThat(cleanOutgoingBody("<div>Hi</div><p><br></p>")).isEqualTo("<div>Hi</div>")
    }

    @Test
    fun `empty lines in the middle are kept`() {
        assertThat(cleanOutgoingBody("<p>A</p><div><br></div><p>B</p>")).isEqualTo("<p>A</p><div><br></div><p>B</p>")
    }

    @Test
    fun `non-HTML angle-bracket fragments are removed, tags and comments are kept`() {
        assertThat(cleanOutgoingBody("<p>Hi <a@b.cd></p><!-- c --><br/>x")).isEqualTo("<p>Hi </p><!-- c --><br/>x")
    }
}
