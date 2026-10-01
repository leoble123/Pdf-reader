package app.lumen.reader.core

import java.util.Locale

enum class FileKind(val label: String) {
    Pdf("PDF"),
    Image("Image"),
    Video("Video"),
    Audio("Audio"),
    Markdown("Markdown"),
    Code("Code"),
    Text("Text"),
    Csv("Table"),
    Html("Web page"),
    Svg("Vector image"),
    Docx("Word"),
    Xlsx("Spreadsheet"),
    Pptx("Slides"),
    Odt("Document"),
    Epub("eBook"),
    Zip("Archive"),
    Unknown("File");

    companion object {

        private val codeExtensions = setOf(
            "kt", "kts", "java", "c", "h", "cpp", "cc", "hpp", "cs", "go", "rs", "swift", "m", "mm",
            "py", "rb", "php", "js", "mjs", "cjs", "ts", "tsx", "jsx", "dart", "lua", "pl", "r",
            "scala", "groovy", "gradle", "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd",
            "json", "jsonc", "json5", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties",
            "sql", "graphql", "gql", "css", "scss", "sass", "less", "vue", "svelte", "proto",
            "dockerfile", "makefile", "cmake", "tf", "nix", "zig", "hs", "ex", "exs", "erl", "clj",
            "elm", "fs", "vb", "asm", "s", "diff", "patch", "gitignore", "env", "lock"
        )

        private val textExtensions = setOf("txt", "log", "text", "nfo", "srt", "vtt", "ass", "tex", "bib", "rst", "adoc", "org")

        fun detect(name: String, mime: String?): FileKind {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val m = mime?.lowercase(Locale.ROOT).orEmpty()
            fromExtension(ext)?.let { return it }
            return when {
                m == "application/pdf" -> Pdf
                m == "image/svg+xml" -> Svg
                m.startsWith("image/") -> Image
                m.startsWith("video/") -> Video
                m.startsWith("audio/") -> Audio
                m == "text/markdown" -> Markdown
                m == "text/html" -> Html
                m == "text/csv" || m == "text/tab-separated-values" -> Csv
                m == "application/epub+zip" -> Epub
                m.contains("wordprocessingml") -> Docx
                m.contains("spreadsheetml") -> Xlsx
                m.contains("presentationml") -> Pptx
                m.contains("opendocument.text") -> Odt
                m.contains("zip") || m == "application/java-archive" -> Zip
                m == "application/json" || m.contains("xml") || m.contains("javascript") -> Code
                m.startsWith("text/") -> Text
                else -> when (name.lowercase(Locale.ROOT)) {
                    "dockerfile", "makefile", "license", "readme", "changelog" -> Text
                    else -> Unknown
                }
            }
        }

        fun fromExtension(ext: String): FileKind? = when (ext) {
            "pdf" -> Pdf
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "ico", "wbmp", "dng" -> Image
            "svg" -> Svg
            "mp4", "mkv", "webm", "mov", "3gp", "m4v", "avi" -> Video
            "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "amr", "mid", "midi" -> Audio
            "md", "markdown", "mdown", "mkd" -> Markdown
            "csv", "tsv" -> Csv
            "html", "htm", "xhtml", "mhtml" -> Html
            "docx", "docm", "dotx" -> Docx
            "xlsx", "xlsm", "xltx" -> Xlsx
            "pptx", "pptm", "ppsx" -> Pptx
            "odt" -> Odt
            "epub" -> Epub
            "zip", "jar", "apk", "aar", "cbz", "xpi", "whl" -> Zip
            in codeExtensions -> Code
            in textExtensions -> Text
            else -> null
        }

        /** Content sniffing for files with no useful name or MIME type. */
        fun sniff(head: ByteArray): FileKind? {
            fun startsWith(vararg bytes: Int) =
                head.size >= bytes.size && bytes.indices.all { head[it] == bytes[it].toByte() }
            return when {
                startsWith(0x25, 0x50, 0x44, 0x46) -> Pdf
                startsWith(0x89, 0x50, 0x4E, 0x47) -> Image
                startsWith(0xFF, 0xD8, 0xFF) -> Image
                startsWith(0x47, 0x49, 0x46, 0x38) -> Image
                startsWith(0x52, 0x49, 0x46, 0x46) && head.size > 11 &&
                    String(head, 8, 4, Charsets.US_ASCII) == "WEBP" -> Image
                startsWith(0x50, 0x4B, 0x03, 0x04) -> Zip
                looksLikeText(head) -> Text
                else -> null
            }
        }

        fun looksLikeText(head: ByteArray): Boolean {
            if (head.isEmpty()) return true
            var control = 0
            for (b in head) {
                val c = b.toInt() and 0xFF
                if (c == 0) return false
                if (c < 0x09 || (c in 0x0E..0x1F)) control++
            }
            return control * 20 < head.size
        }
    }
}
