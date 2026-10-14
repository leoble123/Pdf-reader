package app.lumen.reader.pdf.engine

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.Inflater

/** Minimal PDF object model: enough to copy pages losslessly and append annotations. */
sealed interface PdfObj

data object PdfNull : PdfObj
data class PdfBool(val value: Boolean) : PdfObj
data class PdfNum(val value: Double) : PdfObj {
    val int: Int get() = value.toInt()
    constructor(v: Int) : this(v.toDouble())
}
class PdfStr(val bytes: ByteArray) : PdfObj {
    override fun equals(other: Any?) = other is PdfStr && other.bytes.contentEquals(bytes)
    override fun hashCode() = bytes.contentHashCode()
    fun text(): String = if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
        String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE) else String(bytes, Charsets.ISO_8859_1)
}
data class PdfName(val name: String) : PdfObj
class PdfArray(val items: MutableList<PdfObj> = ArrayList()) : PdfObj {
    operator fun get(i: Int) = items[i]
    val size get() = items.size
}
class PdfDict(val map: LinkedHashMap<String, PdfObj> = LinkedHashMap()) : PdfObj {
    operator fun get(key: String): PdfObj? = map[key]
    operator fun set(key: String, value: PdfObj) {
        map[key] = value
    }
    fun name(key: String) = (map[key] as? PdfName)?.name
    fun copy() = PdfDict(LinkedHashMap(map))
}
data class PdfRef(val num: Int, val gen: Int) : PdfObj

/** A stream keeps its bytes exactly as stored (still encoded) so copying never re-compresses. */
class PdfStream(val dict: PdfDict, val raw: ByteArray) : PdfObj {
    fun decoded(): ByteArray {
        val filter = dict["Filter"]
        val filters = when (filter) {
            is PdfName -> listOf(filter.name)
            is PdfArray -> filter.items.mapNotNull { (it as? PdfName)?.name }
            else -> emptyList()
        }
        var data = raw
        filters.forEachIndexed { i, f ->
            data = when (f) {
                "FlateDecode", "Fl" -> {
                    val parms = when (val p = dict["DecodeParms"]) {
                        is PdfDict -> p
                        is PdfArray -> p.items.getOrNull(i) as? PdfDict
                        else -> null
                    }
                    unpredict(inflate(data), parms)
                }
                else -> throw PdfException("Unsupported stream filter $f")
            }
        }
        return data
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(data)
        val out = ByteArrayOutputStream(data.size * 4)
        val buf = ByteArray(8192)
        try {
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break
                }
                out.write(buf, 0, n)
            }
        } finally {
            inf.end()
        }
        return out.toByteArray()
    }

    /** PNG predictors (used by cross-reference and object streams). */
    private fun unpredict(data: ByteArray, parms: PdfDict?): ByteArray {
        val predictor = (parms?.get("Predictor") as? PdfNum)?.int ?: 1
        if (predictor < 10) return data
        val colors = (parms?.get("Colors") as? PdfNum)?.int ?: 1
        val bpc = (parms?.get("BitsPerComponent") as? PdfNum)?.int ?: 8
        val columns = (parms?.get("Columns") as? PdfNum)?.int ?: 1
        val bpp = maxOf(1, colors * bpc / 8)
        val rowLen = (columns * colors * bpc + 7) / 8
        val out = ByteArrayOutputStream(data.size)
        var prev = ByteArray(rowLen)
        var i = 0
        while (i + rowLen < data.size + 1 && i < data.size) {
            val type = data[i].toInt() and 0xFF
            val row = ByteArray(rowLen)
            val n = minOf(rowLen, data.size - i - 1)
            System.arraycopy(data, i + 1, row, 0, n)
            for (x in 0 until rowLen) {
                val left = if (x >= bpp) row[x - bpp].toInt() and 0xFF else 0
                val up = prev[x].toInt() and 0xFF
                val ul = if (x >= bpp) prev[x - bpp].toInt() and 0xFF else 0
                val v = row[x].toInt() and 0xFF
                row[x] = when (type) {
                    1 -> v + left
                    2 -> v + up
                    3 -> v + (left + up) / 2
                    4 -> {
                        val p = left + up - ul
                        val pa = kotlin.math.abs(p - left)
                        val pb = kotlin.math.abs(p - up)
                        val pc = kotlin.math.abs(p - ul)
                        v + if (pa <= pb && pa <= pc) left else if (pb <= pc) up else ul
                    }
                    else -> v
                }.toByte()
            }
            out.write(row)
            prev = row
            i += rowLen + 1
        }
        return out.toByteArray()
    }
}

class PdfException(message: String) : Exception(message)

/** Serialises objects back to PDF syntax. */
object PdfSerializer {

    fun write(obj: PdfObj, out: OutputStream) {
        when (obj) {
            PdfNull -> out.ascii("null")
            is PdfBool -> out.ascii(if (obj.value) "true" else "false")
            is PdfNum -> out.ascii(num(obj.value))
            is PdfName -> out.ascii(name(obj.name))
            is PdfStr -> {
                out.ascii("<")
                for (b in obj.bytes) out.ascii("%02X".format(b.toInt() and 0xFF))
                out.ascii(">")
            }
            is PdfRef -> out.ascii("${obj.num} ${obj.gen} R")
            is PdfArray -> {
                out.ascii("[")
                obj.items.forEachIndexed { i, it ->
                    if (i > 0) out.ascii(" ")
                    write(it, out)
                }
                out.ascii("]")
            }
            is PdfDict -> {
                out.ascii("<<")
                for ((k, v) in obj.map) {
                    out.ascii(name(k)); out.ascii(" "); write(v, out); out.ascii("\n")
                }
                out.ascii(">>")
            }
            is PdfStream -> {
                val d = obj.dict.copy()
                d["Length"] = PdfNum(obj.raw.size)
                write(d, out)
                out.ascii("\nstream\n")
                out.write(obj.raw)
                out.ascii("\nendstream")
            }
        }
    }

    fun num(v: Double): String =
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString()
        else "%.4f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')

    fun name(n: String): String {
        val sb = StringBuilder("/")
        for (c in n) {
            if (c.code in 0x21..0x7E && c !in "#()<>[]{}/%") sb.append(c)
            else for (b in c.toString().toByteArray(Charsets.UTF_8)) sb.append("#%02X".format(b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun OutputStream.ascii(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))
}
