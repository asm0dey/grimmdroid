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
package io.github.asm0dey.grimmdroid.catalog

enum class ReadState { READ, IN_PROGRESS, NOT_STARTED, DISMISSED }

/** [readProgress] is a 0-100 percentage. Unknown statuses count as not started. */
fun readState(
    readStatus: String?,
    readProgress: Float?,
): ReadState {
    val progress = readProgress ?: 0f
    return when {
        readStatus == "READ" || progress >= 99f -> ReadState.READ
        readStatus == "ABANDONED" || readStatus == "WONT_READ" -> ReadState.DISMISSED
        readStatus in setOf("READING", "RE_READING", "PARTIALLY_READ", "PAUSED") || progress > 0f -> ReadState.IN_PROGRESS
        else -> ReadState.NOT_STARTED
    }
}

// Task 2 switches this to the readStatus column.
val BookEntity.readState: ReadState get() = readState(null, readProgress)

val SeriesOrder: Comparator<BookEntity> =
    compareBy<BookEntity, Float?>(nullsLast()) { it.seriesNumber }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        .thenBy { it.id }

fun nextToRead(
    series: List<BookEntity>,
    state: (BookEntity) -> ReadState = { it.readState },
): BookEntity? {
    val sorted = series.sortedWith(SeriesOrder)
    sorted.firstOrNull { state(it) == ReadState.IN_PROGRESS }?.let { return it }
    val lastDone =
        sorted
            .filter { it.seriesNumber != null && state(it).let { s -> s == ReadState.READ || s == ReadState.DISMISSED } }
            .maxOfOrNull { it.seriesNumber!! }
    val notStarted = sorted.filter { state(it) == ReadState.NOT_STARTED }
    return notStarted.firstOrNull { lastDone != null && (it.seriesNumber == null || it.seriesNumber > lastDone) }
        ?: notStarted.firstOrNull()
}

data class Group(
    val name: String,
    val books: List<BookEntity>,
    val readCount: Int,
    val total: Int,
    val started: Boolean,
)

fun List<BookEntity>.toGroup(
    name: String,
    state: (BookEntity) -> ReadState = { it.readState },
): Group {
    val states = map(state)
    return Group(
        name,
        sortedWith(SeriesOrder),
        states.count { it == ReadState.READ },
        size,
        states.any { it == ReadState.READ || it == ReadState.IN_PROGRESS },
    )
}
