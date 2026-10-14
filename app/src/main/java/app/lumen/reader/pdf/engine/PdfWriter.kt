package app.lumen.reader.pdf.engine

import app.lumen.reader.pdf.engine.PdfSerializer.ascii
import java.io.OutputStream

/**
 * Builds a brand-new PDF out of pages taken from existing documents (copied object-for-object,
 * so text, fonts and vectors are untouched) and/or JPEG images (for scans and photos).
 */
class PdfAssembler {

    private val objects = ArrayList<PdfObj?>() // index = number - 1
    private val pageRefs = ArrayList<PdfRef>()
    private val pagesRef: PdfRef = reserve()
    private val copyMaps = HashMap<PdfReader, HashMap<Int, PdfRef>>()

    private fun reserve(): PdfRef {
        objects += null
        return PdfRef(objects.size, 0)
    }

    private fun add(obj: PdfObj): PdfRef {
        objects += obj
        return PdfRef(objects.size, 0)
    }

    private fun set(ref: PdfRef, obj: PdfObj) {
        objects[ref.num - 1] = obj
    }

    val pageCount get() = pageRefs.size

    /**
     * Copies [page] from [reader]. [extraRotation] is added to the page's own /Rotate.
     * Links that jump to other pages are dropped (they could drag the whole source along).
     */
    fun addPage(reader: PdfReader, page: PdfReader.Page, extraRotation: Int = 0) {
        val map = copyMaps.getOrPut(reader) { HashMap() }
        val dict = page.dict.copy()
        for (k in PdfReader.INHERITABLE) if (dict[k] == null) page.inherited[k]?.let { dict[k] = it }
        dict.map.remove("Parent")
        dict.map.remove("StructParents")
        dict.map.remove("B") // article beads point at other pages
        val rotate = ((reader.resolve(dict["Rotate"]) as? PdfNum)?.int ?: 0) + extraRotation
        dict["Rotate"] = PdfNum(((rotate % 360) + 360) % 360)
        (reader.resolve(dict["Annots"]) as? PdfArray)?.let { annots ->
            dict["Annots"] = PdfArray(annots.items.mapNotNull { a -> sanitizeAnnot(reader, a) }.toMutableList())
        }
        val newPage = reserve()
        dict["Parent"] = pagesRef
        set(newPage, copy(reader, dict, map, newPage))
        pageRefs += newPage
    }

    private fun sanitizeAnnot(reader: PdfReader, a: PdfObj): PdfObj? {
        val d = reader.dict(a)?.copy() ?: return null
        if (d.name("Subtype") == "Popup") return null
        d.map.remove("P")
        d.map.remove("Parent")
        d.map.remove("Popup")
        d.map.remove("Dest")
        val action = reader.dict(d["A"])
        if (action != null && action.name("S") != "URI") d.map.remove("A")
        return d
    }

    /** Deep-copies an object graph, renumbering indirect objects. */
    private fun copy(reader: PdfReader, obj: PdfObj, map: HashMap<Int, PdfRef>, self: PdfRef?): PdfObj = when (obj) {
        is PdfRef -> map[obj.num] ?: run {
            val target = reader.resolve(obj) ?: PdfNull
            // Never follow a reference into another page or the page tree.
            val td = (target as? PdfDict) ?: (target as? PdfStream)?.dict
            if (td != null && (td.name("Type") == "Page" || td.name("Type") == "Pages")) PdfNull
            else {
                val ref = reserve()
                map[obj.num] = ref
                set(ref, copy(reader, target, map, null))
                ref
            }
        }
        is PdfDict -> PdfDict(LinkedHashMap<String, PdfObj>().also { m ->
            for ((k, v) in obj.map) m[k] = if (k == "Parent" && self != null) v else copy(reader, v, map, null)
        })
        is PdfArray -> PdfArray(obj.items.map { copy(reader, it, map, null) }.toMutableList())
        is PdfStream -> PdfStream(copy(reader, obj.dict, map, null) as PdfDict, obj.raw)
        else -> obj
    }

    /** Adds a page showing one JPEG, fitted to [pageWidth]×[pageHeight] points. */
    fun addJpegPage(jpeg: ByteArray, pixelWidth: Int, pixelHeight: Int, pageWidth: Float, pageHeight: Float, gray: Boolean = false, rotate: Int = 0) {
        val image = add(PdfStream(PdfDict().apply {
            this["Type"] = PdfName("XObject")
            this["Subtype"] = PdfName("Image")
            this["Width"] = PdfNum(pixelWidth)
            this["Height"] = PdfNum(pixelHeight)
            this["ColorSpace"] = PdfName(if (gray) "DeviceGray" else "DeviceRGB")
            this["BitsPerComponent"] = PdfNum(8)
            this["Filter"] = PdfName("DCTDecode")
        }, jpeg))
        val content = "q ${PdfSerializer.num(pageWidth.toDouble())} 0 0 ${PdfSerializer.num(pageHeight.toDouble())} 0 0 cm /Im0 Do Q".toByteArray()
        val contentRef = add(PdfStream(PdfDict(), content))
        val page = PdfDict().apply {
            this["Type"] = PdfName("Page")
            this["Parent"] = pagesRef
            this["MediaBox"] = PdfArray(mutableListOf(PdfNum(0), PdfNum(0), PdfNum(pageWidth.toDouble()), PdfNum(pageHeight.toDouble())))
            this["Resources"] = PdfDict().apply {
                this["XObject"] = PdfDict().apply { this["Im0"] = image }
                this["ProcSet"] = PdfArray(mutableListOf(PdfName("PDF"), PdfName("ImageC")))
            }
            this["Contents"] = contentRef
            if (rotate != 0) this["Rotate"] = PdfNum(rotate)
        }
        pageRefs += add(page)
    }

    fun write(out: OutputStream, title: String? = null) {
        set(pagesRef, PdfDict().apply {
            this["Type"] = PdfName("Pages")
            this["Kids"] = PdfArray(pageRefs.toMutableList())
            this["Count"] = PdfNum(pageRefs.size)
        })
        val catalog = add(PdfDict().apply {
            this["Type"] = PdfName("Catalog")
            this["Pages"] = pagesRef
        })
        val info = add(PdfDict().apply {
            this["Producer"] = PdfStr("Lumen".toByteArray())
            if (title != null) this["Title"] = utf16(title)
        })
        val counting = CountingStream(out)
        counting.ascii("%PDF-1.7\n%âãÏÓ\n")
        val offsets = IntArray(objects.size)
        objects.forEachIndexed { i, obj ->
            offsets[i] = counting.count
            counting.ascii("${i + 1} 0 obj\n")
            PdfSerializer.write(obj ?: PdfNull, counting)
            counting.ascii("\nendobj\n")
        }
        val xrefAt = counting.count
        counting.ascii("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) counting.ascii("%010d 00000 n \n".format(o))
        counting.ascii("trailer\n")
        PdfSerializer.write(PdfDict().apply {
            this["Size"] = PdfNum(objects.size + 1)
            this["Root"] = catalog
            this["Info"] = info
        }, counting)
        counting.ascii("\nstartxref\n$xrefAt\n%%EOF\n")
        counting.flush()
    }

    companion object {
        fun utf16(s: String) = PdfStr(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + s.toByteArray(Charsets.UTF_16BE))
    }
}

/**
 * Appends an incremental update to an existing PDF: new objects and replaced objects go after
 * the original bytes with a new cross-reference section. Nothing in the original is rewritten,
 * so bookmarks, forms, signatures' covered bytes and metadata all survive.
 */
class PdfUpdater(private val reader: PdfReader) {
    private var next = reader.maxObjectNumber + 1
    private val changed = LinkedHashMap<Int, Pair<Int, PdfObj>>() // num → (gen, obj)

    fun add(obj: PdfObj): PdfRef {
        val ref = PdfRef(next++, 0)
        changed[ref.num] = 0 to obj
        return ref
    }

    fun replace(ref: PdfRef, obj: PdfObj) {
        changed[ref.num] = ref.gen to obj
    }

    fun write(out: OutputStream) {
        val counting = CountingStream(out)
        counting.write(reader.data)
        if (reader.data.isNotEmpty() && reader.data.last() != '\n'.code.toByte()) counting.ascii("\n")
        val offsets = LinkedHashMap<Int, Int>()
        for ((num, pair) in changed) {
            offsets[num] = counting.count
            counting.ascii("$num ${pair.first} obj\n")
            PdfSerializer.write(pair.second, counting)
            counting.ascii("\nendobj\n")
        }
        val xrefAt = counting.count
        counting.ascii("xref\n0 1\n0000000000 65535 f \n")
        // One subsection per object keeps this simple and valid.
        for ((num, off) in offsets.toSortedMap()) {
            counting.ascii("$num 1\n%010d %05d n \n".format(off, changed.getValue(num).first))
        }
        val trailer = PdfDict().apply {
            for (k in listOf("Root", "Info", "ID")) reader.trailer[k]?.let { this[k] = it }
            this["Size"] = PdfNum(maxOf(next, reader.maxObjectNumber + 1))
            this["Prev"] = PdfNum(reader.startXref)
        }
        counting.ascii("trailer\n")
        PdfSerializer.write(trailer, counting)
        counting.ascii("\nstartxref\n$xrefAt\n%%EOF\n")
        counting.flush()
    }
}

internal class CountingStream(private val out: OutputStream) : OutputStream() {
    var count = 0
        private set

    override fun write(b: Int) {
        out.write(b); count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len); count += len
    }

    override fun flush() = out.flush()
}
