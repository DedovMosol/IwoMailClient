package com.dedovmosol.iwomail.eas

import java.io.InputStream
import java.io.OutputStream

/**
 * Содержимое вложения из ответа EWS GetAttachment — потоком, без строки ответа в памяти.
 *
 * Exchange 2007 SP2 отдаёт файл 30 МБ ответом 40,7 МБ: `<t:FileAttachment>…<t:Content>base64</t:Content>`
 * (output_EasProbe_att2.txt). Прежний путь читал ответ в String, потом regex и Base64 — несколько копий файла
 * в памяти. Здесь теги до `Content` разбираются по одному, base64 декодируется прямо в [OutputStream].
 * Ошибка операции (`ResponseClass="Error"` с `ResponseCode`, например ErrorInvalidIdMalformed) и SOAP Fault
 * распознаются до содержимого.
 */
internal object EwsAttachmentContent {

    sealed class Result {
        data class Written(val bytes: Long) : Result()
        data class Error(val code: String) : Result()
        /** Ответ без `Content` (вложенное письмо — ItemAttachment) или оборван до него. */
        object NoContent : Result()
    }

    private const val MAX_TAG = 8 * 1024
    private const val MAX_TEXT = 4 * 1024
    private const val LT = '<'.code.toByte()

    fun extract(
        input: InputStream, out: OutputStream, maxResponseBytes: Long = Long.MAX_VALUE, onChunk: () -> Unit = {}
    ): Result {
        val source = Source(input, maxResponseBytes, onChunk)
        var error = false
        while (true) {
            if (!source.skipPast(LT)) return Result.NoContent
            val tag = source.readTag() ?: return Result.NoContent
            if (tag.startsWith("/") || tag.startsWith("?") || tag.startsWith("!")) continue
            val name = tag.substringBefore(' ').substringBefore('/').substringAfter(':')
            if (tag.contains("ResponseClass=\"Error\"")) error = true
            when {
                name == "faultstring" -> return Result.Error(source.readText().ifBlank { "SOAP Fault" })
                name == "ResponseCode" && error -> return Result.Error(source.readText().ifBlank { "Error" })
                // `<t:Content/>` — вложение найдено, но пустое
                name == "Content" && !error && tag.endsWith("/") -> return Result.Written(0)
                name == "Content" && !error -> return Result.Written(decode(source, out, onChunk))
            }
        }
    }

    /** base64 до `<` (в base64 его нет; пробелы и переводы строк MIME-декодер пропускает). */
    private fun decode(source: Source, out: OutputStream, onChunk: () -> Unit): Long {
        val base64 = object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int = source.readUntil(LT, b, off, len)
        }
        val decoder = java.util.Base64.getMimeDecoder().wrap(base64)
        val buffer = ByteArray(48 * 1024)
        var total = 0L
        while (true) {
            onChunk()
            val n = decoder.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return total
    }

    /** Буфер без синхронизации (BufferedInputStream.read() синхронизирован — 40 млн вызовов на файл 30 МБ). */
    private class Source(
        private val input: InputStream, private var remaining: Long, private val checkActive: () -> Unit
    ) {
        private val buf = ByteArray(64 * 1024)
        private var pos = 0
        private var lim = 0
        private var stopped = false

        private fun fill(): Boolean {
            if (pos < lim) return true
            checkActive()
            if (remaining <= 0) throw EasAttachmentService.BoundedBuffer.Overflow()
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n <= 0) return false
            remaining -= n
            pos = 0
            lim = n
            return true
        }

        fun read(): Int = if (fill()) buf[pos++].toInt() and 0xFF else -1

        fun skipPast(delim: Byte): Boolean {
            while (fill()) {
                while (pos < lim) if (buf[pos++] == delim) return true
            }
            return false
        }

        fun readTag(): String? {
            val sb = StringBuilder()
            while (true) {
                val c = read()
                if (c < 0) return null
                if (c == '>'.code) return sb.toString().trim()
                if (sb.length < MAX_TAG) sb.append(c.toChar())
            }
        }

        fun readText(): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                val c = read()
                if (c < 0 || c == '<'.code) break
                if (bytes.size() < MAX_TEXT) bytes.write(c)
            }
            return bytes.toString(Charsets.UTF_8.name()).trim()
        }

        /** Байты до [delim] (сам он не отдаётся; дальше — конец). */
        fun readUntil(delim: Byte, b: ByteArray, off: Int, len: Int): Int {
            if (stopped || len == 0) return if (stopped) -1 else 0
            // Конец потока до `</Content>` — ответ оборван: недописанный файл не должен сойти за целый
            if (!fill()) throw java.io.EOFException("EWS GetAttachment: response ended inside Content")
            var n = 0
            while (n < len && pos < lim) {
                val c = buf[pos]
                if (c == delim) { stopped = true; break }
                b[off + n++] = c
                pos++
            }
            return if (n == 0 && stopped) -1 else n
        }
    }
}
