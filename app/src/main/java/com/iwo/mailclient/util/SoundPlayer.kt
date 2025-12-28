package com.iwo.mailclient.util

import android.content.Context
import android.media.MediaPlayer
import com.iwo.mailclient.R

/**
 * Утилита для воспроизведения звуков приложения
 */
object SoundPlayer {
    
    private var mediaPlayer: MediaPlayer? = null
    
    /**
     * Воспроизводит звук отправки письма
     */
    fun playSendSound(context: Context) {
        playSound(context, R.raw.send_message)
    }
    
    /**
     * Воспроизводит звук получения письма
     */
    fun playReceiveSound(context: Context) {
        playSound(context, R.raw.get_message)
    }
    
    /**
     * Воспроизводит звук удаления письма
     */
    fun playDeleteSound(context: Context) {
        playSound(context, R.raw.delete_message)
    }
    
    private fun playSound(context: Context, resId: Int) {
        try {
            // Освобождаем предыдущий плеер
            mediaPlayer?.release()
            
            mediaPlayer = MediaPlayer.create(context, resId)?.apply {
                setOnCompletionListener { mp ->
                    mp.release()
                    if (mediaPlayer == mp) {
                        mediaPlayer = null
                    }
                }
                start()
            }
        } catch (e: Exception) {
            // Игнорируем ошибки воспроизведения
        }
    }
    
    /**
     * Освобождает ресурсы
     */
    fun release() {
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
