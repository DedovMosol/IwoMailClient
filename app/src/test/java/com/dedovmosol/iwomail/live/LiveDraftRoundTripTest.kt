package com.dedovmosol.iwomail.live

import com.dedovmosol.iwomail.eas.DraftIds
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Opt-in EWS write/read test. No recipients, no delivery, cleanup only by our returned server ID. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveDraftRoundTripTest {
    @Test fun `new draft survives server read and edit by item id`() = runBlocking<Unit> {
        val host = System.getenv("IWO_LIVE_HOST").orEmpty()
        val user = System.getenv("IWO_LIVE_USER").orEmpty()
        val pass = System.getenv("IWO_LIVE_PASS").orEmpty()
        assumeTrue("dedicated write mailbox not configured", System.getenv("IWO_LIVE_WRITE") == "1" &&
            host.isNotBlank() && user.isNotBlank() && pass.isNotBlank())
        val client = EasClient(host, user, pass, domain = System.getenv("IWO_LIVE_DOMAIN").orEmpty(),
            certificatePath = System.getenv("IWO_LIVE_CA_FILE"), deviceIdSuffix = "iwo-draft-roundtrip")
        val tag = "IWO-test-${UUID.randomUUID()}"
        val initial = "$tag original"
        val edited = "$tag edited"
        val created = client.createDraft("", "", "", tag, "<p>$initial</p>")
        val id = (created as? EasResult.Success)?.data?.takeIf(DraftIds::isEwsItemId)
            ?: error("No confirmed server ItemId; inspect only the synthetic draft $tag")
        try {
            fun EasResult<com.dedovmosol.iwomail.eas.EwsDraftXml.DraftDetails>.containsText(text: String) =
                (this as? EasResult.Success)?.data?.body?.contains(text) == true
            assertTrue("server did not return the original body", client.getDraftDetails(id).containsText(initial))
            assertTrue("server refused exact-ID edit", (client.updateDraft(id, "", "", null, tag, "<p>$edited</p>") as? EasResult.Success)?.data == true)
            assertTrue("server did not return the edited body", client.getDraftDetails(id).containsText(edited))
        } finally {
            assertTrue("cleanup of synthetic draft $tag failed", client.hardDeleteDraft(id) is EasResult.Success)
        }
    }
}
