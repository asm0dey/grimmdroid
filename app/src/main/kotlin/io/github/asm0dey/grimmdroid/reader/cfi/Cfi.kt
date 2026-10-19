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

/**
 * An EPUB CFI reduced to what the reader needs: the spine step (even, `(spineIndex + 1) * 2`),
 * the steps after `!`, and the character offset on the last step.
 */
data class Cfi(
    val spineStep: Int,
    val path: List<Int>,
    val offset: Int?,
)

val Cfi.spineIndex: Int get() = spineStep / 2 - 1

private val ASSERTION = Regex("\\[[^\\]]*]")
private val STEPS = Regex("(/\\d+)+")

private fun steps(s: String): List<Int>? =
    if (s.isEmpty()) {
        emptyList()
    } else if (STEPS.matches(s)) {
        s.split('/').drop(1).map { it.toInt() }
    } else {
        null
    }

/** Returns null for anything unparseable; assertions, spatial/temporal suffixes and range ends are dropped. */
fun parseCfi(s: String): Cfi? {
    val body = s.removePrefix("epubcfi(").takeIf { it != s && it.endsWith(")") }?.dropLast(1) ?: return null
    val parts = body.replace(ASSERTION, "").split(',')
    // range: parent + start; the end part is irrelevant for a position
    val start =
        if (parts.size == 3) {
            parts[0] + parts[1]
        } else if (parts.size == 1) {
            parts[0]
        } else {
            return null
        }
    val bare = start.substringBefore('~').substringBefore('@')
    val offset = bare.substringAfter(':', "").takeIf { it.isNotEmpty() }?.toIntOrNull()
    if (':' in bare && offset == null) return null
    val halves = bare.substringBefore(':').split('!')
    if (halves.size > 2) return null
    val spine = steps(halves[0]) ?: return null
    if (spine.size != 2 || spine[0] != 6 || spine[1] % 2 != 0 || spine[1] < 2) return null
    val path = if (halves.size == 2) steps(halves[1]) ?: return null else emptyList()
    return Cfi(spine[1], path, offset)
}

fun Cfi.format(): String =
    buildString {
        append("epubcfi(/6/").append(spineStep)
        if (path.isNotEmpty()) append('!').append(path.joinToString("") { "/$it" })
        if (offset != null) append(':').append(offset)
        append(')')
    }
