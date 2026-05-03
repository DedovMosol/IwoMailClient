package com.dedovmosol.iwomail.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Утилита для показа Toast без дублирования.
 * Отменяет предыдущий Toast перед показом нового — предотвращает
 * очередь из десятков одинаковых уведомлений при быстрых повторных нажатиях.
 *
 * Безопасно вызывать из любого потока: если вызов не с Main —
 * автоматически dispatch-ится на MainLooper через Handler.
 */
object SafeToast {
    private var currentToast: Toast? = null
    private var lastMessage: String? = null
    private var lastShowTimeMs: Long = 0L

    private const val DEDUP_WINDOW_MS = 2000L

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Показать Toast. Если предыдущий Toast ещё показывается — отменяет его.
     * Если тот же текст показан менее [DEDUP_WINDOW_MS] мс назад — игнорирует.
     */
    fun show(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val appContext = context.applicationContext
            mainHandler.post { show(appContext, message, duration) }
            return
        }
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (message == lastMessage && (now - lastShowTimeMs) < DEDUP_WINDOW_MS) {
                return
            }
            currentToast?.cancel()
            val toast = Toast.makeText(context.applicationContext, message, duration)
            currentToast = toast
            lastMessage = message
            lastShowTimeMs = now
            toast.show()
        }
    }

    fun short(context: Context, message: String) = show(context, message, Toast.LENGTH_SHORT)
    fun long(context: Context, message: String) = show(context, message, Toast.LENGTH_LONG)
}
