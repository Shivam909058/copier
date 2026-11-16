package dev.copier

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException

/** "Ring phone" from the Mac: the alarm sound at full alarm volume until stopped, or 30 s. */
class Ringer(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var previousVolume: Int? = null
    private val timeout = Runnable { stop() }

    fun start() {
        main.post {
            if (player != null) return@post
            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            previousVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            setAlarmVolume(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM))
            val next = MediaPlayer()
            try {
                next.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                next.setDataSource(context, sound)
                next.isLooping = true
                next.prepare()
                next.start()
            } catch (e: IOException) {
                Log.w(TAG, "could not play the alarm sound", e)
                next.release()
                restoreVolume()
                return@post
            }
            player = next
            Notifications.ringing(context)
            main.postDelayed(timeout, RING_MS)
        }
    }

    fun stop() {
        main.post {
            main.removeCallbacks(timeout)
            player?.run {
                stop()
                release()
            }
            player = null
            restoreVolume()
            Notifications.cancelRinging(context)
        }
    }

    private fun restoreVolume() {
        previousVolume?.let { setAlarmVolume(it) }
        previousVolume = null
    }

    private fun setAlarmVolume(volume: Int) {
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
        } catch (e: SecurityException) {
            Log.i(TAG, "alarm volume is locked (Do Not Disturb?)")
        }
    }

    private companion object {
        const val RING_MS = 30_000L
    }
}
