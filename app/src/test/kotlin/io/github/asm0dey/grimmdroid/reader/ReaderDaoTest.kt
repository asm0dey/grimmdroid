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

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.asm0dey.grimmdroid.catalog.BookEntity
import io.github.asm0dey.grimmdroid.catalog.CatalogDb
import io.github.asm0dey.grimmdroid.catalog.ReadState
import io.github.asm0dey.grimmdroid.catalog.readState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderDaoTest {
    private lateinit var db: CatalogDb
    private lateinit var dao: ReaderDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, CatalogDb::class.java).allowMainThreadQueries().build()
        dao = db.readerDao()
    }

    @After
    fun tearDown() = db.close()

    private fun pos(
        account: Long = 1,
        book: Long = 1,
        pending: PendingUpload = PendingUpload.NONE,
    ) = ReadingPosition(account, book, "{}", 0.5, pending, 7)

    private fun mark(
        title: String,
        progress: Double,
        state: BookmarkState = BookmarkState.SYNCED,
        account: Long = 1,
        book: Long = 1,
    ) = BookmarkRow(
        accountId = account,
        bookId = book,
        serverId = null,
        cfi = null,
        locatorJson = "{}",
        title = title,
        totalProgression = progress,
        state = state,
    )

    @Test
    fun upsertThenReadAndOverwrite() =
        runBlocking {
            dao.upsertPosition(pos())
            assertEquals(pos(), dao.position(1, 1))
            dao.upsertPosition(pos(pending = PendingUpload.FORCE))
            assertEquals(PendingUpload.FORCE, dao.position(1, 1)!!.pending)
            assertNull(dao.position(1, 2))
        }

    @Test
    fun pendingPositionsExcludeNone() =
        runBlocking {
            dao.upsertPosition(pos(book = 1))
            dao.upsertPosition(pos(book = 2, pending = PendingUpload.NORMAL))
            dao.upsertPosition(pos(book = 3, pending = PendingUpload.FORCE))
            assertEquals(listOf(2L, 3L), dao.pendingPositions().map { it.bookId }.sorted())
        }

    @Test
    fun bookmarksHideDeletedAndSortByProgression() =
        runBlocking {
            dao.insertBookmark(mark("late", 0.9))
            dao.insertBookmark(mark("gone", 0.1, BookmarkState.PENDING_DELETE))
            dao.insertBookmark(mark("early", 0.2, BookmarkState.PENDING_CREATE))
            dao.insertBookmark(mark("other book", 0.0, book = 2))
            assertEquals(listOf("early", "late"), dao.bookmarks(1, 1).first().map { it.title })
            assertEquals(
                listOf("gone", "early"),
                dao.pendingBookmarks().filter { it.bookId == 1L }.map { it.title }.sortedByDescending {
                    it ==
                        "gone"
                },
            )
        }

    @Test
    fun updateAndDeleteBookmark() =
        runBlocking {
            val id = dao.insertBookmark(mark("a", 0.1))
            dao.updateBookmark(mark("b", 0.1).copy(localId = id))
            assertEquals(listOf("b"), dao.bookmarks(1, 1).first().map { it.title })
            dao.deleteBookmark(id)
            assertEquals(emptyList<BookmarkRow>(), dao.bookmarks(1, 1).first())
        }

    @Test
    fun replaceSyncedKeepsPendingCreate() =
        runBlocking {
            dao.insertBookmark(mark("old", 0.1))
            dao.insertBookmark(mark("mine", 0.5, BookmarkState.PENDING_CREATE))
            dao.insertBookmark(mark("elsewhere", 0.1, book = 2))
            dao.replaceSynced(1, 1, listOf(mark("new", 0.3)))
            assertEquals(listOf("new", "mine"), dao.bookmarks(1, 1).first().map { it.title })
            assertEquals(listOf("elsewhere"), dao.bookmarks(1, 2).first().map { it.title })
        }

    @Test
    fun deleteAccountClearsOnlyThatAccount() =
        runBlocking {
            dao.upsertPosition(pos(account = 1))
            dao.upsertPosition(pos(account = 2))
            dao.insertBookmark(mark("a", 0.1, account = 1))
            dao.insertBookmark(mark("b", 0.1, account = 2))
            dao.deleteAccount(1)
            assertNull(dao.position(1, 1))
            assertEquals(emptyList<BookmarkRow>(), dao.bookmarks(1, 1).first())
            assertEquals(pos(account = 2), dao.position(2, 1))
            assertEquals(listOf("b"), dao.bookmarks(2, 1).first().map { it.title })
        }

    @Test
    fun savingPositionUpdatesBookProgress() =
        runBlocking {
            val book =
                BookEntity(
                    1,
                    1,
                    "t",
                    "a",
                    null,
                    null,
                    null,
                    null,
                    null,
                    10f,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    0,
                )
            db.catalogDao().upsertBooks(listOf(book))
            dao.savePosition(pos().copy(totalProgression = 0.995))
            val saved = db.catalogDao().book(1, 1).first()!!
            assertEquals(99.5f, saved.readProgress!!, 0.001f)
            assertEquals(7L, saved.lastReadTime)
            assertEquals(ReadState.READ, saved.readState)
        }
}
