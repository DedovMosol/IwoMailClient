package com.dedovmosol.iwomail.util

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import com.dedovmosol.iwomail.R
import com.dedovmosol.iwomail.data.repository.SettingsRepository

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
        if (!SettingsRepository.getInstance(context).getSoundEnabledSync()) return
        if (isSystemSoundMuted(context)) return
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

    private fun isSystemSoundMuted(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false

        // Android docs: getRingerMode() returns NORMAL/SILENT/VIBRATE.
        // In SILENT/VIBRATE user explicitly disables audible ringtone/notification behavior.
        val ringerMode = audioManager.ringerMode
        if (ringerMode == AudioManager.RINGER_MODE_SILENT || ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            return true
        }

        // Android docs: getStreamVolume(streamType) returns current volume index for a stream.
        // If all relevant streams are 0, skip creating MediaPlayer.
        val musicMuted = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0
        val notificationMuted = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION) <= 0
        val ringMuted = audioManager.getStreamVolume(AudioManager.STREAM_RING) <= 0
        return musicMuted && notificationMuted && ringMuted
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
