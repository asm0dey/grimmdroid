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
package io.github.asm0dey.grimmdroid.reader

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ReaderDao {
    @Query("SELECT * FROM ReadingPosition WHERE accountId = :accountId AND bookId = :bookId")
    abstract suspend fun position(
        accountId: Long,
        bookId: Long,
    ): ReadingPosition?

    @Upsert
    abstract suspend fun upsertPosition(p: ReadingPosition)

    /** Upserts [p] and mirrors its progress onto the cached book, but a position already waiting for a FORCE upload keeps FORCE. */
    @Transaction
    open suspend fun savePosition(p: ReadingPosition) {
        val force = position(p.accountId, p.bookId)?.pending == PendingUpload.FORCE
        upsertPosition(if (force) p.copy(pending = PendingUpload.FORCE) else p)
        setBookProgress(p.accountId, p.bookId, (p.totalProgression * 100).toFloat(), p.updatedAt)
    }

    @Query("UPDATE BookEntity SET readProgress = :percent, lastReadTime = :at WHERE accountId = :accountId AND id = :bookId")
    abstract suspend fun setBookProgress(
        accountId: Long,
        bookId: Long,
        percent: Float,
        at: Long,
    )

    @Query("UPDATE ReadingPosition SET pending = 'FORCE' WHERE accountId = :accountId AND bookId = :bookId")
    abstract suspend fun forcePosition(
        accountId: Long,
        bookId: Long,
    )

    /** Clears pending only while the row is still the uploaded one, so a position saved during the upload stays pending. */
    @Query(
        "UPDATE ReadingPosition SET pending = 'NONE' WHERE accountId = :accountId AND bookId = :bookId " +
            "AND updatedAt = :updatedAt AND locatorJson = :locatorJson",
    )
    abstract suspend fun markUploaded(
        accountId: Long,
        bookId: Long,
        updatedAt: Long,
        locatorJson: String,
    )

    @Query("SELECT * FROM ReadingPosition WHERE pending != 'NONE'")
    abstract suspend fun pendingPositions(): List<ReadingPosition>

    @Query(
        "SELECT * FROM BookmarkRow WHERE accountId = :accountId AND bookId = :bookId AND state != 'PENDING_DELETE' " +
            "ORDER BY totalProgression, localId",
    )
    abstract fun bookmarks(
        accountId: Long,
        bookId: Long,
    ): Flow<List<BookmarkRow>>

    @Query("SELECT * FROM BookmarkRow WHERE localId = :localId")
    abstract suspend fun bookmark(localId: Long): BookmarkRow?

    @Query("SELECT * FROM BookmarkRow WHERE state != 'SYNCED'")
    abstract suspend fun pendingBookmarks(): List<BookmarkRow>

    @Insert
    abstract suspend fun insertBookmark(row: BookmarkRow): Long

    @Update
    abstract suspend fun updateBookmark(row: BookmarkRow)

    @Query("DELETE FROM BookmarkRow WHERE localId = :localId")
    abstract suspend fun deleteBookmark(localId: Long)

    @Query("DELETE FROM BookmarkRow WHERE accountId = :accountId AND bookId = :bookId AND state = 'SYNCED'")
    protected abstract suspend fun deleteSynced(
        accountId: Long,
        bookId: Long,
    )

    @Insert
    protected abstract suspend fun insertBookmarks(rows: List<BookmarkRow>)

    /** Swaps the book's SYNCED bookmarks for the server's; local pending rows survive. */
    @Transaction
    open suspend fun replaceSynced(
        accountId: Long,
        bookId: Long,
        rows: List<BookmarkRow>,
    ) {
        deleteSynced(accountId, bookId)
        insertBookmarks(rows)
    }

    @Query("DELETE FROM ReadingPosition WHERE accountId = :accountId")
    protected abstract suspend fun deletePositions(accountId: Long)

    @Query("DELETE FROM BookmarkRow WHERE accountId = :accountId")
    protected abstract suspend fun deleteBookmarks(accountId: Long)

    @Transaction
    open suspend fun deleteAccount(accountId: Long) {
        deletePositions(accountId)
        deleteBookmarks(accountId)
    }
}
