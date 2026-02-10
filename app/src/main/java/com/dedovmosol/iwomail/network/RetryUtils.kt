package com.dedovmosol.iwomail.network

import kotlinx.coroutines.delay
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/**
 * Утилиты для повторных попыток с exponential backoff
 */
object RetryUtils {
    
    /**
     * Выполняет блок с exponential backoff при сетевых ошибках
     * 
     * @param maxRetries максимальное количество попыток (по умолчанию 3)
     * @param initialDelayMs начальная задержка в мс (по умолчанию 1000)
     * @param maxDelayMs максимальная задержка в мс (по умолчанию 30000)
     * @param factor множитель задержки (по умолчанию 2.0)
     * @param block блок для выполнения
     */
    suspend fun <T> withExponentialBackoff(
        maxRetries: Int = 3,
        initialDelayMs: Long = 1000,
        maxDelayMs: Long = 30000,
        factor: Double = 2.0,
        block: suspend (attempt: Int) -> T
    ): T {
        var currentDelay = initialDelayMs
        var lastException: Exception? = null
        
        repeat(maxRetries) { attempt ->
            try {
                return block(attempt)
            } catch (e: Exception) {
                lastException = e
                
                // Повторяем только для сетевых ошибок
                if (!isRetryableException(e)) {
                    throw e
                }
                
                // Последняя попытка — не ждём, просто бросаем
                if (attempt == maxRetries - 1) {
                    throw e
                }
                
                // Ждём с exponential backoff
                delay(currentDelay)
                currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelayMs)
            }
        }
        
        throw lastException ?: IllegalStateException("Retry failed")
    }
    
    /**
     * Проверяет, стоит ли повторять запрос при данной ошибке
     */
    fun isRetryableException(e: Exception): Boolean {
        return when (e) {
            is SocketTimeoutException -> true
            is IOException -> {
                // Повторяем при сетевых ошибках, но не при ошибках протокола
                val message = e.message?.lowercase() ?: ""
                message.contains("timeout") ||
                message.contains("connection reset") ||
                message.contains("connection refused") ||
                message.contains("network unreachable") ||
                message.contains("no route to host") ||
                message.contains("failed to connect")
            }
            is SSLException -> {
                // Повторяем при временных SSL ошибках
                val message = e.message?.lowercase() ?: ""
                message.contains("connection reset") ||
                message.contains("handshake")
            }
            else -> false
        }
    }
    
    /**
     * Проверяет, стоит ли повторять запрос при данном HTTP коде
     */
    fun isRetryableHttpCode(code: Int): Boolean {
        return when (code) {
            408 -> true  // Request Timeout
            429 -> true  // Too Many Requests
            500 -> true  // Internal Server Error
            502 -> true  // Bad Gateway
            503 -> true  // Service Unavailable
            504 -> true  // Gateway Timeout
            else -> false
        }
    }
}
