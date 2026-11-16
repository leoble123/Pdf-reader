package app.lumen.reader

import android.net.Uri
import app.lumen.reader.viewer.decodeDataUrl
import app.lumen.reader.viewer.web.LocalSite
import app.lumen.reader.viewer.web.MhtmlArchive
import app.lumen.reader.viewer.withDarkStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebTest {

    @Test
    fun servesFilesBesideThePageOnly() {
        val root = Files.createTempDirectory("site").toFile()
        val dir = File(root, "page").apply { mkdirs() }
        File(dir, "index.html").writeText("<link rel=stylesheet href=css/a.css>")
        File(dir, "css").mkdirs()
        File(dir, "css/a.css").writeText("body{color:red}")
        File(dir, "two.html").writeText("<h1>Two</h1>")
        File(root, "secret.txt").writeText("nope")
        val site = LocalSite.forFolder(dir, "index.html", "<p>")

        val css = site.open(Uri.parse("https://lumen.local/site/css/a.css"))!!
        assertEquals("text/css", css.mime)
        assertEquals("body{color:red}", css.stream.use { it.readBytes().decodeToString() })
        assertEquals("<h1>Two</h1>", site.page(Uri.parse("https://lumen.local/site/two.html")))
        // Nothing outside the page's folder, nothing from other hosts, nothing missing.
        assertNull(site.open(Uri.parse("https://lumen.local/site/../secret.txt")))
        assertNull(site.open(Uri.parse("https://lumen.local/secret.txt")))
        assertNull(site.open(Uri.parse("https://example.com/site/css/a.css")))
        assertNull(site.open(Uri.parse("https://lumen.local/site/missing.js")))
        assertNull(site.page(Uri.parse("https://lumen.local/site/css/a.css")))
        root.deleteRecursively()
    }

    @Test
    fun readsSavedWebPages() {
        val mhtml = """From: <Saved by Blink>
Subject: Test
MIME-Version: 1.0
Content-Type: multipart/related;
	type="text/html";
	boundary="----MultipartBoundary--abc"

------MultipartBoundary--abc
Content-Type: text/html
Content-ID: <frame-1@mhtml.blink>
Content-Transfer-Encoding: quoted-printable
Content-Location: https://example.com/article

<html><head><link rel=3D"stylesheet" href=3D"https://example.com/s.css"></he=
ad><body>Caf=C3=A9</body></html>
------MultipartBoundary--abc
Content-Type: text/css
Content-Transfer-Encoding: quoted-printable
Content-Location: https://example.com/s.css

body { color: blue; }
------MultipartBoundary--abc
Content-Type: image/png
Content-Transfer-Encoding: base64
Content-Location: https://example.com/a.png

iVBORw0KGgo=
------MultipartBoundary--abc--
""".replace("\n", "\r\n").toByteArray(Charsets.ISO_8859_1)
        assertTrue(MhtmlArchive.looksLike(mhtml))
        val a = MhtmlArchive.parse(mhtml)!!
        assertEquals("https://example.com/article", a.mainUrl)
        assertTrue(a.mainHtml, a.mainHtml.contains("<link rel=\"stylesheet\" href=\"https://example.com/s.css\"></head><body>Café</body>"))
        assertEquals("body { color: blue; }", a.find("https://example.com/s.css")!!.stream.readBytes().decodeToString())
        assertEquals(8, a.find("https://example.com/a.png#x")!!.stream.readBytes().size)
        assertNull(a.find("https://example.com/other.js"))
    }

    @Test
    fun decodesLegacyCharsetsAndHelpers() {
        val latin = "<meta charset=windows-1252><p>café".toByteArray(Charsets.ISO_8859_1)
        assertTrue(LocalSite.decodeText(latin, null).endsWith("café"))
        assertEquals("héllo", LocalSite.decodeText("héllo".toByteArray(), null))

        val dark = withDarkStyle("<html><HEAD><title>x</title></HEAD><body></body></html>")
        assertTrue(dark.indexOf("lumen-dark") < dark.indexOf("</HEAD>"))
        assertTrue(withDarkStyle("<p>no head").startsWith("<style"))

        assertEquals("hi there", decodeDataUrl("data:text/plain,hi%20there")!!.decodeToString())
        assertEquals("hello", decodeDataUrl("data:text/plain;base64,aGVsbG8=")!!.decodeToString())
        assertNotNull(LocalSite.mimeOf("app.mjs").takeIf { it == "text/javascript" })
    }
}
