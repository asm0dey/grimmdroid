// Regenerates integration/fixtures/: three EPUBs, one PDF and one MP3 audiobook, all tiny and public-domain text.
// Run from the repo root: kotlin integration/make-fixtures.kts
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

val out = File("integration/fixtures").apply { mkdirs() }

data class Book(
    val file: String,
    val title: String,
    val author: String,
    val text: String,
)

val books =
    listOf(
        Book(
            "alice.epub",
            "Alice's Adventures in Wonderland",
            "Lewis Carroll",
            "Alice was beginning to get very tired of sitting by her sister on the bank, and of having nothing to do.",
        ),
        Book(
            "pride.epub",
            "Pride and Prejudice",
            "Jane Austen",
            "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.",
        ),
        Book(
            "moby.epub",
            "Moby Dick",
            "Herman Melville",
            "Call me Ishmael. Some years ago, never mind how long precisely, I thought I would sail about a little.",
        ),
    )

fun epub(b: Book): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        // OCF: "mimetype" first and stored uncompressed.
        val mime = "application/epub+zip".toByteArray()
        zip.putNextEntry(
            ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                crc = CRC32().apply { update(mime) }.value
            },
        )
        zip.write(mime)
        fun entry(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.trimIndent().toByteArray())
        }
        entry(
            "META-INF/container.xml",
            """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
            """,
        )
        entry(
            "OEBPS/content.opf",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="id">urn:grimmdroid:${b.file}</dc:identifier>
                <dc:title>${b.title.replace("'", "&apos;")}</dc:title>
                <dc:creator>${b.author}</dc:creator>
                <dc:language>en</dc:language>
                <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="ch1"/></spine>
            </package>
            """,
        )
        entry(
            "OEBPS/nav.xhtml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head>
            <body><nav epub:type="toc"><ol><li><a href="ch1.xhtml">Chapter 1</a></li></ol></nav></body></html>
            """,
        )
        entry(
            "OEBPS/ch1.xhtml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter 1</title></head>
            <body><h1>Chapter 1</h1><p>${b.text}</p></body></html>
            """,
        )
    }
    return bytes.toByteArray()
}

// One page, Helvetica text; xref offsets computed so strict parsers accept it.
fun pdf(text: String): ByteArray {
    val stream = "BT /F1 18 Tf 72 720 Td ($text) Tj ET"
    val objects =
        listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length ${stream.length} >>\nstream\n$stream\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Title (The Time Machine) /Author (H. G. Wells) >>",
        )
    val sb = StringBuilder("%PDF-1.4\n")
    val offsets = objects.mapIndexed { i, body -> sb.length.also { sb.append("${i + 1} 0 obj\n$body\nendobj\n") } }
    val xref = sb.length
    sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
    offsets.forEach { sb.append("%010d 00000 n \n".format(it)) }
    sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R /Info 6 0 R >>\nstartxref\n$xref\n%%EOF\n")
    return sb.toString().toByteArray(Charsets.US_ASCII)
}

// ID3v2.3 title/artist tag followed by ~1 s of silent MPEG-1 Layer III frames (128 kbps, 44.1 kHz).
fun mp3(
    title: String,
    artist: String,
): ByteArray {
    fun frame(id: String, value: String): ByteArray {
        val data = byteArrayOf(0) + value.toByteArray(Charsets.ISO_8859_1) // encoding 0 = ISO-8859-1
        val size = data.size
        return id.toByteArray() + byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte(), 0, 0) + data
    }
    val frames = frame("TIT2", title) + frame("TPE1", artist) + frame("TALB", title)
    val n = frames.size // syncsafe size
    val tag =
        "ID3".toByteArray() + byteArrayOf(3, 0, 0, (n shr 21 and 0x7f).toByte(), (n shr 14 and 0x7f).toByte(), (n shr 7 and 0x7f).toByte(), (n and 0x7f).toByte()) + frames
    val audio = ByteArrayOutputStream()
    repeat(38) {
        audio.write(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64))
        audio.write(ByteArray(417 - 4))
    }
    return tag + audio.toByteArray()
}

books.forEach { File(out, it.file).writeBytes(epub(it)) }
File(out, "time-machine.pdf").writeBytes(pdf("The Time Traveller was expounding a recondite matter to us."))
File(out, "flatland.mp3").writeBytes(mp3("Flatland", "Edwin A. Abbott"))
println("Wrote ${out.listFiles()!!.map { it.name }.sorted()}")
