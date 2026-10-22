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
        "SELECT b.*, $DOWNLOADED_EXISTS AS downloaded FROM BookEntity b WHERE b.accountId = :accountId " +
            "AND b.rowid IN (SELECT rowid FROM BookFts WHERE BookFts MATCH :ftsQuery) ORDER BY b.title COLLATE NOCASE, b.id",
    )
    abstract fun search(
        accountId: Long,
        ftsQuery: String,
    ): PagingSource<Int, BookRow>

    @Upsert
    abstract suspend fun upsertBooks(books: List<BookEntity>)

    @Query("DELETE FROM BookAuthor WHERE accountId = :accountId AND bookId IN (:bookIds)")
    protected abstract suspend fun deleteAuthorsOf(
        accountId: Long,
        bookIds: List<Long>,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertAuthors(authors: List<BookAuthor>)

    @Query("DELETE FROM BookAuthor WHERE accountId = :accountId AND bookId NOT IN (SELECT id FROM BookEntity WHERE accountId = :accountId)")
    protected abstract suspend fun deleteOrphanAuthors(accountId: Long)

    @Query("DELETE FROM BookAuthor WHERE accountId = :accountId")
    protected abstract suspend fun deleteAuthors(accountId: Long)

    /** Upserts [books] and replaces their author rows with [authors] (book id to names); names are trimmed, blanks and duplicates dropped. */
    @Transaction
    open suspend fun upsertBooksWithAuthors(
        books: List<BookEntity>,
        authors: Map<Long, List<String>>,
    ) {
        upsertBooks(books)
        // One account per page; chunked deletes stay under SQLite's bound-variable limit.
        books.groupBy { it.accountId }.forEach { (account, bs) ->
            bs.map { it.id }.chunked(500).forEach { deleteAuthorsOf(account, it) }
            insertAuthors(
                bs.flatMap { b ->
                    authors[b.id]
                        .orEmpty()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .map { BookAuthor(account, b.id, it) }
                },
            )
        }
    }

    @Query(
        "SELECT DISTINCT bookId FROM DownloadEntity WHERE accountId = :accountId AND bookId IN " +
            "(SELECT b.id FROM BookEntity b WHERE b.accountId = :accountId AND b.syncGeneration != :generation AND NOT $DOWNLOADED_EXISTS)",
    )
    protected abstract suspend fun unfinishedDownloadsOfAbsentBooks(
        accountId: Long,
        generation: Long,
    ): List<Long>

    @Query(
        "DELETE FROM BookEntity WHERE accountId = :accountId AND syncGeneration != :generation AND NOT EXISTS " +
            "(SELECT 1 FROM DownloadEntity d WHERE d.accountId = BookEntity.accountId AND d.bookId = BookEntity.id AND d.state = 'DONE')",
    )
    protected abstract suspend fun deleteAbsentUndownloadedBooks(
        accountId: Long,
        generation: Long,
    )

    @Query(
        "DELETE FROM DownloadEntity WHERE accountId = :accountId AND bookId NOT IN (SELECT id FROM BookEntity WHERE accountId = :accountId)",
    )
    protected abstract suspend fun deleteOrphanDownloads(accountId: Long)

    /**
     * Drops books missing from sweep [generation], except downloaded ones (R26: they stay visible offline until deleted
     * locally), and the unfinished download rows of the dropped books. Returns the books whose download rows were dropped,
     * so the caller can stop their work and remove their files.
     */
    @Transaction
    open suspend fun deleteBooksNotIn(
        accountId: Long,
        generation: Long,
    ): List<Long> {
        val dropped = unfinishedDownloadsOfAbsentBooks(accountId, generation)
        deleteAbsentUndownloadedBooks(accountId, generation)
        deleteOrphanDownloads(accountId)
        deleteOrphanAuthors(accountId)
        return dropped
    }

    @Query("SELECT * FROM BookEntity WHERE accountId = :accountId AND id = :id")
    abstract fun book(
        accountId: Long,
        id: Long,
    ): Flow<BookEntity?>

    @Query("SELECT * FROM BookEntity WHERE accountId = :accountId AND seriesName = :name")
    abstract fun seriesBooks(
        accountId: Long,
        name: String,
    ): Flow<List<BookEntity>>

    @Query("SELECT * FROM BookEntity WHERE accountId = :accountId AND seriesName IS NOT NULL")
    abstract fun seriesBooksAll(accountId: Long): Flow<List<BookEntity>>

    @Query("SELECT name FROM BookAuthor WHERE accountId = :accountId AND bookId = :bookId ORDER BY rowid")
    abstract fun bookAuthors(
        accountId: Long,
        bookId: Long,
    ): Flow<List<String>>

    @Query(
        "SELECT b.* FROM BookEntity b JOIN BookAuthor a ON a.accountId = b.accountId AND a.bookId = b.id " +
            "WHERE b.accountId = :accountId AND a.name = :name",
    )
    abstract fun authorBooks(
        accountId: Long,
        name: String,
    ): Flow<List<BookEntity>>

    @Query(
        "SELECT a.name AS name, b.* FROM BookEntity b JOIN BookAuthor a ON a.accountId = b.accountId AND a.bookId = b.id " +
            "WHERE b.accountId = :accountId",
    )
    abstract fun authorBookPairs(accountId: Long): Flow<List<AuthorBook>>

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

    @Query("SELECT * FROM LibraryEntity WHERE accountId = :accountId ORDER BY name COLLATE NOCASE, id")
    abstract fun libraries(accountId: Long): Flow<List<LibraryEntity>>

    @Query("SELECT * FROM ShelfEntity WHERE accountId = :accountId ORDER BY name COLLATE NOCASE, id")
    abstract fun shelves(accountId: Long): Flow<List<ShelfEntity>>

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

    @Query("DELETE FROM LibraryEntity WHERE accountId = :accountId AND id NOT IN (:keep)")
    protected abstract suspend fun deleteLibrariesNotIn(
        accountId: Long,
        keep: List<Long>,
    )

    @Transaction
    open suspend fun replaceLibraries(
        accountId: Long,
        libs: List<LibraryEntity>,
    ) {
        deleteLibrariesNotIn(accountId, libs.map { it.id })
        upsertLibraries(libs)
    }

    @Query("DELETE FROM ShelfEntity WHERE accountId = :accountId AND magic = :magic AND id NOT IN (:keep)")
    protected abstract suspend fun deleteShelvesNotIn(
        accountId: Long,
        magic: Boolean,
        keep: List<Long>,
    )

    @Query("DELETE FROM ShelfBookCrossRef WHERE accountId = :accountId AND magic = :magic AND shelfId NOT IN (:keep)")
    protected abstract suspend fun deleteShelfMembersNotIn(
        accountId: Long,
        magic: Boolean,
        keep: List<Long>,
    )

    @Transaction
    open suspend fun replaceShelves(
        accountId: Long,
        magic: Boolean,
        shelves: List<ShelfEntity>,
    ) {
        val keep = shelves.map { it.id }
        deleteShelvesNotIn(accountId, magic, keep)
        deleteShelfMembersNotIn(accountId, magic, keep)
        upsertShelves(shelves)
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

    @Upsert
    abstract suspend fun upsertDownload(download: DownloadEntity)

    @Query("SELECT * FROM DownloadEntity WHERE accountId = :accountId AND bookId = :bookId LIMIT 1")
    abstract fun download(
        accountId: Long,
        bookId: Long,
    ): Flow<DownloadEntity?>

    /**
     * One row per book with a DONE download: sizeBytes is summed over its files, the other download columns come from its
     * first file. Ordered by the book's first-download rowid, newest first: an approximation (upserts keep the rowid, so a
     * re-download doesn't move up); there is no download timestamp in v1.
     */
    @Query(
        "SELECT d.accountId, d.bookId, MIN(d.fileId) AS fileId, MIN(d.fileName) AS fileName, MIN(d.path) AS path, " +
            "SUM(d.sizeBytes) AS sizeBytes, d.state, 1.0 AS progress, COUNT(*) AS fileCount, " +
            "b.accountId AS b_accountId, b.id AS b_id, b.title AS b_title, " +
            "b.authors AS b_authors, b.seriesName AS b_seriesName, " +
            "b.seriesNumber AS b_seriesNumber, b.libraryId AS b_libraryId, " +
            "b.addedOn AS b_addedOn, b.lastReadTime AS b_lastReadTime, " +
            "b.readProgress AS b_readProgress, b.primaryFileId AS b_primaryFileId, " +
            "b.primaryFileType AS b_primaryFileType, b.primaryFileName AS b_primaryFileName, " +
            "b.fileSizeKb AS b_fileSizeKb, b.coverUpdatedOn AS b_coverUpdatedOn, " +
            "b.isAudiobook AS b_isAudiobook, b.syncGeneration AS b_syncGeneration, b.readStatus AS b_readStatus " +
            "FROM DownloadEntity d JOIN BookEntity b ON b.accountId = d.accountId AND b.id = d.bookId " +
            "WHERE d.accountId = :accountId AND d.state = 'DONE' GROUP BY d.accountId, d.bookId ORDER BY MIN(d.rowid) DESC",
    )
    abstract fun downloads(accountId: Long): Flow<List<DownloadWithBook>>

    @Query("UPDATE DownloadEntity SET progress = :progress WHERE accountId = :accountId AND bookId = :bookId")
    abstract suspend fun setDownloadProgress(
        accountId: Long,
        bookId: Long,
        progress: Float,
    )

    @Query("DELETE FROM DownloadEntity WHERE accountId = :accountId AND bookId = :bookId")
    abstract suspend fun deleteBookDownloads(
        accountId: Long,
        bookId: Long,
    )

    @Transaction
    open suspend fun deleteAccount(accountId: Long) {
        deleteBooks(accountId)
        deleteAuthors(accountId)
        deleteLibraries(accountId)
        deleteShelves(accountId)
        deleteShelfMembers(accountId)
        deleteDownloads(accountId)
    }
}
