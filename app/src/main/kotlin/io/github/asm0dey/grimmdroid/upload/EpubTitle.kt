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

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** The EPUB's first `dc:title`, or null when the file isn't a readable EPUB or has no title. */
fun epubTitle(file: File): String? =
    try {
        ZipFile(file).use { zip ->
            fun xml(path: String) = zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { Jsoup.parse(it, null, "", Parser.xmlParser()) } }
            val opfPath = xml("META-INF/container.xml")?.selectFirst("rootfile[full-path]")?.attr("full-path") ?: return null
            xml(opfPath)?.selectFirst("dc|title, title")?.text()?.trim()?.ifBlank { null }
        }
    } catch (_: IOException) {
        null
    }
