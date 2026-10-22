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
package io.github.asm0dey.grimmdroid.upload

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val CONTAINER =
    """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""" +
        """<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""

private fun opf(title: String) =
    """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">""" +
        """<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>$title</dc:title></metadata></package>"""

private fun epub(vararg entries: Pair<String, String>): File =
    File.createTempFile("title", ".epub").apply {
        deleteOnExit()
        ZipOutputStream(outputStream()).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }

class EpubTitleSpec :
    FunSpec({
        test("reads dc:title from the OPF named by container.xml") {
            epubTitle(epub("META-INF/container.xml" to CONTAINER, "OEBPS/content.opf" to opf(" Dune "))) shouldBe "Dune"
        }

        test("null when container.xml is missing") {
            epubTitle(epub("OEBPS/content.opf" to opf("Dune"))).shouldBeNull()
        }

        test("null for a non-zip file") {
            epubTitle(File.createTempFile("title", ".epub").apply { deleteOnExit() }.apply { writeText("not a zip") }).shouldBeNull()
        }

        test("null for a blank title") {
            epubTitle(epub("META-INF/container.xml" to CONTAINER, "OEBPS/content.opf" to opf("  "))).shouldBeNull()
        }
    })
