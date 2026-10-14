package app.lumen.reader.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Reads a list of text chunks aloud with the system TTS engine (offline voices on most phones).
 * [onChunk] reports which chunk is being spoken so the reader can follow along.
 */
class ReadAloud(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var pending: (() -> Unit)? = null
    var speaking by mutableStateOf(false)
        private set
    var current by mutableStateOf(-1)
        private set
    private var onChunk: (Int) -> Unit = {}

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        main.post { if (ready) pending?.invoke(); pending = null }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {
                main.post { current = id.toInt(); onChunk(current) }
            }

            override fun onDone(id: String) {
                if (id.startsWith("last:") || id.toIntOrNull() == lastId) main.post { speaking = false; current = -1 }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String) {
                main.post { speaking = false }
            }
        })
    }
    private var lastId = -1

    fun speak(chunks: List<String>, from: Int, onChunk: (Int) -> Unit) {
        val run = {
            this.onChunk = onChunk
            tts.stop()
            var first = true
            lastId = -1
            for (i in from until chunks.size) {
                val text = chunks[i].trim()
                if (text.isEmpty()) continue
                // TTS engines cap utterance length; long paragraphs are split on sentence ends.
                text.chunked(3500).forEach { part ->
                    tts.speak(part, if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, i.toString())
                    first = false
                }
                lastId = i
            }
            speaking = lastId >= 0
        }
        if (ready) run() else pending = run
    }

    fun stop() {
        tts.stop()
        speaking = false
        current = -1
    }

    fun shutdown() = tts.shutdown()
}
