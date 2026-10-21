package io.github.realkhfan.foco.device

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlin.math.max

enum class MicStatus { UNKNOWN, OK, BLOCKED, NO_PERMISSION }

/**
 * Opens the microphone for a moment when you switch to Video. If the microphone is blocked in quick
 * settings, Android shows its "Unblock microphone?" dialog right then – before you press record.
 */
object MicCheck {
    private const val RATE = 16_000

    /** Blocked microphones deliver pure digital silence; a working one always has some noise. */
    fun isSilent(samples: ShortArray, count: Int): Boolean {
        if (count <= 0) return true
        for (i in 0 until count) if (samples[i].toInt() != 0) return false
        return true
    }

    /** Runs on a background thread. Returns OK or BLOCKED (null if the mic couldn't be opened at all). */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun probe(): MicStatus? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.CAMCORDER, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, RATE))
        } catch (_: Exception) {
            return null
        }
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) return null
            record.startRecording()
            val buffer = ShortArray(RATE / 4)
            var silent = true
            var total = 0
            // About 0.4 s: skip the first chunk (start-up), then look for any sound at all.
            repeat(2) { round ->
                val n = record.read(buffer, 0, buffer.size)
                if (round == 1 && n > 0) {
                    total += n
                    silent = isSilent(buffer, n)
                }
            }
            val silencedBySystem = runCatching { record.activeRecordingConfiguration?.isClientSilenced == true }.getOrDefault(false)
            return if (silencedBySystem || (total > 0 && silent)) MicStatus.BLOCKED else MicStatus.OK
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }
}
