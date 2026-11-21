package com.internalmicguard

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

class MicTester {

    companion object {
        const val MIN_DB = -90f
        private const val SAMPLE_RATE = 48000
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(
        source: Int,
        onUpdate: (Float, AudioDeviceInfo?) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        stop()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            onError("AudioRecord.getMinBufferSize=$minBuf")
            return false
        }
        val record = try {
            AudioRecord(
                source,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        } catch (e: Exception) {
            onError("AudioRecord: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError("AudioRecord não inicializado (source=$source)")
            return false
        }

        running = true
        worker = thread(name = "MicTester") {
            val buffer = ShortArray(SAMPLE_RATE / 20)
            try {
                record.startRecording()
                while (running) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n < 0) {
                        running = false
                        main.post { onError("AudioRecord.read=$n") }
                        break
                    }
                    if (n == 0) continue
                    var sum = 0.0
                    for (i in 0 until n) {
                        val s = buffer[i] / 32768.0
                        sum += s * s
                    }
                    val rms = sqrt(sum / n)
                    val db = if (rms > 0) (20 * log10(rms)).toFloat().coerceAtLeast(MIN_DB) else MIN_DB
                    val device = record.routedDevice
                    main.post { if (running) onUpdate(db, device) }
                }
            } catch (e: Exception) {
                running = false
                main.post { onError("Teste: ${e.javaClass.simpleName}: ${e.message}") }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }
        return true
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
    }
}
