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
package io.github.asm0dey.grimmdroid.download

import java.net.URLDecoder

private val FILENAME_STAR = Regex("""filename\*\s*=\s*UTF-8''([^;\s]+)""", RegexOption.IGNORE_CASE)
private val FILENAME = Regex("""filename\s*=\s*(?:"([^"]*)"|([^;\s]+))""", RegexOption.IGNORE_CASE)
private val UNSAFE = Regex("""[\p{Cntrl}:*?"<>|]""")
private const val MAX_BYTES = 120

/** Prefers RFC 5987 `filename*=UTF-8''…` (percent-decoded) over `filename="…"`; null if neither is present. */
fun parseContentDisposition(header: String?): String? {
    if (header == null) return null
    FILENAME_STAR.find(header)?.let { m ->
        // RFC 5987 has no '+'-for-space, unlike form encoding.
        runCatching { return URLDecoder.decode(m.groupValues[1].replace("+", "%2B"), "UTF-8") }
    }
    return FILENAME.find(header)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
}

/** A single path component that can't escape the book directory; the sanitised [fallback] (or "book") when nothing usable is left. */
fun safeFileName(
    raw: String,
    fallback: String,
): String = sanitise(raw) ?: sanitise(fallback) ?: "book"

private fun sanitise(raw: String): String? {
    val name =
        raw
            .replace('/', '_')
            .replace('\\', '_')
            .replace(UNSAFE, "") // before "..", so dropping a char can't splice a new ".."
            .replace("..", "")
            .trim()
    if (name.trim('.').isBlank()) return null
    if (name.encodeToByteArray().size <= MAX_BYTES) return name
    val dot = name.lastIndexOf('.')
    val ext = if (dot > 0 && name.length - dot <= 10) name.substring(dot) else ""
    return truncateUtf8(name.removeSuffix(ext), MAX_BYTES - ext.encodeToByteArray().size) + ext
}

private fun truncateUtf8(
    s: String,
    maxBytes: Int,
): String {
    val out = StringBuilder()
    var bytes = 0
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val cpStr = String(Character.toChars(cp))
        bytes += cpStr.encodeToByteArray().size
        if (bytes > maxBytes) break
        out.append(cpStr)
        i += Character.charCount(cp)
    }
    return out.toString()
}
