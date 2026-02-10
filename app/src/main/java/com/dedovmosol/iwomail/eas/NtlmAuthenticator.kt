package com.dedovmosol.iwomail.eas

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * NTLM аутентификация для Exchange.
 * Поддерживает NTLMv2 для Exchange 2007+
 */
class NtlmAuthenticator(
    private val domain: String,
    private val username: String,
    private val password: String
) {
    companion object {
        private const val WORKSTATION = "ANDROID"
        
        // NTLM флаги
        private const val NTLM_FLAG_NEGOTIATE_UNICODE = 0x00000001
        private const val NTLM_FLAG_NEGOTIATE_OEM = 0x00000002
        private const val NTLM_FLAG_REQUEST_TARGET = 0x00000004
        private const val NTLM_FLAG_NEGOTIATE_NTLM = 0x00000200
        private const val NTLM_FLAG_NEGOTIATE_ALWAYS_SIGN = 0x00008000
        private const val NTLM_FLAG_NEGOTIATE_NTLM2_KEY = 0x00080000
        private const val NTLM_FLAG_NEGOTIATE_128 = 0x02000000
        private const val NTLM_FLAG_NEGOTIATE_56 = 0x20000000
    }
    
    /**
     * Создаёт NTLM Type 1 (Negotiate) сообщение
     */
    fun createType1Message(): ByteArray {
        val domainBytes = domain.uppercase().toByteArray(Charsets.US_ASCII)
        val workstationBytes = WORKSTATION.toByteArray(Charsets.US_ASCII)
        
        val flags = NTLM_FLAG_NEGOTIATE_UNICODE or
                    NTLM_FLAG_NEGOTIATE_OEM or
                    NTLM_FLAG_REQUEST_TARGET or
                    NTLM_FLAG_NEGOTIATE_NTLM or
                    NTLM_FLAG_NEGOTIATE_ALWAYS_SIGN or
                    NTLM_FLAG_NEGOTIATE_NTLM2_KEY or
                    NTLM_FLAG_NEGOTIATE_128 or
                    NTLM_FLAG_NEGOTIATE_56
        
        val message = ByteArrayOutputStream()
        
        // Signature "NTLMSSP\0"
        message.write("NTLMSSP".toByteArray(Charsets.US_ASCII))
        message.write(0)
        
        // Type 1 indicator
        writeInt32LE(message, 1)
        
        // Flags
        writeInt32LE(message, flags)
        
        // Domain (security buffer): length, allocated, offset
        val domainOffset = 32
        writeInt16LE(message, domainBytes.size)
        writeInt16LE(message, domainBytes.size)
        writeInt32LE(message, domainOffset)
        
        // Workstation (security buffer)
        val workstationOffset = domainOffset + domainBytes.size
        writeInt16LE(message, workstationBytes.size)
        writeInt16LE(message, workstationBytes.size)
        writeInt32LE(message, workstationOffset)
        
        // Domain and workstation data
        message.write(domainBytes)
        message.write(workstationBytes)
        
        return message.toByteArray()
    }
    
    /**
     * Создаёт NTLM Type 3 (Authenticate) сообщение с NTLMv2
     */
    fun createType3Message(type2Message: ByteArray): ByteArray {
        // Извлекаем данные из Type 2
        val serverChallenge = type2Message.copyOfRange(24, 32)
        
        // Извлекаем target info из Type 2 (если есть)
        val targetInfo = extractTargetInfo(type2Message)
        
        // Подготавливаем данные
        val domainUnicode = domain.uppercase().toByteArray(Charsets.UTF_16LE)
        val userUnicode = username.toByteArray(Charsets.UTF_16LE)
        val workstationUnicode = WORKSTATION.toByteArray(Charsets.UTF_16LE)
        
        // NTLMv2 вычисления
        val ntlmHash = createNtlmHash(password)
        val ntlmv2Hash = createNtlmv2Hash(ntlmHash, username, domain)
        
        // Создаём client challenge (8 random bytes)
        val clientChallenge = ByteArray(8)
        SecureRandom().nextBytes(clientChallenge)
        
        // Создаём blob для NTLMv2
        val blob = createNtlmv2Blob(clientChallenge, targetInfo)
        
        // Вычисляем NTLMv2 response
        val ntlmv2Response = createNtlmv2Response(ntlmv2Hash, serverChallenge, blob)
        
        // LMv2 response (упрощённый - используем client challenge)
        val lmv2Response = createLmv2Response(ntlmv2Hash, serverChallenge, clientChallenge)
        
        // Строим Type 3 сообщение
        val message = ByteArrayOutputStream()
        
        // Signature
        message.write("NTLMSSP".toByteArray(Charsets.US_ASCII))
        message.write(0)
        
        // Type 3 indicator
        writeInt32LE(message, 3)
        
        // Вычисляем смещения (header = 88 bytes для NTLMv2)
        val headerSize = 88
        var offset = headerSize
        
        // LM Response (security buffer)
        writeInt16LE(message, lmv2Response.size)
        writeInt16LE(message, lmv2Response.size)
        writeInt32LE(message, offset)
        offset += lmv2Response.size
        
        // NTLM Response (security buffer)
        writeInt16LE(message, ntlmv2Response.size)
        writeInt16LE(message, ntlmv2Response.size)
        writeInt32LE(message, offset)
        offset += ntlmv2Response.size
        
        // Domain (security buffer)
        writeInt16LE(message, domainUnicode.size)
        writeInt16LE(message, domainUnicode.size)
        writeInt32LE(message, offset)
        offset += domainUnicode.size
        
        // User (security buffer)
        writeInt16LE(message, userUnicode.size)
        writeInt16LE(message, userUnicode.size)
        writeInt32LE(message, offset)
        offset += userUnicode.size
        
        // Workstation (security buffer)
        writeInt16LE(message, workstationUnicode.size)
        writeInt16LE(message, workstationUnicode.size)
        writeInt32LE(message, offset)
        offset += workstationUnicode.size
        
        // Encrypted random session key (empty)
        writeInt16LE(message, 0)
        writeInt16LE(message, 0)
        writeInt32LE(message, offset)
        
        // Flags (NTLMv2)
        val flags = NTLM_FLAG_NEGOTIATE_UNICODE or
                    NTLM_FLAG_NEGOTIATE_NTLM or
                    NTLM_FLAG_NEGOTIATE_ALWAYS_SIGN or
                    NTLM_FLAG_NEGOTIATE_NTLM2_KEY or
                    NTLM_FLAG_NEGOTIATE_128 or
                    NTLM_FLAG_NEGOTIATE_56
        writeInt32LE(message, flags)
        
        // Version (optional, 8 bytes)
        message.write(byteArrayOf(6, 1, 0, 0, 0, 0, 0, 15)) // Windows Vista
        
        // MIC (16 bytes, zeros for now)
        message.write(ByteArray(16))
        
        // Данные
        message.write(lmv2Response)
        message.write(ntlmv2Response)
        message.write(domainUnicode)
        message.write(userUnicode)
        message.write(workstationUnicode)
        
        return message.toByteArray()
    }
    
    /**
     * Парсит Type 2 challenge из WWW-Authenticate header
     */
    fun parseType2FromHeader(wwwAuthHeader: String): ByteArray? {
        val type2Base64 = wwwAuthHeader
            .split(",")
            .map { it.trim() }
            .find { it.startsWith("NTLM ", ignoreCase = true) }
            ?.substringAfter("NTLM ")
            ?.trim()
        
        if (type2Base64.isNullOrEmpty()) {
            return null
        }
        
        return Base64.decode(type2Base64, Base64.DEFAULT)
    }
    
    /**
     * Создаёт Authorization header с Type 1 message
     */
    fun createType1AuthHeader(): String {
        val type1Message = createType1Message()
        val type1Base64 = Base64.encodeToString(type1Message, Base64.NO_WRAP)
        return "NTLM $type1Base64"
    }
    
    /**
     * Создаёт Authorization header с Type 3 message
     */
    fun createType3AuthHeader(type2Message: ByteArray): String {
        val type3Message = createType3Message(type2Message)
        val type3Base64 = Base64.encodeToString(type3Message, Base64.NO_WRAP)
        return "NTLM $type3Base64"
    }
    
    // ==================== Private helper methods ====================
    
    private fun extractTargetInfo(type2Message: ByteArray): ByteArray {
        if (type2Message.size < 48) return ByteArray(0)
        
        val targetInfoLen = readInt16LE(type2Message, 40)
        val targetInfoOffset = readInt32LE(type2Message, 44)
        
        if (targetInfoOffset + targetInfoLen > type2Message.size) return ByteArray(0)
        
        return type2Message.copyOfRange(targetInfoOffset, targetInfoOffset + targetInfoLen)
    }
    
    private fun createNtlmv2Hash(ntlmHash: ByteArray, user: String, domain: String): ByteArray {
        val identity = (user.uppercase() + domain.uppercase()).toByteArray(Charsets.UTF_16LE)
        return hmacMd5(ntlmHash, identity)
    }
    
    private fun createNtlmv2Blob(clientChallenge: ByteArray, targetInfo: ByteArray): ByteArray {
        val blob = ByteArrayOutputStream()
        
        // Blob signature
        blob.write(byteArrayOf(0x01, 0x01, 0x00, 0x00))
        
        // Reserved
        blob.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
        
        // Timestamp (FILETIME - 100ns intervals since 1601)
        val now = System.currentTimeMillis()
        val filetime = (now + 11644473600000L) * 10000L
        for (i in 0..7) {
            blob.write((filetime shr (i * 8)).toInt() and 0xFF)
        }
        
        // Client challenge
        blob.write(clientChallenge)
        
        // Reserved
        blob.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
        
        // Target info
        blob.write(targetInfo)
        
        // Reserved
        blob.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
        
        return blob.toByteArray()
    }
    
    private fun createNtlmv2Response(ntlmv2Hash: ByteArray, serverChallenge: ByteArray, blob: ByteArray): ByteArray {
        val data = serverChallenge + blob
        val ntProofStr = hmacMd5(ntlmv2Hash, data)
        return ntProofStr + blob
    }
    
    private fun createLmv2Response(ntlmv2Hash: ByteArray, serverChallenge: ByteArray, clientChallenge: ByteArray): ByteArray {
        val data = serverChallenge + clientChallenge
        val hash = hmacMd5(ntlmv2Hash, data)
        return hash + clientChallenge
    }
    
    private fun hmacMd5(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(key, "HmacMD5"))
        return mac.doFinal(data)
    }
    
    private fun createNtlmHash(password: String): ByteArray {
        val passwordUnicode = password.toByteArray(Charsets.UTF_16LE)
        
        return try {
            val md4 = java.security.MessageDigest.getInstance("MD4")
            md4.digest(passwordUnicode)
        } catch (e: Exception) {
            android.util.Log.d("NtlmAuthenticator", "MD4 not available, using custom implementation")
            md4Hash(passwordUnicode)
        }
    }
    
    // ==================== MD4 Implementation ====================
    
    private fun md4Hash(input: ByteArray): ByteArray {
        val originalLength = input.size
        val paddedLength = ((originalLength + 8) / 64 + 1) * 64
        val padded = ByteArray(paddedLength)
        System.arraycopy(input, 0, padded, 0, originalLength)
        padded[originalLength] = 0x80.toByte()
        
        val bitLength = originalLength.toLong() * 8
        for (i in 0..7) {
            padded[paddedLength - 8 + i] = (bitLength shr (i * 8)).toByte()
        }
        
        var a = 0x67452301
        var b = 0xefcdab89.toInt()
        var c = 0x98badcfe.toInt()
        var d = 0x10325476
        
        for (blockStart in 0 until paddedLength step 64) {
            val x = IntArray(16)
            for (i in 0..15) {
                x[i] = (padded[blockStart + i * 4].toInt() and 0xFF) or
                       ((padded[blockStart + i * 4 + 1].toInt() and 0xFF) shl 8) or
                       ((padded[blockStart + i * 4 + 2].toInt() and 0xFF) shl 16) or
                       ((padded[blockStart + i * 4 + 3].toInt() and 0xFF) shl 24)
            }
            
            val aa = a; val bb = b; val cc = c; val dd = d
            
            // Round 1
            a = md4Round1(a, b, c, d, x[0], 3)
            d = md4Round1(d, a, b, c, x[1], 7)
            c = md4Round1(c, d, a, b, x[2], 11)
            b = md4Round1(b, c, d, a, x[3], 19)
            a = md4Round1(a, b, c, d, x[4], 3)
            d = md4Round1(d, a, b, c, x[5], 7)
            c = md4Round1(c, d, a, b, x[6], 11)
            b = md4Round1(b, c, d, a, x[7], 19)
            a = md4Round1(a, b, c, d, x[8], 3)
            d = md4Round1(d, a, b, c, x[9], 7)
            c = md4Round1(c, d, a, b, x[10], 11)
            b = md4Round1(b, c, d, a, x[11], 19)
            a = md4Round1(a, b, c, d, x[12], 3)
            d = md4Round1(d, a, b, c, x[13], 7)
            c = md4Round1(c, d, a, b, x[14], 11)
            b = md4Round1(b, c, d, a, x[15], 19)
            
            // Round 2
            a = md4Round2(a, b, c, d, x[0], 3)
            d = md4Round2(d, a, b, c, x[4], 5)
            c = md4Round2(c, d, a, b, x[8], 9)
            b = md4Round2(b, c, d, a, x[12], 13)
            a = md4Round2(a, b, c, d, x[1], 3)
            d = md4Round2(d, a, b, c, x[5], 5)
            c = md4Round2(c, d, a, b, x[9], 9)
            b = md4Round2(b, c, d, a, x[13], 13)
            a = md4Round2(a, b, c, d, x[2], 3)
            d = md4Round2(d, a, b, c, x[6], 5)
            c = md4Round2(c, d, a, b, x[10], 9)
            b = md4Round2(b, c, d, a, x[14], 13)
            a = md4Round2(a, b, c, d, x[3], 3)
            d = md4Round2(d, a, b, c, x[7], 5)
            c = md4Round2(c, d, a, b, x[11], 9)
            b = md4Round2(b, c, d, a, x[15], 13)
            
            // Round 3
            a = md4Round3(a, b, c, d, x[0], 3)
            d = md4Round3(d, a, b, c, x[8], 9)
            c = md4Round3(c, d, a, b, x[4], 11)
            b = md4Round3(b, c, d, a, x[12], 15)
            a = md4Round3(a, b, c, d, x[2], 3)
            d = md4Round3(d, a, b, c, x[10], 9)
            c = md4Round3(c, d, a, b, x[6], 11)
            b = md4Round3(b, c, d, a, x[14], 15)
            a = md4Round3(a, b, c, d, x[1], 3)
            d = md4Round3(d, a, b, c, x[9], 9)
            c = md4Round3(c, d, a, b, x[5], 11)
            b = md4Round3(b, c, d, a, x[13], 15)
            a = md4Round3(a, b, c, d, x[3], 3)
            d = md4Round3(d, a, b, c, x[11], 9)
            c = md4Round3(c, d, a, b, x[7], 11)
            b = md4Round3(b, c, d, a, x[15], 15)
            
            a += aa; b += bb; c += cc; d += dd
        }
        
        val result = ByteArray(16)
        for (i in 0..3) {
            result[i] = (a shr (i * 8)).toByte()
            result[i + 4] = (b shr (i * 8)).toByte()
            result[i + 8] = (c shr (i * 8)).toByte()
            result[i + 12] = (d shr (i * 8)).toByte()
        }
        return result
    }
    
    private fun md4Round1(a: Int, b: Int, c: Int, d: Int, x: Int, s: Int): Int {
        val f = (b and c) or (b.inv() and d)
        return Integer.rotateLeft(a + f + x, s)
    }
    
    private fun md4Round2(a: Int, b: Int, c: Int, d: Int, x: Int, s: Int): Int {
        val f = (b and c) or (b and d) or (c and d)
        return Integer.rotateLeft(a + f + x + 0x5a827999, s)
    }
    
    private fun md4Round3(a: Int, b: Int, c: Int, d: Int, x: Int, s: Int): Int {
        val f = b xor c xor d
        return Integer.rotateLeft(a + f + x + 0x6ed9eba1, s)
    }
    
    // ==================== Binary helpers ====================
    
    private fun writeInt16LE(stream: ByteArrayOutputStream, value: Int) {
        stream.write(value and 0xFF)
        stream.write((value shr 8) and 0xFF)
    }
    
    private fun writeInt32LE(stream: ByteArrayOutputStream, value: Int) {
        stream.write(value and 0xFF)
        stream.write((value shr 8) and 0xFF)
        stream.write((value shr 16) and 0xFF)
        stream.write((value shr 24) and 0xFF)
    }
    
    private fun readInt16LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
               ((data[offset + 1].toInt() and 0xFF) shl 8)
    }
    
    private fun readInt32LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
               ((data[offset + 1].toInt() and 0xFF) shl 8) or
               ((data[offset + 2].toInt() and 0xFF) shl 16) or
               ((data[offset + 3].toInt() and 0xFF) shl 24)
    }
}
