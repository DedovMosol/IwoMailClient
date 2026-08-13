package com.dedovmosol.iwomail.config

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Юнит-тесты конфигурации безопасности (N-14).
 *
 * Проверяет три проблемы best-practice:
 * 1. FileProvider `file_paths.xml` - scoped paths вместо широкого `path="/"`
 * 2. Network Security Config - удалён user CA trust для уменьшения MITM-поверхности
 * 3. security-crypto dependency - документирован как deprecated с fallback-стратегией
 *
 * Эти тесты гарантируют, что security-настройки не регрессируют при будущих изменениях.
 * Все тесты - чистые JVM (без Android/Robolectric), парсят XML-файлы напрямую.
 */
class SecurityConfigTest {

    private val projectRoot = File(".").absoluteFile.let { current ->
        // Поиск корня проекта (содержит app/build.gradle.kts)
        generateSequence(current) { it.parentFile }
            .firstOrNull { File(it, "app/build.gradle.kts").exists() }
            ?: error("Project root not found from ${current.absolutePath}")
    }

    private fun parseXml(relativePath: String): Document {
        val file = File(projectRoot, relativePath)
        assertThat(file.exists()).isTrue()
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
    }

    @Test
    fun `file_paths xml has scoped cache paths not root slash`() {
        val doc = parseXml("app/src/main/res/xml/file_paths.xml")
        val cachePaths = doc.getElementsByTagName("cache-path")

        // Проверяем, что нет широкого path="/"
        (0 until cachePaths.length).forEach { i ->
            val element = cachePaths.item(i) as Element
            val path = element.getAttribute("path")
            assertThat(path).isNotEqualTo("/")
            assertThat(path).isNotEmpty()
            // Все пути должны быть scoped (директории или ".")
            assertThat(path.endsWith("/") || path == ".").isTrue()
        }

        // Проверяем наличие всех необходимых cache-paths (используются в коде)
        val pathNames = (0 until cachePaths.length).map { i ->
            (cachePaths.item(i) as Element).getAttribute("name")
        }.toSet()

        val expectedPaths = setOf(
            "calendar_preview",           // CalendarAttachmentsList.kt:127
            "calendar_event_attachments", // CreateEventDialog.kt:208
            "email_preview",              // EmailDetailScreen.kt:1269
            "email_share",                // EmailDetailScreen.kt:1371
            "email_drag",                 // EmailDetailScreen.kt:1404
            "share_attachments",          // AppNavigation.kt:71
            "cache_root"                  // ContactUtils.kt:26 (creates file directly in cacheDir)
        )

        assertThat(pathNames).containsAtLeastElementsIn(expectedPaths)
    }

    @Test
    fun `network_security_config does not trust user CA in base-config`() {
        val doc = parseXml("app/src/main/res/xml/network_security_config.xml")
        val baseConfig = doc.getElementsByTagName("base-config").item(0) as Element
        val trustAnchors = baseConfig.getElementsByTagName("trust-anchors").item(0) as Element
        val certificates = trustAnchors.getElementsByTagName("certificates")

        val sources = (0 until certificates.length).map { i ->
            (certificates.item(i) as Element).getAttribute("src")
        }

        // user CA должен быть удалён (расширяет MITM-поверхность, не нужен при explicit cert pinning)
        assertThat(sources).doesNotContain("user")
        // system CA должен остаться
        assertThat(sources).contains("system")
    }

    @Test
    fun `network_security_config disables cleartext traffic`() {
        val doc = parseXml("app/src/main/res/xml/network_security_config.xml")
        val baseConfig = doc.getElementsByTagName("base-config").item(0) as Element
        val cleartextPermitted = baseConfig.getAttribute("cleartextTrafficPermitted")

        assertThat(cleartextPermitted).isEqualTo("false")
    }

    @Test
    fun `build gradle documents security-crypto deprecation`() {
        val buildGradle = File(projectRoot, "app/build.gradle.kts")
        assertThat(buildGradle.exists()).isTrue()

        val content = buildGradle.readText()

        // Проверяем наличие dependency
        assertThat(content).contains("androidx.security:security-crypto:1.1.0-alpha06")

        // Проверяем наличие комментария о deprecation (добавлен в N-14)
        assertThat(content).containsMatch("(?s)security-crypto.*deprecated")
        assertThat(content).containsMatch("(?s)security-crypto.*fallback")
    }

    @Test
    fun `file_paths xml files-path entries are scoped`() {
        val doc = parseXml("app/src/main/res/xml/file_paths.xml")
        val filesPaths = doc.getElementsByTagName("files-path")

        (0 until filesPaths.length).forEach { i ->
            val element = filesPaths.item(i) as Element
            val path = element.getAttribute("path")
            val name = element.getAttribute("name")

            // files-path должны быть scoped (не пустые, не "/")
            assertThat(path).isNotEmpty()
            assertThat(path).isNotEqualTo("/")
            assertThat(name).isNotEmpty()
        }

        // Проверяем наличие ожидаемых files-paths
        val names = (0 until filesPaths.length).map { i ->
            (filesPaths.item(i) as Element).getAttribute("name")
        }.toSet()

        assertThat(names).containsAtLeast(
            "attachments",
            "forward_attachments",
            "reply_attachments",
            "draft_attachments",
            "updates"
        )
    }

    @Test
    fun `all FileProvider paths have non-empty name attributes`() {
        val doc = parseXml("app/src/main/res/xml/file_paths.xml")
        val allPaths = listOf("files-path", "cache-path").flatMap { tag ->
            val nodes = doc.getElementsByTagName(tag)
            (0 until nodes.length).map { i -> nodes.item(i) as Element }
        }

        allPaths.forEach { element ->
            val name = element.getAttribute("name")
            val path = element.getAttribute("path")

            assertThat(name).isNotEmpty()
            assertThat(path).isNotEmpty()
            // name не должен содержать пробелы или спецсимволы
            assertThat(name).matches("[a-z_]+")
        }
    }
}
