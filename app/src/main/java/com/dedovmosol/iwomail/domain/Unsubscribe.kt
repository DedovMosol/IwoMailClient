package com.dedovmosol.iwomail.domain

import android.content.Context
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.ListUnsubscribe
import com.dedovmosol.iwomail.util.escapeHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Итог отписки (локализация — в UI). */
sealed interface UnsubscribeResult {
    data object Done : UnsubscribeResult
    /** Отписка — на странице рассылки: открыть в браузере. */
    data class OpenPage(val url: String) : UnsubscribeResult
    data class Failed(val reason: String?) : UnsubscribeResult
}

/**
 * Отписка от рассылки выбранным способом ([ListUnsubscribe.method]).
 *
 * - One-click (RFC 8058 §3.2): POST `List-Unsubscribe=One-Click` без куки и учётных данных, по отдельному клиенту
 *   (не клиенту Exchange: пароль и сертификаты аккаунта чужому серверу не уходят) и без перенаправлений — их
 *   отправителю делать запрещено. Не 2xx — страница отписки в браузере, если она есть.
 * - Письмом (RFC 2369): отправляется от аккаунта письма, как обычное.
 * - Страница — открывает UI.
 */
class Unsubscriber(
    private val http: OkHttpClient = DEFAULT_HTTP,
    private val sendMail: suspend (accountId: Long, mail: OutgoingMail) -> EasResult<Boolean>
) {

    suspend fun unsubscribe(accountId: Long, unsubscribe: ListUnsubscribe): UnsubscribeResult =
        when (val method = unsubscribe.method) {
            is ListUnsubscribe.Method.OneClick ->
                if (postOneClick(method.url)) UnsubscribeResult.Done else UnsubscribeResult.OpenPage(method.url)
            is ListUnsubscribe.Method.Mail -> {
                val mail = OutgoingMail(
                    to = method.mailto.to, cc = "", bcc = "", subject = method.mailto.subject,
                    body = method.mailto.body.escapeHtml().replace("\n", "<br>")
                )
                when (val result = sendMail(accountId, mail)) {
                    is EasResult.Success -> UnsubscribeResult.Done
                    is EasResult.Error -> UnsubscribeResult.Failed(result.message)
                }
            }
            is ListUnsubscribe.Method.Web -> UnsubscribeResult.OpenPage(method.url)
        }

    private suspend fun postOneClick(url: String): Boolean = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder()
                .url(url)
                .post(ListUnsubscribe.ONE_CLICK_BODY.toRequestBody(FORM))
                .build()
        } catch (_: IllegalArgumentException) {
            return@withContext false
        }
        try {
            http.newCall(request).execute().use { it.isSuccessful }
        } catch (e: IOException) {
            android.util.Log.w("Unsubscriber", "One-click unsubscribe failed: ${e.message}")
            false
        }
    }

    companion object {
        private val FORM = "application/x-www-form-urlencoded".toMediaType()

        internal val DEFAULT_HTTP: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /** Письмо отписки — от аккаунта через Exchange. */
        fun forApp(context: Context): Unsubscriber {
            val app = context.applicationContext
            return Unsubscriber { accountId, mail ->
                RepositoryProvider.getAccountRepository(app).createEasClient(accountId)?.send(mail)
                    ?: EasResult.Error(com.dedovmosol.iwomail.data.repository.RepositoryErrors.CLIENT_CREATE_FAILED)
            }
        }
    }
}
