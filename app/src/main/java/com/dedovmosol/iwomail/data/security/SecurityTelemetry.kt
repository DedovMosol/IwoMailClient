package com.dedovmosol.iwomail.data.security

import android.content.Context
import android.content.SharedPreferences
import com.dedovmosol.iwomail.util.SafeToast

/**
 * Security telemetry for password storage.
 *
 * Tracks and logs security events related to password storage fallback.
 * Implements user notification requirements from L-7.
 *
 * SOLID: Single Responsibility
 * - Only handles security event tracking and user notification
 * - Does not mix with password storage logic
 *
 * DRY: Centralized notification logic
 * - All security warnings go through this class
 * - No duplication of Toast/logging code
 */
class SecurityTelemetry private constructor(
    private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("security_telemetry", Context.MODE_PRIVATE)
    }

    /**
     * Record that insecure storage is being used.
     * Shows warning to user on first occurrence.
     */
    fun recordInsecureStorageUsed(reason: String) {
        android.util.Log.w(TAG, "Insecure password storage in use. Reason: $reason")

        val alreadyWarned = prefs.getBoolean(KEY_INSECURE_STORAGE_WARNING_SHOWN, false)

        // Save the reason and mark as using insecure storage
        prefs.edit()
            .putBoolean(KEY_USING_INSECURE_STORAGE, true)
            .putString(KEY_INSECURE_STORAGE_REASON, reason)
            .putLong(KEY_INSECURE_STORAGE_FIRST_USED, System.currentTimeMillis())
            .apply()

        // Show warning to user (once per app lifetime)
        if (!alreadyWarned) {
            showInsecureStorageWarning(reason)
            prefs.edit()
                .putBoolean(KEY_INSECURE_STORAGE_WARNING_SHOWN, true)
                .apply()
        }
    }

    /**
     * Check if currently using insecure storage.
     */
    fun isUsingInsecureStorage(): Boolean {
        return prefs.getBoolean(KEY_USING_INSECURE_STORAGE, false)
    }

    /**
     * Get reason for insecure storage usage.
     */
    fun getInsecureStorageReason(): String? {
        return prefs.getString(KEY_INSECURE_STORAGE_REASON, null)
    }

    /**
     * Get timestamp when insecure storage was first used.
     */
    fun getInsecureStorageFirstUsedTime(): Long {
        return prefs.getLong(KEY_INSECURE_STORAGE_FIRST_USED, 0L)
    }

    /**
     * Check if user has acknowledged the security warning.
     */
    fun hasAcknowledgedWarning(): Boolean {
        return prefs.getBoolean(KEY_WARNING_ACKNOWLEDGED, false)
    }

    /**
     * Mark security warning as acknowledged by user.
     */
    fun acknowledgeWarning() {
        prefs.edit()
            .putBoolean(KEY_WARNING_ACKNOWLEDGED, true)
            .apply()
    }

    /**
     * Check if fail-closed mode is enabled.
     * When enabled, saving passwords to insecure storage throws SecurityException.
     */
    fun isFailClosedEnabled(): Boolean {
        return prefs.getBoolean(KEY_FAIL_CLOSED_MODE, false)
    }

    /**
     * Enable or disable fail-closed mode.
     */
    fun setFailClosedMode(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_FAIL_CLOSED_MODE, enabled)
            .apply()
    }

    /**
     * Reset all security telemetry (for testing or after KeyStore recovery).
     */
    fun reset() {
        prefs.edit().clear().apply()
    }

    /**
     * Show warning to user about insecure storage.
     */
    private fun showInsecureStorageWarning(reason: String) {
        val message = buildString {
            append("⚠️ Security Warning: Password encryption unavailable. ")
            append("Passwords stored with reduced protection. ")
            append("Reason: $reason")
        }

        // Use SafeToast for thread-safe, non-blocking notification
        SafeToast.long(context, message)

        android.util.Log.w(TAG, "Insecure storage warning shown to user: $message")
    }

    companion object {
        private const val TAG = "SecurityTelemetry"

        // SharedPreferences keys
        private const val KEY_USING_INSECURE_STORAGE = "using_insecure_storage"
        private const val KEY_INSECURE_STORAGE_REASON = "insecure_storage_reason"
        private const val KEY_INSECURE_STORAGE_FIRST_USED = "insecure_storage_first_used"
        private const val KEY_INSECURE_STORAGE_WARNING_SHOWN = "insecure_storage_warning_shown"
        private const val KEY_WARNING_ACKNOWLEDGED = "warning_acknowledged"
        private const val KEY_FAIL_CLOSED_MODE = "fail_closed_mode"

        @Volatile
        private var instance: SecurityTelemetry? = null

        fun getInstance(context: Context): SecurityTelemetry {
            return instance ?: synchronized(this) {
                instance ?: SecurityTelemetry(context.applicationContext).also { instance = it }
            }
        }
    }
}
