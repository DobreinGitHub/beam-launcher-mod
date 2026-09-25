package com.home.tiles

import android.app.Activity
import android.content.ContentValues
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import kotlin.math.abs

/**
 * Diagnostic: records the microphone for a few seconds to find out whether the remote's voice
 * reaches ordinary apps. Started over adb:
 *   adb shell am start -n com.home.tiles/.MicTestActivity --ei seconds 6 --ei source 6
 * Writes Download/beam_mic.wav and logs the peak level (tag MicTest).
 */
class MicTestActivity : Activity() {
    private lateinit var label: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        label = TextView(this).apply {
            textSize = 28f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            gravity = Gravity.CENTER
            setPadding(48, 32, 48, 32)
        }
        setContentView(label)
        val seconds = intent.getIntExtra("seconds", 6)
        val source = intent.getIntExtra("source", MediaRecorder.AudioSource.VOICE_RECOGNITION)
        Thread { record(seconds, source) }.start()
    }

    private fun show(text: String) = runOnUiThread { label.text = text }

    private fun record(seconds: Int, source: Int) {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = runCatching {
            AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        }.getOrElse {
            Log.w("MicTest", "AudioRecord failed", it)
            show("Микрофон недоступен: $it")
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.w("MicTest", "AudioRecord not initialized (source=$source)")
            show("Микрофон не инициализирован")
            return
        }
        val pcm = java.io.ByteArrayOutputStream()
        val buffer = ShortArray(rate / 2)
        recorder.startRecording()
        Log.i("MicTest", "recording source=$source device=${recorder.routedDevice?.productName}/${recorder.routedDevice?.type}")
        val end = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < end) {
            val n = recorder.read(buffer, 0, buffer.size)
            if (n <= 0) continue
            var peak = 0
            for (i in 0 until n) {
                peak = maxOf(peak, abs(buffer[i].toInt()))
                pcm.write(buffer[i].toInt() and 0xFF)
                pcm.write(buffer[i].toInt() shr 8 and 0xFF)
            }
            Log.i("MicTest", "peak=$peak")
            show("Запись… говорите\nуровень: $peak")
        }
        recorder.stop()
        recorder.release()
        val saved = saveToDownloads(wav(pcm.toByteArray(), rate))
        Log.i("MicTest", "saved $saved (${pcm.size()} bytes)")
        show("Готово")
        runOnUiThread { label.postDelayed({ finish() }, 800) }
    }

    /** Download/beam_mic.wav, where adb can read it (the app's own folder is closed to adb here). */
    private fun saveToDownloads(bytes: ByteArray): String {
        val resolver = contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.delete(collection, "${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf("beam_mic.wav"))
        val uri = resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "beam_mic.wav")
                put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
            },
        ) ?: return "failed"
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        return "/sdcard/Download/beam_mic.wav"
    }

    private fun wav(data: ByteArray, rate: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun int(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun short(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.write("RIFF".toByteArray()); int(36 + data.size); out.write("WAVEfmt ".toByteArray())
        int(16); short(1); short(1); int(rate); int(rate * 2); short(2); short(16)
        out.write("data".toByteArray()); int(data.size); out.write(data)
        return out.toByteArray()
    }
}
