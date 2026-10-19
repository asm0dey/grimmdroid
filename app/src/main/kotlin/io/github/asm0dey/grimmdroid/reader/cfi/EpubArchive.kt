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

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.File
import java.net.URI
import java.util.zip.ZipFile

/** Read-only view of an EPUB zip: the spine and its XHTML documents. */
class EpubArchive(
    file: File,
) : Closeable {
    private val zip = ZipFile(file)
    private val documents = mutableMapOf<String, Document?>()

    /** Zip paths of every spine `itemref` (linear or not) in OPF order — CFI spine steps count them all. */
    val spine: List<String> = readSpine()

    private fun parse(path: String): Document? {
        val entry = zip.getEntry(path) ?: return null
        val doc = zip.getInputStream(entry).use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
        // XML end-of-line handling (as browsers, so foliate-js offsets match): CRLF and lone CR become LF
        textNodes(doc).forEach { if ('\r' in it.wholeText) it.text(it.wholeText.replace("\r\n", "\n").replace('\r', '\n')) }
        return doc
    }

    private fun readSpine(): List<String> {
        val opfPath = parse("META-INF/container.xml")?.selectFirst("*|rootfile[full-path]")?.attr("full-path") ?: return emptyList()
        val opf = parse(opfPath) ?: return emptyList()
        val hrefs = opf.select("*|manifest > *|item").associate { it.attr("id") to resolvePath(opfPath, it.attr("href")) }
        return opf.select("*|spine > *|itemref").mapNotNull { hrefs[it.attr("idref")] }
    }

    /** Parsed (and cached) document at [href]; null when it isn't in the archive. */
    fun document(href: String): Document? = normalize(href).let { path -> documents.getOrPut(path) { parse(path) } }

    /** Spine index of [href] (a zip path or Readium's publication-relative URL), or -1. */
    fun spineIndexOf(href: String): Int = spine.indexOf(normalize(href))

    override fun close() = zip.close()
}

private fun normalize(href: String): String {
    val bare = href.substringBefore('#')
    return (runCatching { URI(bare).normalize().path }.getOrNull() ?: bare).trimStart('/')
}

/** Zip path of [href] as written in the file at [base] (relative, percent-encoded). */
private fun resolvePath(
    base: String,
    href: String,
): String =
    runCatching { URI(null, null, base, null).resolve(URI(href.substringBefore('#'))).path }.getOrNull()?.trimStart('/')
        ?: (base.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } + href.substringBefore('#'))
