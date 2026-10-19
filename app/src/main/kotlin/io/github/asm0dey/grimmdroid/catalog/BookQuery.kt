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

import androidx.sqlite.db.SimpleSQLiteQuery

enum class BookSort(
    val column: String,
) {
    ADDED("addedOn DESC"),
    TITLE("title COLLATE NOCASE ASC"),
    AUTHOR("authors COLLATE NOCASE ASC"),
    SERIES("seriesName IS NULL, seriesName COLLATE NOCASE, seriesNumber"),
}

data class BookQuery(
    val accountId: Long,
    val libraryId: Long? = null,
    val shelfId: Long? = null,
    val magicShelfId: Long? = null,
    val formats: Set<String> = emptySet(),
    val downloadedOnly: Boolean = false,
    val sort: BookSort = BookSort.ADDED,
)

// Must match DownloadState.DONE.name (asserted in CatalogDaoTest); a constant so @Query can use it.
internal const val DOWNLOADED_EXISTS =
    "EXISTS (SELECT 1 FROM DownloadEntity d WHERE d.accountId = b.accountId AND d.bookId = b.id AND d.state = 'DONE')"

/** Every value is a bound argument; only [BookSort.column] (a fixed enum constant) is spliced into the text. */
fun BookQuery.toSql(): SimpleSQLiteQuery {
    val args = mutableListOf<Any?>(accountId)
    val where = StringBuilder("b.accountId = ?")
    libraryId?.let {
        where.append(" AND b.libraryId = ?")
        args += it
    }
    for ((id, magic) in listOf(shelfId to 0, magicShelfId to 1)) {
        if (id == null) continue
        where.append(
            " AND EXISTS (SELECT 1 FROM ShelfBookCrossRef s WHERE s.accountId = b.accountId" +
                " AND s.bookId = b.id AND s.shelfId = ? AND s.magic = ?)",
        )
        args += id
        args += magic
    }
    if (formats.isNotEmpty()) {
        where.append(" AND b.primaryFileType IN (${formats.joinToString { "?" }})")
        args.addAll(formats)
    }
    if (downloadedOnly) {
        where.append(" AND $DOWNLOADED_EXISTS")
    }
    val sql =
        "SELECT b.*, $DOWNLOADED_EXISTS AS downloaded FROM BookEntity b WHERE $where ORDER BY ${sort.column}, b.id"
    return SimpleSQLiteQuery(sql, args.toTypedArray())
}
