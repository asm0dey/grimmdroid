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

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CatalogDao {
    @RawQuery(observedEntities = [BookEntity::class, ShelfBookCrossRef::class, DownloadEntity::class])
    abstract fun books(q: SupportSQLiteQuery): PagingSource<Int, BookRow>

    /** [ftsQuery] is an already-built FTS4 MATCH expression; it is bound, never concatenated. */
    @Query(
        "SELECT b.*, $DOWNLOADED_EXISTS_DONE AS downloaded FROM BookEntity b WHERE b.accountId = :accountId " +
            "AND b.rowid IN (SELECT rowid FROM BookFts WHERE BookFts MATCH :ftsQuery) ORDER BY b.title COLLATE NOCASE, b.id",
    )
    abstract fun search(
        accountId: Long,
        ftsQuery: String,
    ): PagingSource<Int, BookRow>

    @Upsert
    abstract suspend fun upsertBooks(books: List<BookEntity>)

    @Query("DELETE FROM BookEntity WHERE accountId = :accountId AND syncGeneration < :generation")
    abstract suspend fun deleteBooksNotIn(
        accountId: Long,
        generation: Long,
    )

    @Query("SELECT * FROM BookEntity WHERE accountId = :accountId AND id = :id")
    abstract fun book(
        accountId: Long,
        id: Long,
    ): Flow<BookEntity?>

    @Query(
        "SELECT * FROM BookEntity WHERE accountId = :accountId AND lastReadTime IS NOT NULL AND readProgress < 100 " +
            "ORDER BY lastReadTime DESC LIMIT :limit",
    )
    abstract fun continueReading(
        accountId: Long,
        limit: Int = 20,
    ): Flow<List<BookEntity>>

    @Query("SELECT * FROM BookEntity WHERE accountId = :accountId ORDER BY addedOn DESC LIMIT :limit")
    abstract fun recentlyAdded(
        accountId: Long,
        limit: Int = 20,
    ): Flow<List<BookEntity>>

    @Upsert
    abstract suspend fun upsertLibraries(libraries: List<LibraryEntity>)

    @Upsert
    abstract suspend fun upsertShelves(shelves: List<ShelfEntity>)

    @Query("DELETE FROM ShelfBookCrossRef WHERE accountId = :accountId AND shelfId = :shelfId AND magic = :magic")
    protected abstract suspend fun clearShelfMembers(
        accountId: Long,
        shelfId: Long,
        magic: Boolean,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertShelfMembers(refs: List<ShelfBookCrossRef>)

    @Transaction
    open suspend fun replaceShelfMembers(
        accountId: Long,
        shelfId: Long,
        magic: Boolean,
        bookIds: List<Long>,
    ) {
        clearShelfMembers(accountId, shelfId, magic)
        insertShelfMembers(bookIds.map { ShelfBookCrossRef(accountId, shelfId, magic, it) })
    }

    @Query("DELETE FROM BookEntity WHERE accountId = :accountId")
    protected abstract suspend fun deleteBooks(accountId: Long)

    @Query("DELETE FROM LibraryEntity WHERE accountId = :accountId")
    protected abstract suspend fun deleteLibraries(accountId: Long)

    @Query("DELETE FROM ShelfEntity WHERE accountId = :accountId")
    protected abstract suspend fun deleteShelves(accountId: Long)

    @Query("DELETE FROM ShelfBookCrossRef WHERE accountId = :accountId")
    protected abstract suspend fun deleteShelfMembers(accountId: Long)

    @Query("DELETE FROM DownloadEntity WHERE accountId = :accountId")
    protected abstract suspend fun deleteDownloads(accountId: Long)

    @Transaction
    open suspend fun deleteAccount(accountId: Long) {
        deleteBooks(accountId)
        deleteLibraries(accountId)
        deleteShelves(accountId)
        deleteShelfMembers(accountId)
        deleteDownloads(accountId)
    }
}

private const val DOWNLOADED_EXISTS_DONE =
    "EXISTS (SELECT 1 FROM DownloadEntity d WHERE d.accountId = b.accountId AND d.bookId = b.id AND d.state = 'DONE')"
