package app.lumen.reader.text

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle

@Immutable
data class CodePalette(
    val plain: Color,
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val type: Color,
    val annotation: Color,
    val tag: Color,
    val attr: Color,
    val punctuation: Color
)

val DarkCode = CodePalette(
    plain = Color(0xFFE4E6EF), keyword = Color(0xFFC792EA), string = Color(0xFFC3E88D),
    number = Color(0xFFF78C6C), comment = Color(0xFF6B7189), type = Color(0xFFFFCB6B),
    annotation = Color(0xFF89DDFF), tag = Color(0xFFFF757F), attr = Color(0xFFFFCB6B), punctuation = Color(0xFF89DDFF)
)

val LightCode = CodePalette(
    plain = Color(0xFF1F2330), keyword = Color(0xFF8A3FD1), string = Color(0xFF2E7D32),
    number = Color(0xFFD9480F), comment = Color(0xFF8E94A8), type = Color(0xFFB26A00),
    annotation = Color(0xFF0B7ABF), tag = Color(0xFFD6336C), attr = Color(0xFFB26A00), punctuation = Color(0xFF0B7ABF)
)

enum class Lang { CLike, Python, Shell, Markup, Data, Sql, Css, Plain }

object Highlighter {

    fun langFor(ext: String): Lang = when (ext) {
        "kt", "kts", "java", "c", "h", "cpp", "cc", "hpp", "cs", "go", "rs", "swift", "m", "mm", "js", "mjs", "cjs",
        "ts", "tsx", "jsx", "dart", "scala", "groovy", "gradle", "php", "proto", "zig", "vue", "svelte", "json5", "jsonc" -> Lang.CLike
        "py", "rb", "r", "pl", "ex", "exs", "nix", "toml", "yaml", "yml", "ini", "cfg", "conf", "properties", "env",
        "dockerfile", "makefile", "cmake", "tf", "gitignore" -> Lang.Python
        "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd" -> Lang.Shell
        "xml", "html", "htm", "xhtml", "svg", "plist" -> Lang.Markup
        "json", "lock" -> Lang.Data
        "sql", "graphql", "gql" -> Lang.Sql
        "css", "scss", "sass", "less" -> Lang.Css
        else -> Lang.Plain
    }

    private val keywords = (
        "abstract actual annotation as assert async await break by case catch class companion const constructor continue " +
            "crossinline data def default defer del delegate do dynamic elif else enum except expect explicit export extends " +
            "extern external false final finally fn for from fun func function get go goto if impl implements import in " +
            "infix init inline inner instanceof interface internal is lambda lateinit let loop match mod module mut namespace " +
            "native new nil none not null object of open operator or out override package pass private protected pub public " +
            "raise readonly reified return sealed select self set static struct super suspend switch synchronized tailrec " +
            "this throw throws trait true try type typealias typeof union unsafe use using val var vararg virtual void " +
            "volatile when where while with yield None True False and elif echo fi then esac done local function " +
            "int long short byte char float double boolean bool string uint usize isize i32 i64 u8 u32 u64 f32 f64"
        ).split(' ').toHashSet()

    private val sqlKeywords = (
        "select from where and or not insert into values update set delete create table index view drop alter add " +
            "join left right inner outer full on as group by order having limit offset distinct union all case when " +
            "then else end null is in like between exists primary key foreign references default unique asc desc count " +
            "sum avg min max with returning query mutation fragment"
        ).split(' ').toHashSet()

    /** Marks lines that begin inside a block comment (C-style or markup), computed once per file. */
    fun blockCommentStarts(lines: List<String>, lang: Lang): BooleanArray {
        val result = BooleanArray(lines.size)
        val (open, close) = when (lang) {
            Lang.CLike, Lang.Css -> "/*" to "*/"
            Lang.Markup -> "<!--" to "-->"
            else -> return result
        }
        var inside = false
        for (i in lines.indices) {
            result[i] = inside
            val line = lines[i]
            var idx = 0
            while (idx < line.length) {
                if (!inside) {
                    val o = line.indexOf(open, idx)
                    if (o < 0) break
                    if (lang == Lang.CLike && line.lastIndexOf("//", o).let { it in 0 until o }) break
                    inside = true; idx = o + open.length
                } else {
                    val c = line.indexOf(close, idx)
                    if (c < 0) break
                    inside = false; idx = c + close.length
                }
            }
        }
        return result
    }

    fun highlight(line: String, lang: Lang, p: CodePalette, startsInComment: Boolean): AnnotatedString {
        if (lang == Lang.Plain || line.isEmpty()) return AnnotatedString(line)
        if (line.length > 4000) return AnnotatedString(line)
        return buildAnnotatedString {
            append(line)
            fun span(color: Color, s: Int, e: Int, italic: Boolean = false) {
                addStyle(SpanStyle(color = color, fontStyle = if (italic) FontStyle.Italic else null), s, e)
            }
            var i = 0
            val n = line.length
            if (startsInComment) {
                val close = if (lang == Lang.Markup) "-->" else "*/"
                val end = line.indexOf(close).let { if (it < 0) n else it + close.length }
                span(p.comment, 0, end, true)
                i = end
            }
            if (lang == Lang.Markup) {
                markup(line, i, p, ::span)
                return@buildAnnotatedString
            }
            while (i < n) {
                val c = line[i]
                when {
                    // Comments
                    lang == Lang.CLike && c == '/' && i + 1 < n && line[i + 1] == '/' ||
                        (lang == Lang.Python || lang == Lang.Shell) && c == '#' ||
                        lang == Lang.Sql && c == '-' && i + 1 < n && line[i + 1] == '-' -> {
                        span(p.comment, i, n, true); i = n
                    }
                    (lang == Lang.CLike || lang == Lang.Css) && c == '/' && i + 1 < n && line[i + 1] == '*' -> {
                        val end = line.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                        span(p.comment, i, end, true); i = end
                    }
                    c == '"' || c == '\'' || c == '`' -> {
                        var j = i + 1
                        while (j < n && line[j] != c) {
                            if (line[j] == '\\') j++
                            j++
                        }
                        val end = minOf(n, j + 1)
                        // JSON keys read better in the attribute colour.
                        val isKey = lang == Lang.Data && line.indexOf(':', end).let { it >= 0 && line.substring(end, it).isBlank() }
                        span(if (isKey) p.attr else p.string, i, end); i = end
                    }
                    c.isDigit() && (i == 0 || !line[i - 1].isLetterOrDigit() && line[i - 1] != '_') -> {
                        var j = i + 1
                        while (j < n && (line[j].isLetterOrDigit() || line[j] == '.' || line[j] == '_')) j++
                        span(p.number, i, j); i = j
                    }
                    c == '@' && lang != Lang.Css -> {
                        var j = i + 1
                        while (j < n && (line[j].isLetterOrDigit() || line[j] == '_' || line[j] == '.')) j++
                        span(p.annotation, i, j); i = j
                    }
                    c.isLetter() || c == '_' || c == '$' -> {
                        var j = i + 1
                        while (j < n && (line[j].isLetterOrDigit() || line[j] == '_' || line[j] == '$' || (lang == Lang.Css && line[j] == '-'))) j++
                        val word = line.substring(i, j)
                        when {
                            lang == Lang.Sql && word.lowercase() in sqlKeywords -> span(p.keyword, i, j)
                            lang == Lang.Css && j < n && line.indexOf(':', j).let { it >= 0 && line.substring(j, it).isBlank() } -> span(p.attr, i, j)
                            lang != Lang.Sql && word in keywords -> span(p.keyword, i, j)
                            lang == Lang.Data && (word == "true" || word == "false" || word == "null") -> span(p.keyword, i, j)
                            word[0].isUpperCase() && lang != Lang.Sql -> span(p.type, i, j)
                        }
                        i = j
                    }
                    c in "{}[]()<>=+-*/%!&|^~?:;,." -> {
                        span(p.punctuation.copy(alpha = 0.85f), i, i + 1); i++
                    }
                    else -> i++
                }
            }
        }
    }

    private inline fun markup(line: String, from: Int, p: CodePalette, span: (Color, Int, Int, Boolean) -> Unit) {
        var i = from
        val n = line.length
        while (i < n) {
            if (line.startsWith("<!--", i)) {
                val end = line.indexOf("-->", i).let { if (it < 0) n else it + 3 }
                span(p.comment, i, end, true); i = end
            } else if (line[i] == '<') {
                var j = i + 1
                if (j < n && (line[j] == '/' || line[j] == '?' || line[j] == '!')) j++
                val nameStart = j
                while (j < n && !line[j].isWhitespace() && line[j] != '>' && line[j] != '/') j++
                span(p.punctuation, i, nameStart, false)
                span(p.tag, nameStart, j, false)
                // attributes until '>'
                while (j < n && line[j] != '>') {
                    val c = line[j]
                    if (c == '"' || c == '\'') {
                        val e = line.indexOf(c, j + 1).let { if (it < 0) n else it + 1 }
                        span(p.string, j, e, false); j = e
                    } else if (c.isLetter()) {
                        val s = j
                        while (j < n && (line[j].isLetterOrDigit() || line[j] == '-' || line[j] == ':' || line[j] == '_')) j++
                        span(p.attr, s, j, false)
                    } else j++
                }
                if (j < n) span(p.punctuation, j, j + 1, false)
                i = j + 1
            } else if (line[i] == '&') {
                val e = line.indexOf(';', i)
                if (e in (i + 1)..(i + 10)) {
                    span(p.number, i, e + 1, false); i = e + 1
                } else i++
            } else i++
        }
    }
}
