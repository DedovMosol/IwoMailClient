package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Юнит-тесты EasProvisioning.
 *
 * Ключевой пин: settings:DeviceInformation внутри Provision-запроса валиден
 * только с EAS 14.1 (MS-ASPROV) — на 14.0 (Exchange 2010 RTM) сервер может
 * ответить Status=2 (protocol error), на 12.x (Exchange 2007 SP1/SP2)
 * элемент не поддерживается вовсе. Для версий < 14.1 информация об устройстве
 * передаётся отдельной командой Settings (buildSettingsRequest).
 */
class EasProvisioningTest {

    private fun provisioning(version: String) = EasProvisioning("ABCDEF1234567890", version)

    // ===================== supportsDeviceInfo =====================

    @Test
    fun `supportsDeviceInfo false for EAS 12x Exchange 2007`() {
        assertThat(provisioning("12.0").supportsDeviceInfo()).isFalse()
        assertThat(provisioning("12.1").supportsDeviceInfo()).isFalse()
    }

    @Test
    fun `supportsDeviceInfo false for EAS 14_0 Exchange 2010 RTM`() {
        assertThat(provisioning("14.0").supportsDeviceInfo()).isFalse()
    }

    @Test
    fun `supportsDeviceInfo true for EAS 14_1 and newer`() {
        assertThat(provisioning("14.1").supportsDeviceInfo()).isTrue()
        assertThat(provisioning("16.0").supportsDeviceInfo()).isTrue()
        assertThat(provisioning("16.1").supportsDeviceInfo()).isTrue()
    }

    @Test
    fun `supportsDeviceInfo false for malformed version`() {
        assertThat(provisioning("").supportsDeviceInfo()).isFalse()
        assertThat(provisioning("garbage").supportsDeviceInfo()).isFalse()
    }

    // ===================== buildPhase1Request =====================

    @Test
    fun `phase1 request omits DeviceInformation for 12_1 and 14_0`() {
        assertThat(provisioning("12.1").buildPhase1Request()).doesNotContain("DeviceInformation")
        assertThat(provisioning("14.0").buildPhase1Request()).doesNotContain("DeviceInformation")
    }

    @Test
    fun `phase1 request includes DeviceInformation for 14_1`() {
        val xml = provisioning("14.1").buildPhase1Request()
        assertThat(xml).contains("<settings:DeviceInformation>")
        assertThat(xml).contains("MS-EAS-Provisioning-WBXML")
    }

    @Test
    fun `phase1 request always requests WBXML policy type`() {
        val xml = provisioning("12.1").buildPhase1Request()
        assertThat(xml).contains("<PolicyType>MS-EAS-Provisioning-WBXML</PolicyType>")
        assertThat(xml).contains("<Provision xmlns=\"Provision\"")
    }

    // ===================== buildPhase2Request =====================

    @Test
    fun `phase2 request acknowledges policy with temp key`() {
        val xml = provisioning("12.1").buildPhase2Request("1234567890")
        assertThat(xml).contains("<PolicyKey>1234567890</PolicyKey>")
        assertThat(xml).contains("<Status>1</Status>")
    }

    // ===================== parseResponse / validate =====================

    @Test
    fun `parseResponse extracts policy key and both statuses`() {
        val xml = """
            <Provision>
                <Status>1</Status>
                <Policies>
                    <Policy>
                        <PolicyType>MS-EAS-Provisioning-WBXML</PolicyType>
                        <Status>1</Status>
                        <PolicyKey>3942919513</PolicyKey>
                    </Policy>
                </Policies>
            </Provision>
        """.trimIndent()
        val resp = provisioning("12.1").parseResponse(xml)
        assertThat(resp.policyKey).isEqualTo("3942919513")
        assertThat(resp.provisionStatus).isEqualTo(1)
        assertThat(resp.policyStatus).isEqualTo(1)
    }

    @Test
    fun `validatePhase1 passes on success with key`() {
        val p = provisioning("12.1")
        val resp = EasProvisioning.ProvisionResponse("12345", 1, 1)
        assertThat(p.validatePhase1(resp)).isNull()
    }

    @Test
    fun `validatePhase1 allows policyStatus 2 no policy without key`() {
        val p = provisioning("12.1")
        val resp = EasProvisioning.ProvisionResponse(null, 1, 2)
        assertThat(p.validatePhase1(resp)).isNull()
    }

    @Test
    fun `validatePhase1 fails without key on success status`() {
        val p = provisioning("12.1")
        val resp = EasProvisioning.ProvisionResponse(null, 1, 1)
        assertThat(p.validatePhase1(resp)).isNotNull()
    }

    @Test
    fun `validatePhase2 falls back to temp key`() {
        val p = provisioning("12.1")
        val resp = EasProvisioning.ProvisionResponse(null, 1, null)
        assertThat(p.validatePhase2(resp, "temp")).isNull()
        assertThat(p.validatePhase2(resp, null)).isNotNull()
    }
}
