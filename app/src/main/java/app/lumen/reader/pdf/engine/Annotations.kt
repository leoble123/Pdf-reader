package app.lumen.reader.pdf.engine

import app.lumen.reader.pdf.Mark
import java.io.OutputStream

/**
 * Turns Lumen's page-normalized markup into real PDF annotations (Ink and FreeText) with
 * appearance streams, so every PDF viewer shows them and they stay editable/removable there.
 */
object PdfAnnotator {

    /** Box the page is displayed with, plus its rotation; mirrors how PDFium lays pages out. */
    private class Geometry(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val rotate: Int) {
        val displayW get() = if (rotate % 180 == 0) x1 - x0 else y1 - y0
        val displayH get() = if (rotate % 180 == 0) y1 - y0 else x1 - x0

        /** Normalized display point → PDF user space. */
        fun map(nx: Float, ny: Float): Pair<Double, Double> {
            val dx = nx * displayW
            val dy = ny * displayH
            return when (rotate) {
                90 -> (x0 + dy) to (y0 + dx)
                180 -> (x1 - dx) to (y0 + dy)
                270 -> (x1 - dy) to (y1 - dx)
                else -> (x0 + dx) to (y1 - dy)
            }
        }
    }

    private fun geometry(reader: PdfReader, page: PdfReader.Page): Geometry {
        fun box(key: String): List<Double>? = (reader.resolve(page.dict[key] ?: page.inherited[key]) as? PdfArray)
            ?.items?.mapNotNull { (reader.resolve(it) as? PdfNum)?.value }?.takeIf { it.size == 4 }
        val b = box("CropBox") ?: box("MediaBox") ?: listOf(0.0, 0.0, 612.0, 792.0)
        val rot = (reader.resolve(page.dict["Rotate"] ?: page.inherited["Rotate"]) as? PdfNum)?.int ?: 0
        return Geometry(minOf(b[0], b[2]), minOf(b[1], b[3]), maxOf(b[0], b[2]), maxOf(b[1], b[3]), ((rot % 360) + 360) % 360)
    }

    /**
     * Writes [source] plus the given marks as an incremental update. Falls back to a full
     * rewrite when the original's cross-reference is damaged.
     */
    fun save(reader: PdfReader, marks: Map<Int, List<Mark>>, out: OutputStream) {
        if (reader.isRebuilt) {
            val asm = PdfAssembler()
            reader.pages.forEach { asm.addPage(reader, it) }
            val tmp = java.io.ByteArrayOutputStream().also { asm.write(it) }.toByteArray()
            return save(PdfReader(tmp), marks, out)
        }
        val up = PdfUpdater(reader)
        for ((index, list) in marks) {
            if (list.isEmpty()) continue
            val page = reader.pages.getOrNull(index) ?: continue
            val geo = geometry(reader, page)
            val refs = list.mapNotNull { m -> annotation(up, geo, m) }
            val existing = (reader.resolve(page.dict["Annots"]) as? PdfArray)?.items.orEmpty()
            val dict = page.dict.copy()
            dict["Annots"] = PdfArray((existing + refs).toMutableList())
            up.replace(page.ref, dict)
        }
        up.write(out)
    }

    private fun annotation(up: PdfUpdater, geo: Geometry, m: Mark): PdfRef? = when (m) {
        is Mark.Ink -> ink(up, geo, m)
        is Mark.Note -> note(up, geo, m)
    }

    private fun rgb(color: Int) = Triple(((color shr 16) and 0xFF) / 255.0, ((color shr 8) and 0xFF) / 255.0, (color and 0xFF) / 255.0)

    private fun nums(vararg v: Double) = PdfArray(v.map { PdfNum(it) }.toMutableList())

    private fun ink(up: PdfUpdater, geo: Geometry, m: Mark.Ink): PdfRef {
        val pts = (0 until m.points.size / 2).map { geo.map(m.points[it * 2], m.points[it * 2 + 1]) }
        val width = m.width * geo.displayW
        val pad = width
        val minX = pts.minOf { it.first } - pad; val maxX = pts.maxOf { it.first } + pad
        val minY = pts.minOf { it.second } - pad; val maxY = pts.maxOf { it.second } + pad
        val (r, g, b) = rgb(m.color)
        val f = PdfSerializer::num
        val path = StringBuilder()
        path.append("${f(r)} ${f(g)} ${f(b)} RG ${f(width)} w ${if (m.highlighter) 2 else 1} J 1 j ")
        if (m.highlighter) path.append("/GS0 gs ")
        pts.forEachIndexed { i, (x, y) -> path.append("${f(x)} ${f(y)} ${if (i == 0) "m" else "l"} ") }
        if (pts.size == 1) path.append("${f(pts[0].first + 0.01)} ${f(pts[0].second)} l ")
        path.append("S")
        val resources = PdfDict()
        if (m.highlighter) {
            resources["ExtGState"] = PdfDict().apply {
                this["GS0"] = PdfDict().apply {
                    this["Type"] = PdfName("ExtGState"); this["CA"] = PdfNum(0.4); this["BM"] = PdfName("Multiply")
                }
            }
        }
        val ap = up.add(PdfStream(PdfDict().apply {
            this["Type"] = PdfName("XObject"); this["Subtype"] = PdfName("Form")
            this["BBox"] = nums(minX, minY, maxX, maxY)
            this["Resources"] = resources
        }, path.toString().toByteArray(Charsets.ISO_8859_1)))
        return up.add(PdfDict().apply {
            this["Type"] = PdfName("Annot")
            this["Subtype"] = PdfName("Ink")
            this["Rect"] = nums(minX, minY, maxX, maxY)
            this["InkList"] = PdfArray(mutableListOf(PdfArray(pts.flatMap { listOf(PdfNum(it.first), PdfNum(it.second)) }.toMutableList())))
            this["C"] = nums(r, g, b)
            this["BS"] = PdfDict().apply { this["W"] = PdfNum(width) }
            if (m.highlighter) { this["CA"] = PdfNum(0.4); this["IT"] = PdfName("InkHighlight") }
            this["F"] = PdfNum(4)
            this["T"] = PdfAssembler.utf16("Lumen")
            this["AP"] = PdfDict().apply { this["N"] = ap }
        })
    }

    private fun note(up: PdfUpdater, geo: Geometry, m: Mark.Note): PdfRef {
        val size = m.size * geo.displayW
        // Text runs horizontally on screen; for rotated pages we keep it upright by rotating the matrix.
        val (x, y) = geo.map(m.x, m.y)
        val text = m.text.map { c -> if (c.code in 32..255) c else '?' }.joinToString("")
        val width = text.length * size * 0.55 + size
        val (r, g, b) = rgb(m.color)
        val f = PdfSerializer::num
        val esc = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        val angle = when (geo.rotate) { 90 -> 90.0; 180 -> 180.0; 270 -> 270.0; else -> 0.0 }
        val rad = Math.toRadians(angle)
        val cos = Math.cos(rad); val sin = Math.sin(rad)
        val content = "BT /Helv ${f(size)} Tf ${f(r)} ${f(g)} ${f(b)} rg ${f(cos)} ${f(sin)} ${f(-sin)} ${f(cos)} ${f(x)} ${f(y - size / 3)} Tm ($esc) Tj ET"
        val minX = minOf(x, x + width * cos) - size; val maxX = maxOf(x, x + width * cos) + size
        val minY = minOf(y, y + width * sin) - size; val maxY = maxOf(y, y + width * sin) + size
        val font = PdfDict().apply {
            this["Type"] = PdfName("Font"); this["Subtype"] = PdfName("Type1")
            this["BaseFont"] = PdfName("Helvetica"); this["Encoding"] = PdfName("WinAnsiEncoding")
        }
        val ap = up.add(PdfStream(PdfDict().apply {
            this["Type"] = PdfName("XObject"); this["Subtype"] = PdfName("Form")
            this["BBox"] = nums(minX, minY, maxX, maxY)
            this["Resources"] = PdfDict().apply { this["Font"] = PdfDict().apply { this["Helv"] = font } }
        }, content.toByteArray(Charsets.ISO_8859_1)))
        return up.add(PdfDict().apply {
            this["Type"] = PdfName("Annot")
            this["Subtype"] = PdfName("FreeText")
            this["Rect"] = nums(minX, minY, maxX, maxY)
            this["Contents"] = PdfAssembler.utf16(m.text)
            this["DA"] = PdfStr("/Helv ${f(size)} Tf ${f(r)} ${f(g)} ${f(b)} rg".toByteArray())
            this["F"] = PdfNum(4)
            this["T"] = PdfAssembler.utf16("Lumen")
            this["AP"] = PdfDict().apply { this["N"] = ap }
        })
    }
}
