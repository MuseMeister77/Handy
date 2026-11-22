package com.offlinevoice.keyboard

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import kotlin.math.sqrt

/**
 * Records 16 kHz mono audio from the microphone into memory. Audio never
 * leaves the phone and is not written to disk.
 */
class AudioRecorder(
    private val onLevel: (Float) -> Unit,
    private val onMaxDurationReached: () -> Unit,
    /** Every frame of audio as it arrives (on the capture thread). */
    private val onFrame: (ShortArray) -> Unit = {},
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_SECONDS = 300
    }

    @Volatile private var recording = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private val chunks = ArrayList<ShortArray>()
    private var totalSamples = 0

    val isRecording: Boolean get() = recording

    /**
     * True if Android silenced the last recording (it does this when an app
     * records from the background without being allowed to).
     */
    var wasSilenced = false
        private set

    /** Starts recording. Returns false if the microphone could not be opened. */
    @SuppressLint("MissingPermission") // checked by the caller before starting
    fun start(): Boolean {
        if (recording) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return false
        // 1 s of headroom, so audio isn't dropped while earlier sentences are
        // being transcribed on the same CPU.
        val bufSize = maxOf(minBuf, SAMPLE_RATE * 2)
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
        } catch (e: Exception) {
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        try {
            r.startRecording()
        } catch (e: IllegalStateException) {
            r.release()
            return false
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            r.release()
            return false
        }

        synchronized(chunks) {
            chunks.clear()
            totalSamples = 0
        }
        wasSilenced = false
        record = r
        recording = true
        thread = Thread({ loop(r) }, "mic-capture").apply { start() }
        return true
    }

    private fun loop(r: AudioRecord) {
        val buf = ShortArray(SAMPLE_RATE / 10) // 100 ms
        val maxSamples = SAMPLE_RATE * MAX_SECONDS
        while (recording) {
            val n = r.read(buf, 0, buf.size)
            if (n <= 0) continue
            val chunk = buf.copyOf(n)
            val reachedMax: Boolean
            synchronized(chunks) {
                chunks.add(chunk)
                totalSamples += n
                reachedMax = totalSamples >= maxSamples
            }
            onLevel(rms(chunk, n))
            onFrame(chunk)
            if (reachedMax) {
                onMaxDurationReached()
                break
            }
        }
    }

    /** Stops recording and returns the audio as floats in [-1, 1]. */
    fun stop(): FloatArray {
        recording = false
        thread?.join(1000)
        thread = null
        record?.let {
            if (Build.VERSION.SDK_INT >= 29) {
                wasSilenced = it.activeRecordingConfiguration?.isClientSilenced == true
            }
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        record = null
        return synchronized(chunks) {
            val out = FloatArray(totalSamples)
            var i = 0
            for (c in chunks) {
                for (s in c) out[i++] = s / 32768f
            }
            chunks.clear()
            totalSamples = 0
            out
        }
    }

    /** Stops recording and throws the audio away. */
    fun cancel() {
        stop()
    }

    private fun rms(data: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) {
            val v = data[i] / 32768.0
            sum += v * v
        }
        return sqrt(sum / n).toFloat()
    }
}

/** Returns true when the clip is so quiet it almost certainly holds no speech. */
fun isSilent(samples: FloatArray): Boolean {
    if (samples.size < AudioRecorder.SAMPLE_RATE / 4) return true // < 250 ms
    var peak = 0f
    for (s in samples) {
        val a = if (s < 0) -s else s
        if (a > peak) peak = a
    }
    return peak < 0.01f
}
