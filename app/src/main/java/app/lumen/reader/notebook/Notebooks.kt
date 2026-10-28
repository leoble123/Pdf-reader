package app.lumen.reader.notebook

import android.content.Context
import android.graphics.Paint
import android.net.Uri
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.FileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

enum class Paper(val label: String) { Blank("Blank"), Lined("Lined"), Grid("Grid"), Dots("Dots") }

/**
 * Notebooks are plain PDFs of empty paper kept in app storage. Opening one drops straight into
 * markup, so every pen feature (pressure, palm rejection, lasso, shapes…) works on them, and the
 * ink is stored separately, which lets pages be added by regenerating the paper underneath.
 */
object Notebooks {
    /** A4 in points. */
    const val PAGE_W = 595
    const val PAGE_H = 842

    class Info(val file: File, val name: String, val paper: Paper, val pages: Int, val modified: Long) {
        fun ref() = DocRef(Uri.fromFile(file), file.name, "application/pdf", file.length(), FileKind.Pdf)
    }

    fun dir(context: Context) = File(context.filesDir, "notebooks").apply { mkdirs() }

    fun isNotebook(context: Context, ref: DocRef): Boolean =
        ref.uri.scheme == "file" && ref.uri.path?.startsWith(dir(context).path + "/") == true

    private fun metaFile(pdf: File) = File(pdf.parentFile, pdf.nameWithoutExtension + ".json")

    fun list(context: Context): List<Info> =
        dir(context).listFiles { f -> f.extension == "pdf" }.orEmpty()
            .mapNotNull { info(it) }
            .sortedByDescending { it.modified }

    fun info(pdf: File): Info? = runCatching {
        val meta = JSONObject(metaFile(pdf).readText())
        Info(pdf, meta.optString("name", pdf.nameWithoutExtension), Paper.valueOf(meta.getString("paper")), meta.getInt("pages"), pdf.lastModified())
    }.getOrNull()

    fun info(context: Context, ref: DocRef): Info? = ref.uri.path?.let { File(it) }?.takeIf { isNotebook(context, ref) }?.let { info(it) }

    suspend fun create(context: Context, paper: Paper, pages: Int = 3): Info = withContext(Dispatchers.IO) {
        val taken = list(context).map { it.name }.toSet()
        val name = generateSequence(1) { it + 1 }.map { "Notebook $it" }.first { it !in taken }
        val file = File(dir(context), "notebook-" + System.currentTimeMillis() + ".pdf")
        write(file, paper, pages, name)
        info(file)!!
    }

    /** Appends a blank page. Ink is keyed by page index, so existing pages keep theirs. */
    suspend fun addPage(context: Context, ref: DocRef): Info? = withContext(Dispatchers.IO) {
        val current = info(context, ref) ?: return@withContext null
        write(current.file, current.paper, current.pages + 1, current.name)
        info(current.file)
    }

    suspend fun rename(info: Info, name: String) = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext
        metaFile(info.file).writeText(meta(info.paper, info.pages, name.trim()).toString())
    }

    fun delete(context: Context, info: Info) {
        info.file.delete()
        metaFile(info.file).delete()
        app.lumen.reader.pdf.InkController.fileFor(context, info.ref()).delete()
    }

    private fun meta(paper: Paper, pages: Int, name: String) =
        JSONObject().put("paper", paper.name).put("pages", pages).put("name", name)

    /**
     * Writes [pages] pages of [paper] as a vector PDF. Every page points at the same content
     * stream, so a 100-page notebook is barely bigger than a 1-page one.
     */
    fun write(file: File, paper: Paper, pages: Int, name: String) {
        val out = java.io.ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        fun obj(body: String) {
            offsets += out.size()
            out.write("${offsets.size} 0 obj\n$body\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        out.write("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n".toByteArray(Charsets.ISO_8859_1))
        val content = paperOps(paper)
        // 1 catalog, 2 pages, 3 content, 4.. page objects
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [${(0 until pages).joinToString(" ") { "${it + 4} 0 R" }}] /Count $pages >>")
        obj("<< /Length ${content.length} >>\nstream\n$content\nendstream")
        repeat(pages) { obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $PAGE_W $PAGE_H] /Contents 3 0 R /Resources << >> >>") }
        val xref = out.size()
        val sb = StringBuilder("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { sb.append(String.format(Locale.US, "%010d 00000 n \n", it)) }
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))

        // Write beside the old file and swap, so an open renderer never sees a half-written PDF.
        val tmp = File(file.parentFile, file.name + ".part")
        tmp.writeBytes(out.toByteArray())
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true); tmp.delete()
        }
        metaFile(file).writeText(meta(paper, pages, name).toString())
    }

    /** The paper pattern as PDF drawing operators (origin bottom-left), matching [drawPaper]. */
    private fun paperOps(paper: Paper): String {
        val w = PAGE_W.toFloat(); val h = PAGE_H.toFloat()
        fun f(v: Float) = String.format(Locale.US, "%.2f", v)
        val sb = StringBuilder("1 1 1 rg 0 0 $PAGE_W $PAGE_H re f\n")
        when (paper) {
            Paper.Blank -> {}
            Paper.Lined -> {
                sb.append("0.725 0.796 0.902 RG 0.6 w\n")
                var y = 96f
                while (y < h - 36f) { sb.append("36 ${f(h - y)} m ${f(w - 36f)} ${f(h - y)} l\n"); y += 24f }
                sb.append("S\n0.941 0.659 0.659 RG 0.8 w 72 36 m 72 ${f(h - 36f)} l S\n")
            }
            Paper.Grid -> {
                sb.append("0.827 0.859 0.902 RG 0.6 w\n")
                var x = 18f
                while (x < w) { sb.append("${f(x)} 0 m ${f(x)} $PAGE_H l\n"); x += 18f }
                var y = 18f
                while (y < h) { sb.append("0 ${f(h - y)} m $PAGE_W ${f(h - y)} l\n"); y += 18f }
                sb.append("S\n")
            }
            Paper.Dots -> {
                // Round-capped zero-length lines are the cheapest dots in PDF.
                sb.append("0.682 0.722 0.776 RG 1.8 w 1 J\n")
                var y = 18f
                while (y < h) {
                    var x = 18f
                    while (x < w) { sb.append("${f(x)} ${f(h - y)} m ${f(x)} ${f(h - y)} l\n"); x += 18f }
                    y += 18f
                }
                sb.append("S\n")
            }
        }
        return sb.toString()
    }

    /** Draws one sheet of paper in page points; also used for previews. */
    fun drawPaper(c: android.graphics.Canvas, paper: Paper, w: Float, h: Float) {
        c.drawColor(android.graphics.Color.WHITE)
        val k = w / PAGE_W
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB9CBE6.toInt(); strokeWidth = 0.6f * k }
        when (paper) {
            Paper.Blank -> {}
            Paper.Lined -> {
                val step = 24f * k
                var y = 96f * k
                while (y < h - 36f * k) {
                    c.drawLine(36f * k, y, w - 36f * k, y, line); y += step
                }
                val margin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF0A8A8.toInt(); strokeWidth = 0.8f * k }
                c.drawLine(72f * k, 36f * k, 72f * k, h - 36f * k, margin)
            }
            Paper.Grid -> {
                line.color = 0xFFD3DBE6.toInt()
                val step = 18f * k
                var x = 18f * k
                while (x < w) { c.drawLine(x, 0f, x, h, line); x += step }
                var y = 18f * k
                while (y < h) { c.drawLine(0f, y, w, y, line); y += step }
            }
            Paper.Dots -> {
                val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFAEB8C6.toInt() }
                val step = 18f * k
                var y = 18f * k
                while (y < h) {
                    var x = 18f * k
                    while (x < w) { c.drawCircle(x, y, 0.9f * k, dot); x += step }
                    y += step
                }
            }
        }
    }
}
