package app.lumen.reader.text

import android.content.Context
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.Docs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

class LoadedText(val text: String, val truncated: Boolean, val charset: String)

object TextLoader {

    const val MAX_BYTES = 24 shl 20

    suspend fun load(context: Context, ref: DocRef, maxBytes: Int = MAX_BYTES): LoadedText = withContext(Dispatchers.IO) {
        val out = ByteArrayOutputStream(if (ref.size in 1..maxBytes) ref.size.toInt() else 1 shl 16)
        var truncated = false
        Docs.open(context, ref).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                val room = maxBytes - out.size()
                if (n > room) {
                    out.write(buf, 0, room)
                    truncated = true
                    break
                }
                out.write(buf, 0, n)
            }
        }
        decode(out.toByteArray()).let { (text, cs) -> LoadedText(text, truncated, cs) }
    }

    fun decode(bytes: ByteArray): Pair<String, String> {
        // BOMs first.
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8) to "UTF-8"
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE) to "UTF-16LE"
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE) to "UTF-16BE"
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, trimPartialUtf8(bytes)))
                .toString() to "UTF-8"
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252")) to "Windows-1252"
        }
    }

    /** A truncated read may cut a multi-byte sequence in half; don't treat that as invalid UTF-8. */
    private fun trimPartialUtf8(b: ByteArray): Int {
        var end = b.size
        var i = end - 1
        var back = 0
        while (i >= 0 && back < 4 && (b[i].toInt() and 0xC0) == 0x80) {
            i--; back++
        }
        if (i >= 0) {
            val lead = b[i].toInt() and 0xFF
            val len = when {
                lead >= 0xF0 -> 4
                lead >= 0xE0 -> 3
                lead >= 0xC0 -> 2
                else -> 1
            }
            if (len > back + 1) end = i
        }
        return end
    }

    fun splitLines(text: String): List<String> {
        val lines = ArrayList<String>(text.length / 40 + 1)
        var start = 0
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                lines += text.substring(start, i).replace("\t", "    ")
                if (c == '\r' && i + 1 < n && text[i + 1] == '\n') i++
                start = i + 1
            }
            i++
        }
        lines += text.substring(start).replace("\t", "    ")
        return lines
    }
}
