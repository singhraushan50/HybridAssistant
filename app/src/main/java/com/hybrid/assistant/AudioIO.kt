package com.hybrid.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/** Native audio IN: 16 kHz mono PCM16 WAV, sent straight to the multimodal model (no separate STT). */
class Recorder {
    private var rec: AudioRecord? = null
    private val buf = ByteArrayOutputStream()
    @Volatile private var on = false
    private var t: Thread? = null
    val recording get() = on

    @SuppressLint("MissingPermission")
    fun start() {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        rec = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
        buf.reset(); rec!!.startRecording(); on = true
        t = thread { val b = ByteArray(min); while (on) { val n = rec!!.read(b, 0, b.size); if (n > 0) buf.write(b, 0, n) } }
    }

    fun stop(): ByteArray {
        on = false; t?.join(); rec?.stop(); rec?.release(); rec = null
        val pcm = buf.toByteArray()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size)
        return h.array() + pcm
    }
}

/** Native audio OUT via Android TextToSpeech (fully offline when a voice pack is installed). */
class Speaker(ctx: Context) {
    private var ready = false
    private val tts = TextToSpeech(ctx) { ready = it == TextToSpeech.SUCCESS }
    fun speak(t: String) { if (ready && t.isNotBlank()) tts.speak(t.take(1500), TextToSpeech.QUEUE_FLUSH, null, "r") }
    fun stop() { tts.stop() }
}
