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
package io.github.asm0dey.grimmdroid.export

private val MIME =
    mapOf(
        "epub" to "application/epub+zip",
        "pdf" to "application/pdf",
        "cbz" to "application/vnd.comicbook+zip",
        "cbr" to "application/vnd.comicbook-rar",
        "mobi" to "application/x-mobipocket-ebook",
        "azw3" to "application/vnd.amazon.ebook",
        "fb2" to "application/x-fictionbook+xml",
    )

fun mimeFor(fileName: String): String = MIME[fileName.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

/** [wanted], or "Name (1).ext", "Name (2).ext"... — the first that is not in [existing]. */
fun uniqueName(
    existing: Set<String>,
    wanted: String,
): String {
    if (wanted !in existing) return wanted
    val base = wanted.substringBeforeLast('.', wanted)
    val ext = wanted.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    return generateSequence(1) { it + 1 }.map { "$base ($it)$ext" }.first { it !in existing }
}
