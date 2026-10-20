/*
 * Grimmdroid - Android client for the Grimmory book server
 * Copyright (C) 2026 Grimmdroid contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.asm0dey.grimmdroid.reader.cfi

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun xhtml(body: String) =
    """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>T</title></head>""" +
        "<body>$body</body></html>"

internal fun tinyEpub(
    c1: String = xhtml("<p>One</p>"),
    c2: String = xhtml("""<p id="a">Alpha</p><p>Beta</p><div><p>Gamma</p></div>"""),
    c2Linear: Boolean = false,
): File =
    File.createTempFile("tiny", ".epub").apply {
        deleteOnExit()
        ZipOutputStream(outputStream()).use { zip ->
            fun put(
                name: String,
                text: String,
            ) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""" +
                    """<rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            put(
                "content.opf",
                """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">""" +
                    // Readium rejects an OPF without metadata.
                    """<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">""" +
                    """<dc:title>Tiny</dc:title><dc:identifier>tiny</dc:identifier></metadata>""" +
                    """<manifest>""" +
                    """<item id="i2" href="c2.xhtml" media-type="application/xhtml+xml"/>""" +
                    """<item id="i1" href="c1.xhtml" media-type="application/xhtml+xml"/>""" +
                    """</manifest><spine><itemref idref="i1"/><itemref idref="i2"${if (c2Linear) "" else " linear=\"no\""}/></spine></package>""",
            )
            put("c1.xhtml", c1)
            put("c2.xhtml", c2)
        }
    }

class CfiMappingSpec :
    FunSpec({
        val epub = EpubArchive(tinyEpub())
        afterSpec { epub.close() }

        fun resolve(cfi: String) = epub.resolve(parseCfi(cfi)!!)

        test("spine lists every itemref in OPF order") {
            epub.spine shouldBe listOf("c1.xhtml", "c2.xhtml")
        }
        test("spineIndexOf normalizes Readium-style hrefs") {
            epub.spineIndexOf("/c2.xhtml#a") shouldBe 1
            epub.spineIndexOf("./c1.xhtml") shouldBe 0
            epub.spineIndexOf("nope.xhtml") shouldBe -1
        }
        test("element with unique id resolves to #id") {
            resolve("epubcfi(/6/4!/4/2)") shouldBe PositionTarget("c2.xhtml", "#a", 0.0)
        }
        test("element without id resolves to nth-child path") {
            resolve("epubcfi(/6/4!/4/6/2)")!!.cssSelector shouldBe ":root > :nth-child(2) > :nth-child(3) > :nth-child(1)"
        }
        test("step past the end falls back to the deepest existing element") {
            val t = resolve("epubcfi(/6/4!/4/40)")!!
            t.href shouldBe "c2.xhtml"
            t.cssSelector shouldBe ":root > :nth-child(2)"
        }
        test("spine step out of range is null") {
            resolve("epubcfi(/6/40)") shouldBe null
        }
        test("spine-only CFI resolves to the start of the resource") {
            resolve("epubcfi(/6/2)") shouldBe PositionTarget("c1.xhtml", null, 0.0)
        }
        test("text-node step resolves to the containing element with progression from the offset") {
            val t = resolve("epubcfi(/6/4!/4/2/1:3)")!!
            t.cssSelector shouldBe "#a"
            t.progression shouldBeGreaterThan 0.0
            t.progression shouldBe (3.0 / 14).plusOrMinus(1e-9)
        }
        test("id assertions are ignored") {
            resolve("epubcfi(/6/4!/4[body]/2[a]/1:2)")!!.cssSelector shouldBe "#a"
        }
        test("text-node step and offset past the end never throw") {
            val t = resolve("epubcfi(/6/4!/4/2/9:100)")!!
            t.cssSelector shouldBe "#a"
            t.progression shouldBeLessThan 1.0
            resolve("epubcfi(/6/4!/4/7:0)")!!.cssSelector shouldBe ":root > :nth-child(2)"
            resolve("epubcfi(/6/4!/4/2/1/4/2:5)")!!.cssSelector shouldBe "#a"
            resolve("epubcfi(/6/4!/40)") shouldBe PositionTarget("c2.xhtml", ":root", 0.0)
        }
        test("cfiFor an element with an id") {
            epub.cfiFor("c2.xhtml", "#a", 0.0) shouldBe "epubcfi(/6/4!/4/2)"
        }
        test("cfiFor without a selector uses progression") {
            epub.cfiFor("c2.xhtml", null, 0.99)!! shouldStartWith "epubcfi(/6/4!/4/6/2"
        }
        test("progression round-trips through a text offset") {
            val cfi = epub.cfiFor("c2.xhtml", null, 0.5)!!
            cfi shouldBe "epubcfi(/6/4!/4/4/1:2)"
            resolve(cfi)!!.progression shouldBe 0.5
        }
        test("invalid or non-matching selectors fall back to progression") {
            val fromProgression = epub.cfiFor("c2.xhtml", null, 0.0)
            epub.cfiFor("c2.xhtml", "not a [valid selector", 0.0) shouldBe fromProgression
            epub.cfiFor("c2.xhtml", "#nothing-here", 0.0) shouldBe fromProgression
            epub.cfiFor("c2.xhtml", "", 0.0) shouldBe fromProgression
            epub.cfiFor("c2.xhtml", "p:matches((", 0.0) shouldBe fromProgression
        }
        test("cfiFor an href outside the spine is null") {
            epub.cfiFor("missing.xhtml", null, 0.0) shouldBe null
        }

        test("CRLF is normalized to LF like the browser DOM, so offsets match") {
            val lf = EpubArchive(tinyEpub(c2 = xhtml("<p>ab\ncd</p>")))
            val crlf = EpubArchive(tinyEpub(c2 = xhtml("<p>ab\r\ncd</p>")))
            val cr = EpubArchive(tinyEpub(c2 = xhtml("<p>ab\rcd</p>")))
            listOf(lf, crlf, cr).forEach { book ->
                book.use { it.cfiFor("c2.xhtml", null, 0.9) shouldBe "epubcfi(/6/4!/4/2/1:4)" }
            }
        }

        context("progression round-trips on realistic shapes") {
            val shaped =
                EpubArchive(
                    tinyEpub(
                        c1 =
                            xhtml(
                                "\n  <h1>Chapter &amp; verse</h1>\n  <p>Call me <em>Ishmael</em>. Some years ago, never mind " +
                                    "<!-- note -->how long <b>precisely</b>, having little money.</p>\n" +
                                    "  <div>\n    <p>I thought I would <i>sail</i> about a little &amp; see the watery part.</p>\n  </div>\n",
                            ),
                        c2 =
                            xhtml(
                                "<section><p>It is a way I have of driving off the spleen</p>\n" +
                                    "<p>and regulating the <em>circulation</em>; whenever I find myself growing grim.</p></section>",
                            ),
                    ),
                )
            afterSpec { shaped.close() }
            listOf("c1.xhtml", "c2.xhtml").forEach { href ->
                listOf(0.0, 0.13, 0.5, 0.87).forEach { p ->
                    test("$href at $p") {
                        val target = shaped.resolve(parseCfi(shaped.cfiFor(href, null, p)!!)!!)!!
                        target.href shouldBe href
                        // each document has > 100 body chars, so flooring to a char is off by < 0.01
                        target.progression shouldBe p.plusOrMinus(0.01)
                        target.progression shouldBeLessThan p + 1e-9
                    }
                }
            }
        }

        context("round-trip over fixture EPUBs") {
            val dir = File(System.getProperty("grimmdroid.fixtures")!!)
            listOf("moby", "pride", "alice").forEach { name ->
                test(name) {
                    EpubArchive(File(dir, "$name.epub")).use { book ->
                        var checked = 0
                        book.spine.forEach { href ->
                            book.document(href)!!.select("*|p").forEach { p ->
                                val selector = selectorOf(p)
                                val cfi = book.cfiFor(href, selector, 0.0)!!
                                book.resolve(parseCfi(cfi)!!)!!.cssSelector shouldBe selector
                                checked++
                            }
                        }
                        checked shouldBeGreaterThan 0
                    }
                }
            }
        }
    })
