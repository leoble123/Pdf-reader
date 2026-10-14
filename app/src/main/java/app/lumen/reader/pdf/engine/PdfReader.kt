package app.lumen.reader.pdf.engine

import java.io.ByteArrayOutputStream

/**
 * Reads the object structure of a PDF from memory: classic and stream cross-reference
 * sections (including hybrid files and /Prev chains), compressed object streams, and a
 * full-file scan fallback for damaged cross-reference tables.
 */
class PdfReader(val data: ByteArray) {

    private class Entry(val offset: Int, val gen: Int, val inStream: Int = -1, val index: Int = 0)

    private val xref = HashMap<Int, Entry>()
    private val cache = HashMap<Int, PdfObj>()
    private val objStreams = HashMap<Int, Pair<Int, List<Pair<Int, Int>>>>() // stream num → (first, [(num, offset)])
    val trailer: PdfDict
    val startXref: Int
    /** True when the cross-reference was broken and rebuilt by scanning; such files can't take incremental updates. */
    var isRebuilt = false
        private set

    init {
        startXref = findStartXref()
        val t = runCatching { readXrefChain(startXref) }.getOrNull()
        trailer = if (t != null && t["Root"] != null) t else {
            isRebuilt = true
            rebuild()
        }
        if (trailer["Encrypt"] != null) throw PdfException("This PDF is encrypted")
    }

    // ---------------------------------------------------------------- objects

    fun resolve(obj: PdfObj?): PdfObj? = if (obj is PdfRef) getObject(obj.num) else obj

    fun dict(obj: PdfObj?): PdfDict? = when (val r = resolve(obj)) {
        is PdfDict -> r
        is PdfStream -> r.dict
        else -> null
    }

    fun getObject(num: Int): PdfObj {
        cache[num]?.let { return it }
        val e = xref[num] ?: return PdfNull
        val obj = if (e.inStream >= 0) fromObjectStream(e.inStream, num) else parseIndirect(e.offset)
        cache[num] = obj
        return obj
    }

    val maxObjectNumber: Int get() = maxOf(xref.keys.maxOrNull() ?: 0, (trailer["Size"] as? PdfNum)?.int?.minus(1) ?: 0)

    private fun parseIndirect(offset: Int): PdfObj {
        val lx = Lexer(data, offset)
        lx.nextToken() // num
        lx.nextToken() // gen
        val kw = lx.nextToken()
        if (kw != "obj") throw PdfException("Bad object at $offset")
        val obj = Parser(lx, this).parseObject()
        if (obj is PdfDict) {
            val save = lx.pos
            if (lx.nextToken() == "stream") return readStream(obj, lx)
            lx.pos = save
        }
        return obj
    }

    private fun readStream(dict: PdfDict, lx: Lexer): PdfStream {
        var p = lx.pos
        if (p < data.size && data[p] == '\r'.code.toByte()) p++
        if (p < data.size && data[p] == '\n'.code.toByte()) p++
        val declared = (resolve(dict["Length"]) as? PdfNum)?.int
        val end = if (declared != null && p + declared <= data.size && matchesAt(endStreamAfter(p + declared), "endstream")) p + declared
        else {
            // Length is wrong or indirect-and-missing: find "endstream" ourselves.
            var e = indexOf("endstream", p)
            if (e < 0) e = data.size
            while (e > p && (data[e - 1] == '\n'.code.toByte() || data[e - 1] == '\r'.code.toByte())) e--
            e
        }
        return PdfStream(dict, data.copyOfRange(p, end))
    }

    private fun endStreamAfter(pos: Int): Int {
        var p = pos
        while (p < data.size && isWhite(data[p])) p++
        return p
    }

    private fun fromObjectStream(streamNum: Int, num: Int): PdfObj {
        val stream = getObject(streamNum) as? PdfStream ?: return PdfNull
        val bytes = stream.decoded()
        val (first, table) = objStreams.getOrPut(streamNum) {
            val n = (stream.dict["N"] as? PdfNum)?.int ?: 0
            val first = (stream.dict["First"] as? PdfNum)?.int ?: 0
            val lx = Lexer(bytes, 0)
            first to List(n) { lx.nextToken()!!.toInt() to lx.nextToken()!!.toInt() }
        }
        val off = table.firstOrNull { it.first == num }?.second ?: return PdfNull
        return Parser(Lexer(bytes, first + off), this).parseObject()
    }

    // ---------------------------------------------------------------- xref

    private fun findStartXref(): Int {
        val idx = lastIndexOf("startxref")
        if (idx < 0) return -1
        val lx = Lexer(data, idx + 9)
        return lx.nextToken()?.toIntOrNull() ?: -1
    }

    private fun readXrefChain(start: Int): PdfDict {
        var offset = start
        var first: PdfDict? = null
        val seen = HashSet<Int>()
        while (offset in data.indices && seen.add(offset)) {
            val section = if (matchesAt(skipWhite(offset), "xref")) readXrefTable(skipWhite(offset)) else readXrefStream(offset)
            if (first == null) first = section
            (section["XRefStm"] as? PdfNum)?.let { readXrefStream(it.int) }
            offset = (section["Prev"] as? PdfNum)?.int ?: break
        }
        return first ?: throw PdfException("No cross-reference")
    }

    private fun readXrefTable(offset: Int): PdfDict {
        val lx = Lexer(data, offset + 4)
        while (true) {
            val save = lx.pos
            val tok = lx.nextToken() ?: throw PdfException("Truncated xref")
            if (tok == "trailer") break
            val start = tok.toIntOrNull() ?: run { lx.pos = save; throw PdfException("Bad xref") }
            val count = lx.nextToken()!!.toInt()
            for (i in 0 until count) {
                val off = lx.nextToken()!!.toLong()
                val gen = lx.nextToken()!!.toInt()
                val type = lx.nextToken()
                val num = start + i
                if (type == "n" && !xref.containsKey(num) && off > 0) xref[num] = Entry(off.toInt(), gen)
                else if (type == "f" && !xref.containsKey(num)) xref[num] = Entry(-1, gen)
            }
        }
        return Parser(lx, this).parseObject() as PdfDict
    }

    private fun readXrefStream(offset: Int): PdfDict {
        val stream = parseIndirect(offset) as? PdfStream ?: throw PdfException("Bad xref stream")
        val d = stream.dict
        val w = (d["W"] as PdfArray).items.map { (it as PdfNum).int }
        val size = (d["Size"] as PdfNum).int
        val index = (d["Index"] as? PdfArray)?.items?.map { (it as PdfNum).int } ?: listOf(0, size)
        val bytes = stream.decoded()
        var p = 0
        fun field(width: Int, default: Long): Long {
            if (width == 0) return default
            var v = 0L
            repeat(width) { v = (v shl 8) or (bytes.getOrElse(p++) { 0 }.toLong() and 0xFF) }
            return v
        }
        var i = 0
        while (i + 1 < index.size) {
            val start = index[i]
            val count = index[i + 1]
            for (k in 0 until count) {
                val type = field(w[0], 1)
                val a = field(w[1], 0)
                val b = field(w[2], 0)
                val num = start + k
                if (xref.containsKey(num)) continue
                when (type) {
                    1L -> xref[num] = Entry(a.toInt(), b.toInt())
                    2L -> xref[num] = Entry(-1, 0, a.toInt(), b.toInt())
                    0L -> xref[num] = Entry(-1, 0)
                }
            }
            i += 2
        }
        return d
    }

    /** Damaged file: find every "n g obj" and the last trailer (or catalog) by scanning. */
    private fun rebuild(): PdfDict {
        xref.clear()
        val re = Regex("(\\d+)\\s+(\\d+)\\s+obj")
        val text = String(data, Charsets.ISO_8859_1)
        var root: PdfRef? = null
        for (m in re.findAll(text)) {
            val num = m.groupValues[1].toInt()
            xref[num] = Entry(m.range.first, m.groupValues[2].toInt())
        }
        // Expand object streams so compressed objects are reachable too.
        for ((num, _) in xref.toMap()) {
            val obj = runCatching { getObject(num) }.getOrNull()
            if (obj is PdfStream && obj.dict.name("Type") == "ObjStm") {
                runCatching {
                    val bytes = obj.decoded()
                    val n = (obj.dict["N"] as PdfNum).int
                    val lx = Lexer(bytes, 0)
                    for (k in 0 until n) {
                        val on = lx.nextToken()!!.toInt(); lx.nextToken()
                        if (!xref.containsKey(on)) xref[on] = Entry(-1, 0, num, k)
                    }
                }
            }
            if (obj is PdfDict && obj.name("Type") == "Catalog") root = PdfRef(num, 0)
        }
        cache.clear()
        val t = text.lastIndexOf("trailer")
        val trailer = if (t >= 0) runCatching { Parser(Lexer(data, t + 7), this).parseObject() as PdfDict }.getOrNull() else null
        return (trailer ?: PdfDict()).also { tr ->
            if (tr["Root"] == null) tr["Root"] = root ?: throw PdfException("Not a PDF")
            tr["Size"] = PdfNum((xref.keys.maxOrNull() ?: 0) + 1)
        }
    }

    // ---------------------------------------------------------------- pages

    class Page(val ref: PdfRef, val dict: PdfDict, val inherited: PdfDict)

    val pages: List<Page> by lazy {
        val out = ArrayList<Page>()
        val root = dict(trailer["Root"]) ?: throw PdfException("No catalog")
        fun walk(nodeRef: PdfObj?, inherited: PdfDict, depth: Int) {
            if (depth > 64) return
            val node = dict(nodeRef) ?: return
            val inh = inherited.copy()
            for (k in INHERITABLE) node[k]?.let { inh[k] = it }
            val kids = resolve(node["Kids"]) as? PdfArray
            if (node.name("Type") == "Pages" || kids != null) {
                kids?.items?.forEach { walk(it, inh, depth + 1) }
            } else if (nodeRef is PdfRef) {
                out += Page(nodeRef, node, inh)
            }
        }
        walk(root["Pages"], PdfDict(), 0)
        out
    }

    // ---------------------------------------------------------------- bytes

    private fun isWhite(b: Byte) = b == ' '.code.toByte() || b == '\n'.code.toByte() || b == '\r'.code.toByte() ||
        b == '\t'.code.toByte() || b == 0.toByte() || b == 12.toByte()

    private fun skipWhite(p0: Int): Int {
        var p = p0
        while (p < data.size && isWhite(data[p])) p++
        return p
    }

    private fun matchesAt(p: Int, s: String): Boolean {
        if (p < 0 || p + s.length > data.size) return false
        for (i in s.indices) if (data[p + i] != s[i].code.toByte()) return false
        return true
    }

    private fun indexOf(s: String, from: Int): Int {
        var i = from
        while (i <= data.size - s.length) {
            if (matchesAt(i, s)) return i
            i++
        }
        return -1
    }

    private fun lastIndexOf(s: String): Int {
        var i = data.size - s.length
        val stop = maxOf(0, data.size - 4096 * 4)
        while (i >= stop) {
            if (matchesAt(i, s)) return i
            i--
        }
        return -1
    }

    companion object {
        val INHERITABLE = listOf("Resources", "MediaBox", "CropBox", "Rotate")
    }
}

internal class Lexer(val data: ByteArray, var pos: Int) {

    private fun isWhite(c: Int) = c == 32 || c == 10 || c == 13 || c == 9 || c == 0 || c == 12
    private fun isDelim(c: Int) = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
        c == '['.code || c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code

    fun peek(): Int = if (pos < data.size) data[pos].toInt() and 0xFF else -1

    fun skip() {
        while (pos < data.size) {
            val c = peek()
            if (isWhite(c)) pos++
            else if (c == '%'.code) {
                while (pos < data.size && peek() != 10 && peek() != 13) pos++
            } else break
        }
    }

    /** Next bare token (number / keyword / delimiter), or null at EOF. */
    fun nextToken(): String? {
        skip()
        if (pos >= data.size) return null
        val c = peek()
        if (c == '<'.code && pos + 1 < data.size && data[pos + 1] == '<'.code.toByte()) {
            pos += 2; return "<<"
        }
        if (c == '>'.code && pos + 1 < data.size && data[pos + 1] == '>'.code.toByte()) {
            pos += 2; return ">>"
        }
        if (isDelim(c)) {
            pos++; return c.toChar().toString()
        }
        val start = pos
        while (pos < data.size && !isWhite(peek()) && !isDelim(peek())) pos++
        return String(data, start, pos - start, Charsets.ISO_8859_1)
    }

    fun readName(): String {
        val out = ByteArrayOutputStream()
        while (pos < data.size && !isWhite(peek()) && !isDelim(peek())) {
            val c = peek()
            if (c == '#'.code && pos + 2 < data.size) {
                val hex = String(data, pos + 1, 2, Charsets.ISO_8859_1).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex); pos += 3; continue
                }
            }
            out.write(c); pos++
        }
        return out.toString("UTF-8")
    }

    fun readLiteralString(): ByteArray {
        val out = ByteArrayOutputStream()
        var depth = 1
        while (pos < data.size) {
            var c = peek(); pos++
            when (c) {
                '('.code -> { depth++; out.write(c) }
                ')'.code -> { depth--; if (depth == 0) return out.toByteArray(); out.write(c) }
                '\\'.code -> {
                    c = peek(); pos++
                    when (c) {
                        'n'.code -> out.write(10); 'r'.code -> out.write(13); 't'.code -> out.write(9)
                        'b'.code -> out.write(8); 'f'.code -> out.write(12)
                        13 -> if (peek() == 10) pos++
                        10 -> Unit
                        in '0'.code..'7'.code -> {
                            var v = c - '0'.code
                            repeat(2) {
                                val d = peek()
                                if (d in '0'.code..'7'.code) { v = v * 8 + (d - '0'.code); pos++ }
                            }
                            out.write(v and 0xFF)
                        }
                        else -> out.write(c)
                    }
                }
                else -> out.write(c)
            }
        }
        return out.toByteArray()
    }

    fun readHexString(): ByteArray {
        val out = ByteArrayOutputStream()
        var hi = -1
        while (pos < data.size) {
            val c = peek(); pos++
            if (c == '>'.code) break
            val v = Character.digit(c, 16)
            if (v < 0) continue
            if (hi < 0) hi = v else { out.write(hi * 16 + v); hi = -1 }
        }
        if (hi >= 0) out.write(hi * 16)
        return out.toByteArray()
    }
}

internal class Parser(private val lx: Lexer, private val reader: PdfReader?) {

    fun parseObject(): PdfObj {
        lx.skip()
        return when (lx.peek()) {
            '/'.code -> { lx.pos++; PdfName(lx.readName()) }
            '('.code -> { lx.pos++; PdfStr(lx.readLiteralString()) }
            '['.code -> {
                lx.pos++
                val arr = PdfArray()
                while (true) {
                    lx.skip()
                    if (lx.peek() == ']'.code || lx.peek() < 0) { lx.pos++; break }
                    arr.items += parseObject()
                }
                arr
            }
            '<'.code -> {
                if (lx.pos + 1 < lx.data.size && lx.data[lx.pos + 1] == '<'.code.toByte()) {
                    lx.pos += 2
                    val d = PdfDict()
                    while (true) {
                        lx.skip()
                        if (lx.peek() == '>'.code || lx.peek() < 0) { lx.pos += 2; break }
                        if (lx.peek() != '/'.code) { parseObject(); continue } // junk: skip
                        lx.pos++
                        val key = lx.readName()
                        d[key] = parseObject()
                    }
                    d
                } else {
                    lx.pos++; PdfStr(lx.readHexString())
                }
            }
            else -> {
                val tok = lx.nextToken() ?: return PdfNull
                when (tok) {
                    "true" -> PdfBool(true)
                    "false" -> PdfBool(false)
                    "null" -> PdfNull
                    else -> {
                        val n = tok.toDoubleOrNull() ?: return PdfNull
                        // Look ahead for "gen R".
                        if (tok.all { it.isDigit() }) {
                            val save = lx.pos
                            val t2 = lx.nextToken()
                            val t3 = lx.nextToken()
                            if (t2 != null && t2.all { it.isDigit() } && t3 == "R") return PdfRef(tok.toInt(), t2.toInt())
                            lx.pos = save
                        }
                        PdfNum(n)
                    }
                }
            }
        }
    }
}
