package com.dedovmosol.iwomail.util

import android.content.Context
import android.media.MediaPlayer
import com.dedovmosol.iwomail.R

/**
 * Утилита для воспроизведения звуков приложения
 */
object SoundPlayer {
    
    private var mediaPlayer: MediaPlayer? = null
    private val lock = Any()
    
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
        synchronized(lock) {
            try {
                // Освобождаем предыдущий плеер
                mediaPlayer?.release()
                mediaPlayer = null
                
                mediaPlayer = MediaPlayer.create(context, resId)?.apply {
                    setOnCompletionListener { mp ->
                        synchronized(lock) {
                            mp.release()
                            if (mediaPlayer == mp) {
                                mediaPlayer = null
                            }
                        }
                    }
                    setOnErrorListener { mp, _, _ ->
                        synchronized(lock) {
                            mp.release()
                            if (mediaPlayer == mp) {
                                mediaPlayer = null
                            }
                        }
                        true
                    }
                    start()
                }
            } catch (e: Exception) {
                // Игнорируем ошибки воспроизведения
            }
        }
    }
    
    /**
     * Освобождает ресурсы
     */
    fun release() {
        synchronized(lock) {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }
}
