package com.dedovmosol.iwomail.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for SecurityTelemetry (L-7 fix).
 *
 * Tests cover:
 * - Insecure storage tracking
 * - User warning state management
 * - Fail-closed mode configuration
 * - Telemetry data persistence
 *
 * Internet best practices:
 * - Test state transitions
 * - Test data persistence
 * - Test singleton pattern
 * - Test boundary conditions
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class SecurityTelemetryTest {

    private lateinit var context: Context
    private lateinit var telemetry: SecurityTelemetry

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Изоляция синглтона: не наследовать состояние от других тест-классов.
        SecurityTelemetry.resetForTesting()
        context.getSharedPreferences("security_telemetry", Context.MODE_PRIVATE).edit().clear().commit()
        telemetry = SecurityTelemetry.getInstance(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("security_telemetry", Context.MODE_PRIVATE).edit().clear().commit()
        SecurityTelemetry.resetForTesting()
    }

    @Test
    fun `initial state - not using insecure storage`() {
        assertThat(telemetry.isUsingInsecureStorage()).isFalse()
        assertThat(telemetry.getInsecureStorageReason()).isNull()
        assertThat(telemetry.getInsecureStorageFirstUsedTime()).isEqualTo(0L)
    }

    @Test
    fun `recordInsecureStorageUsed sets flag and reason`() {
        val reason = "KeyStore unavailable"

        telemetry.recordInsecureStorageUsed(reason)

        assertThat(telemetry.isUsingInsecureStorage()).isTrue()
        assertThat(telemetry.getInsecureStorageReason()).isEqualTo(reason)
        assertThat(telemetry.getInsecureStorageFirstUsedTime()).isGreaterThan(0L)
    }

    @Test
    fun `recordInsecureStorageUsed records timestamp`() {
        val beforeTime = System.currentTimeMillis()

        telemetry.recordInsecureStorageUsed("test reason")

        val recordedTime = telemetry.getInsecureStorageFirstUsedTime()
        val afterTime = System.currentTimeMillis()

        assertThat(recordedTime).isAtLeast(beforeTime)
        assertThat(recordedTime).isAtMost(afterTime)
    }

    @Test
    fun `hasAcknowledgedWarning - initial state is false`() {
        assertThat(telemetry.hasAcknowledgedWarning()).isFalse()
    }

    @Test
    fun `acknowledgeWarning sets flag`() {
        telemetry.acknowledgeWarning()
        assertThat(telemetry.hasAcknowledgedWarning()).isTrue()
    }

    @Test
    fun `isFailClosedEnabled - initial state is false`() {
        assertThat(telemetry.isFailClosedEnabled()).isFalse()
    }

    @Test
    fun `setFailClosedMode enables fail-closed`() {
        telemetry.setFailClosedMode(true)
        assertThat(telemetry.isFailClosedEnabled()).isTrue()
    }

    @Test
    fun `setFailClosedMode disables fail-closed`() {
        telemetry.setFailClosedMode(true)
        assertThat(telemetry.isFailClosedEnabled()).isTrue()

        telemetry.setFailClosedMode(false)
        assertThat(telemetry.isFailClosedEnabled()).isFalse()
    }

    @Test
    fun `reset clears all telemetry data`() {
        telemetry.recordInsecureStorageUsed("test reason")
        telemetry.acknowledgeWarning()
        telemetry.setFailClosedMode(true)

        assertThat(telemetry.isUsingInsecureStorage()).isTrue()
        assertThat(telemetry.hasAcknowledgedWarning()).isTrue()
        assertThat(telemetry.isFailClosedEnabled()).isTrue()

        telemetry.reset()

        assertThat(telemetry.isUsingInsecureStorage()).isFalse()
        assertThat(telemetry.hasAcknowledgedWarning()).isFalse()
        assertThat(telemetry.isFailClosedEnabled()).isFalse()
        assertThat(telemetry.getInsecureStorageReason()).isNull()
        assertThat(telemetry.getInsecureStorageFirstUsedTime()).isEqualTo(0L)
    }

    @Test
    fun `singleton pattern - same instance returned`() {
        val instance1 = SecurityTelemetry.getInstance(context)
        val instance2 = SecurityTelemetry.getInstance(context)

        assertThat(instance1).isSameInstanceAs(instance2)
    }

    @Test
    fun `data persists across instances`() {
        val telemetry1 = SecurityTelemetry.getInstance(context)
        telemetry1.recordInsecureStorageUsed("persistence test")
        telemetry1.setFailClosedMode(true)

        val telemetry2 = SecurityTelemetry.getInstance(context)

        assertThat(telemetry2.isUsingInsecureStorage()).isTrue()
        assertThat(telemetry2.getInsecureStorageReason()).isEqualTo("persistence test")
        assertThat(telemetry2.isFailClosedEnabled()).isTrue()
    }

    @Test
    fun `multiple recordInsecureStorageUsed calls update reason`() {
        telemetry.recordInsecureStorageUsed("reason 1")
        assertThat(telemetry.getInsecureStorageReason()).isEqualTo("reason 1")

        telemetry.recordInsecureStorageUsed("reason 2")
        assertThat(telemetry.getInsecureStorageReason()).isEqualTo("reason 2")
    }

    @Test
    fun `first used timestamp does not change on subsequent calls`() {
        telemetry.recordInsecureStorageUsed("first call")
        val firstTimestamp = telemetry.getInsecureStorageFirstUsedTime()

        // Small delay
        Thread.sleep(10)

        telemetry.recordInsecureStorageUsed("second call")
        val secondTimestamp = telemetry.getInsecureStorageFirstUsedTime()

        assertThat(secondTimestamp).isEqualTo(firstTimestamp)
    }
}
