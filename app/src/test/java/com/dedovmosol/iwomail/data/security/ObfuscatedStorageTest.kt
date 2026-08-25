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
 * Тесты PBKDF2-обфускации (L-7) — реальный класс [ObfuscatedSharedPreferences].
 *
 * Механизм: [prefs] — обфусцирующая обёртка поверх сырого делегата [rawPrefs].
 * Все записи/чтения идут через обёртку; инвариант «данные не лежат в открытом
 * виде» проверяется чтением СЫРОГО делегата (то, что реально на диске).
 *
 * Покрытие:
 * - сырое значение на диске не является открытым текстом и не содержит подстрок
 * - сырое значение — валидный Base64
 * - round-trip: простой текст, спецсимволы, Unicode, пустая строка, переводы строк, длинная строка
 * - обработка null, отсутствующих ключей, независимость ключей, перезапись, удаление, очистка
 * - персистентность между экземплярами поверх одного файла
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ObfuscatedStorageTest {

    private lateinit var context: Context

    /** Сырой делегат — то, что реально пишется на диск. */
    private lateinit var rawPrefs: android.content.SharedPreferences

    /** Обфусцирующая обёртка — интерфейс, через который работает приложение. */
    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        rawPrefs = context.getSharedPreferences("test_obfuscated", Context.MODE_PRIVATE)
        rawPrefs.edit().clear().commit()
        prefs = ObfuscatedSharedPreferences(rawPrefs, context)
    }

    @After
    fun tearDown() {
        rawPrefs.edit().clear().commit()
    }

    @Test
    fun `obfuscated data is not stored as plaintext`() {
        val key = "test_key"
        val value = "sensitive_password_123"

        prefs.edit().putString(key, value).commit()

        // Читаем СЫРОЙ делегат (то, что на диске) — должен быть обфускат, не открытый текст.
        val rawValue = rawPrefs.getString(key, null)
        assertThat(rawValue).isNotNull()
        assertThat(rawValue).isNotEqualTo(value)
        assertThat(rawValue).doesNotContain("sensitive")
        assertThat(rawValue).doesNotContain("password")

        // Обфускат — валидный Base64.
        assertThat(rawValue).matches("[A-Za-z0-9+/=]+")

        // Через обёртку значение читается обратно как оригинал.
        assertThat(prefs.getString(key, null)).isEqualTo(value)
    }

    @Test
    fun `round trip with simple string`() {
        val key = "key1"
        val value = "simple_value"

        prefs.edit().putString(key, value).commit()
        val retrieved = prefs.getString(key, null)

        assertThat(retrieved).isEqualTo(value)
    }

    @Test
    fun `round trip with special characters`() {
        val key = "key2"
        val value = "p@ssw0rd!#\$%^&*()_+-=[]{}|;':\",./<>?"

        prefs.edit().putString(key, value).commit()
        val retrieved = prefs.getString(key, null)

        assertThat(retrieved).isEqualTo(value)
    }

    @Test
    fun `round trip with Unicode characters`() {
        val key = "key3"
        val value = "пароль密码パスワード🔐😀"

        prefs.edit().putString(key, value).commit()
        val retrieved = prefs.getString(key, null)

        assertThat(retrieved).isEqualTo(value)
    }

    @Test
    fun `round trip with empty string`() {
        val key = "key4"
        val value = ""

        prefs.edit().putString(key, value).commit()
        val retrieved = prefs.getString(key, null)

        assertThat(retrieved).isEqualTo(value)
    }

    @Test
    fun `round trip with newlines and tabs`() {
        val key = "key5"
        val value = "line1\nline2\tindented"

        prefs.edit().putString(key, value).commit()
        val retrieved = prefs.getString(key, null)

        assertThat(retrieved).isEqualTo(value)
    }

    @Test
    fun `round trip with very long string`() {
        val key = "key6"
        val value = "x".repeat(10_000)

        prefs.edit().putString(key, value).commit()
        val retrieved = prefs.getString(key, null)

        assertThat(retrieved).isEqualTo(value)
    }

    @Test
    fun `null value handling`() {
        val key = "key7"

        prefs.edit().putString(key, null).commit()
        val retrieved = prefs.getString(key, "default")

        assertThat(retrieved).isEqualTo("default")
    }

    @Test
    fun `non-existent key returns default`() {
        val retrieved = prefs.getString("non_existent_key", "default_value")
        assertThat(retrieved).isEqualTo("default_value")
    }

    @Test
    fun `multiple keys are independent`() {
        prefs.edit()
            .putString("key_a", "value_a")
            .putString("key_b", "value_b")
            .putString("key_c", "value_c")
            .commit()

        assertThat(prefs.getString("key_a", null)).isEqualTo("value_a")
        assertThat(prefs.getString("key_b", null)).isEqualTo("value_b")
        assertThat(prefs.getString("key_c", null)).isEqualTo("value_c")
    }

    @Test
    fun `overwriting value works correctly`() {
        val key = "key8"

        prefs.edit().putString(key, "old_value").commit()
        assertThat(prefs.getString(key, null)).isEqualTo("old_value")

        prefs.edit().putString(key, "new_value").commit()
        assertThat(prefs.getString(key, null)).isEqualTo("new_value")
    }

    @Test
    fun `removing value works correctly`() {
        val key = "key9"

        prefs.edit().putString(key, "value_to_remove").commit()
        assertThat(prefs.getString(key, null)).isEqualTo("value_to_remove")

        prefs.edit().remove(key).commit()
        assertThat(prefs.getString(key, null)).isNull()
    }

    @Test
    fun `clear removes all values`() {
        prefs.edit()
            .putString("key1", "value1")
            .putString("key2", "value2")
            .commit()

        assertThat(prefs.all.size).isEqualTo(2)

        prefs.edit().clear().commit()

        assertThat(prefs.all).isEmpty()
    }

    @Test
    fun `same plaintext at different keys both decrypt correctly`() {
        val value = "same_password"

        prefs.edit()
            .putString("account1", value)
            .putString("account2", value)
            .commit()

        assertThat(prefs.getString("account1", null)).isEqualTo(value)
        assertThat(prefs.getString("account2", null)).isEqualTo(value)

        // Оба сырых значения — не открытый текст.
        assertThat(rawPrefs.getString("account1", null)).doesNotContain("same_password")
        assertThat(rawPrefs.getString("account2", null)).doesNotContain("same_password")
    }

    @Test
    fun `batch operations work correctly`() {
        prefs.edit()
            .putString("batch1", "value1")
            .putString("batch2", "value2")
            .putString("batch3", "value3")
            .remove("batch2")
            .commit()

        assertThat(prefs.getString("batch1", null)).isEqualTo("value1")
        assertThat(prefs.getString("batch2", null)).isNull()
        assertThat(prefs.getString("batch3", null)).isEqualTo("value3")
    }

    @Test
    fun `data persists across preference instances`() {
        val key = "persistent_key"
        val value = "persistent_value"

        val prefs1 = ObfuscatedSharedPreferences(
            context.getSharedPreferences("test_obfuscated_persist", Context.MODE_PRIVATE),
            context
        )
        prefs1.edit().putString(key, value).commit()

        val prefs2 = ObfuscatedSharedPreferences(
            context.getSharedPreferences("test_obfuscated_persist", Context.MODE_PRIVATE),
            context
        )
        val retrieved = prefs2.getString(key, null)

        assertThat(retrieved).isEqualTo(value)
    }
}
