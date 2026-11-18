package com.dedovmosol.iwomail.eas

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Двоичный ответ ItemOperations с `MS-ASAcceptMultiPart: T` ([MS-ASCMD] 2.2.2.10 MultiPartResponse):
 * PartsCount (int32 LE), затем PartsCount пар Offset/Length (int32 LE, от начала ответа), затем части.
 * Часть 0 — WBXML ответа, в нём `<Part>N</Part>` ([MS-ASAIRS] Part) вместо base64 `<Data>`.
 *
 * Читается потоком: заголовок и WBXML — в памяти (десятки байт), содержимое вложения — сразу в [OutputStream].
 * Без multipart файл 5 МБ занимал в памяти ~4 копии (WBXML, XML-строка, группа regex, байты). Проверено на
 * Exchange 2007 SP2: 2 части, части подряд без зазоров, байты совпадают с base64-ответом, GetAttachment и EWS
 * (output_EasProbe_att2.txt).
 */
internal object EasMultipart {

    /** Защита от мусора вместо заголовка: у Fetch одного вложения частей 1-2. */
    const val MAX_PARTS = 64

    /** WBXML ответа без данных — десятки байт; больше — не ответ ItemOperations. */
    const val MAX_WBXML_PART = 1 shl 20

    data class Part(val offset: Long, val length: Long)

    class Reader(private val input: InputStream) {
        private var position = 0L
        lateinit var parts: List<Part>
            private set

        /** Заголовок. Нарушение структуры (части внахлёст, за пределами, отрицательная длина) — [IOException]. */
        fun readHeader(): List<Part> {
            val count = readInt()
            if (count !in 1..MAX_PARTS) throw IOException("multipart: parts count $count")
            val headerEnd = 4L + 8L * count
            var previousEnd = headerEnd
            parts = List(count) {
                val offset = readInt().toLong() and 0xFFFFFFFFL
                val length = readInt().toLong() and 0xFFFFFFFFL
                if (offset < previousEnd) throw IOException("multipart: part $it overlaps (offset $offset < $previousEnd)")
                previousEnd = offset + length
                Part(offset, length)
            }
            return parts
        }

        /** Часть 0 (WBXML) целиком. */
        fun readFirstPart(): ByteArray {
            val first = parts[0]
            if (first.length > MAX_WBXML_PART) throw IOException("multipart: WBXML part ${first.length} bytes")
            skipTo(first.offset)
            val bytes = ByteArray(first.length.toInt())
            readFully(bytes)
            return bytes
        }

        /** Часть [index] — в [out] ровно её длиной; обрыв потока раньше — [EOFException]. */
        fun copyPart(index: Int, out: OutputStream, onChunk: () -> Unit = {}): Long {
            val part = parts.getOrNull(index) ?: throw IOException("multipart: no part $index of ${parts.size}")
            skipTo(part.offset)
            val buffer = ByteArray(64 * 1024)
            var left = part.length
            while (left > 0) {
                onChunk()
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) throw EOFException("multipart: part $index ended ${part.length - left} of ${part.length}")
                out.write(buffer, 0, n)
                left -= n
                position += n
            }
            return part.length
        }

        private fun skipTo(offset: Long) {
            if (offset < position) throw IOException("multipart: part at $offset is behind $position")
            var left = offset - position
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped > 0) {
                    left -= skipped
                } else {
                    if (input.read() < 0) throw EOFException("multipart: ended before offset $offset")
                    left--
                }
            }
            position = offset
        }

        private fun readFully(bytes: ByteArray) {
            var read = 0
            while (read < bytes.size) {
                val n = input.read(bytes, read, bytes.size - read)
                if (n < 0) throw EOFException("multipart: ended inside WBXML part")
                read += n
            }
            position += bytes.size
        }

        private fun readInt(): Int {
            var value = 0
            for (shift in 0 until 32 step 8) {
                val b = input.read()
                if (b < 0) throw EOFException("multipart: header truncated")
                value = value or (b shl shift)
            }
            position += 4
            return value
        }
    }

    /** Statuses ответа ItemOperations Fetch по XML части 0: глобальный, Fetch и номер части с данными. */
    data class FetchReply(val status: String?, val fetchStatus: String?, val part: Int?)

    private val PART = Regex("<Part>(\\d+)</Part>")

    fun fetchReply(xml: String): FetchReply = FetchReply(
        status = EasPatterns.ITEM_OPS_GLOBAL_STATUS.find(xml)?.groupValues?.get(1),
        fetchStatus = EasPatterns.ITEM_OPS_FETCH_STATUS.find(xml)?.groupValues?.get(1),
        part = PART.find(xml)?.groupValues?.get(1)?.toIntOrNull()
    )
}
